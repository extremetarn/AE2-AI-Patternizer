package dev.patternizer.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.capabilities.Capabilities;
import dev.patternizer.registry.PRegistry;

/**
 * AI 样板编写台 BlockEntity。
 * - 持有持久化槽位（空白样板/输出）；
 * - 持有 AE2 网格节点：智能线缆可连，空白样板可从网络扣取、编码结果可回写网络。
 *   不设 GridFlags.REQUIRE_CHANNEL：即插即用，ad-hoc 小网也能工作。
 * - 通过 {@link Capabilities#IN_WORLD_GRID_NODE_HOST} 向线缆暴露节点宿主——
 *   缺这一步线缆在视觉上和逻辑上都连不上（节点会自成单节点网格）。
 */
public class AiPatternizerBlockEntity extends BlockEntity
        implements IGridNodeListener<AiPatternizerBlockEntity>, IActionHost, IInWorldGridNodeHost {

    private final ItemStackHandler storage = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final IManagedGridNode gridNode;
    private final LazyOptional<IInWorldGridNodeHost> gridNodeHostCap = LazyOptional.of(() -> this);

    public AiPatternizerBlockEntity(BlockPos pos, BlockState state) {
        super(PRegistry.AI_PATTERNIZER_BE.get(), pos, state);
        this.gridNode = GridHelper.createManagedNode(this, this);
        this.gridNode.setIdlePowerUsage(1.0);
        // 关键：默认不可被其他节点在世界中发现——必须显式开启，否则线缆永远连不上
        this.gridNode.setInWorldNode(true);
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
        gridNodeHostCap.invalidate();
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

    // ---------- IInWorldGridNodeHost（线缆连接的关键） ----------

    @Override
    public IGridNode getGridNode(Direction dir) {
        return gridNode.getNode();
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction facing) {
        if (capability == Capabilities.IN_WORLD_GRID_NODE_HOST) {
            return gridNodeHostCap.cast();
        }
        return super.getCapability(capability, facing);
    }
}
