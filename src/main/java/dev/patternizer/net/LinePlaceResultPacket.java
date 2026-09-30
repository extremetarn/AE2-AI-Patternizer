package dev.patternizer.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C：整线落位报告（§8 LinePlaceResult）。
 */
public class LinePlaceResultPacket {

    private final int placed;
    private final int todoCount;
    private final List<String> lines;

    public LinePlaceResultPacket(int placed, int todoCount, List<String> lines) {
        this.placed = placed;
        this.todoCount = todoCount;
        this.lines = lines;
    }

    public int placed() {
        return placed;
    }

    public int todoCount() {
        return todoCount;
    }

    public List<String> lines() {
        return lines;
    }

    public static void encode(LinePlaceResultPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.placed);
        buf.writeVarInt(msg.todoCount);
        buf.writeVarInt(msg.lines.size());
        for (String line : msg.lines) {
            buf.writeUtf(line, 320);
        }
    }

    public static LinePlaceResultPacket decode(FriendlyByteBuf buf) {
        int placed = buf.readVarInt();
        int todo = buf.readVarInt();
        int size = buf.readVarInt();
        List<String> lines = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            lines.add(buf.readUtf(320));
        }
        return new LinePlaceResultPacket(placed, todo, lines);
    }

    public static void handle(LinePlaceResultPacket msg, java.util.function.Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.patternizer.client.ClientPacketHandler.onLinePlaceResult(msg)));
        context.setPacketHandled(true);
    }
}
