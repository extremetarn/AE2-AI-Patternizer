package dev.patternizer.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C：整线规划方案回执（§8 LinePlanResult）。
 * 携带汇总数据与缺口样例（物品 id 由客户端本地化显示）。
 */
public class LinePlanResultPacket {

    private final int totalNodes;
    private final int missingCount;
    private final int cycleCount;
    private final int manualCount;
    private final List<String> missingTop;
    private final List<String> cycleIds;
    private final List<String> manualTop;

    public LinePlanResultPacket(int totalNodes, int missingCount, int cycleCount, int manualCount,
            List<String> missingTop, List<String> cycleIds, List<String> manualTop) {
        this.totalNodes = totalNodes;
        this.missingCount = missingCount;
        this.cycleCount = cycleCount;
        this.manualCount = manualCount;
        this.missingTop = missingTop;
        this.cycleIds = cycleIds;
        this.manualTop = manualTop;
    }

    public int totalNodes() {
        return totalNodes;
    }

    public int missingCount() {
        return missingCount;
    }

    public int cycleCount() {
        return cycleCount;
    }

    public int manualCount() {
        return manualCount;
    }

    public List<String> missingTop() {
        return missingTop;
    }

    public List<String> cycleIds() {
        return cycleIds;
    }

    public List<String> manualTop() {
        return manualTop;
    }

    public static void encode(LinePlanResultPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.totalNodes);
        buf.writeVarInt(msg.missingCount);
        buf.writeVarInt(msg.cycleCount);
        buf.writeVarInt(msg.manualCount);
        writeStringList(buf, msg.missingTop);
        writeStringList(buf, msg.cycleIds);
        writeStringList(buf, msg.manualTop);
    }

    public static LinePlanResultPacket decode(FriendlyByteBuf buf) {
        return new LinePlanResultPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), readStringList(buf), readStringList(buf), readStringList(buf));
    }

    public static void handle(LinePlanResultPacket msg, java.util.function.Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> dev.patternizer.client.ClientPacketHandler.onLinePlanResult(msg)));
        context.setPacketHandled(true);
    }

    private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) {
            buf.writeUtf(s, 320);
        }
    }

    private static List<String> readStringList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(buf.readUtf(320));
        }
        return out;
    }
}
