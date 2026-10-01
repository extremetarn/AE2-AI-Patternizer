package dev.patternizer.net;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.networking.IGrid;
import dev.patternizer.menu.AiPatternizerMenu;
import dev.patternizer.planner.GapAnalyzer;
import dev.patternizer.planner.GapAnalyzer.PlanResult;
import dev.patternizer.planner.NetworkSurvey;

/**
 * C2S：整线规划请求（§8 LinePlanRequest）。
 * 服务端：网络现状调查 → 缺口分析 → 缓存方案 → 回执 LinePlanResult。
 */
public class LinePlanRequestPacket {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final String target;
    private final long count;

    public LinePlanRequestPacket(String target, long count) {
        this.target = target;
        this.count = count;
    }

    public static void encode(LinePlanRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.target, 256);
        buf.writeVarLong(msg.count);
    }

    public static LinePlanRequestPacket decode(FriendlyByteBuf buf) {
        return new LinePlanRequestPacket(buf.readUtf(256), buf.readVarLong());
    }

    public static void handle(LinePlanRequestPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (!(player.containerMenu instanceof AiPatternizerMenu menu) || menu.getBlockEntity() == null) {
                PatternizerNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new LinePlanResultPacket(0, 0, 0, 0, List.of(), List.of(), List.of()));
                return;
            }
            IGrid grid = menu.getBlockEntity().getGrid();
            NetworkSurvey survey = grid != null ? NetworkSurvey.of(grid) : NetworkSurvey.empty();

            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(msg.target));
            PlanResult result;
            if (item == null) {
                LOGGER.warn("[aipatternizer] line plan: unknown target item '{}'", msg.target);
                result = new PlanResult(List.of(), List.of(), List.of(), 0);
            } else {
                result = GapAnalyzer.analyze(player.server.getRecipeManager(), player.level(), survey,
                        item, Math.max(1, msg.count));
            }
            LOGGER.info("[aipatternizer] line plan target={} | survey craftable={} patterns={} stock={} | "
                    + "canCraft(target)={} stockOf(target)={} | nodes={} missing={} cycles={} manual={}",
                    msg.target,
                    survey.craftableItems().size(), survey.patternOutputs().size(), survey.stock().size(),
                    item != null && survey.canCraft(item), item != null ? survey.stockOf(item) : -1,
                    result.totalNodes(), result.missing().size(), result.cycleItems().size(),
                    result.manualItems().size());
            LinePlanStateCache.put(player.getUUID(), result);

            List<String> missingTop = new ArrayList<>();
            for (var node : result.missing()) {
                missingTop.add(ForgeRegistries.ITEMS.getKey(node.item()) + " x" + node.amount());
                if (missingTop.size() >= 16) {
                    break;
                }
            }
            List<String> cycleIds = result.cycleItems().stream()
                    .map(i -> String.valueOf(ForgeRegistries.ITEMS.getKey(i))).toList();
            List<String> manualTop = result.manualItems().stream()
                    .map(i -> String.valueOf(ForgeRegistries.ITEMS.getKey(i))).limit(8).toList();

            PatternizerNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new LinePlanResultPacket(result.totalNodes(), result.missing().size(),
                            cycleIds.size(), result.manualItems().size(), missingTop, cycleIds, manualTop));
        });
        ctx.setPacketHandled(true);
    }
}
