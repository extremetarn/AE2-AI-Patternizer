package dev.patternizer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * S2C 包的客户端处理（与 EncodeResultPacket 分离，避免服务端类加载客户端类）。
 */
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void onEncodeResult(String result) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("message.aipatternizer.encode." + result), false);
        }
    }
}
