package dev.patternizer.client.screen;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import dev.patternizer.net.PatternSpecRequestPacket;
import dev.patternizer.net.PatternizerNetwork;
import dev.patternizer.spec.PatternSpec;
import dev.patternizer.spec.PatternSpecJson;

/**
 * 多配方冲突选择界面（§10.5 不猜原则）。
 * 同一目标有多个配方时列出候选配方 id，玩家点击选定后携带 recipe_id 重新发包编码。
 * 支持滚轮滚动与 PageUp/PageDown。
 */
public class RecipeChoiceScreen extends Screen {

    private static final int LIST_TOP = 60;
    private static final int ROW_H = 14;

    private final Screen parent;
    private final PatternSpec spec;
    private final List<String> recipeIds;
    private int scrollOffset;

    public RecipeChoiceScreen(Screen parent, PatternSpec spec, List<String> recipeIds) {
        super(Component.translatable("gui.aipatternizer.choice.title"));
        this.parent = parent;
        this.spec = spec;
        this.recipeIds = recipeIds;
    }

    private int listBottom() {
        return this.height - 46;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - LIST_TOP) / ROW_H);
    }

    private int maxOffset() {
        return Math.max(0, this.recipeIds.size() - visibleRows());
    }

    private int listX0() {
        return this.width / 2 - 180;
    }

    private int listX1() {
        return this.width / 2 + 180;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.back"),
                btn -> this.minecraft.setScreen(this.parent))
                .bounds(this.width / 2 - 60, this.height - 36, 120, 20)
                .build());
    }

    private void choose(String recipeId) {
        this.spec.recipeId = recipeId;
        PatternizerNetwork.CHANNEL.sendToServer(
                new PatternSpecRequestPacket(PatternSpecJson.write(this.spec)));
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= listX0() && mouseX <= listX1() && mouseY >= LIST_TOP && mouseY < listBottom()) {
            int idx = this.scrollOffset + (int) (mouseY - LIST_TOP) / ROW_H;
            if (idx >= 0 && idx < this.recipeIds.size()) {
                choose(this.recipeIds.get(idx));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= listX0() && mouseX <= listX1() && mouseY >= LIST_TOP && mouseY < listBottom()) {
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

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 20, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.aipatternizer.choice.hint", displayNameOf(this.spec.target)),
                centerX, 36, 0xFFA0A0A0);

        this.scrollOffset = clamp(this.scrollOffset, 0, maxOffset());
        int visible = visibleRows();
        int end = Math.min(this.recipeIds.size(), this.scrollOffset + visible);
        for (int i = this.scrollOffset; i < end; i++) {
            int rowY = LIST_TOP + (i - this.scrollOffset) * ROW_H;
            boolean hovered = mouseX >= listX0() && mouseX <= listX1() && mouseY >= rowY && mouseY < rowY + ROW_H;
            if (hovered) {
                graphics.fill(listX0(), rowY, listX1(), rowY + ROW_H - 1, 0x33FFFFFF);
            }
            graphics.drawString(this.font, this.recipeIds.get(i), listX0() + 4, rowY + 2, 0xFFFFFFFF, false);
        }

        if (this.recipeIds.size() > visible) {
            int trackX = listX1() + 2;
            int trackH = listBottom() - LIST_TOP;
            graphics.fill(trackX, LIST_TOP, trackX + 5, listBottom(), 0xFF2A2A2A);
            int thumbH = Math.max(12, trackH * visible / this.recipeIds.size());
            int thumbY = LIST_TOP
                    + (trackH - thumbH) * this.scrollOffset / Math.max(1, this.recipeIds.size() - visible);
            graphics.fill(trackX, thumbY, trackX + 5, thumbY + thumbH, 0xFF9A9A9A);
            graphics.drawString(this.font,
                    Component.literal((this.scrollOffset + 1) + "-" + end + " / " + this.recipeIds.size()),
                    listX0() + 4, listBottom() - 10, 0xFFAAAAAA, false);
        }
    }

    private static String displayNameOf(String itemId) {
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        return item != null ? new ItemStack(item).getHoverName().getString() : itemId;
    }
}
