package dev.patternizer.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import dev.patternizer.menu.AiPatternizerMenu;
import dev.patternizer.net.PatternSpecRequestPacket;
import dev.patternizer.net.PatternizerNetwork;

/**
 * AI 样板编写台界面（M1 空壳）。
 * 文本框是 M2 LLM prompt 的占位；按钮触发假数据编码链路。
 */
public class AiPatternizerScreen extends AbstractContainerScreen<AiPatternizerMenu> {

    private EditBox promptBox;

    public AiPatternizerScreen(AiPatternizerMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();

        int boxX = this.leftPos + 8;
        int boxY = this.topPos + 8;
        this.promptBox = new EditBox(this.font, boxX, boxY, this.imageWidth - 16, 18,
                Component.translatable("gui.aipatternizer.prompt_hint"));
        this.promptBox.setHint(Component.translatable("gui.aipatternizer.prompt_hint"));
        this.promptBox.setMaxLength(256);
        this.addRenderableWidget(this.promptBox);

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.generate"),
                btn -> PatternizerNetwork.CHANNEL.sendToServer(new PatternSpecRequestPacket()))
                .bounds(this.leftPos + 52, this.topPos + 58, 100, 20)
                .build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        // M1：纯色面板占位，正式材质后续里程碑再画
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFFC6C6C6);
        graphics.fill(x + 3, y + 3, x + this.imageWidth - 3, y + this.imageHeight - 3, 0xFF3B3F4C);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, 8, -14, 0xFFFFFFFF, false);
    }
}
