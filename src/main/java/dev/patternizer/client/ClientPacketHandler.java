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
        // 多配方冲突：当前打开着编写台界面时，弹出配方选择界面
        if ("choose_recipe".equals(result) && detail != null
                && mc.screen instanceof dev.patternizer.client.screen.AiPatternizerScreen aps) {
            aps.openRecipeChoice(List.of(detail.split("\n")));
            return;
        }
        mc.player.displayClientMessage(Component.translatable("message.aipatternizer.encode." + result), false);
        if ("ok".equals(result) && mc.screen instanceof dev.patternizer.client.screen.AiPatternizerScreen aps) {
            aps.onEncodeOk();
        }
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
            // note 兜底截断：即使模型啰嗦，聊天栏也只显示前 80 字（§10.22 提示词纪律）
            String text = detail.length() <= 80 ? detail : detail.substring(0, 80) + "…";
            mc.player.displayClientMessage(Component.literal(" - " + text), false);
        }
    }
}
