package dev.patternizer.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import dev.patternizer.spec.PatternSpecValidator.ValidationError;

/**
 * S2C 包的客户端处理（与 EncodeResultPacket 分离，避免服务端类加载客户端类）。
 */
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void onEncodeResult(String result, String detail) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.player.displayClientMessage(Component.translatable("message.aipatternizer.encode." + result), false);
        if (detail == null || detail.isEmpty()) {
            return;
        }
        if ("invalid_spec".equals(result)) {
            // 服务端校验错误：逐行反序列化为结构化错误并本地化
            for (String line : detail.split("\n")) {
                ValidationError ve = ValidationError.deserialize(line);
                mc.player.displayClientMessage(
                        Component.literal(" - ").append(
                                Component.translatable(ve.key(), (Object[]) ve.args())),
                        false);
            }
        } else {
            mc.player.displayClientMessage(Component.literal(" - " + detail), false);
        }
    }
}
