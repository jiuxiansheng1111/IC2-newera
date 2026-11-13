package com.ic2newera.field.board.client;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.field.board.BoardBlockEntity;
import com.ic2newera.field.board.BoardMenu;
import com.ic2newera.field.board.BoardNetwork;
import com.ic2newera.field.contracts.DailyContracts;
import com.ic2newera.field.contracts.WeeklyContracts;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import net.minecraft.resources.ResourceLocation;

/** Daily contract kiosk: visible terms, real reward stacks, and server-authoritative actions. */
public final class BoardMainScreen extends AbstractContainerScreen<BoardMenu> {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int ROW_TOP = 50;
    private static final int ROW_HEIGHT = 54;
    private static final int VISIBLE_ROWS = 3;
    private DailyContracts.BoardSnapshot snapshot;
    private WeeklyContracts.WeeklyView weeklySnapshot;
    private boolean weeklyMode;
    private int weeklyIndex;
    private int weeklyInputOffset;
    private String notice = "正在读取每日任务……";
    private boolean waiting;
    private int waitTicks;
    private int scrollOffset;
    private long countdownStartNanos;
    private long countdownStartSeconds;
    private long nextAutomaticRefreshNanos;
    private long nextWeeklyRefreshNanos;

    public BoardMainScreen(BoardMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = HEIGHT + 64;
    }

    @Override protected void init() {
        super.init();
        clearWidgets();
        addRenderableWidget(new UiButton(leftPos + 8, topPos + 25, 48, 16, Component.literal("日常任务"),
                b -> switchMode(false), Component.literal("查看半小时轮换的个人委托"))).active = weeklyMode && !waiting;
        addRenderableWidget(new UiButton(leftPos + 59, topPos + 25, 70, 16, Component.literal("每周联合工单"),
                b -> switchMode(true), Component.literal("查看本周可接的工单、自己的交付进度和奖励"))).active = !weeklyMode && !waiting;
        if (snapshot == null && !weeklyMode && !waiting) {
            menu.request(0, "", "");
        }
        if (weeklyMode) {
            initWeeklyWidgets();
            return;
        }
        if (snapshot == null) return;
        int page = snapshot.page();
        int count = snapshot.contracts().size();
        int visible = Math.min(VISIBLE_ROWS, Math.max(0, count - scrollOffset));
        addRenderableWidget(new UiButton(leftPos + 7, topPos + 219, 26, 16, Component.literal("‹"),
                b -> send(Math.max(0, page - 1), "", ""), Component.literal("查看上一页任务"))).active = page > 0 && !waiting;
        addRenderableWidget(new UiButton(leftPos + 36, topPos + 219, 26, 16, Component.literal("›"),
                b -> send(Math.min(snapshot.pageCount() - 1, page + 1), "", ""), Component.literal("查看下一页任务")))
                .active = page + 1 < snapshot.pageCount() && !waiting;
        addRenderableWidget(new UiButton(leftPos + 65, topPos + 219, 34, 16, Component.literal("刷新"),
                b -> send(page, "refresh", ""), Component.literal("重新读取当前轮次的任务与个人进度"))).active = !waiting;
        for (int i = 0; i < visible; i++) {
            int index = scrollOffset + i;
            DailyContracts.ContractCard card = snapshot.contracts().get(index);
            int y = topPos + ROW_TOP + i * ROW_HEIGHT;
            String action = card.claimed() ? "" : !card.accepted() ? "accept"
                    : card.completed() ? "claim" : "submit";
            String label = card.claimed() ? "已领取" : !card.accepted() ? "接取"
                    : card.completed() ? "领取" : card.type() == DailyContracts.ContractType.PHOTO ? "查照片" : "提交";
            UiButton mainAction = addRenderableWidget(new UiButton(leftPos + 168, y + 8, 50, 18,
                    Component.literal(label), b -> send(page, action, card.id()),
                    Component.literal(actionHint(card))));
            mainAction.active = !waiting && !action.isEmpty();
            UiButton route = addRenderableWidget(new UiButton(leftPos + 222, y + 8, 89, 18,
                    Component.literal("加到手机路线"), b -> send(page, "route", card.id()),
                    Component.literal(routeHint(card))));
            route.active = !waiting && card.accepted();
        }
    }

    private void initWeeklyWidgets() {
        if (weeklySnapshot == null) return;
        int count = weeklySnapshot.rows().size();
        int index = Math.max(0, Math.min(weeklyIndex, count - 1));
        weeklyIndex = index;
        WeeklyContracts.WeeklyRow row = count == 0 ? null : weeklySnapshot.rows().get(index);
        boolean active = !waiting && row != null;
        addRenderableWidget(new UiButton(leftPos + 7, topPos + 219, 27, 16, Component.literal("‹"),
                b -> changeWeeklyIndex(-1), Component.literal("上一张联合工单"))).active = active && index > 0;
        addRenderableWidget(new UiButton(leftPos + 37, topPos + 219, 27, 16, Component.literal("›"),
                b -> changeWeeklyIndex(1), Component.literal("下一张联合工单"))).active = active && index + 1 < count;
        addRenderableWidget(new UiButton(leftPos + 68, topPos + 219, 43, 16, Component.literal("日常"),
                b -> switchMode(false), Component.literal("回到半小时轮换的日常委托页"))).active = !waiting;
        if (!active) return;
        WeeklyContracts.State state = row.state();
        if (state == WeeklyContracts.State.OFFERED) {
            addRenderableWidget(new UiButton(leftPos + 218, topPos + 219, 92, 16, Component.literal("参加联合工单"),
                    b -> requestWeekly("accept", row.id(), null, 0), Component.literal("接单后回这台公告栏分批交货，进度按玩家保存")))
                    .active = !waiting;
        } else if (state == WeeklyContracts.State.COMPLETED) {
            addRenderableWidget(new UiButton(leftPos + 218, topPos + 219, 92, 16, Component.literal("领取联合奖励"),
                    b -> requestWeekly("claim", row.id(), null, 0), Component.literal("将这份工单的全部奖励领到自己的背包")))
                    .active = !waiting;
        }
        if (state == WeeklyContracts.State.ACTIVE) {
            int shown = Math.min(4, Math.max(0, row.inputs().size() - weeklyInputOffset));
            for (int i = 0; i < shown; i++) {
                int inputIndex = i + weeklyInputOffset;
                WeeklyContracts.StackAmount amount = row.inputs().get(inputIndex);
                boolean canDeliver = !amount.stack().isEmpty() && amount.delivered() < amount.required();
                int buttonY = topPos + 116 + i * 19;
                addRenderableWidget(new UiButton(leftPos + 224, buttonY, 38, 16,
                        Component.literal("交 1"), b -> requestWeekly("submit", row.id(),
                        itemKey(row.inputs().get(inputIndex).stack()), 1),
                        Component.literal("只提交这一种材料 1 个；服务器检查背包并扣料")))
                        .active = !waiting && canDeliver;
                addRenderableWidget(new UiButton(leftPos + 264, buttonY, 46, 16,
                        Component.literal("交满"), b -> requestWeekly("submit_all", row.id(),
                        itemKey(row.inputs().get(inputIndex).stack()), 0),
                        Component.literal("服务器按背包可用数量和此项剩余需求取较小值，一次交付")))
                        .active = !waiting && canDeliver;
            }
        }
    }

    private void switchMode(boolean weekly) {
        if (waiting || weeklyMode == weekly) return;
        weeklyMode = weekly;
        waiting = true; waitTicks = 0; notice = "正在读取" + (weekly ? "每周联合工单……" : "每日任务……");
        clearWidgets(); init();
        if (weekly) BoardNetwork.requestWeekly(menu, "view", "", null, 0);
        else menu.request(0, "", "");
    }

    private void changeWeeklyIndex(int delta) {
        if (weeklySnapshot == null) return;
        weeklyIndex = Math.max(0, Math.min(weeklySnapshot.rows().size() - 1, weeklyIndex + delta));
        weeklyInputOffset = 0;
        clearWidgets(); init();
    }

    private void requestWeekly(String action, String id, ResourceLocation item, int count) {
        if (waiting) return;
        waiting = true; waitTicks = 0;
        notice = action.equals("view") ? "正在刷新联合工单……" : "正在向服务器提交请求……";
        clearWidgets(); init(); BoardNetwork.requestWeekly(menu, action, id, item, count);
    }

    private static ResourceLocation itemKey(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private String actionHint(DailyContracts.ContractCard card) {
        if (!card.accepted()) return "接取个人任务。每轮每条任务只能接取一次，最多同时进行五条。";
        if (card.completed() && !card.claimed()) return "任务已完成。领取前请确认物品栏有奖励空间。";
        if (card.claimed()) return "此任务奖励已领取。";
        return card.type() == DailyContracts.ContractType.PHOTO
                ? "刷新服务器生态相册证据；照片任务由服务端自动核验。"
                : "服务端核对并扣除所需物资；数量不足时不会扣除任何物品。";
    }

    private String routeHint(DailyContracts.ContractCard card) {
        return card.type() == DailyContracts.ContractType.PHOTO
                ? "把这条任务的已勘探目标添加到你的手机导航路线；未勘探时会明确提示。"
                : "在手机导航里显示公告栏坐标，沿可验证的分段路线前往。";
    }

    private void send(int page, String action, String id) {
        if (waiting) return;
        waiting = true;
        waitTicks = 0;
        notice = action.equals("route") ? "正在添加路线……" : "正在与服务器核对……";
        clearWidgets();
        init();
        menu.request(page, action, id);
    }

    public static void receive(int menuId, DailyContracts.BoardSnapshot snapshot,
                               WeeklyContracts.WeeklyView weekly, String notice) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (!(mc.screen instanceof BoardMainScreen screen) || screen.menu.containerId != menuId) return;
        if (snapshot != null && snapshot.board().pos().equals(screen.menu.boardPos())) {
            screen.snapshot = snapshot;
            screen.countdownStartSeconds = snapshot.secondsRemaining();
            screen.countdownStartNanos = System.nanoTime();
            screen.nextAutomaticRefreshNanos = snapshot.secondsRemaining() == 0
                    ? screen.countdownStartNanos + 5_000_000_000L : 0;
        }
        if (weekly != null && weekly.board().pos().equals(screen.menu.boardPos())) {
            screen.weeklySnapshot = weekly;
            screen.nextWeeklyRefreshNanos = weekly.deadlineMillis() > 0
                    && System.currentTimeMillis() >= weekly.deadlineMillis()
                    ? System.nanoTime() + 5_000_000_000L : 0;
            screen.weeklyIndex = Math.max(0, Math.min(screen.weeklyIndex, weekly.rows().size() - 1));
            if (!weekly.rows().isEmpty()) screen.weeklyInputOffset = Math.max(0, Math.min(
                    screen.weeklyInputOffset, Math.max(0, weekly.rows().get(screen.weeklyIndex).inputs().size() - 4)));
            else screen.weeklyInputOffset = 0;
        }
        screen.notice = notice == null || notice.isBlank()
                ? (weekly != null ? weekly.notice() : "每日任务已更新。") : notice;
        screen.waiting = false;
        screen.waitTicks = 0;
        screen.clearWidgets();
        screen.init();
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (waiting && ++waitTicks > 100) {
            waiting = false;
            notice = "服务器尚未回应，请刷新任务后重试。";
            if (remainingSeconds() == 0) nextAutomaticRefreshNanos = System.nanoTime() + 5_000_000_000L;
            clearWidgets(); init();
            return;
        }
        if (!weeklyMode && !waiting && snapshot != null && remainingSeconds() == 0) {
            long now = System.nanoTime();
            if (nextAutomaticRefreshNanos == 0 || now >= nextAutomaticRefreshNanos) {
                nextAutomaticRefreshNanos = now + 5_000_000_000L;
                send(snapshot.page(), "refresh", "");
            }
        }
        if (weeklyMode && !waiting && weeklySnapshot != null && weeklySnapshot.deadlineMillis() > 0
                && System.currentTimeMillis() >= weeklySnapshot.deadlineMillis()) {
            long now = System.nanoTime();
            if (nextWeeklyRefreshNanos == 0 || now >= nextWeeklyRefreshNanos) {
                nextWeeklyRefreshNanos = now + 5_000_000_000L;
                requestWeekly("view", "", null, 0);
            }
        }
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF111C24);
        graphics.fill(x + 4, y + 4, x + imageWidth - 4, y + 22, 0xFF263B45);
        graphics.fill(x + 7, y + 25, x + imageWidth - 7, y + 44, 0xFF1A2A32);
        if (weeklyMode) {
            graphics.fill(x + 7, y + 48, x + imageWidth - 7, y + 209, 0xFF1B2931);
            graphics.fill(x + 8, y + 64, x + imageWidth - 8, y + 65, 0xFF796039);
            graphics.fill(x + 8, y + 157, x + imageWidth - 8, y + 158, 0xFF796039);
            graphics.fill(x + 4, y + 215, x + imageWidth - 4, y + 237, 0xFF263B45);
            int energyX = x + imageWidth - 93;
            graphics.fill(energyX, y + 29, energyX + 84, y + 42, poweredTextValue() ? 0xFF426D59 : 0xFF713B38);
            return;
        }
        if (snapshot == null) return;
        int rows = Math.min(VISIBLE_ROWS, Math.max(0, snapshot.contracts().size() - scrollOffset));
        for (int i = 0; i < rows; i++) {
            int ry = y + ROW_TOP + i * ROW_HEIGHT;
            graphics.fill(x + 7, ry, x + imageWidth - 7, ry + ROW_HEIGHT - 2,
                    i % 2 == 0 ? 0xFF202F37 : 0xFF1B2931);
            graphics.fill(x + 8, ry + 7, x + 28, ry + 27, 0xFF3D3427);
            graphics.fill(x + 9, ry + 8, x + 27, ry + 26, 0xFF0F191D);
            graphics.fill(x + 8, ry + ROW_HEIGHT - 4, x + imageWidth - 8, ry + ROW_HEIGHT - 2, 0xFF796039);
        }
        if (rows == 0) graphics.fill(x + 8, y + ROW_TOP, x + imageWidth - 8, y + ROW_TOP + 42, 0xFF202F37);
        int energyX = x + imageWidth - 93;
        boolean powered = poweredTextValue();
        graphics.fill(energyX, y + 29, energyX + 84, y + 42, powered ? 0xFF426D59 : 0xFF713B38);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        if (weeklyMode) {
            renderWeeklyLabels(graphics);
            return;
        }
        graphics.drawString(font, Component.literal("野外每日任务公告栏"), 12, 8, 0xFFF1E4C1, false);
        if (snapshot == null) {
            graphics.drawString(font, Component.literal("正在读取服务器任务……"), 14, 80, 0xFFE0E7E2, false);
            return;
        }
        String status = "段位 " + snapshot.rank() + "/E–SSS · 累计 " + snapshot.totalCompleted();
        graphics.drawString(font, fit(status, 91), 136, 27, 0xFFDFE6DC, false);
        String cycle = "第 " + snapshot.cycle() + " 轮 · " + time(remainingSeconds());
        graphics.drawString(font, fit(cycle, 91), 136, 37, 0xFFC8D4CC, false);
        graphics.drawString(font, fit(poweredText(), 78), imageWidth - 88, 32, 0xFFFFFFFF, false);
        int rows = Math.min(VISIBLE_ROWS, Math.max(0, snapshot.contracts().size() - scrollOffset));
        if (rows == 0) graphics.drawString(font, Component.literal("本页暂时没有任务。稍后刷新查看新一轮公告。"),
                14, ROW_TOP + 10, 0xFFE1E6E2, false);
        for (int i = 0; i < rows; i++) {
            DailyContracts.ContractCard card = snapshot.contracts().get(scrollOffset + i);
            int ry = ROW_TOP + i * ROW_HEIGHT;
            ItemStack input = displayCount(card.input(), card.type() == DailyContracts.ContractType.PHOTO ? 1 : card.required());
            ItemStack reward = displayCount(card.reward(), card.reward().getCount());
            if (!input.isEmpty()) {
                graphics.renderItem(input, 9, ry + 8);
                graphics.renderItemDecorations(font, input, 9, ry + 8);
            }
            if (!reward.isEmpty()) {
                graphics.renderItem(reward, 32, ry + 8);
                graphics.renderItemDecorations(font, reward, 32, ry + 8);
            }
            graphics.drawString(font, Component.literal("交"), 13, ry + 27, 0xFFC7D1C9, false);
            graphics.drawString(font, Component.literal("奖"), 36, ry + 27, 0xFFE5C985, false);
            graphics.drawString(font, fit(card.title(), 106), 54, ry + 6, 0xFFF0D28A, false);
            String detail = card.objective() + "　" + Math.min(card.progress(), card.required()) + "/" + card.required();
            graphics.drawString(font, fit(detail, 106), 54, ry + 19, 0xFFE1E6E2, false);
            int barX = 54, barY = ry + 35, barW = 109;
            graphics.fill(barX, barY, barX + barW, barY + 4, 0xFF10171B);
            int filled = card.required() <= 0 ? 0 : Math.max(0, Math.min(barW,
                    (int) ((long) barW * Math.min(card.progress(), card.required()) / card.required())));
            if (filled > 0) graphics.fill(barX, barY, barX + filled, barY + 4, card.completed() ? 0xFF78B987 : 0xFF65A6AE);
        }
        String footer = notice.replace('\n', ' ');
        graphics.drawString(font, fit(footer, imageWidth - 111), 104, imageHeight - 13, 0xFFEADCA4, false);
    }

    private void renderWeeklyLabels(GuiGraphics graphics) {
        graphics.drawString(font, Component.literal("每周联合工单"), 12, 8, 0xFFF1E4C1, false);
        graphics.drawString(font, fit("本周主题：" + (weeklySnapshot == null ? "读取中" : weeklySnapshot.theme()), 92),
                136, 27, 0xFFFFD98A, false);
        graphics.drawString(font, fit(poweredText(), 79), imageWidth - 88, 37, 0xFFFFFFFF, false);
        if (weeklySnapshot == null) {
            graphics.drawString(font, "正在读取本周联合工单……", 14, 82, 0xFFE0E7E2, false);
            drawNotice(graphics);
            return;
        }
        String deadline = weeklySnapshot.deadlineMillis() <= 0 ? "未设截止时间"
                : "截止 " + DateTimeFormatter.ofPattern("MM-dd HH:mm", java.util.Locale.ROOT)
                .withZone(java.time.ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(weeklySnapshot.deadlineMillis()));
        graphics.drawString(font, fit(deadline, 91), 136, 37, 0xFFC8D4CC, false);
        if (weeklySnapshot.rows().isEmpty()) {
            graphics.drawString(font, "本周暂无联合工单。", 14, 80, 0xFFE1E6E2, false);
            drawNotice(graphics); return;
        }
        WeeklyContracts.WeeklyRow row = weeklySnapshot.rows().get(Math.max(0,
                Math.min(weeklyIndex, weeklySnapshot.rows().size() - 1)));
        graphics.drawString(font, fit(row.title(), 285), 12, 51, 0xFFFFD98A, false);
        String state = switch (row.state()) {
            case OFFERED -> "等待参加";
            case ACTIVE -> "共同进行中";
            case COMPLETED -> "已完成 · 可领奖";
            case CLAIMED -> "已领取";
        };
        if (isCarriedOver(row)) state += " · 跨周续做";
        graphics.drawString(font, fit("工单 " + (weeklyIndex + 1) + "/" + weeklySnapshot.rows().size()
                + " · " + state, 270), 12, 65, 0xFFD7E0D9, false);
        graphics.drawString(font, fit(row.description(), 292), 12, 78, 0xFFE1E6E2, false);
        if (row.sideType() == WeeklyContracts.SideType.DAILY_TASKS) {
            graphics.drawString(font, "协作目标：完成日常委托 " + row.sideProgress() + "/" + row.sideRequired(),
                    12, 94, 0xFF9ED4C7, false);
        } else if (row.sideType() == WeeklyContracts.SideType.VERIFIED_PHOTO) {
            String species = net.minecraft.world.entity.EntityType.byString(row.sideTarget())
                    .map(type -> type.getDescription().getString()).orElse(row.sideTarget());
            graphics.drawString(font, fit("协作目标：拍摄" + species + " "
                    + row.sideProgress() + "/" + row.sideRequired(), 292), 12, 94, 0xFF9ED4C7, false);
        }
        int shownInputs = Math.min(4, Math.max(0, row.inputs().size() - weeklyInputOffset));
        graphics.drawString(font, fit("需交材料　" + (row.inputs().isEmpty() ? "0 种" :
                (weeklyInputOffset + 1) + "–" + (weeklyInputOffset + shownInputs) + "/" + row.inputs().size())
                + (row.inputs().size() > 4 ? "　滚轮翻页" : ""), 220), 12, 109, 0xFFD7E0D9, false);
        if (shownInputs == 0) graphics.drawString(font, "无物品材料", 14, 128, 0xFFC8D4CC, false);
        for (int i = 0; i < shownInputs; i++) {
            WeeklyContracts.StackAmount amount = row.inputs().get(i + weeklyInputOffset);
            int iy = 116 + i * 19;
            ItemStack stack = displayCount(amount.stack(), 1);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, 10, iy);
                graphics.renderItemDecorations(font, stack, 10, iy);
            }
            graphics.drawString(font, fit(amount.stack().getHoverName().getString(), 146), 31, iy + 2, 0xFFE1E6E2, false);
            graphics.drawString(font, amount.delivered() + "/" + amount.required(), 176, iy + 2, 0xFFFFD98A, false);
        }
        graphics.drawString(font, "本周奖励", 12, 191, 0xFFE5C985, false);
        int shownRewards = Math.min(8, row.rewards().size());
        for (int i = 0; i < shownRewards; i++) {
            ItemStack reward = row.rewards().get(i);
            if (reward.isEmpty()) continue;
            int rx = 12 + i * 24;
            graphics.renderItem(reward, rx, 197); graphics.renderItemDecorations(font, reward, rx, 197);
        }
        String footer = notice.isBlank() ? weeklySnapshot.notice() : notice;
        boolean actionAtRight = row.state() == WeeklyContracts.State.OFFERED
                || row.state() == WeeklyContracts.State.COMPLETED;
        drawNotice(graphics, footer, actionAtRight ? 100 : imageWidth - 116);
    }

    private void drawNotice(GuiGraphics graphics) { drawNotice(graphics, notice, imageWidth - 116); }
    private void drawNotice(GuiGraphics graphics, String text, int availableWidth) {
        graphics.drawString(font, fit(text.replace('\n', ' '), availableWidth), 114, imageHeight - 13,
                0xFFEADCA4, false);
    }

    private boolean isCarriedOver(WeeklyContracts.WeeklyRow row) {
        if (weeklySnapshot == null || !row.id().startsWith("week:")) return false;
        int end = row.id().indexOf(':', 5);
        if (end < 0) return false;
        try { return Long.parseLong(row.id().substring(5, end)) < weeklySnapshot.weekStartMillis(); }
        catch (NumberFormatException ignored) { return false; }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (weeklyMode) {
            if (weeklySnapshot != null && !weeklySnapshot.rows().isEmpty()) {
                WeeklyContracts.WeeklyRow row = weeklySnapshot.rows().get(Math.max(0,
                        Math.min(weeklyIndex, weeklySnapshot.rows().size() - 1)));
                for (int i = 0; i < Math.min(4, Math.max(0, row.inputs().size() - weeklyInputOffset)); i++) {
                    WeeklyContracts.StackAmount amount = row.inputs().get(i + weeklyInputOffset);
                    int iy = topPos + 116 + i * 19;
                    if (mouseX >= leftPos + 9 && mouseX <= leftPos + 29 && mouseY >= iy && mouseY <= iy + 20
                            && !amount.stack().isEmpty()) {
                        graphics.renderTooltip(font, displayCount(amount.stack(), amount.required()), mouseX, mouseY);
                        return;
                    }
                }
                for (int i = 0; i < Math.min(8, row.rewards().size()); i++) {
                    int rx = leftPos + 12 + i * 24;
                    if (mouseX >= rx && mouseX <= rx + 20 && mouseY >= topPos + 196 && mouseY <= topPos + 218) {
                        graphics.renderTooltip(font, row.rewards().get(i), mouseX, mouseY); return;
                    }
                }
            }
            renderTooltip(graphics, mouseX, mouseY);
            return;
        }
        if (snapshot != null) {
            for (int i = 0; i < Math.min(VISIBLE_ROWS, Math.max(0, snapshot.contracts().size() - scrollOffset)); i++) {
                DailyContracts.ContractCard card = snapshot.contracts().get(scrollOffset + i);
                int ry = topPos + ROW_TOP + i * ROW_HEIGHT;
                if (mouseX >= leftPos + 8 && mouseX < leftPos + 29
                        && mouseY >= ry + 6 && mouseY < ry + 29 && !card.input().isEmpty()) {
                    ItemStack input = displayCount(card.input(), card.type() == DailyContracts.ContractType.PHOTO ? 1 : card.required());
                    graphics.renderTooltip(font, input, mouseX, mouseY);
                    return;
                }
                if (mouseX >= leftPos + 31 && mouseX < leftPos + 52
                        && mouseY >= ry + 6 && mouseY < ry + 29 && !card.reward().isEmpty()) {
                    graphics.renderTooltip(font, card.reward(), mouseX, mouseY);
                    return;
                }
                if (mouseX >= leftPos + 53 && mouseX < leftPos + 165
                        && mouseY >= ry && mouseY < ry + ROW_HEIGHT - 2) {
                    var lines = new ArrayList<Component>();
                    lines.add(Component.literal(card.text()));
                    lines.add(Component.literal("条件：" + card.objective()));
                    lines.add(Component.literal("进度：" + card.progress() + " / " + card.required()));
                    if (!card.input().isEmpty()) lines.add(Component.literal("提交：" + card.input().getHoverName().getString()
                            + (card.type() == DailyContracts.ContractType.PHOTO ? "（拍摄目标）" : " × " + card.required())));
                    lines.add(Component.literal("奖励：" + (card.reward().isEmpty() ? "无" : card.reward().getHoverName().getString())));
                    var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
                    for (Component line : lines) wrapped.addAll(font.split(line, 280));
                    graphics.renderTooltip(font, wrapped, mouseX, mouseY);
                    return;
                }
            }
            if (mouseX >= leftPos + imageWidth - 94 && mouseX < leftPos + imageWidth - 8
                    && mouseY >= topPos + 28 && mouseY < topPos + 43) {
                var lines = new ArrayList<net.minecraft.util.FormattedCharSequence>();
                for (Component line : powerTooltip()) lines.addAll(font.split(line, 220));
                graphics.renderTooltip(font, lines, mouseX, mouseY);
                return;
            }
        }
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (weeklyMode) {
            if (weeklySnapshot != null && !weeklySnapshot.rows().isEmpty()
                    && mouseY >= topPos + 110 && mouseY <= topPos + 191) {
                WeeklyContracts.WeeklyRow row = weeklySnapshot.rows().get(Math.max(0,
                        Math.min(weeklyIndex, weeklySnapshot.rows().size() - 1)));
                int max = Math.max(0, row.inputs().size() - 4);
                int next = Math.max(0, Math.min(max, weeklyInputOffset - (int) Math.signum(delta)));
                if (next != weeklyInputOffset) {
                    weeklyInputOffset = next; clearWidgets(); init();
                }
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        if (snapshot != null && mouseY >= topPos + ROW_TOP
                && mouseY <= topPos + ROW_TOP + VISIBLE_ROWS * ROW_HEIGHT) {
            int max = Math.max(0, snapshot.contracts().size() - VISIBLE_ROWS);
            int next = Math.max(0, Math.min(max, scrollOffset - (int) Math.signum(delta)));
            if (next != scrollOffset) {
                scrollOffset = next;
                clearWidgets(); init();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    private boolean poweredTextValue() {
        return minecraft.level != null && minecraft.level.getBlockEntity(menu.boardPos()) instanceof BoardBlockEntity board
                && board.isPowered();
    }
    private String poweredText() {
        if (minecraft.level != null && minecraft.level.getBlockEntity(menu.boardPos()) instanceof BoardBlockEntity board)
            return (board.isPowered() ? "运行 " : "缺电 ") + board.storedEU() + "EU";
        return "缺电";
    }
    private List<Component> powerTooltip() {
        if (minecraft.level != null && minecraft.level.getBlockEntity(menu.boardPos()) instanceof BoardBlockEntity board) {
            return List.of(Component.literal(board.isPowered() ? "公告栏正在运行。" : "公告栏未供电，操作已关闭。"),
                    Component.literal("IC2 一级低压 · 储能 " + board.storedEU() + "/" + BoardBlockEntity.CAPACITY + " EU"),
                    Component.literal("每 12 个加载中的游戏刻消耗 1 EU；未加载期间不运行，也不计时。"),
                    Component.literal("当前计量周期剩余 " + board.remainingPeriodTicks() + " 刻。"));
        }
        return List.of(Component.literal("连接 IC2 一级低压线路后可运行。"));
    }
    private String fit(String value, int width) {
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, width - 8) + "…";
    }
    private long remainingSeconds() {
        if (snapshot == null) return 0;
        long elapsed = Math.max(0, System.nanoTime() - countdownStartNanos) / 1_000_000_000L;
        return Math.max(0, countdownStartSeconds - elapsed);
    }
    private static ItemStack displayCount(ItemStack original, int count) {
        ItemStack display = original == null ? ItemStack.EMPTY : original.copy();
        if (!display.isEmpty()) display.setCount(Math.max(1, Math.min(999, count)));
        return display;
    }
    private static String time(long seconds) {
        long mins = Math.max(0, seconds) / 60;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", mins, Math.max(0, seconds) % 60);
    }
}
