package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.robot.companionship.RobotCompanionshipPackets;
import com.ic2newera.robot.RobotCharacter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Single adaptive companion page for direct robot control and phone access. */
public final class RobotCompanionshipScreen extends Screen {
    private final Screen parent;
    private final UUID robot;
    private final String character;
    private final boolean remote;
    private RobotCompanionshipPackets.Reply snapshot;
    private UUID pending;
    private String status = "正在读取陪伴状态……", guessDraft = "";
    private int pendingTicks, pendingAction = -1;
    private boolean gameMayBeActive;
    private EditBox guess;
    private Button pet, hands, guessStart, guessSubmit, rpsStart, rock, paper, scissors, language;
    private int panelWidth, left, top, rowStep;

    public RobotCompanionshipScreen(Screen parent, UUID robot, String character, boolean remote) {
        super(Component.literal("陪伴互动 · " + RobotCharacter.byId(character).chineseName));
        this.parent = parent; this.robot = robot; this.character = character; this.remote = remote;
    }

    public static void receive(RobotCompanionshipPackets.Reply reply) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !(client.screen instanceof RobotCompanionshipScreen screen)
                || !screen.robot.equals(reply.robot()) || screen.remote != reply.remote()
                || screen.pending == null || !screen.pending.equals(reply.ticket())) return;
        screen.pending = null;
        screen.pendingTicks = 0; screen.pendingAction = -1;
        if (reply.hasStatus()) {
            screen.snapshot = reply;
            screen.gameMayBeActive = reply.game() != 0;
        }
        screen.status = message(reply.code(), reply.accepted(), screen.snapshot == null ? 0 : screen.snapshot.game());
        screen.updateControls();
    }

    private static String message(int code, boolean accepted, int game) {
        return switch (code) {
            case 0 -> "状态已更新。"; case 1 -> "只有机器人主人可以使用此功能。";
            case 2 -> "需要在机器人八格内才能进行这个动作。"; case 3 -> "机器人或手机电量不足。";
            case 4 -> "当前状态不适合进行陪伴互动。"; case 5 -> "操作太快，请稍后再试。";
            case 6 -> "今天的互动次数已用完。"; case 7 -> "当前没有进行中的小游戏。";
            case 8 -> "输入无效，请检查后重试。"; case 9 -> "小游戏已超时，请重新开始。";
            case 10 -> game == 2 ? "石头剪刀布已开始。" : "小游戏开始：猜一个 1 到 10 的数字。";
            case 11 -> "猜大了。"; case 12 -> "猜小了。";
            case 13, 15 -> "这一局赢了！"; case 14, 16 -> "这一局结束了。"; case 17 -> "平局，再来一局吧。";
            case 18 -> "已有一局小游戏进行中，请先完成或离开当前小游戏。"; case 19 -> "小游戏已结束。";
            default -> accepted ? "操作完成。" : "操作未完成。";
        };
    }

    private void send(int action, int value) {
        if (pending != null) return;
        pending = UUID.randomUUID(); pendingTicks = 0; pendingAction = action; status = "正在联系机器人……";
        if (action == RobotCompanionshipPackets.START_GUESS || action == RobotCompanionshipPackets.START_RPS) gameMayBeActive = true;
        RobotCompanionshipPackets.request(robot, remote, action, value, pending);
        updateControls();
    }

    @Override protected void init() {
        panelWidth = Math.max(120, Math.min(560, width - 20)); left = (width - panelWidth) / 2;
        // Reserve a fixed two-line header; compress button rows on short screens.
        top = 64;
        rowStep = Math.max(20, Math.min(26, (height - top - 32) / 4));
        addRenderableWidget(new UiButton(left + panelWidth - 52, 8, 52, 20, Component.literal("返回"), b -> onClose()));
        pet = addButton(0, "摸头", "需要主人权限，3格内且无遮挡。", b -> send(RobotCompanionshipPackets.PET, 0));
        hands = addButton(1, "牵手", "启用需主人权限、3格内且无遮挡；解除可在8格管理范围内。", b -> send(RobotCompanionshipPackets.HOLD_HANDS, snapshot != null && !snapshot.holdingHands() ? 1 : 0));
        guessStart = addButton(2, "开始猜数字", "猜 1 到 10；每局最多三次。", b -> send(RobotCompanionshipPackets.START_GUESS, 0));
        // The number-entry row reuses the two choice-button cells while guessing.
        int fieldY = top + 2 * rowStep;
        int half = (panelWidth - 6) / 2;
        guess = addRenderableWidget(new EditBox(font, left, fieldY, half, 20, Component.literal("猜数字 1–10")));
        guess.setMaxLength(2); guess.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit)); guess.setValue(guessDraft);
        guessSubmit = addRenderableWidget(new UiButton(left + half + 6, fieldY, panelWidth - half - 6, 20, Component.literal("提交数字"), b -> {
            try {
                int value = Integer.parseInt(guess.getValue());
                if (value < 1 || value > 10) { status = "请输入 1 到 10 的整数。"; return; }
                send(RobotCompanionshipPackets.GUESS, value);
            }
            catch (NumberFormatException ignored) { status = "请输入 1 到 10 的整数。"; }
        }));
        rpsStart = addButton(3, "开始石头剪刀布", "开始一局无奖励的猜拳游戏。", b -> send(RobotCompanionshipPackets.START_RPS, 0));
        rock = addButton(4, "石头", "出石头。", b -> send(RobotCompanionshipPackets.RPS, 0));
        paper = addButton(5, "布", "出布。", b -> send(RobotCompanionshipPackets.RPS, 1));
        scissors = addButton(6, "剪刀", "出剪刀。", b -> send(RobotCompanionshipPackets.RPS, 2));
        language = addButton(7, "回复语言", "在日语、中文、English之间循环；由服务端保存。", b -> {
            int current = snapshot == null ? 1 : snapshot.language();
            send(RobotCompanionshipPackets.SET_LANGUAGE, switch (current) { case 1 -> 0; case 0 -> 2; default -> 1; });
        });
        if (snapshot == null) send(RobotCompanionshipPackets.STATUS, 0); else updateControls();
    }

    private Button addButton(int index, String label, String hint, Button.OnPress action) {
        int cell = (panelWidth - 6) / 2;
        int x = left + index % 2 * (cell + 6);
        int y = top + index / 2 * rowStep;
        Button button = addRenderableWidget(new UiButton(x, y, index % 2 == 0 ? cell : panelWidth - cell - 6,
                20, Component.literal(label), action));
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(hint)));
        return button;
    }

    private void updateControls() {
        if (pet == null) return;
        boolean loaded = snapshot != null && snapshot.hasStatus();
        pet.visible = !remote; hands.visible = !remote;
        pet.active = loaded && pending == null && !remote; hands.active = loaded && pending == null && !remote;
        hands.setMessage(Component.literal(loaded && snapshot.holdingHands() ? "松开牵手" : "牵手"));
        guessStart.active = loaded && pending == null; rpsStart.active = loaded && pending == null;
        boolean guessGame = loaded && snapshot.game() == 1;
        guess.visible = guessGame; guess.active = guessGame && pending == null;
        guessSubmit.visible = guessGame; guessSubmit.active = guessGame && pending == null;
        boolean rpsGame = loaded && snapshot.game() == 2;
        rock.visible = rpsGame; paper.visible = rpsGame; scissors.visible = rpsGame;
        rock.active = pending == null && rpsGame; paper.active = pending == null && rpsGame; scissors.active = pending == null && rpsGame;
        language.setMessage(Component.literal("回复语言：" + (loaded ? languageName(snapshot.language()) : "读取中"))); language.active = loaded && pending == null;
    }
    private static String languageName(int id) { return switch (id) { case 0 -> "中文"; case 1 -> "日语"; case 2 -> "English"; default -> "日语"; }; }
    private static String tierName(int id) { return switch (id) { case 0 -> "陌生"; case 1 -> "熟悉"; case 2 -> "亲近"; case 3 -> "挚友"; default -> "未知"; }; }
    private static String gameName(int id) { return switch (id) { case 1 -> "猜数字"; case 2 -> "石头剪刀布"; default -> "无"; }; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left - 6, 4, left + panelWidth + 6, height - 4, 0xEF152631);
        graphics.drawString(font, UiText.fit(font, title, panelWidth - 64), left, 12, 0xFFFFFF);
        if (snapshot == null || !snapshot.hasStatus()) graphics.drawString(font, UiText.fit(font, Component.literal(status), panelWidth), left, 32, 0xD6E5E9);
        else {
            graphics.drawString(font, UiText.fit(font, Component.literal("好感 " + snapshot.affection() + "/100 · " + tierName(snapshot.tier())), panelWidth), left, 31, 0xBCEFE8);
            graphics.drawString(font, UiText.fit(font, Component.literal("状态：" + (snapshot.petting() ? "摸头中" : snapshot.holdingHands() ? "牵手中" : "陪伴待命")
                    + " · 小游戏：" + gameName(snapshot.game()) + (snapshot.game() == 1 ? " · 剩余尝试 " + snapshot.attemptsRemaining() : "")), panelWidth), left, 44, 0xBCEFE8);
            graphics.drawString(font, UiText.fit(font, Component.literal(status), panelWidth), left, height - 18, 0xD6E5E9);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void tick() {
        if (guess != null) { guess.tick(); guessDraft = guess.getValue(); }
        if (pending != null && ++pendingTicks >= 100) {
            pending = null; pendingTicks = 0; pendingAction = -1;
            updateControls();
            send(RobotCompanionshipPackets.STATUS, 0);
            status = "请求超时，正在重新读取陪伴状态……";
        }
    }
    @Override public void onClose() {
        boolean cancelGame = gameMayBeActive || snapshot != null && snapshot.hasStatus() && snapshot.game() != 0
                || pendingAction == RobotCompanionshipPackets.START_GUESS || pendingAction == RobotCompanionshipPackets.START_RPS;
        if (cancelGame && minecraft != null && minecraft.player != null)
            RobotCompanionshipPackets.request(robot, remote, RobotCompanionshipPackets.CANCEL_GAME, 0, UUID.randomUUID());
        minecraft.setScreen(parent);
    }
    @Override public void removed() {
        if (guess != null) guessDraft = guess.getValue();
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
}
