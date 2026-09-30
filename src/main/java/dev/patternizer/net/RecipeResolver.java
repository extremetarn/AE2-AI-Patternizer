package dev.patternizer.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.patternizer.spec.CatalystLayout;
import dev.patternizer.spec.PatternSpec;

/**
 * 配方枚举与编码（v0.11 全量枚举版，取代 M3 的类型猜测与跨类型回退）。
 *
 * 流程：AI 选定目标 → 遍历全部配方类型（含模组机器配方）找出所有产出该物品的配方 →
 * 有效性过滤（纹饰/动态改性剔除）→ 0/1/N 分流 → 按所选配方编码：
 * 原版类型走原生样板，机器类型转处理样板。
 */
public final class RecipeResolver {

    /** 配方路线的类别。 */
    public enum Kind {
        CRAFTING, STONECUTTING, SMITHING, MACHINE
    }

    /** 一条可编码的配方选项。 */
    public record Option(String recipeId, String recipeTypeId, Kind kind) {

        /** 选择界面/回传用的序列化行：recipeId|recipeTypeId|kind */
        public String serialize() {
            return recipeId + "|" + recipeTypeId + "|" + kind.name();
        }

        public static Option deserialize(String line) {
            String[] parts = line.split("\\|", -1);
            return new Option(parts[0], parts.length > 1 ? parts[1] : "minecraft:crafting",
                    parts.length > 2 ? Kind.valueOf(parts[2]) : Kind.CRAFTING);
        }
    }

    public sealed interface Resolution {
        record Encoded(ItemStack stack, String recipeTypeId) implements Resolution {
        }

        /** 多条有效路线：全量配方选项，等玩家挑选（v0.11）。 */
        record ChooseRecipe(List<Option> options) implements Resolution {
        }

        record Failed(String code, String detail) implements Resolution {
        }
    }

    private RecipeResolver() {
    }

    public static Resolution resolveAndEncode(MinecraftServer server, Level level, PatternSpec spec) {
        if (spec.type == PatternSpec.Type.PROCESSING) {
            return new Resolution.Encoded(PatternDetailsHelper.encodeProcessingPattern(
                    CatalystLayout.buildInputs(spec), CatalystLayout.buildOutputs(spec)),
                    "processing");
        }

        // 玩家已指定具体配方（选择界面回传）：全类型按 id 精确编码
        if (spec.recipeId != null && !spec.recipeId.isBlank()) {
            Recipe<?> recipe = findById(server.getRecipeManager(), level, spec.recipeId);
            if (recipe == null) {
                return new Resolution.Failed("recipe_not_found", spec.recipeId);
            }
            return encodeRecipe(level, recipe, spec);
        }

        // 全量枚举（v0.11 原则：不走捷径）
        List<Option> options = enumerate(server.getRecipeManager(), level, spec.target);
        if (options.isEmpty()) {
            return new Resolution.Failed("recipe_not_found", spec.target);
        }
        if (options.size() > 1) {
            return new Resolution.ChooseRecipe(options);
        }
        Recipe<?> recipe = findById(server.getRecipeManager(), level, options.get(0).recipeId());
        if (recipe == null) {
            return new Resolution.Failed("recipe_not_found", spec.target);
        }
        return encodeRecipe(level, recipe, spec);
    }

    /**
     * 全量枚举目标物品的全部有效制造路线。
     * 遍历 ForgeRegistries.RECIPE_TYPES（含模组机器配方类型）。
     * 参数为 RecipeManager，客户端（同步配方本）与服务端均可调用。
     */
    public static List<Option> enumerate(net.minecraft.world.item.crafting.RecipeManager recipeManager,
            Level level, String targetId) {
        List<Option> out = new ArrayList<>();
        Item target = ForgeRegistries.ITEMS.getValue(new ResourceLocation(targetId));
        if (target == null) {
            return out;
        }
        for (RecipeType<?> type : ForgeRegistries.RECIPE_TYPES) {
            ResourceLocation typeKey = ForgeRegistries.RECIPE_TYPES.getKey(type);
            String typeId = typeKey != null ? typeKey.toString() : "unknown:unknown";
            Kind kind = classify(type);
            for (Recipe<?> recipe : allOf(recipeManager, type)) {
                if (!isValidRoute(recipe, level, target)) {
                    continue;
                }
                out.add(new Option(recipe.getId().toString(), typeId, kind));
            }
        }
        return out;
    }

    /**
     * 有效性过滤（v0.11「无效配方」剔除）：
     * - 结果为空或与目标不符（动态配方常见）；
     * - SmithingTrimRecipe 纹饰（同物品改性，不产出新物，玩家眼中的「附魔配方」）；
     * - crafting_special_* 动态配方（烟花/染色/地图复制等，无法编码为静态样板）。
     */
    private static boolean isValidRoute(Recipe<?> recipe, Level level, Item target) {
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty() || result.getItem() != target) {
            return false;
        }
        if (recipe instanceof SmithingTrimRecipe) {
            return false;
        }
        ResourceLocation serializerId = ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.getSerializer());
        if (serializerId != null && serializerId.getPath().startsWith("crafting_special")) {
            return false;
        }
        return true;
    }

    private static Kind classify(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) {
            return Kind.CRAFTING;
        }
        if (type == RecipeType.STONECUTTING) {
            return Kind.STONECUTTING;
        }
        if (type == RecipeType.SMITHING) {
            return Kind.SMITHING;
        }
        return Kind.MACHINE;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<? extends Recipe<?>> allOf(net.minecraft.world.item.crafting.RecipeManager recipeManager,
            RecipeType<?> type) {
        return (List) recipeManager.getAllRecipesFor((RecipeType) type);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Recipe<?> findById(net.minecraft.world.item.crafting.RecipeManager recipeManager, Level level,
            String recipeId) {
        ResourceLocation id = new ResourceLocation(recipeId);
        for (RecipeType<?> type : ForgeRegistries.RECIPE_TYPES) {
            for (Recipe<?> recipe : (List<Recipe<?>>) (List) recipeManager
                    .getAllRecipesFor((RecipeType) type)) {
                if (recipe.getId().equals(id)) {
                    return recipe;
                }
            }
        }
        return null;
    }

    /** 按配方类别编码：原版类型走原生样板，机器类型转处理样板。 */
    private static Resolution encodeRecipe(Level level, Recipe<?> recipe, PatternSpec spec) {
        if (recipe instanceof CraftingRecipe crafting) {
            return new Resolution.Encoded(encodeCrafting(level, crafting, spec), "minecraft:crafting");
        }
        if (recipe instanceof StonecutterRecipe stonecutting) {
            ItemStack encoded = encodeStonecutting(level, stonecutting, spec);
            return encoded != null
                    ? new Resolution.Encoded(encoded, "minecraft:stonecutting")
                    : fallbackToMachine(level, recipe);
        }
        if (recipe instanceof SmithingRecipe smithing) {
            ItemStack encoded = encodeSmithing(level, smithing, spec);
            return encoded != null
                    ? new Resolution.Encoded(encoded, "minecraft:smithing")
                    : fallbackToMachine(level, recipe);
        }
        ItemStack encoded = encodeMachineRecipe(level, recipe);
        return encoded != null
            ? new Resolution.Encoded(encoded,
                    ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()).toString())
            : new Resolution.Failed("unsupported_type", recipe.getId().toString());
    }

    /** 原生编码失败（如锻造配方存在空槽位）时退回处理样板编码。 */
    private static Resolution fallbackToMachine(Level level, Recipe<?> recipe) {
        ItemStack encoded = encodeMachineRecipe(level, recipe);
        return encoded != null
                ? new Resolution.Encoded(encoded,
                        ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType()).toString())
                : new Resolution.Failed("unsupported_type", recipe.getId().toString());
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

    /**
     * 机器配方 → 处理样板（v0.11）。
     * 配料取 getIngredients() 首个变体（原版优先）并按物品合并计数；
     * 产物取 getResultItem。流体配料 v1 未导入（§10.19 待桥接 mod）。
     */
    private static ItemStack encodeMachineRecipe(Level level, Recipe<?> recipe) {
        Map<Item, Integer> inputs = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack stack = firstStack(ingredient);
            if (!stack.isEmpty()) {
                inputs.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        ItemStack out = recipe.getResultItem(level.registryAccess()).copy();
        if (inputs.isEmpty() || out.isEmpty()) {
            return null;
        }
        if (inputs.size() + 1 > dev.patternizer.spec.PatternSpecValidator.MAX_PROCESSING_SLOTS) {
            return null;
        }
        GenericStack[] in = new GenericStack[inputs.size()];
        int i = 0;
        for (Map.Entry<Item, Integer> e : inputs.entrySet()) {
            in[i++] = new GenericStack(AEItemKey.of(new ItemStack(e.getKey())), e.getValue());
        }
        GenericStack[] outs = new GenericStack[] { new GenericStack(AEItemKey.of(out), out.getCount()) };
        return PatternDetailsHelper.encodeProcessingPattern(in, outs);
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
