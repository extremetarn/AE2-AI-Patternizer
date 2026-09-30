package dev.patternizer.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * C2S：携带客户端校验通过的 PatternSpec JSON，请求服务端编码（§8）。
 * specJson 上限 8KB，服务端反序列化后重新权威校验（§3.2 ⑤⑥）。
 */
public class PatternSpecRequestPacket {

    public static final int MAX_SPEC_BYTES = 8192;

    private final String specJson;

    public PatternSpecRequestPacket(String specJson) {
        this.specJson = specJson;
    }

    public String specJson() {
        return specJson;
    }

    public static void encode(PatternSpecRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.specJson, MAX_SPEC_BYTES);
    }

    public static PatternSpecRequestPacket decode(FriendlyByteBuf buf) {
        return new PatternSpecRequestPacket(buf.readUtf(MAX_SPEC_BYTES));
    }

    public static void handle(PatternSpecRequestPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            String[] result = ServerPatternEncoder.encodeFromSpec(player, msg.specJson());
            PatternizerNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new EncodeResultPacket(result[0], result[1]));
        });
        ctx.setPacketHandled(true);
    }
}
