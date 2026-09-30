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
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.patternizer.block.AiPatternizerBlockEntity;
import dev.patternizer.menu.AiPatternizerMenu;
import dev.patternizer.spec.CatalystLayout;
import dev.patternizer.spec.PatternSpec;
import dev.patternizer.spec.PatternSpecJson;
import dev.patternizer.spec.PatternSpecValidator;
import dev.patternizer.spec.PatternSpecValidator.ValidationError;

/**
 * 服务端样板编码管线（§6）。
 * 流程：解析 → 权威校验 → 空白样板获取（网格优先，槽位兜底）→
 * AE2 官方 API 编码 → 消耗空白样板 → 产出（回写网格优先，槽位/背包兜底）。
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
        if (blankPattern == null) {
            return new String[] { "no_blank_pattern", null };
        }

        // 网格（在线时优先从网络取空白样板、结果回写网络）
        AiPatternizerBlockEntity blockEntity = menu.getBlockEntity();
        IGrid grid = blockEntity != null ? blockEntity.getGrid() : null;
        IActionSource source = blockEntity != null
                ? IActionSource.ofMachine(blockEntity)
                : IActionSource.empty();

        // ① 空白样板获取
        boolean blankFromGrid = false;
        if (grid != null) {
            blankFromGrid = GridPatternIO.extract(grid, AEItemKey.of(new ItemStack(blankPattern)), 1,
                    source) == 1;
        }
        if (!blankFromGrid && storage.getStackInSlot(0).getItem() != blankPattern) {
            return new String[] { "no_blank_pattern", null };
        }

        // ② 编码
        ItemStack encoded;
        try {
            encoded = switch (spec.type) {
            case PROCESSING -> encodeProcessing(spec);
            case CRAFTING -> encodeCrafting(player, spec);
            default -> null; // STONECUTTING / SMITHING 见 M3
            };
        } catch (Exception e) {
            refundBlankPattern(grid, player, storage, blankPattern, blankFromGrid, source);
            return new String[] { "encode_failed", e.getClass().getSimpleName() };
        }
        if (encoded == null) {
            refundBlankPattern(grid, player, storage, blankPattern, blankFromGrid, source);
            return new String[] {
                    spec.type == PatternSpec.Type.CRAFTING ? "recipe_not_found" : "unsupported_type",
                    spec.target
            };
        }

        // ③ 消耗空白样板
        if (!blankFromGrid) {
            storage.extractItem(0, 1, false);
        }

        // ④ 产出：网格优先回写，余量进输出槽/背包
        if (grid != null) {
            long inserted = GridPatternIO.insert(grid, AEItemKey.of(encoded), encoded.getCount(), source);
            if (inserted > 0) {
                encoded.shrink((int) inserted);
            }
        }
        if (!encoded.isEmpty()) {
            if (storage.getStackInSlot(1).isEmpty()) {
                storage.setStackInSlot(1, encoded);
            } else {
                player.getInventory().placeItemBackInInventory(encoded);
            }
        }
        return new String[] { "ok", spec.note };
    }

    /** 编码失败时把已扣的空白样板退回（网格扣的退回网格，槽位未扣则无需操作）。 */
    private static void refundBlankPattern(IGrid grid, ServerPlayer player, ItemStackHandler storage,
            Item blankPattern, boolean blankFromGrid, IActionSource source) {
        if (!blankFromGrid) {
            return;
        }
        if (grid != null && GridPatternIO.insert(grid, AEItemKey.of(new ItemStack(blankPattern)), 1, source) == 1) {
            return;
        }
        player.getInventory().placeItemBackInInventory(new ItemStack(blankPattern));
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
