package dev.patternizer.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.patternizer.menu.AiPatternizerMenu;

/**
 * 服务端样板编码管线（§6）。
 * M1：硬编码假数据（1 铁锭 → 9 铁粒，处理样板），验证
 * 「C2S 包 → 服务端校验 → AE2 官方 API 编码 → 消耗空白样板 → 产出」全链路。
 * M2 起输入改为 LLM 生成的 PatternSpec。
 */
public final class ServerPatternEncoder {

    private static final ResourceLocation BLANK_PATTERN_ID = new ResourceLocation("ae2", "blank_pattern");

    private ServerPatternEncoder() {
    }

    /**
     * @return 结果码：ok / no_menu / no_blank_pattern（客户端按 lang 翻译）
     */
    public static String encodeFakeProcessing(ServerPlayer player) {
        if (!(player.containerMenu instanceof AiPatternizerMenu menu)) {
            return "no_menu";
        }
        ItemStackHandler storage = menu.getStorage();

        Item blankPattern = ForgeRegistries.ITEMS.getValue(BLANK_PATTERN_ID);
        if (blankPattern == null) {
            // AE2 未加载，防御性返回
            return "no_blank_pattern";
        }
        ItemStack blankStack = storage.getStackInSlot(0);
        if (blankStack.isEmpty() || blankStack.getItem() != blankPattern) {
            return "no_blank_pattern";
        }

        GenericStack[] inputs = new GenericStack[] {
                new GenericStack(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), 1)
        };
        GenericStack[] outputs = new GenericStack[] {
                new GenericStack(AEItemKey.of(new ItemStack(Items.IRON_NUGGET)), 9)
        };
        ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);

        storage.extractItem(0, 1, false);
        if (storage.getStackInSlot(1).isEmpty()) {
            storage.setStackInSlot(1, encoded);
        } else {
            player.getInventory().placeItemBackInInventory(encoded);
        }
        return "ok";
    }
}
