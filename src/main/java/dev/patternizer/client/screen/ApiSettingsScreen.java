package dev.patternizer.client.screen;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

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
 * 模型列表支持滚轮滚动、滚动条拖拽与 PageUp/PageDown 翻页。
 * 可从两个入口进入：mod 列表「Config」按钮、编写台界面右上角齿轮。
 * 保存即写入客户端配置文件，API Key 用密码框显示（§10.9）。
 */
public class ApiSettingsScreen extends Screen {

    private static final int LIST_TOP = 218;
    private static final int LIST_BOTTOM_MARGIN = 34;
    private static final int ROW_H = 14;

    @Nullable
    private final Screen parent;

    private EditBox baseUrlBox;
    private EditBox apiKeyBox;
    private EditBox modelBox;
    private Button fetchButton;

    private final List<String> modelList = new ArrayList<>();
    private Component statusLine = Component.empty();

    /** 列表滚动偏移（行）与滚动条拖拽状态 */
    private int scrollOffset;
    private boolean draggingScrollbar;

    public ApiSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.aipatternizer.config.title"));
        this.parent = parent;
    }

    private int listBottom() {
        return this.height - LIST_BOTTOM_MARGIN;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - LIST_TOP) / ROW_H);
    }

    private int maxOffset() {
        return Math.max(0, this.modelList.size() - visibleRows());
    }

    private int listX0() {
        return this.width / 2 - 150;
    }

    private int listX1() {
        return this.width / 2 + 150;
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
        this.scrollOffset = 0;

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

    private boolean isInListArea(double mouseX, double mouseY) {
        return mouseX >= listX0() && mouseX <= listX1() + 8
                && mouseY >= LIST_TOP && mouseY < listBottom();
    }

    private boolean isOnScrollbar(double mouseX, double mouseY) {
        return this.modelList.size() > visibleRows()
                && mouseX >= listX1() + 2 && mouseX <= listX1() + 7
                && mouseY >= LIST_TOP && mouseY < listBottom();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isOnScrollbar(mouseX, mouseY)) {
            this.draggingScrollbar = true;
            scrollToMouse(mouseY);
            return true;
        }
        if (isInListArea(mouseX, mouseY)) {
            int idx = this.scrollOffset + (int) (mouseY - LIST_TOP) / ROW_H;
            if (idx >= 0 && idx < this.modelList.size()) {
                this.modelBox.setValue(this.modelList.get(idx));
                this.statusLine = Component.translatable("gui.aipatternizer.config.model_selected",
                        this.modelList.get(idx));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar) {
            scrollToMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isInListArea(mouseX, mouseY) || isOnScrollbar(mouseX, mouseY)) {
            this.scrollOffset = clamp(this.scrollOffset - (int) Math.signum(delta), 0, maxOffset());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            this.scrollOffset = clamp(this.scrollOffset + visibleRows(), 0, maxOffset());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            this.scrollOffset = clamp(this.scrollOffset - visibleRows(), 0, maxOffset());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void scrollToMouse(double mouseY) {
        int total = this.modelList.size();
        int visible = visibleRows();
        if (total <= visible) {
            this.scrollOffset = 0;
            return;
        }
        double frac = (mouseY - LIST_TOP) / (double) (listBottom() - LIST_TOP);
        this.scrollOffset = clamp((int) Math.round(frac * (total - visible)), 0, maxOffset());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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
            this.scrollOffset = clamp(this.scrollOffset, 0, maxOffset());
            graphics.drawString(this.font,
                    Component.translatable("gui.aipatternizer.config.models_title"),
                    centerX - 150, 206, 0xFFA0A0A0);

            int visible = visibleRows();
            int end = Math.min(this.modelList.size(), this.scrollOffset + visible);
            for (int i = this.scrollOffset; i < end; i++) {
                int rowY = LIST_TOP + (i - this.scrollOffset) * ROW_H;
                boolean hovered = mouseX >= listX0() && mouseX <= listX1()
                        && mouseY >= rowY && mouseY < rowY + ROW_H;
                if (hovered) {
                    graphics.fill(listX0(), rowY, listX1(), rowY + ROW_H - 1, 0x33FFFFFF);
                }
                String name = this.modelList.get(i);
                if (name.equals(this.modelBox.getValue().trim())) {
                    name = "▶ " + name;
                }
                graphics.drawString(this.font, name, listX0() + 4, rowY + 2, 0xFFFFFFFF, false);
            }

            // 滚动条
            if (this.modelList.size() > visible) {
                int trackX = listX1() + 2;
                int trackH = listBottom() - LIST_TOP;
                graphics.fill(trackX, LIST_TOP, trackX + 5, listBottom(), 0xFF2A2A2A);
                int thumbH = Math.max(12, trackH * visible / this.modelList.size());
                int thumbY = LIST_TOP
                        + (trackH - thumbH) * this.scrollOffset / Math.max(1, this.modelList.size() - visible);
                graphics.fill(trackX, thumbY, trackX + 5, thumbY + thumbH, 0xFF9A9A9A);

                graphics.drawString(this.font,
                        Component.literal((this.scrollOffset + 1) + "-" + end + " / " + this.modelList.size()),
                        listX0() + 4, listBottom() - 10, 0xFFAAAAAA, false);
            }
        }

        graphics.drawCenteredString(this.font, Component.translatable("gui.aipatternizer.config.key_hint"),
                centerX, this.height - 20, 0xFF808080);
    }
}
