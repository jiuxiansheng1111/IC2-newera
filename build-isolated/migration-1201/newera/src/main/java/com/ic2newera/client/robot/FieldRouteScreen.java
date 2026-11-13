package com.ic2newera.client.robot;

import com.ic2newera.client.ui.UiButton;
import com.ic2newera.client.ui.UiText;
import com.ic2newera.field.FieldJournal;
import com.ic2newera.field.FieldRoutes;
import com.ic2newera.field.nav.RouteDirections;
import com.ic2newera.robot.RobotRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.UUID;

public final class FieldRouteScreen extends Screen {
    private final Screen parent;
    private boolean list;
    private int left, panel, top, bottom, zoom, scroll;
    private static final int ROUTE = 0xFF49D5BB, PLAYER = 0xFFFFDD83, TARGET = 0xFFFF946C;
    public FieldRouteScreen(Screen parent) { super(Component.literal("导航地图")); this.parent = parent; }
    @Override protected void init() {
        panel = Math.min(560, width - 16); left = (width - panel) / 2; top = 66; bottom = height - 46;
        String[] labels = {list ? "看地图" : "分步路线", "缩小", "放大", "停止导航", "返回"};
        int cell = (panel + 3) / labels.length;
        for (int i = 0; i < labels.length; i++) {
            int action = i;
            var b = addRenderableWidget(new UiButton(left + i * cell, height - 25, cell - 3, 18, Component.literal(labels[i]), btn -> {
                switch (action) {
                    case 0 -> { list = !list; scroll = 0; rebuildWidgets(); }
                    case 1 -> { zoom = Math.max(-3, zoom - 1); rebuildWidgets(); }
                    case 2 -> { zoom = Math.min(3, zoom + 1); rebuildWidgets(); }
                    case 3 -> send("stop_nav");
                    default -> onClose();
                }
            }));
            if (i == 1) b.active = !list && zoom > -3;
            if (i == 2) b.active = !list && zoom < 3;
        }
        if (!FieldJournalScreen.route().active()) send("route");
    }
    private static void send(String op) {
        FieldJournal.request(new FieldJournal.Request(UUID.randomUUID(), FieldJournal.Tab.MOON, op, "", FieldJournal.NO_ROBOT, false));
    }
    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) minecraft.setScreen(null);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.fill(left - 4, 2, left + panel + 4, height - 2, 0xF3182934);
        var route = FieldJournalScreen.route();
        g.drawString(font, title, left + 4, 8, 0xEEF8FA);
        if (!route.active() || minecraft.player == null) {
            line(g, "暂无目的地", 34, 0xFFE8A8);
            line(g, "从生态相册、月球远征或任务路线选一个地点后出发。", 50, 0xDCEDEF);
        } else {
            String dim = route.dimension().equals("minecraft:overworld") ? "主世界" : route.dimension().equals("ad_astra:moon") ? "月球" : route.dimension();
            line(g, route.label() + "　" + dim + "　" + route.target().toShortString(), 23, TARGET);
            line(g, route.status(), 38, 0xE7EEF1);
            line(g, "下一步　" + route.next(), 52, PLAYER);
            boolean same = minecraft.player.level().dimension().location().toString().equals(route.dimension());
            if (!same) line(g, "进入目标维度后会从你的位置重新规划。", top + 12, 0xB6C9CF);
            else if (list) steps(g, route.points());
            else map(g, route, minecraft.player.blockPosition());
        }
        super.render(g, mx, my, partial);
    }
    private void line(GuiGraphics g, String s, int y, int color) {
        g.drawString(font, UiText.fit(font, Component.literal(s), panel - 8), left + 4, y, color);
    }
    private void steps(GuiGraphics g, List<BlockPos> path) {
        int rows = Math.max(1, (bottom - top) / 13);
        scroll = Math.max(0, Math.min(scroll, path.size() - 1 - rows));
        for (int i = 1 + scroll; i < path.size() && i <= scroll + rows; i++) {
            BlockPos from = path.get(i - 1), to = path.get(i);
            String s = RouteDirections.heading(to.getX()-from.getX(), to.getY()-from.getY(), to.getZ()-from.getZ());
            line(g, i + "　" + s + "　　 " + to.toShortString(), top + (i - scroll - 1) * 13, i == 1 ? PLAYER : 0xD3E7E8);
        }
        if (path.size() < 2) line(g, "当前没有待走的路线节点。", top + 8, 0xBDD0D5);
        else line(g, "滚轮查看剩余 " + (path.size() - 1) + " 步", height - 39, 0xBDD0D5);
    }
    private void map(GuiGraphics g, FieldRoutes.Snapshot r, BlockPos here) {
        int x1 = left + 2, x2 = left + panel - 2, y1 = top, y2 = bottom;
        int cx = (x1 + x2) / 2, cy = (y1 + y2) / 2;
        double rx = 3, rz = 3;
        for (BlockPos p : r.points()) { rx = Math.max(rx, Math.abs(p.getX() - here.getX())); rz = Math.max(rz, Math.abs(p.getZ() - here.getZ())); }
        double unit = Math.max(.5, Math.min(10, Math.min((x2 - x1 - 18) / (2 * rx), (y2 - y1 - 22) / (2 * rz)))) * Math.pow(1.55, zoom);
        g.fill(x1, y1, x2, y2, 0xFF101D26);
        g.enableScissor(x1, y1, x2, y2);
        try {
            int grid = 1;
            while (grid * unit < 24) grid *= 2;
            int ox = Math.floorDiv(here.getX(), grid) * grid, oz = Math.floorDiv(here.getZ(), grid) * grid;
            int gx = RouteDirections.mapX(here.getX(), ox, cx, unit), gz = RouteDirections.mapY(here.getZ(), oz, cy, unit);
            for (double i = gx - Math.ceil((gx - x1) / (grid * unit)) * grid * unit; i < x2; i += grid * unit)
                g.fill((int)i, y1, (int)i + 1, y2, 0xFF223B44);
            for (double i = gz - Math.ceil((gz - y1) / (grid * unit)) * grid * unit; i < y2; i += grid * unit)
                g.fill(x1, (int)i, x2, (int)i + 1, 0xFF223B44);
            BlockPos prev = null; int px = 0, py = 0;
            for (BlockPos p : r.points()) {
                int x = RouteDirections.mapX(here.getX(), p.getX(), cx, unit), y = RouteDirections.mapY(here.getZ(), p.getZ(), cy, unit);
                if (prev != null) {
                    drawLine(g, px, py, x, y);
                    if (prev.getY() != p.getY()) g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF9CAFF7);
                }
                prev = p; px = x; py = y;
            }
            int tx = RouteDirections.mapX(here.getX(), r.target().getX(), cx, unit);
            int ty = RouteDirections.mapY(here.getZ(), r.target().getZ(), cy, unit);
            if (tx >= x1 && tx < x2 && ty >= y1 && ty < y2) {
                g.fill(tx-4, ty-1, tx+5, ty+2, TARGET); g.fill(tx-1, ty-4, tx+2, ty+5, TARGET);
            }
            g.fill(cx - 2, cy - 2, cx + 3, cy + 3, PLAYER);
            g.drawString(font, "↑ 北", x1 + 4, y1 + 3, 0xCADADD);
            g.drawString(font, "每格 " + grid + " 米", x1 + 4, y2 - 12, 0xB0C8D0);
        } finally { g.disableScissor(); }
        line(g, "黄点 本人　绿线 路线　紫点 高差　橙十字 目的地", height - 39, 0xCEE1E4);
    }
    private static void drawLine(GuiGraphics g, int x, int y, int endX, int endY) {
        int count = Math.max(1, Math.max(Math.abs(endX - x), Math.abs(endY - y)));
        for (int i = 0; i <= count; i++) {
            int px = x + Math.round((endX - x) * (float)i / count), py = y + Math.round((endY - y) * (float)i / count);
            g.fill(px, py, px + 2, py + 2, ROUTE);
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double dy) {
        if (list) { scroll = Math.max(0, scroll - (int)Math.signum(dy) * 3); return true; }
        return super.mouseScrolled(x, y, dy);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
