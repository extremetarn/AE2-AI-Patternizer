package dev.patternizer.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import dev.patternizer.block.AiPatternizerBlockEntity;
import dev.patternizer.menu.AiPatternizerMenu;
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

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

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
        // 催化剂策略防御（§10.15）：客户端漏网的黑名单命中在服务端兜底
        List<String> policyHits = dev.patternizer.spec.CatalystPolicy.apply(spec, java.util.Set.of());
        if (!policyHits.isEmpty()) {
            LOGGER.info("CatalystPolicy forced preplaced for {} (player {})", policyHits, player.getName().getString());
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

        // ② 编码（配方反查与多配方冲突见 RecipeResolver）
        RecipeResolver.Resolution resolution;
        try {
            resolution = RecipeResolver.resolveAndEncode(player.server, player.level(), spec);
        } catch (Exception e) {
            LOGGER.warn("encodeFromSpec threw for spec {}: {}", specJson, e.toString());
            refundBlankPattern(grid, player, storage, blankPattern, blankFromGrid, source);
            return new String[] { "encode_failed", e.getClass().getSimpleName() };
        }
        if (resolution instanceof RecipeResolver.Resolution.ChooseRecipe choose) {
            // 多配方冲突：不猜，把候选配方 id 回给客户端选择（§10.5），空白样板已退/未扣
            refundBlankPattern(grid, player, storage, blankPattern, blankFromGrid, source);
            return new String[] { "choose_recipe", String.join("\n", choose.recipeIds()) };
        }
        if (resolution instanceof RecipeResolver.Resolution.Failed failed) {
            LOGGER.warn("encodeFromSpec failed [{}] {} for target={} type={}",
                    failed.code(), failed.detail(), spec.target, spec.type);
            refundBlankPattern(grid, player, storage, blankPattern, blankFromGrid, source);
            return new String[] { failed.code(), failed.detail() };
        }
        ItemStack encoded = ((RecipeResolver.Resolution.Encoded) resolution).stack();

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
}
