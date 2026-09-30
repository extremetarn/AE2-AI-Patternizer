package dev.patternizer.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * C2S：请求生成测试样板（M1 假数据链路，无载荷）。
 * M2 起此包将携带 LLM 生成的 PatternSpec JSON。
 */
public class PatternSpecRequestPacket {

    public static void encode(PatternSpecRequestPacket msg, FriendlyByteBuf buf) {
    }

    public static PatternSpecRequestPacket decode(FriendlyByteBuf buf) {
        return new PatternSpecRequestPacket();
    }

    public static void handle(PatternSpecRequestPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            String result = ServerPatternEncoder.encodeFakeProcessing(player);
            PatternizerNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new EncodeResultPacket(result));
        });
        ctx.setPacketHandled(true);
    }
}
