package dev.patternizer.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import dev.patternizer.client.llm.LlmGenerateService;
import dev.patternizer.menu.AiPatternizerMenu;
import dev.patternizer.net.PatternSpecRequestPacket;
import dev.patternizer.net.PatternizerNetwork;
import dev.patternizer.spec.PatternSpec;
import dev.patternizer.spec.PatternSpec.Entry;
import dev.patternizer.spec.PatternSpecJson;

/**
 * AI 样板编写台界面（M2）。
 * 三态：INPUT（输入需求）→ CALLING（调用 LLM）→ PREVIEW（预览确认后发包编码）。
 * 预览面板是交互安全闸（§4.1）：AI 输出未经玩家确认不会落地。
 */
public class AiPatternizerScreen extends AbstractContainerScreen<AiPatternizerMenu> {

    private enum State {
        INPUT, CALLING, PREVIEW
    }

    private State state = State.INPUT;
    private EditBox promptBox;
    private Button actionButton;
    private Button backButton;
    private final List<Component> statusLines = new ArrayList<>();
    private final List<Component> previewLines = new ArrayList<>();
    private PatternSpec confirmedSpec;

    public AiPatternizerScreen(AiPatternizerMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();

        this.promptBox = new EditBox(this.font, this.leftPos + 8, this.topPos + 8,
                this.imageWidth - 16, 18,
                Component.translatable("gui.aipatternizer.prompt_hint"));
        this.promptBox.setHint(Component.translatable("gui.aipatternizer.prompt_hint"));
        this.promptBox.setMaxLength(256);
        this.addRenderableWidget(this.promptBox);

        this.actionButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.generate"),
                btn -> onAction())
                .bounds(this.leftPos + 8, this.topPos + 140, 80, 20)
                .build());
        this.backButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.back"),
                btn -> setState(State.INPUT))
                .bounds(this.leftPos + 92, this.topPos + 140, 76, 20)
                .build());

        setState(State.INPUT);
    }

    private void setState(State newState) {
        this.state = newState;
        this.statusLines.clear();
        switch (newState) {
        case INPUT -> {
            this.promptBox.visible = true;
            this.actionButton.setMessage(Component.translatable("gui.aipatternizer.generate"));
            this.actionButton.active = true;
            this.backButton.visible = false;
        }
        case CALLING -> {
            this.promptBox.visible = true;
            this.actionButton.active = false;
            this.backButton.visible = true;
            this.backButton.setMessage(Component.translatable("gui.aipatternizer.cancel"));
        }
        case PREVIEW -> {
            this.promptBox.visible = false;
            this.actionButton.setMessage(Component.translatable("gui.aipatternizer.confirm"));
            this.actionButton.active = true;
            this.backButton.visible = true;
            this.backButton.setMessage(Component.translatable("gui.aipatternizer.back"));
        }
        }
    }

    private void onAction() {
        switch (this.state) {
        case INPUT -> startGenerate();
        case PREVIEW -> confirmEncode();
        default -> {
        }
        }
    }

    private void startGenerate() {
        String prompt = this.promptBox.getValue().trim();
        if (prompt.isEmpty()) {
            this.statusLines.clear();
            this.statusLines.add(Component.translatable("gui.aipatternizer.status.empty_prompt"));
            return;
        }
        setState(State.CALLING);
        this.statusLines.add(Component.translatable("gui.aipatternizer.status.calling"));
        LlmGenerateService.generate(prompt, result -> Minecraft.getInstance().execute(() -> {
            if (result instanceof LlmGenerateService.Result.Ok ok) {
                this.confirmedSpec = ok.spec();
                buildPreview(ok.spec());
                setState(State.PREVIEW);
            } else if (result instanceof LlmGenerateService.Result.Failed failed) {
                setState(State.INPUT);
                for (String line : failed.errorLines()) {
                    this.statusLines.add(Component.translatable("gui.aipatternizer.status.failed", line));
                }
            }
        }));
    }

    private void confirmEncode() {
        if (this.confirmedSpec == null) {
            setState(State.INPUT);
            return;
        }
        PatternizerNetwork.CHANNEL.sendToServer(
                new PatternSpecRequestPacket(PatternSpecJson.write(this.confirmedSpec)));
        setState(State.INPUT);
    }

    private void buildPreview(PatternSpec spec) {
        this.previewLines.clear();
        this.previewLines.add(Component.translatable("gui.aipatternizer.preview.title"));
        if (spec.type != PatternSpec.Type.PROCESSING) {
            this.previewLines.add(Component.translatable("gui.aipatternizer.preview.crafting_target",
                    displayNameOf(spec.target)));
        }
        if (spec.type == PatternSpec.Type.PROCESSING) {
            this.previewLines.add(Component.translatable("gui.aipatternizer.preview.inputs"));
            for (Entry e : spec.inputs) {
                this.previewLines.add(Component.literal("  " + describeEntry(e)));
            }
            this.previewLines.add(Component.translatable("gui.aipatternizer.preview.outputs"));
            for (Entry e : spec.outputs) {
                this.previewLines.add(Component.literal("  " + describeEntry(e)));
            }
        }
        if (spec.note != null && !spec.note.isBlank()) {
            this.previewLines.add(Component.translatable("gui.aipatternizer.preview.note", spec.note));
        }
    }

    private String describeEntry(Entry e) {
        String base;
        if (e.isFluid()) {
            base = e.amount + "mB " + e.fluid;
        } else {
            base = e.count + "× " + displayNameOf(e.item);
        }
        if (e.role != PatternSpec.Role.CONSUMED) {
            base += Component.translatable("gui.aipatternizer.role." + e.role.name()).getString();
        }
        return base;
    }

    private static String displayNameOf(String itemId) {
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        return item != null ? new ItemStack(item).getHoverName().getString() : itemId;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFFC6C6C6);
        graphics.fill(x + 3, y + 3, x + this.imageWidth - 3, y + this.imageHeight - 3, 0xFF3B3F4C);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int textX = this.leftPos + 8;
        int textY = this.topPos + 32;
        List<Component> lines = this.state == State.PREVIEW ? this.previewLines : this.statusLines;
        for (Component line : lines) {
            graphics.drawString(this.font, line, textX, textY, 0xFFFFFFFF, false);
            textY += 10;
        }

        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题由 render() 中的状态行区域统一绘制，避免与输入框重叠
    }
}
