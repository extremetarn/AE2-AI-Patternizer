package dev.patternizer.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import dev.patternizer.registry.PRegistry;

/**
 * AI 样板编写台 BlockEntity。
 * - 持有持久化槽位（空白样板/输出），替代 M1 的临时槽位；
 * - 持有 AE2 网格节点：智能线缆可连，空白样板可从网络扣取、编码结果可回写网络。
 *   不设 GridFlags.REQUIRE_CHANNEL：即插即用，ad-hoc 小网也能工作。
 */
public class AiPatternizerBlockEntity extends BlockEntity
        implements IGridNodeListener<AiPatternizerBlockEntity>, IActionHost {

    private final ItemStackHandler storage = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final IManagedGridNode gridNode;

    public AiPatternizerBlockEntity(BlockPos pos, BlockState state) {
        super(PRegistry.AI_PATTERNIZER_BE.get(), pos, state);
        this.gridNode = GridHelper.createManagedNode(this, this);
        this.gridNode.setIdlePowerUsage(1.0);
        this.gridNode.setVisualRepresentation(new ItemStack(PRegistry.AI_PATTERNIZER_ITEM.get()));
    }

    public ItemStackHandler getStorage() {
        return storage;
    }

    public IManagedGridNode getGridNode() {
        return gridNode;
    }

    @Nullable
    public IGrid getGrid() {
        return gridNode.getGrid();
    }

    // ---------- 生命周期 ----------

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        GridHelper.onFirstTick(this, be -> be.gridNode.create(be.level, be.worldPosition));
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        gridNode.destroy();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        gridNode.destroy();
    }

    // ---------- 持久化 ----------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("storage", storage.serializeNBT());
        gridNode.saveToNBT(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("storage")) {
            storage.deserializeNBT(tag.getCompound("storage"));
        }
        gridNode.loadFromNBT(tag);
    }

    // ---------- IGridNodeListener ----------

    @Override
    public void onSaveChanges(AiPatternizerBlockEntity nodeOwner, IGridNode node) {
        setChanged();
    }

    // ---------- IActionHost ----------

    @Override
    public IGridNode getActionableNode() {
        return gridNode.getNode();
    }
}
