package dev.patternizer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.server.level.ServerPlayer;

import dev.patternizer.menu.AiPatternizerMenu;

/**
 * AI 样板编写台（M1：打开即是一个带空白样板槽/输出槽的容器，
 * 暂不做 BlockEntity，槽位内容仅存在于菜单打开期间）。
 */
public class AiPatternizerBlock extends Block {

    public AiPatternizerBlock() {
        super(Properties.of()
                .mapColor(MapColor.METAL)
                .strength(2.0f, 6.0f)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        NetworkHooks.openScreen((ServerPlayer) player,
                new SimpleMenuProvider(
                        (windowId, inv, p) -> new AiPatternizerMenu(windowId, inv, pos),
                        Component.translatable("block.aipatternizer.ai_patternizer")),
                pos);
        return InteractionResult.CONSUME;
    }
}
