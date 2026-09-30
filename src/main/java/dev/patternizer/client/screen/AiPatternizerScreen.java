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

import dev.patternizer.AIPatternizer;
import dev.patternizer.client.llm.LlmGenerateService;
import dev.patternizer.menu.AiPatternizerMenu;
import dev.patternizer.net.PatternSpecRequestPacket;
import dev.patternizer.net.PatternizerNetwork;
import dev.patternizer.spec.PatternSpec;
import dev.patternizer.spec.PatternSpec.Entry;
import dev.patternizer.spec.PatternSpecJson;

/**
 * AI 样板编写台界面。
 * 三态：INPUT（输入需求）→ CALLING（调用 LLM）→ PREVIEW（预览确认后发包编码）。
 * 预览面板是交互安全闸（§4.1）：AI 输出未经玩家确认不会落地。
 *
 * 布局（176×222，底图 assets/aipatternizer/textures/gui/ai_patternizer.png）：
 * 标题 y=5；输入框 16..34；状态/预览凹槽 38..100；机器槽 106；按钮 128；背包 150/204。
 */
public class AiPatternizerScreen extends AbstractContainerScreen<AiPatternizerMenu> {

    private static final ResourceLocation GUI_TEXTURE = new ResourceLocation(AIPatternizer.MOD_ID,
            "textures/gui/ai_patternizer.png");
    private static final int MAX_TEXT_LINES = 5;

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
        this.imageHeight = 222;
    }

    @Override
    protected void init() {
        super.init();

        this.promptBox = new EditBox(this.font, this.leftPos + 10, this.topPos + 16, 156, 18,
                Component.translatable("gui.aipatternizer.prompt_hint"));
        this.promptBox.setHint(Component.translatable("gui.aipatternizer.prompt_hint"));
        this.promptBox.setMaxLength(256);
        this.promptBox.setBordered(true);
        this.addRenderableWidget(this.promptBox);

        this.actionButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.generate"),
                btn -> onAction())
                .bounds(this.leftPos + 8, this.topPos + 128, 80, 20)
                .build());
        this.backButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.back"),
                btn -> setState(State.INPUT))
                .bounds(this.leftPos + 92, this.topPos + 128, 76, 20)
                .build());

        setState(State.INPUT);
    }

    private void setState(State newState) {
        this.state = newState;
        this.statusLines.clear();
        switch (newState) {
        case INPUT -> {
            this.actionButton.setMessage(Component.translatable("gui.aipatternizer.generate"));
            this.actionButton.active = true;
            this.backButton.visible = false;
        }
        case CALLING -> {
            this.actionButton.active = false;
            this.backButton.visible = true;
            this.backButton.setMessage(Component.translatable("gui.aipatternizer.cancel"));
        }
        case PREVIEW -> {
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

    /** 服务端回传多配方冲突：弹出选择界面（M3 / §10.5 不猜原则）。 */
    public void openRecipeChoice(java.util.List<String> recipeIds) {
        if (this.confirmedSpec == null) {
            return;
        }
        Minecraft.getInstance().setScreen(new RecipeChoiceScreen(this, this.confirmedSpec, recipeIds));
    }

    private void buildPreview(PatternSpec spec) {
        this.previewLines.clear();
        if (spec.type != PatternSpec.Type.PROCESSING) {
            this.previewLines.add(Component.translatable("gui.aipatternizer.preview.crafting_target",
                    displayNameOf(spec.target)));
        } else {
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
        graphics.blit(GUI_TEXTURE, this.leftPos, this.topPos, 0, 0,
                this.imageWidth, this.imageHeight, this.imageWidth, this.imageHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.title, this.leftPos + 8, this.topPos + 5, 0xFF404040, false);
        // 右上角齿轮：打开 API 设置（Base URL / Key / 模型列表）
        boolean gearHovered = mouseX >= this.leftPos + 158 && mouseX <= this.leftPos + 170
                && mouseY >= this.topPos + 3 && mouseY <= this.topPos + 15;
        graphics.drawString(this.font, "⚙", this.leftPos + 160, this.topPos + 5,
                gearHovered ? 0xFFFFD75E : 0xFF606060, false);

        List<Component> lines = this.state == State.PREVIEW ? this.previewLines : this.statusLines;
        int textX = this.leftPos + 12;
        int textY = this.topPos + 43;
        int shown = Math.min(lines.size(), MAX_TEXT_LINES);
        for (int i = 0; i < shown; i++) {
            graphics.drawString(this.font, lines.get(i), textX, textY, 0xFFFFFFFF, false);
            textY += 10;
        }
        if (lines.size() > shown) {
            graphics.drawString(this.font,
                    Component.translatable("gui.aipatternizer.more_lines", lines.size() - shown),
                    textX, textY, 0xFFAAAAAA, false);
        }

        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.state == State.INPUT
                && mouseX >= this.leftPos + 158 && mouseX <= this.leftPos + 170
                && mouseY >= this.topPos + 3 && mouseY <= this.topPos + 15) {
            Minecraft.getInstance().setScreen(new ApiSettingsScreen(this));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题与状态行在 render() 中按绝对坐标绘制
    }
}
