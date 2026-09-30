package dev.patternizer.menu;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraft.core.BlockPos;

import dev.patternizer.registry.PRegistry;

/**
 * AI 样板编写台容器。
 * 槽位 0：空白样板输入；槽位 1：编码结果输出（只取不放）。
 * M1：内容仅存于菜单打开期间，关闭即弃（M2 加 BlockEntity 持久化）。
 */
public class AiPatternizerMenu extends AbstractContainerMenu {

    private final ItemStackHandler storage = new ItemStackHandler(2);

    public AiPatternizerMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(PRegistry.AI_PATTERNIZER_MENU.get(), windowId);

        this.addSlot(new SlotItemHandler(storage, 0, 26, 35));
        this.addSlot(new SlotItemHandler(storage, 1, 134, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });

        // 玩家背包与快捷栏
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 9; x++) {
                this.addSlot(new Slot(playerInv, x + y * 9 + 9, 8 + x * 18, 84 + y * 18));
            }
        }
        for (int x = 0; x < 9; x++) {
            this.addSlot(new Slot(playerInv, x, 8 + x * 18, 142));
        }
    }

    public ItemStackHandler getStorage() {
        return storage;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // M1 占位：禁用 shift 快速移动，后续里程碑再实现
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // M1 占位：暂不做距离校验
        return true;
    }
}
