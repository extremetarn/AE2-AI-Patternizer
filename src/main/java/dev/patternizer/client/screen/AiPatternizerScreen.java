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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import dev.patternizer.AIPatternizer;
import dev.patternizer.client.llm.LlmGenerateService;
import dev.patternizer.menu.AiPatternizerMenu;
import dev.patternizer.net.LinePlanConfirmPacket;
import dev.patternizer.net.LinePlanRequestPacket;
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
 * 文本区（凹槽 38..100）：按宽度自动换行 + 滚轮滚动 + 迷你滚动条，
 * LLM 长文本不再溢出或被硬截断。
 */
public class AiPatternizerScreen extends AbstractContainerScreen<AiPatternizerMenu> {

    private static final ResourceLocation GUI_TEXTURE = new ResourceLocation(AIPatternizer.MOD_ID,
            "textures/gui/ai_patternizer.png");

    /** 文本区（相对 leftPos/topPos 的偏移与尺寸） */
    private static final int TEXT_X = 12;
    private static final int TEXT_Y = 43;
    private static final int TEXT_WIDTH = 150;
    private static final int CONSOLE_X0 = 8;
    private static final int CONSOLE_Y0 = 38;
    private static final int CONSOLE_X1 = 168;
    private static final int CONSOLE_Y1 = 100;
    private static final int LINE_H = 10;
    private static final int VISIBLE_LINES = (CONSOLE_Y1 - CONSOLE_Y0 - 4) / LINE_H;

    private enum State {
        INPUT, CALLING, PREVIEW, LINE_WAIT, LINE_PLAN, LINE_DONE
    }

    private State state = State.INPUT;
    private EditBox promptBox;
    private Button actionButton;
    private Button backButton;
    private Button lineButton;

    private final List<Component> statusLines = new ArrayList<>();
    private final List<Component> previewLines = new ArrayList<>();
    /** 换行后的展示行与滚动偏移 */
    private final List<FormattedCharSequence> displayLines = new ArrayList<>();
    private int textScroll;

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
                .bounds(this.leftPos + 8, this.topPos + 128, 76, 20)
                .build());
        this.backButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.back"),
                btn -> setState(State.INPUT))
                .bounds(this.leftPos + 92, this.topPos + 128, 76, 20)
                .build());
        this.lineButton = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.aipatternizer.line"),
                btn -> startLinePlan())
                .bounds(this.leftPos + 88, this.topPos + 128, 80, 20)
                .build());

        setState(State.INPUT);
    }

    private void setState(State newState) {
        this.state = newState;
        this.statusLines.clear();
        switch (newState) {
        case INPUT -> {
            this.actionButton.setMessage(Component.translatable("gui.aipatternizer.generate"));
            this.actionButton.visible = true;
            this.actionButton.active = true;
            this.lineButton.visible = true;
            this.backButton.visible = false;
        }
        case CALLING, LINE_WAIT -> {
            this.actionButton.visible = true;
            this.actionButton.active = false;
            this.lineButton.visible = false;
            this.backButton.visible = true;
            this.backButton.setMessage(Component.translatable("gui.aipatternizer.cancel"));
        }
        case PREVIEW, LINE_PLAN -> {
            this.actionButton.setMessage(Component.translatable("gui.aipatternizer.confirm"));
            this.actionButton.visible = true;
            this.actionButton.active = true;
            this.lineButton.visible = false;
            this.backButton.visible = true;
            this.backButton.setMessage(Component.translatable("gui.aipatternizer.back"));
        }
        case LINE_DONE -> {
            this.actionButton.visible = false;
            this.lineButton.visible = false;
            this.backButton.visible = true;
            this.backButton.setMessage(Component.translatable("gui.aipatternizer.back"));
        }
        }
        rebuildDisplay();
    }

    /** 把当前状态对应的源文本按面板宽度换行，重置滚动。 */
    private void rebuildDisplay() {
        this.displayLines.clear();
        List<Component> src = this.state == State.PREVIEW ? this.previewLines : this.statusLines;
        for (Component line : src) {
            this.displayLines.addAll(this.font.split(line, TEXT_WIDTH));
        }
        this.textScroll = 0;
    }

    private int maxTextScroll() {
        return Math.max(0, this.displayLines.size() - VISIBLE_LINES);
    }

    private void onAction() {
        switch (this.state) {
        case INPUT -> startGenerate();
        case PREVIEW -> confirmEncode();
        case LINE_PLAN -> PatternizerNetwork.CHANNEL.sendToServer(new LinePlanConfirmPacket());
        default -> {
        }
        }
    }

    /** 整线补齐：用检索器 Top1 候选作为目标（不走 LLM，确定性分析）。 */
    private void startLinePlan() {
        String prompt = this.promptBox.getValue().trim();
        if (prompt.isEmpty()) {
            this.statusLines.clear();
            this.statusLines.add(Component.translatable("gui.aipatternizer.status.empty_prompt"));
            rebuildDisplay();
            return;
        }
        List<String> candidates = dev.patternizer.client.search.ItemCandidateSearch.search(prompt, 1);
        if (candidates.isEmpty()) {
            this.statusLines.clear();
            this.statusLines.add(Component.translatable("gui.aipatternizer.status.no_target"));
            rebuildDisplay();
            return;
        }
        setState(State.LINE_WAIT);
        this.statusLines.add(Component.translatable("gui.aipatternizer.status.analyzing"));
        rebuildDisplay();
        PatternizerNetwork.CHANNEL.sendToServer(new LinePlanRequestPacket(candidates.get(0), 1));
    }

    /** S2C 整线方案回执：展示缺口汇总，等玩家确认批量编码。 */
    public void onLinePlanResult(dev.patternizer.net.LinePlanResultPacket packet) {
        this.statusLines.clear();
        if (packet.missingCount() == 0) {
            this.statusLines.add(Component.translatable("gui.aipatternizer.line.nothing_missing",
                    packet.totalNodes()));
            for (String manual : packet.manualTop()) {
                this.statusLines.add(Component.translatable("gui.aipatternizer.line.manual_item",
                        displayNameOf(manual)));
            }
            setState(State.LINE_DONE);
            return;
        }
        this.statusLines.add(Component.translatable("gui.aipatternizer.line.summary",
                packet.totalNodes(), packet.missingCount(), packet.cycleCount(), packet.manualCount()));
        for (String entry : packet.missingTop()) {
            String id = entry.substring(0, entry.lastIndexOf(' '));
            String count = entry.substring(entry.lastIndexOf(' ') + 1);
            this.statusLines.add(Component.literal("  " + displayNameOf(id) + " " + count));
        }
        if (!packet.cycleIds().isEmpty()) {
            this.statusLines.add(Component.translatable("gui.aipatternizer.line.cycles"));
            for (String id : packet.cycleIds()) {
                this.statusLines.add(Component.literal("  " + displayNameOf(id)));
            }
        }
        setState(State.LINE_PLAN);
    }

    /** S2C 落位报告。 */
    public void onLinePlaceResult(dev.patternizer.net.LinePlaceResultPacket packet) {
        this.statusLines.clear();
        this.statusLines.add(Component.translatable("gui.aipatternizer.line.place_summary",
                packet.placed(), packet.todoCount()));
        for (String line : packet.lines()) {
            this.statusLines.add(Component.literal("  " + line));
        }
        setState(State.LINE_DONE);
    }

    private void startGenerate() {
        String prompt = this.promptBox.getValue().trim();
        if (prompt.isEmpty()) {
            this.statusLines.clear();
            this.statusLines.add(Component.translatable("gui.aipatternizer.status.empty_prompt"));
            rebuildDisplay();
            return;
        }
        setState(State.CALLING);
        this.statusLines.add(Component.translatable("gui.aipatternizer.status.calling"));
        rebuildDisplay();
        LlmGenerateService.generate(prompt, result -> Minecraft.getInstance().execute(() -> {
            if (result instanceof LlmGenerateService.Result.Ok ok) {
                // 预检：目标类样板（合成/切石/锻造）在客户端配方本里查无配方时，
                // 直接给出改道建议，不让玩家走到编码失败那一步（2026-10-01 摩根案）
                if (!hasRecipeForTarget(ok.spec())) {
                    setState(State.INPUT);
                    this.statusLines.add(Component.translatable("gui.aipatternizer.no_recipe",
                            ok.spec().target));
                    this.statusLines.add(Component.translatable("gui.aipatternizer.no_recipe_hint"));
                    rebuildDisplay();
                    return;
                }
                this.confirmedSpec = ok.spec();
                buildPreview(ok.spec());
                setState(State.PREVIEW);
            } else if (result instanceof LlmGenerateService.Result.Failed failed) {
                setState(State.INPUT);
                for (String line : failed.errorLines()) {
                    this.statusLines.add(describeError(line));
                }
                rebuildDisplay();
            }
        }));
    }

    /** 客户端配方本预检：目标类样板是否存在任何可编码路线（v0.11 全量枚举版）。 */
    private static boolean hasRecipeForTarget(PatternSpec spec) {
        if (spec.type == PatternSpec.Type.PROCESSING || spec.target == null) {
            return true;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return true;
        }
        return !dev.patternizer.net.RecipeResolver
                .enumerate(mc.level.getRecipeManager(), mc.level, spec.target)
                .isEmpty();
    }

    /** 把内部错误码翻译成玩家能懂的提示（§失败要可解释）。 */
    private Component describeError(String line) {
        if (line.startsWith("error.llm.no_api_key")) {
            return Component.translatable("gui.aipatternizer.fail.no_key");
        }
        if (line.startsWith("error.llm.io|")) {
            return Component.translatable("gui.aipatternizer.fail.io",
                    line.substring("error.llm.io|".length()));
        }
        if (line.startsWith("error.llm.http|")) {
            return Component.translatable("gui.aipatternizer.fail.http",
                    line.substring("error.llm.http|".length()));
        }
        if (line.startsWith("error.llm.")) {
            return Component.translatable("gui.aipatternizer.fail.llm_other", line);
        }
        // error.spec.*：首段是 lang key，其余是参数（空格或 | 分隔）
        String normalized = line.replace('|', ' ');
        String[] parts = normalized.split(" ", 2);
        String detail = parts.length > 1
                ? Component.translatable(parts[0], (Object[]) parts[1].split(" ")).getString()
                : parts[0];
        return Component.translatable("gui.aipatternizer.fail.spec", detail);
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

    /** 编码成功：在状态区展示配套搭建清单（§6.2 / M3.5）。 */
    public void onEncodeOk() {
        this.statusLines.clear();
        PatternSpec spec = this.confirmedSpec;
        if (spec == null) {
            rebuildDisplay();
            return;
        }
        List<Component> setup = new ArrayList<>();
        for (Entry e : spec.inputs) {
            switch (e.role) {
            case CATALYST_RETURNED -> setup.add(Component.translatable(
                    "gui.aipatternizer.setup.returned", displayNameOf(e.item)));
            case CATALYST_PREPLACED -> setup.add(Component.translatable(
                    "gui.aipatternizer.setup.preplaced", displayNameOf(e.item)));
            case CATALYST_DURABILITY -> {
                int uses = spec.durabilityBatch != null ? spec.durabilityBatch.usesPerTool : 0;
                setup.add(Component.translatable(
                        "gui.aipatternizer.setup.durability", displayNameOf(e.item), uses));
            }
            default -> {
            }
            }
        }
        if (setup.isEmpty()) {
            rebuildDisplay();
            return;
        }
        this.statusLines.add(Component.translatable("gui.aipatternizer.setup.title"));
        this.statusLines.addAll(setup);
        rebuildDisplay();
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 输入框聚焦时吞掉除 ESC 外的全部按键——否则打英文会触发游戏快捷键
        //（比如按 E 关界面），与铁砧改名界面同款处理
        if (keyCode == 256) {
            this.onClose();
            return true;
        }
        if (this.promptBox != null && this.promptBox.canConsumeInput()) {
            this.promptBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= this.leftPos + CONSOLE_X0 && mouseX <= this.leftPos + CONSOLE_X1
                && mouseY >= this.topPos + CONSOLE_Y0 && mouseY <= this.topPos + CONSOLE_Y1) {
            this.textScroll = clamp(this.textScroll - (int) Math.signum(delta), 0, maxTextScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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
        // 右上角齿轮：打开 API 设置
        boolean gearHovered = mouseX >= this.leftPos + 158 && mouseX <= this.leftPos + 170
                && mouseY >= this.topPos + 3 && mouseY <= this.topPos + 15;
        graphics.drawString(this.font, "⚙", this.leftPos + 160, this.topPos + 5,
                gearHovered ? 0xFFFFD75E : 0xFF606060, false);

        // 文本区：换行 + 滚动
        this.textScroll = clamp(this.textScroll, 0, maxTextScroll());
        int textX = this.leftPos + TEXT_X;
        int textY = this.topPos + TEXT_Y;
        int end = Math.min(this.displayLines.size(), this.textScroll + VISIBLE_LINES);
        for (int i = this.textScroll; i < end; i++) {
            graphics.drawString(this.font, this.displayLines.get(i), textX, textY, 0xFFFFFFFF, false);
            textY += LINE_H;
        }

        // 迷你滚动条与滚动提示
        if (maxTextScroll() > 0) {
            int trackX = this.leftPos + CONSOLE_X1 - 6;
            int trackH = CONSOLE_Y1 - CONSOLE_Y0 - 4;
            int trackY = this.topPos + CONSOLE_Y0 + 2;
            graphics.fill(trackX, trackY, trackX + 4, trackY + trackH, 0xFF1A1A24);
            int thumbH = Math.max(8, trackH * VISIBLE_LINES / this.displayLines.size());
            int thumbY = trackY + (trackH - thumbH) * this.textScroll / maxTextScroll();
            graphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xFF8A8A9A);
        }

        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题与状态行在 render() 中按绝对坐标绘制
    }
}
