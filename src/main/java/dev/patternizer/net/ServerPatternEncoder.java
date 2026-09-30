package dev.patternizer.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
import dev.patternizer.menu.AiPatternizerMenu;
import dev.patternizer.spec.CatalystLayout;
import dev.patternizer.spec.PatternSpec;
import dev.patternizer.spec.PatternSpecJson;
import dev.patternizer.spec.PatternSpecValidator;
import dev.patternizer.spec.PatternSpecValidator.ValidationError;

/**
 * 服务端样板编码管线（§6）。
 * 流程：解析 → 权威校验（注册表/槽位/催化剂规则）→ 空白样板检查 →
 * AE2 官方 API 编码 → 消耗空白样板 → 产出。
 * M2 支持 processing（含假合成三策略，§6.2）与 crafting（首个匹配配方，M3 做冲突消歧）。
 */
public final class ServerPatternEncoder {

    private static final ResourceLocation BLANK_PATTERN_ID = new ResourceLocation("ae2", "blank_pattern");

    private ServerPatternEncoder() {
    }

    /**
     * @return [结果码, 附加信息（可空）]
     */
    public static String[] encodeFromSpec(ServerPlayer player, String specJson) {
        PatternSpec spec;
        try {
            spec = PatternSpecJson.parse(specJson);
        } catch (PatternSpecJson.SpecParseException e) {
            return new String[] { "invalid_spec", e.getMessage() };
        }

        List<ValidationError> errors = PatternSpecValidator.validate(spec);
        if (!errors.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (ValidationError e : errors) {
                lines.add(e.serialize());
            }
            return new String[] { "invalid_spec", String.join("\n", lines) };
        }

        if (!(player.containerMenu instanceof AiPatternizerMenu menu)) {
            return new String[] { "no_menu", null };
        }
        ItemStackHandler storage = menu.getStorage();
        Item blankPattern = ForgeRegistries.ITEMS.getValue(BLANK_PATTERN_ID);
        if (blankPattern == null || storage.getStackInSlot(0).getItem() != blankPattern) {
            return new String[] { "no_blank_pattern", null };
        }

        ItemStack encoded;
        try {
            encoded = switch (spec.type) {
            case PROCESSING -> encodeProcessing(spec);
            case CRAFTING -> encodeCrafting(player, spec);
            default -> null; // STONECUTTING / SMITHING 见 M3
            };
        } catch (Exception e) {
            return new String[] { "encode_failed", e.getClass().getSimpleName() };
        }
        if (encoded == null) {
            return new String[] {
                    spec.type == PatternSpec.Type.CRAFTING ? "recipe_not_found" : "unsupported_type",
                    spec.target
            };
        }

        storage.extractItem(0, 1, false);
        if (storage.getStackInSlot(1).isEmpty()) {
            storage.setStackInSlot(1, encoded);
        } else {
            player.getInventory().placeItemBackInInventory(encoded);
        }
        return new String[] { "ok", spec.note };
    }

    private static ItemStack encodeProcessing(PatternSpec spec) {
        GenericStack[] inputs = CatalystLayout.buildInputs(spec);
        GenericStack[] outputs = CatalystLayout.buildOutputs(spec);
        return PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
    }

    /** M2 简化版：取第一个产物匹配的原版合成配方（多配方冲突消歧见 M3 / §10.5）。 */
    private static ItemStack encodeCrafting(ServerPlayer player, PatternSpec spec) {
        var level = player.level();
        Item targetItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation(spec.target));
        if (targetItem == null) {
            return null;
        }
        CraftingRecipe match = null;
        for (CraftingRecipe recipe : player.server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (recipe.getResultItem(level.registryAccess()).getItem() == targetItem) {
                match = recipe;
                break;
            }
        }
        if (match == null) {
            return null;
        }
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = ItemStack.EMPTY;
        }
        int i = 0;
        for (Ingredient ingredient : match.getIngredients()) {
            if (i >= grid.length) {
                break;
            }
            ItemStack[] variants = ingredient.getItems();
            if (variants.length > 0) {
                grid[i] = variants[0].copy();
            }
            i++;
        }
        ItemStack out = match.getResultItem(level.registryAccess()).copy();
        return PatternDetailsHelper.encodeCraftingPattern(match, grid, out,
                spec.allowSubstitutes, spec.allowFluidSubstitutes);
    }
}
