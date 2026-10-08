package com.ic2newera.client.ui;

import com.ic2newera.integration.llm.LLMChatClient;
import com.ic2newera.integration.llm.LLMChatClient.ChatMessage;
import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.integration.llm.KnowledgeReport;
import com.ic2newera.integration.llm.KnowledgeSource;
import com.ic2newera.integration.llm.FactoryAiContext;
import com.ic2newera.industrial.IndustrialNetworkPackets;
import com.ic2newera.industrial.TerminalProposal;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;

/** Opt-in chat with separately switchable local mod evidence and public documentation retrieval. */
public final class LLMChatScreen extends Screen {
    private final Screen parent;
    private final BlockPos factoryServer;
    private String lastFactorySnapshot = "";
    private TerminalProposal terminalProposal;
    private Button proposalButton;
    private CompletableFuture<?> activeStage;
    private final List<ChatMessage> history = new ArrayList<>();
    private EditBox promptInput;
    private Button sendButton;
    private Button settingsButton;
    private Button cancelButton;
    private Button sourcesButton;
    private CompletableFuture<String> pendingRequest;
    private long requestId;
    private boolean waiting;
    private boolean statusError;
    private boolean openingChild;
    private String partialReply = "";
    private KnowledgeReport knowledgeReport = KnowledgeReport.empty();
    private int scrollOffset;
    private Component status = Component.literal("按设置发送对话、模组资料与公开网页摘录；详情可在资料来源中查看。");

    public LLMChatScreen(Screen parent) {
        this(parent, null);
    }

    public LLMChatScreen(Screen parent, BlockPos factoryServer) {
        super(Component.literal("AI 助手"));
        this.parent = parent;
        this.factoryServer = factoryServer == null ? null : factoryServer.immutable();
    }

    @Override
    protected void init() {
        openingChild = false;
        String draft = promptInput == null ? "" : promptInput.getValue();
        int margin = 10;
        int panelWidth = Math.max(1, Math.min(620, width - margin * 2));
        int left = (width - panelWidth) / 2;
        int inputY = inputY();
        int inputWidth = Math.max(1, panelWidth - 82);
        promptInput = new EditBox(font, left, inputY, inputWidth, 20, Component.literal("输入问题"));
        promptInput.setMaxLength(1200);
        promptInput.setValue(draft);
        addRenderableWidget(promptInput);
        sendButton = addRenderableWidget(new Button(left + inputWidth + 6, inputY,
                Math.max(1, panelWidth - inputWidth - 6), 20, Component.literal("发送"), button -> sendPrompt()));
        int headerButtonWidth = Math.max(1, (panelWidth - 18) / 4);
        addRenderableWidget(new Button(left, 22, headerButtonWidth, 20,
                UiText.fit(font, Component.translatable("gui.back"), headerButtonWidth - 8), button -> onClose()));
        cancelButton = addRenderableWidget(new Button(left + headerButtonWidth + 6, 22,
                headerButtonWidth, 20, UiText.fit(font, Component.literal("取消请求"), headerButtonWidth - 8), button -> cancelRequest()));
        sourcesButton = addRenderableWidget(new Button(left + 2 * (headerButtonWidth + 6), 22,
                headerButtonWidth, 20, UiText.fit(font, Component.literal("资料来源"), headerButtonWidth - 8),
                button -> openChild(new KnowledgeSourcesScreen(this, knowledgeReport))));
        settingsButton = addRenderableWidget(new Button(left + 3 * (headerButtonWidth + 6), 22,
                Math.max(1, panelWidth - 3 * (headerButtonWidth + 6)), 20,
                UiText.fit(font, Component.literal("模型设置"), headerButtonWidth - 8),
                button -> {
                    cancelRequest();
                    openChild(new LLMSettingsScreen(this));
                }));
        if (factoryServer != null) proposalButton = addRenderableWidget(new Button(left, 44, panelWidth, 20,
                UiText.fit(font, Component.literal("审阅终端提案"), panelWidth - 8), button -> reviewProposal()));
        updateSendButton();
    }

    private void sendPrompt() {
        if (waiting) return;
        String prompt = promptInput.getValue().trim();
        if (prompt.isEmpty()) return;
        LLMConfig config = LLMConfig.current();
        if (!config.enabled()) {
            status = Component.literal("AI 服务未开启。请到模型设置开启后再发送；问题已保留。");
            statusError = true;
            return;
        }
        if (config.model().isBlank()) {
            status = Component.literal("尚未配置模型 ID。请到模型设置填写模型；问题已保留。");
            statusError = true;
            return;
        }
        if (config.baseUrl().isBlank()) {
            status = Component.literal("尚未配置 API 地址。请到模型设置填写地址；问题已保留。");
            statusError = true;
            return;
        }
        URI endpoint;
        try { endpoint = LLMConfig.parseBaseUrl(config.baseUrl()); }
        catch (IllegalArgumentException invalid) {
            status = Component.literal("API 地址无效。请到模型设置检查地址；问题已保留。");
            statusError = true;
            return;
        }
        if ("open.bigmodel.cn".equalsIgnoreCase(endpoint.getHost()) && config.apiKey().isBlank()) {
            status = Component.literal("尚未配置 API 密钥。请到模型设置填写密钥；问题已保留。");
            statusError = true;
            return;
        }
        promptInput.setValue("");
        scrollOffset = 0;
        history.add(new ChatMessage("user", prompt));
        if (history.size() > 12) history.subList(0, 2).clear();
        waiting = true;
        partialReply = "";
        statusError = false;
        status = Component.literal("正在整理本机模组资料…");
        knowledgeReport = KnowledgeReport.empty();
        terminalProposal = null;
        updateSendButton();
        long currentRequest = ++requestId;
        CompletableFuture<String> request = new CompletableFuture<>();
        pendingRequest = request;
        CompletableFuture<String> snapshotFuture = factoryServer == null ? CompletableFuture.completedFuture("{}") : FactoryAiContext.request(factoryServer);
        activeStage = snapshotFuture;
        request.whenComplete((reply, failure) -> {
            if (failure != null && activeStage != null) activeStage.cancel(true);
        });
        snapshotFuture.whenComplete((snapshotJson, snapshotFailure) -> Minecraft.getInstance().execute(() -> {
            if (currentRequest != requestId || request.isDone()) return;
            if (snapshotFailure != null) { request.completeExceptionally(new IllegalStateException("工厂查询超时或连接中断；请靠近服务器后重试。", snapshotFailure)); return; }
            final String factoryContext;
            try { factoryContext = FactoryAiContext.modelContext(snapshotJson); }
            catch (RuntimeException unavailable) { request.completeExceptionally(unavailable); return; }
            lastFactorySnapshot = snapshotJson;
            CompletableFuture<String> modelRequest = LLMChatClient.complete(config, List.copyOf(history),
                snapshot -> Minecraft.getInstance().execute(() -> {
                    if (currentRequest != requestId || !waiting) return;
                    partialReply = snapshot == null ? "" : snapshot;
                    status = Component.literal("正在接收回复…");
                }),
                report -> Minecraft.getInstance().execute(() -> {
                    if (currentRequest == requestId) knowledgeReport = report == null ? KnowledgeReport.empty() : report;
                }),
                stage -> Minecraft.getInstance().execute(() -> {
                    if (currentRequest == requestId && waiting) status = Component.literal(stage == null ? "正在检索资料…" : stage);
                }), factoryContext);
            activeStage = modelRequest;
            modelRequest.whenComplete((reply, failure) -> {
                if (failure == null) request.complete(reply);
                else request.completeExceptionally(failure);
            });
        }));
        pendingRequest.whenComplete((reply, failure) -> Minecraft.getInstance().execute(() -> {
                    if (currentRequest != requestId) return;
                    waiting = false;
                    pendingRequest = null;
                    if (failure == null) {
                        history.add(new ChatMessage("assistant", reply));
                        partialReply = "";
                        scrollOffset = 0;
                        statusError = false;
                        status = Component.literal("收到回复。");
                        terminalProposal = extractProposal(reply);
                        if (terminalProposal != null) status = Component.literal("终端提案等待审阅；确认前不会改变机器。");
                    } else {
                        if (!partialReply.isBlank()) history.add(new ChatMessage("assistant", "（回复中断，内容未完整）\n" + partialReply));
                        partialReply = "";
                        status = Component.literal(safeFailure(failure));
                        statusError = true;
                    }
                    updateSendButton();
                }));
    }

    private void updateSendButton() {
        if (sendButton != null && promptInput != null) {
            sendButton.active = !waiting && !promptInput.getValue().isBlank();
            sendButton.setMessage(Component.literal(waiting ? "等待…" : "发送"));
        }
        if (cancelButton != null) cancelButton.active = waiting;
        if (sourcesButton != null) sourcesButton.active = !waiting && !knowledgeReport.sources().isEmpty();
        if (proposalButton != null) proposalButton.active = !waiting && terminalProposal != null;
    }

    private TerminalProposal extractProposal(String reply) {
        if (factoryServer == null || FactoryAiContext.ticket(lastFactorySnapshot).isBlank()) return null;
        String fence = Character.toString(96).repeat(3);
        var matcher = java.util.regex.Pattern.compile("(?s)" + java.util.regex.Pattern.quote(fence + "ic2-terminal") + "\\s*(.*?)" + java.util.regex.Pattern.quote(fence)).matcher(reply);
        if (!matcher.find()) return null;
        String candidate = matcher.group(1);
        if (matcher.find()) return null;
        try { return TerminalProposal.parse(candidate); }
        catch (RuntimeException invalid) { return null; }
    }
    private void reviewProposal() {
        TerminalProposal proposed = terminalProposal;
        if (proposed == null || factoryServer == null) return;
        String ticket = FactoryAiContext.ticket(lastFactorySnapshot);
        openChild(new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (confirmed) {
                IndustrialNetworkPackets.CHANNEL.sendToServer(
                        new IndustrialNetworkPackets.ConfirmTerminalProposal(factoryServer, ticket, proposed.json()));
                terminalProposal = null;
                status = Component.literal("提案已提交复查；服务端的执行或拒绝结果会显示在游戏聊天中。");
                updateSendButton();
            }
        }, Component.literal("确认修改终端机"), Component.literal(proposed.description()
                + " 只有服务器控制启用且装有AI核心才能执行；目标离线、离队、断网、设置变化或提案过期会拒绝。")) {
            @Override public boolean isPauseScreen() { return false; }
        });
    }

    private Component privacyNotice() {
        return Component.literal("按设置发送对话和模组资料；公开搜索会收到问题。"
                + (factoryServer == null ? "" : "本页另发送经权限检查的当前网络状态与终端库存摘要。")
                + "密钥仅使用当前玩家本机配置，不交给游戏服务器或其他玩家，不读取背包或存档。");
    }

    private int transcriptTop() {
        // The full notice is available on hover; it cannot push messages into the footer.
        return noticeY() + font.lineHeight + 5;
    }

    private int noticeY() { return factoryServer == null ? 47 : 69; }
    private int inputY() { return Math.max(0, height - 26); }
    private int statusCapacity() { return height >= 180 ? 2 : 1; }
    private int statusY() { return inputY() - 6 - statusCapacity() * font.lineHeight; }
    private int transcriptBottom() { return Math.max(transcriptTop(), statusY() - 5); }

    private void openChild(Screen child) {
        openingChild = true;
        minecraft.setScreen(child);
    }

    private void cancelRequest() {
        if (!waiting) return;
        requestId++;
        if (pendingRequest != null) pendingRequest.cancel(true);
        pendingRequest = null;
        waiting = false;
        if (!partialReply.isBlank()) history.add(new ChatMessage("assistant", "（已取消，内容未完整）\n" + partialReply));
        partialReply = "";
        status = Component.literal("请求已取消，可以重新发送。");
        statusError = false;
        updateSendButton();
    }

    private String safeFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return "请求超时；请检查网络、服务商负载，或在助手设置中延长超时时间。";
        }
        if (message != null && message.startsWith("API认证失败")) {
            return "API认证失败（401/403）；请检查已保存的密钥及该模型的访问权限。";
        }
        if (message != null && (message.startsWith("请先") || message.startsWith("API Key无效")
                || message.startsWith("智谱账户") || message.startsWith("AI接口返回HTTP")
                || message.startsWith("AI响应格式") || message.startsWith("AI返回")
                || message.startsWith("服务商额度"))) return message;
        if (message != null && message.startsWith("工厂查询")) return message;
        return "AI请求失败（" + cause.getClass().getSimpleName()
                + "）；检查网络、API地址、模型权限和服务商额度。密钥不会写入日志。";
    }

    @Override
    public void tick() {
        if (promptInput != null) promptInput.tick();
        updateSendButton();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        int margin = 10;
        int panelWidth = Math.max(1, Math.min(620, width - margin * 2));
        int left = (width - panelWidth) / 2;
        int right = left + panelWidth;
        int transcriptTop = transcriptTop();
        List<FormattedCharSequence> statusLines = font.split(status, panelWidth);
        int statusLineY = statusY();
        int transcriptBottom = transcriptBottom();
        fill(poseStack, left - 5, 0, right + 5, height, IndustrialPalette.BACKGROUND);
        fill(poseStack, left, transcriptTop, right, transcriptBottom, IndustrialPalette.CANVAS);
        super.render(poseStack, mouseX, mouseY, partialTick);
        font.draw(poseStack, UiText.fit(font, title, panelWidth), left, 7, IndustrialPalette.TEXT);
        Component notice = Component.literal(factoryServer == null
                ? "按模型设置发送资料 · 悬停查看说明"
                : "网络状态与库存摘要将发送给当前模型");
        font.draw(poseStack, UiText.fit(font, notice, panelWidth), left, noticeY(), IndustrialPalette.ACCENT);

        int maxWidth = Math.max(1, panelWidth - 12);
        List<DrawLine> lines = new ArrayList<>();
        for (ChatMessage message : history) {
            String prefix = "user".equals(message.role()) ? "你：" : "助手：";
            int color = "user".equals(message.role()) ? IndustrialPalette.ACCENT : IndustrialPalette.TEXT;
            for (var line : font.split(Component.literal(prefix + message.content()), maxWidth)) {
                lines.add(new DrawLine(line, color));
            }
            lines.add(new DrawLine(Component.literal(" ").getVisualOrderText(), color));
        }
        if (waiting && !partialReply.isEmpty()) {
            for (var line : font.split(Component.literal("助手：" + partialReply), maxWidth)) {
                lines.add(new DrawLine(line, IndustrialPalette.TEXT));
            }
        }
        int lineStep = font.lineHeight + 2;
        int visible = Math.max(0, (transcriptBottom - transcriptTop - 10) / lineStep);
        int maxScroll = Math.max(0, lines.size() - visible);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        int first = Math.max(0, lines.size() - visible - scrollOffset);
        int last = Math.min(lines.size(), first + visible);
        UiScissor.enable(minecraft, left + 1, transcriptTop + 1, right - 1, transcriptBottom);
        int y = transcriptTop + 5;
        for (int index = first; index < last; index++) {
            DrawLine line = lines.get(index);
            font.draw(poseStack, line.text(), left + 6, y, line.color());
            y += lineStep;
        }
        RenderSystem.disableScissor();

        int statusColor = statusError ? 0xFF9B342B : IndustrialPalette.MUTED;
        int statusCount = Math.min(statusCapacity(), statusLines.size());
        for (int index = 0; index < statusCount; index++) {
            FormattedCharSequence line = statusLines.size() > statusCount && index == statusCount - 1
                    ? UiText.fit(font, Component.literal("… 悬停查看完整提示"), panelWidth).getVisualOrderText()
                    : statusLines.get(index);
            font.draw(poseStack, line, left, statusLineY, statusColor);
            statusLineY += font.lineHeight;
        }
        updateSendButton();
        if (mouseX >= left && mouseX < right) {
            Component hovered = null;
            if (mouseY >= noticeY() && mouseY < noticeY() + font.lineHeight) hovered = privacyNotice();
            else if (mouseY >= statusY() && mouseY < statusY() + statusCapacity() * font.lineHeight) hovered = status;
            if (hovered != null) renderTooltip(poseStack, font.split(hovered, Math.max(1, Math.min(300, panelWidth))), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int panelWidth = Math.max(1, Math.min(620, width - 20));
        int left = (width - panelWidth) / 2;
        if (mouseX >= left && mouseX < left + panelWidth && mouseY >= transcriptTop()
                && mouseY < transcriptBottom() && delta != 0) {
            scrollOffset = Math.max(0, scrollOffset + (delta > 0 ? 3 : -3));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && !waiting) { sendPrompt(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        cancelRequest();
        Screen destination = parent;
        if (parent instanceof AbstractContainerScreen<?> container
                && (minecraft.player == null || minecraft.player.containerMenu != container.getMenu()
                || !container.getMenu().stillValid(minecraft.player))) destination = null;
        minecraft.setScreen(destination);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        if (!openingChild) cancelRequest();
        super.removed();
    }

    private record DrawLine(FormattedCharSequence text, int color) { }
}
