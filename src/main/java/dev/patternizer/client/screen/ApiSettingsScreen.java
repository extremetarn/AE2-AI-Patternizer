package dev.patternizer.client.screen;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.patternizer.client.llm.OpenAiCompatibleClient;
import dev.patternizer.config.PatternizerClientConfig;

/**
 * API 设置界面（§4.3）：Base URL / API Key / 模型，一键拉取模型列表点击选择。
 * 可从两个入口进入：mod 列表「Config」按钮、编写台界面右上角齿轮。
 * 保存即写入客户端配置文件，API Key 用密码框显示（§10.9）。
 */
public class ApiSettingsScreen extends Screen {

    @Nullable
    private final Screen parent;

    private EditBox baseUrlBox;
    private EditBox apiKeyBox;
    private EditBox modelBox;
    private Button fetchButton;

    private final List<String> modelList = new ArrayList<>();
    private Component statusLine = Component.empty();

    public ApiSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.aipatternizer.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 56;

        this.baseUrlBox = addBox(centerX - 150, y, 300, PatternizerClientConfig.BASE_URL.get());
        y += 34;
        this.apiKeyBox = addBox(centerX - 150, y, 300, PatternizerClientConfig.API_KEY.get());
        this.apiKeyBox
                .setFormatter((text, cursor) -> Component.literal("•".repeat(text.length())).getVisualOrderText());
        y += 34;
        this.modelBox = addBox(centerX - 150, y, 300, PatternizerClientConfig.MODEL.get());
        y += 34;

        this.fetchButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.config.fetch"),
                btn -> fetchModels())
                .bounds(centerX - 150, y, 147, 20)
                .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> saveAndClose())
                .bounds(centerX + 3, y, 147, 20)
                .build());
    }

    private EditBox addBox(int x, int y, int width, String initial) {
        EditBox box = new EditBox(this.font, x, y, width, 20, Component.empty());
        box.setMaxLength(512);
        box.setValue(initial);
        this.addRenderableWidget(box);
        return box;
    }

    private void fetchModels() {
        saveFields();
        this.fetchButton.active = false;
        this.statusLine = Component.translatable("gui.aipatternizer.config.fetching");
        this.modelList.clear();

        new OpenAiCompatibleClient().fetchModels().whenComplete((models, error) -> {
            Minecraft.getInstance().execute(() -> {
                this.fetchButton.active = true;
                if (error != null) {
                    String code = error.getCause() instanceof OpenAiCompatibleClient.LlmException le
                            ? le.getMessage()
                            : "error.llm.unknown";
                    this.statusLine = Component.translatable("gui.aipatternizer.config.fetch_fail", code);
                    return;
                }
                this.modelList.addAll(models);
                this.statusLine = this.modelList.isEmpty()
                        ? Component.translatable("gui.aipatternizer.config.fetch_empty")
                        : Component.translatable("gui.aipatternizer.config.fetch_ok", this.modelList.size());
            });
        });
    }

    private void saveFields() {
        PatternizerClientConfig.BASE_URL.set(this.baseUrlBox.getValue().trim());
        PatternizerClientConfig.API_KEY.set(this.apiKeyBox.getValue().trim());
        PatternizerClientConfig.MODEL.set(this.modelBox.getValue().trim());
        PatternizerClientConfig.save();
    }

    private void saveAndClose() {
        saveFields();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点击模型列表行选中该模型
        int centerX = this.width / 2;
        int listY = this.width == 0 ? 0 : 218;
        for (int i = 0; i < Math.min(this.modelList.size(), 12); i++) {
            int rowY = listY + i * 14;
            if (mouseX >= centerX - 150 && mouseX <= centerX + 150 && mouseY >= rowY && mouseY < rowY + 14) {
                this.modelBox.setValue(this.modelList.get(i));
                this.statusLine = Component.translatable("gui.aipatternizer.config.model_selected",
                        this.modelList.get(i));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 28, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("gui.aipatternizer.config.base_url"),
                centerX - 150, 46, 0xFFA0A0A0);
        graphics.drawString(this.font, Component.translatable("gui.aipatternizer.config.api_key"),
                centerX - 150, 80, 0xFFA0A0A0);
        graphics.drawString(this.font, Component.translatable("gui.aipatternizer.config.model"),
                centerX - 150, 114, 0xFFA0A0A0);
        graphics.drawCenteredString(this.font, this.statusLine, centerX, 196, 0xFFFFD75E);

        if (!this.modelList.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.aipatternizer.config.models_title"),
                    centerX - 150, 206, 0xFFA0A0A0);
            int listY = 218;
            for (int i = 0; i < Math.min(this.modelList.size(), 12); i++) {
                int rowY = listY + i * 14;
                boolean hovered = mouseX >= centerX - 150 && mouseX <= centerX + 150
                        && mouseY >= rowY && mouseY < rowY + 14;
                if (hovered) {
                    graphics.fill(centerX - 150, rowY, centerX + 150, rowY + 13, 0x33FFFFFF);
                }
                String name = this.modelList.get(i);
                if (name.equals(this.modelBox.getValue().trim())) {
                    name = "▶ " + name;
                }
                graphics.drawString(this.font, name, centerX - 146, rowY + 2, 0xFFFFFFFF, false);
            }
            if (this.modelList.size() > 12) {
                graphics.drawString(this.font,
                        Component.translatable("gui.aipatternizer.more_lines", this.modelList.size() - 12),
                        centerX - 146, listY + 12 * 14 + 2, 0xFFAAAAAA, false);
            }
        }

        graphics.drawCenteredString(this.font, Component.translatable("gui.aipatternizer.config.key_hint"),
                centerX, this.height - 20, 0xFF808080);
    }
}
