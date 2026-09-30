package dev.patternizer.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.patternizer.config.PatternizerClientConfig;

/**
 * API 设置界面（§4.3）。从 mod 列表「Config」按钮进入。
 * API Key 用密码框显示（§10.9）。
 */
public class ApiSettingsScreen extends Screen {

    private final Screen parent;

    private EditBox baseUrlBox;
    private EditBox apiKeyBox;
    private EditBox modelBox;

    public ApiSettingsScreen(Screen parent) {
        super(Component.translatable("gui.aipatternizer.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 60;

        this.baseUrlBox = addBox(centerX - 150, y, 300, PatternizerClientConfig.BASE_URL.get());
        y += 34;
        this.apiKeyBox = addBox(centerX - 150, y, 300, PatternizerClientConfig.API_KEY.get());
        this.apiKeyBox.setFormatter((text, cursor) -> Component.literal("•".repeat(text.length())).getVisualOrderText());
        y += 34;
        this.modelBox = addBox(centerX - 150, y, 300, PatternizerClientConfig.MODEL.get());
        y += 40;

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> saveAndClose())
                .bounds(centerX - 100, y, 200, 20)
                .build());
    }

    private EditBox addBox(int x, int y, int width, String initial) {
        EditBox box = new EditBox(this.font, x, y, width, 20, Component.empty());
        box.setMaxLength(512);
        box.setValue(initial);
        this.addRenderableWidget(box);
        return box;
    }

    private void saveAndClose() {
        PatternizerClientConfig.BASE_URL.set(this.baseUrlBox.getValue().trim());
        PatternizerClientConfig.API_KEY.set(this.apiKeyBox.getValue().trim());
        PatternizerClientConfig.MODEL.set(this.modelBox.getValue().trim());
        PatternizerClientConfig.save();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 30, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("gui.aipatternizer.config.base_url"),
                centerX - 150, 50, 0xFFA0A0A0);
        graphics.drawString(this.font, Component.translatable("gui.aipatternizer.config.api_key"),
                centerX - 150, 84, 0xFFA0A0A0);
        graphics.drawString(this.font, Component.translatable("gui.aipatternizer.config.model"),
                centerX - 150, 118, 0xFFA0A0A0);
        graphics.drawCenteredString(this.font, Component.translatable("gui.aipatternizer.config.key_hint"),
                centerX, this.height - 30, 0xFF808080);
    }
}
