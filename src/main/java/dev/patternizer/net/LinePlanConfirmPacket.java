package dev.patternizer.net;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import dev.patternizer.menu.AiPatternizerMenu;
import dev.patternizer.planner.AutoPlacer;
import dev.patternizer.planner.AutoPlacer.PlacedPattern;
import dev.patternizer.planner.GapAnalyzer.PlanResult;

/**
 * C2S：整线确认（§8 LinePlanConfirm）。
 * 服务端：取缓存方案 → 批量编码（网格扣空白样板）→ 自动落位 → 回执落位报告。
 */
public class LinePlanConfirmPacket {

    private static final ResourceLocation BLANK_PATTERN_ID = new ResourceLocation("ae2", "blank_pattern");

    public static void encode(LinePlanConfirmPacket msg, FriendlyByteBuf buf) {
    }

    public static LinePlanConfirmPacket decode(FriendlyByteBuf buf) {
        return new LinePlanConfirmPacket();
    }

    public static void handle(LinePlanConfirmPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            PlanResult plan = LinePlanStateCache.take(player.getUUID());
            if (plan == null) {
                PatternizerNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new LinePlaceResultPacket(0, 0, List.of("方案已过期，请重新分析")));
                return;
            }
            if (!(player.containerMenu instanceof AiPatternizerMenu menu) || menu.getBlockEntity() == null) {
                PatternizerNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new LinePlaceResultPacket(0, 0, List.of("请保持编写台界面打开")));
                return;
            }
            IGrid grid = menu.getBlockEntity().getGrid();
            Item blankPattern = ForgeRegistries.ITEMS.getValue(BLANK_PATTERN_ID);
            IActionSource source = IActionSource.ofMachine(menu.getBlockEntity());

            // 批量编码：空白样板网络扣取优先，编写台槽位兜底
            List<PlacedPattern> encoded = new ArrayList<>();
            int failed = 0;
            int blankShortage = 0;
            var storage = menu.getStorage();
            for (var node : plan.missing()) {
                if (blankPattern == null) {
                    break;
                }
                boolean gotBlank = false;
                if (grid != null
                        && GridPatternIO.extract(grid, AEItemKey.of(new ItemStack(blankPattern)), 1,
                                source) == 1) {
                    gotBlank = true;
                } else if (storage.getStackInSlot(0).getItem() == blankPattern) {
                    storage.extractItem(0, 1, false);
                    gotBlank = true;
                }
                if (!gotBlank) {
                    blankShortage++;
                    continue;
                }
                var resolution = RecipeResolver.encodeRecipeFor(player.level(), node.recipe());
                if (resolution instanceof RecipeResolver.Resolution.Encoded enc) {
                    encoded.add(new PlacedPattern(enc.stack(), node.recipeTypeId()));
                } else {
                    failed++;
                }
            }

            // 自动落位（v0.8）
            AutoPlacer.PlaceResult placeResult = grid != null
                    ? AutoPlacer.placeAll(grid, player.level(), encoded)
                    : new AutoPlacer.PlaceResult(0, List.of(), List.of());

            List<String> lines = new ArrayList<>();
            if (blankShortage > 0) {
                lines.add("空白样板不足，剩余 " + blankShortage + " 张未编码");
            }
            if (failed > 0) {
                lines.add(failed + " 张编码失败");
            }
            lines.addAll(placeResult.placedLines());
            lines.addAll(placeResult.todoLines());

            PatternizerNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new LinePlaceResultPacket(placeResult.placed(),
                            placeResult.todoLines().size() + blankShortage + failed, lines));
        });
        ctx.setPacketHandled(true);
    }
}
