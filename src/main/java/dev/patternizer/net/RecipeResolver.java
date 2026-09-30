package dev.patternizer.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import dev.patternizer.spec.CatalystLayout;
import dev.patternizer.spec.PatternSpec;

/**
 * 配方解析与编码（§6）：按 spec 的 target 在服务端 RecipeManager 反查真实配方并编码。
 * - 单一匹配 → 直接编码；
 * - 多匹配（§10.5 不猜原则）→ 返回候选配方 id 清单，由客户端选择后带 recipeId 重发；
 * - 无匹配 → recipe_not_found。
 * crafting 同时处理 ShapedRecipe 的九宫格布局（M2 是顺序填充，对有形状配方不准确）。
 */
public final class RecipeResolver {

    public sealed interface Resolution {
        record Encoded(ItemStack stack) implements Resolution {
        }

        /** 多个配方产出同一目标：候选配方 id 清单，等玩家选择。 */
        record ChooseRecipe(List<String> recipeIds) implements Resolution {
        }

        record Failed(String code, String detail) implements Resolution {
        }
    }

    private RecipeResolver() {
    }

    public static Resolution resolveAndEncode(MinecraftServer server, Level level, PatternSpec spec) {
        return switch (spec.type) {
        case PROCESSING -> new Resolution.Encoded(PatternDetailsHelper.encodeProcessingPattern(
                CatalystLayout.buildInputs(spec), CatalystLayout.buildOutputs(spec)));
        case CRAFTING -> encodeByType(server, level, spec, RecipeType.CRAFTING);
        case STONECUTTING -> encodeByType(server, level, spec, RecipeType.STONECUTTING);
        case SMITHING -> encodeByType(server, level, spec, RecipeType.SMITHING);
        };
    }

    private static <C extends Container, T extends Recipe<C>> Resolution encodeByType(MinecraftServer server,
            Level level, PatternSpec spec, RecipeType<T> type) {
        Item target = ForgeRegistries.ITEMS.getValue(new ResourceLocation(spec.target));
        if (target == null) {
            return new Resolution.Failed("recipe_not_found", spec.target);
        }

        List<T> matches = new ArrayList<>();
        for (T recipe : server.getRecipeManager().getAllRecipesFor(type)) {
            if (recipe.getResultItem(level.registryAccess()).getItem() == target) {
                matches.add(recipe);
            }
        }
        if (matches.isEmpty()) {
            return new Resolution.Failed("recipe_not_found", spec.target);
        }

        T chosen = null;
        if (spec.recipeId != null && !spec.recipeId.isBlank()) {
            for (T recipe : matches) {
                if (recipe.getId().toString().equals(spec.recipeId)) {
                    chosen = recipe;
                    break;
                }
            }
            if (chosen == null) {
                return new Resolution.Failed("recipe_not_found", spec.recipeId);
            }
        } else if (matches.size() > 1) {
            return new Resolution.ChooseRecipe(matches.stream().map(r -> r.getId().toString()).toList());
        } else {
            chosen = matches.get(0);
        }

        ItemStack encoded = encodeOne(level, chosen, spec);
        if (encoded == null) {
            return new Resolution.Failed("unsupported_type", chosen.getId().toString());
        }
        return new Resolution.Encoded(encoded);
    }

    private static ItemStack encodeOne(Level level, Recipe<?> recipe, PatternSpec spec) {
        if (recipe instanceof CraftingRecipe crafting) {
            return encodeCrafting(level, crafting, spec);
        }
        if (recipe instanceof StonecutterRecipe stonecutting) {
            return encodeStonecutting(level, stonecutting, spec);
        }
        if (recipe instanceof SmithingRecipe smithing) {
            return encodeSmithing(level, smithing, spec);
        }
        return null;
    }

    private static ItemStack encodeCrafting(Level level, CraftingRecipe recipe, PatternSpec spec) {
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = ItemStack.EMPTY;
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe shaped) {
            // 有形状配方：按宽度/高度还原九宫格布局
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            for (int y = 0; y < h && y < 3; y++) {
                for (int x = 0; x < w && x < 3; x++) {
                    int idx = y * w + x;
                    if (idx < ingredients.size()) {
                        grid[y * 3 + x] = firstStack(ingredients.get(idx));
                    }
                }
            }
        } else {
            for (int i = 0; i < ingredients.size() && i < grid.length; i++) {
                grid[i] = firstStack(ingredients.get(i));
            }
        }
        ItemStack out = recipe.getResultItem(level.registryAccess()).copy();
        return PatternDetailsHelper.encodeCraftingPattern(recipe, grid, out,
                spec.allowSubstitutes, spec.allowFluidSubstitutes);
    }

    private static ItemStack encodeStonecutting(Level level, StonecutterRecipe recipe, PatternSpec spec) {
        if (recipe.getIngredients().isEmpty()) {
            return null;
        }
        ItemStack in = firstStack(recipe.getIngredients().get(0));
        ItemStack out = recipe.getResultItem(level.registryAccess()).copy();
        if (in.isEmpty() || out.isEmpty()) {
            return null;
        }
        return PatternDetailsHelper.encodeStonecuttingPattern(recipe,
                AEItemKey.of(in), AEItemKey.of(out), spec.allowSubstitutes);
    }

    private static ItemStack encodeSmithing(Level level, SmithingRecipe recipe, PatternSpec spec) {
        // template/base/addition 字段在 1.20.1 是包私有的（SmithingRecipe 只暴露
        // isXxxIngredient 谓词），这里用谓词反查代表物品——dev/prod 都不依赖 AT/反射。
        ItemStack templateStack = findSmithingSlot(recipe, 0);
        ItemStack baseStack = findSmithingSlot(recipe, 1);
        ItemStack additionStack = findSmithingSlot(recipe, 2);
        ItemStack out = recipe.getResultItem(level.registryAccess()).copy();
        if (templateStack.isEmpty() || baseStack.isEmpty() || additionStack.isEmpty() || out.isEmpty()) {
            return null;
        }
        return PatternDetailsHelper.encodeSmithingTablePattern(recipe,
                AEItemKey.of(templateStack), AEItemKey.of(baseStack), AEItemKey.of(additionStack),
                AEItemKey.of(out), spec.allowSubstitutes);
    }

    /** 用 isXxxIngredient 谓词在注册表中反查该槽位的第一个代表物品（原版优先）。 */
    private static ItemStack findSmithingSlot(SmithingRecipe recipe, int slot) {
        for (int pass = 0; pass < 2; pass++) {
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (pass == 0 && (id == null || !"minecraft".equals(id.getNamespace()))) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                boolean hit = switch (slot) {
                case 0 -> recipe.isTemplateIngredient(stack);
                case 1 -> recipe.isBaseIngredient(stack);
                default -> recipe.isAdditionIngredient(stack);
                };
                if (hit) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack firstStack(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack[] variants = ingredient.getItems();
        if (variants.length == 0) {
            return ItemStack.EMPTY;
        }
        // 多变体配料优先取原版（minecraft 命名空间）——否则可能拿到模组排在最前的
        // 冷僻变体（如"远古木棍"），配合 allowSubstitutes 才符合玩家直觉
        for (ItemStack variant : variants) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(variant.getItem());
            if (id != null && "minecraft".equals(id.getNamespace())) {
                return variant.copy();
            }
        }
        return variants[0].copy();
    }
}
