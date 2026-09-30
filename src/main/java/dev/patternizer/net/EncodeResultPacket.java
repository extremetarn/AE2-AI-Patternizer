package dev.patternizer.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import dev.patternizer.client.ClientPacketHandler;

/**
 * S2C：样板编码结果回执（M1：仅一个结果码，客户端 toast/聊天提示）。
 */
public class EncodeResultPacket {

    private final String result;

    public EncodeResultPacket(String result) {
        this.result = result;
    }

    public String result() {
        return result;
    }

    public static void encode(EncodeResultPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.result);
    }

    public static EncodeResultPacket decode(FriendlyByteBuf buf) {
        return new EncodeResultPacket(buf.readUtf());
    }

    public static void handle(EncodeResultPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.onEncodeResult(msg.result())));
        ctx.setPacketHandled(true);
    }
}
