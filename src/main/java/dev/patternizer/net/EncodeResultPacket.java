package dev.patternizer.net;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import dev.patternizer.client.ClientPacketHandler;

/**
 * S2C：样板编码结果回执。result 为结果码，detail 为附加信息
 * （校验错误序列化行 / 配方名等，可空）。
 */
public class EncodeResultPacket {

    private final String result;
    private final String detail;

    public EncodeResultPacket(String result, String detail) {
        this.result = result;
        this.detail = detail;
    }

    public String result() {
        return result;
    }

    public String detail() {
        return detail;
    }

    public static void encode(EncodeResultPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.result);
        buf.writeBoolean(msg.detail != null);
        if (msg.detail != null) {
            buf.writeUtf(msg.detail, 8192);
        }
    }

    public static EncodeResultPacket decode(FriendlyByteBuf buf) {
        String result = buf.readUtf();
        String detail = buf.readBoolean() ? buf.readUtf(8192) : null;
        return new EncodeResultPacket(result, detail);
    }

    public static void handle(EncodeResultPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.onEncodeResult(msg.result(), msg.detail())));
        ctx.setPacketHandled(true);
    }
}
