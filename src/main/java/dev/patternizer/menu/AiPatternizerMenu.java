package dev.patternizer.menu;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import dev.patternizer.block.AiPatternizerBlockEntity;
import dev.patternizer.registry.PRegistry;

/**
 * AI 样板编写台容器。
 * 槽位 0：空白样板输入；槽位 1：编码结果输出（只取不放）。
 * 槽位内容持久化于 BlockEntity；网格在线时优先走网络取/存（见 ServerPatternEncoder）。
 */
public class AiPatternizerMenu extends AbstractContainerMenu {

    private final ItemStackHandler storage;
    private final ContainerLevelAccess access;
    @Nullable
    private final AiPatternizerBlockEntity blockEntity;

    public AiPatternizerMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(PRegistry.AI_PATTERNIZER_MENU.get(), windowId);

        this.access = ContainerLevelAccess.create(playerInv.player.level(), pos);
        BlockEntity be = playerInv.player.level().getBlockEntity(pos);
        this.blockEntity = be instanceof AiPatternizerBlockEntity pbe ? pbe : null;
        this.storage = blockEntity != null ? blockEntity.getStorage() : new ItemStackHandler(2);

        this.addSlot(new SlotItemHandler(storage, 0, 27, 107));
        this.addSlot(new SlotItemHandler(storage, 1, 135, 107) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });

        // 玩家背包与快捷栏
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 9; x++) {
                this.addSlot(new Slot(playerInv, x + y * 9 + 9, 9 + x * 18, 151 + y * 18));
            }
        }
        for (int x = 0; x < 9; x++) {
            this.addSlot(new Slot(playerInv, x, 9 + x * 18, 205));
        }
    }

    public ItemStackHandler getStorage() {
        return storage;
    }

    @Nullable
    public AiPatternizerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index < 2) {
            // 机器槽 → 玩家背包
            if (!this.moveItemStackTo(stack, 2, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 → 空白样板槽
            if (!this.moveItemStackTo(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) {
            return true;
        }
        return stillValid(this.access, player, PRegistry.AI_PATTERNIZER.get());
    }
}
