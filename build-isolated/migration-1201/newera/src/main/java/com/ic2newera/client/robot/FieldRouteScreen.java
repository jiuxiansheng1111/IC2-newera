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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.network.chat.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FieldRouteScreen extends Screen {
    private final Screen parent;
    private boolean list;
    private int left, panel, top, bottom, zoom, scroll;
    private Level terrainLevel;
    private long terrainRefreshTick;
    private final Map<Long, Integer> terrainColors = new LinkedHashMap<>(4096, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) { return size() > 8192; }
    };
    private static final int ROUTE = 0xFF49D5BB, PLAYER = 0xFFFFDD83, TARGET = 0xFFFF946C;
    public FieldRouteScreen(Screen parent) { super(Component.literal("导航地图")); this.parent = parent; }
    @Override protected void init() {
        panel = Math.min(560, width - 16); left = (width - panel) / 2; top = 66; bottom = height - 46;
        String[] labels = {list ? "看地图" : "分步路线", "缩小", "放大", "地点", "停止导航", "返回"};
        int cell = (panel + 3) / labels.length;
        for (int i = 0; i < labels.length; i++) {
            int action = i;
            var b = addRenderableWidget(new UiButton(left + i * cell, height - 25, cell - 3, 18, Component.literal(labels[i]), btn -> {
                switch (action) {
                    case 0 -> { list = !list; scroll = 0; rebuildWidgets(); }
                    case 1 -> { zoom = Math.max(-3, zoom - 1); rebuildWidgets(); }
                    case 2 -> { zoom = Math.min(3, zoom + 1); rebuildWidgets(); }
                    case 3 -> FieldPlacesScreen.open(this);
                    case 4 -> send("stop_nav");
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
    /** Current connection-bound route snapshot for optional client integrations. */
    public static FieldRoutes.Snapshot currentRoute() { return FieldJournalScreen.route(); }
    @Override public void tick() {
        if (minecraft.player == null || !(minecraft.player.getMainHandItem().is(RobotRegistry.PHONE.get())
                || minecraft.player.getOffhandItem().is(RobotRegistry.PHONE.get()))) minecraft.setScreen(null);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.fill(left - 4, 2, left + panel + 4, height - 2, 0xF3182934);
        var route = FieldJournalScreen.route();
        g.drawString(font, title, left + 4, 8, 0xEEF8FA);
        if (minecraft.player == null) {
            line(g, "暂无目的地", 34, 0xFFE8A8);
            line(g, "持有手机时可查看当前位置与地形。", 50, 0xDCEDEF);
        } else if (!route.active()) {
            line(g, "暂无目的地", 23, 0xFFE8A8);
            line(g, "只绘制本机已加载地形；深色区域尚无可用地图数据。", 38, 0xDCEDEF);
            map(g, null, minecraft.player.blockPosition());
        } else {
            String dim = switch (route.dimension()) {
                case "minecraft:overworld" -> "主世界";
                case "ad_astra:moon" -> "月球（Ad Astra）";
                case "ad_astra:mars" -> "火星（Ad Astra）";
                case "northstar:moon" -> "旧航点（northstar:moon）";
                case "northstar:mars" -> "旧航点（northstar:mars）";
                default -> route.dimension();
            };
            line(g, com.ic2newera.client.robot.camera.PhoneEntityNames.translateLeadingEntity(route.label()) + "　" + dim + "　" + route.target().toShortString(), 23, TARGET);
            line(g, route.status(), 38, 0xE7EEF1);
            line(g, "下一步　" + route.next(), 52, PLAYER);
            boolean same = minecraft.player.level().dimension().location().toString().equals(route.dimension());
            if (!same) {
                line(g, "进入目标维度后会从你的位置重新规划。", top + 12, 0xB6C9CF);
                map(g, null, minecraft.player.blockPosition());
            }
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
        g.drawManaged(() -> drawMap(g, r, here));
    }
    private void drawMap(GuiGraphics g, FieldRoutes.Snapshot r, BlockPos here) {
        int x1 = left + 2, x2 = left + panel - 2, y1 = top, y2 = bottom;
        int cx = (x1 + x2) / 2, cy = (y1 + y2) / 2;
        double rx = r == null ? 32 : 3, rz = r == null ? 32 : 3;
        for (BlockPos p : r == null ? List.<BlockPos>of() : r.points()) {
            rx = Math.max(rx, Math.abs((long)p.getX() - here.getX()));
            rz = Math.max(rz, Math.abs((long)p.getZ() - here.getZ()));
        }
        double unit = Math.max(.5, Math.min(10, Math.min((x2 - x1 - 18) / (2 * rx), (y2 - y1 - 22) / (2 * rz)))) * Math.pow(1.55, zoom);
        g.fill(x1, y1, x2, y2, 0xFF101D26);
        g.enableScissor(x1, y1, x2, y2);
        try {
            terrain(g, x1, x2, y1, y2, cx, cy, unit, here);
            int grid = 1;
            while (grid * unit < 24) grid *= 2;
            int ox = Math.floorDiv(here.getX(), grid) * grid, oz = Math.floorDiv(here.getZ(), grid) * grid;
            int gx = RouteDirections.mapX(here.getX(), ox, cx, unit), gz = RouteDirections.mapY(here.getZ(), oz, cy, unit);
            for (double i = gx - Math.ceil((gx - x1) / (grid * unit)) * grid * unit; i < x2; i += grid * unit)
                g.fill((int)i, y1, (int)i + 1, y2, 0xFF223B44);
            for (double i = gz - Math.ceil((gz - y1) / (grid * unit)) * grid * unit; i < y2; i += grid * unit)
                g.fill(x1, (int)i, x2, (int)i + 1, 0xFF223B44);
            BlockPos prev = null; int px = 0, py = 0;
            for (BlockPos p : r == null ? List.<BlockPos>of() : r.points()) {
                int x = mapCoord(here.getX(), p.getX(), cx, unit), y = mapCoord(here.getZ(), p.getZ(), cy, unit);
                if (prev != null) {
                    drawClippedLine(g, px, py, x, y, x1, y1, x2, y2);
                    if (prev.getY() != p.getY()) g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF9CAFF7);
                }
                prev = p; px = x; py = y;
            }
            if (r != null && r.active()) {
                int tx = mapCoord(here.getX(), r.target().getX(), cx, unit);
                int ty = mapCoord(here.getZ(), r.target().getZ(), cy, unit);
                if (tx >= x1 && tx < x2 && ty >= y1 && ty < y2) {
                    g.fill(tx-4, ty-1, tx+5, ty+2, TARGET); g.fill(tx-1, ty-4, tx+2, ty+5, TARGET);
                }
            }
            g.fill(cx - 2, cy - 2, cx + 3, cy + 3, PLAYER);
            heading(g, cx, cy, minecraft.player.getYRot());
            g.drawString(font, "↑ 北", x1 + 4, y1 + 3, 0xCADADD);
            g.drawString(font, "每格 " + grid + " 米", x1 + 4, y2 - 12, 0xB0C8D0);
        } finally { g.disableScissor(); }
        line(g, r != null && r.active() ? "地形仅显示已加载区　黄点 本人　绿线 路线　橙十字 目的地"
                : "地形仅显示已加载区　黄点 本人", height - 39, 0xCEE1E4);
    }
    private void terrain(GuiGraphics g, int x1, int x2, int y1, int y2, int cx, int cy, double unit, BlockPos here) {
        Level level = minecraft.level;
        if (level == null) return;
        if (terrainLevel != level) {
            terrainLevel = level;
            terrainColors.clear();
            terrainRefreshTick = 0;
        }
        boolean refresh = minecraft.level.getGameTime() - terrainRefreshTick >= 20;
        if (refresh) terrainRefreshTick = minecraft.level.getGameTime();
        final int tile = Math.max(4, (int)Math.ceil(Math.sqrt((long)(x2 - x1) * (y2 - y1) / 8000.0)));
        double pxPerBlock = Math.max(.001, unit);
        for (int sy = y1; sy < y2; sy += tile) {
            int py = Math.min(y2, sy + tile);
            long wz = (long)Math.floor(here.getZ() + (sy + (py - sy) / 2.0 - cy) / pxPerBlock);
            if (wz < Integer.MIN_VALUE || wz > Integer.MAX_VALUE) continue;
            int runStart = x1;
            int runColor = 0xFF111C24;
            for (int sx = x1; sx < x2; sx += tile) {
                int ex = Math.min(x2, sx + tile);
                long wx = (long)Math.floor(here.getX() + (sx + (ex - sx) / 2.0 - cx) / pxPerBlock);
                int color = wx < Integer.MIN_VALUE || wx > Integer.MAX_VALUE ? 0xFF111C24
                        : terrainCellColor(level, (int)wx, (int)wz, refresh);
                if (sx == x1) runColor = color;
                else if (color != runColor) {
                    g.fill(runStart, sy, sx, py, runColor);
                    runStart = sx;
                    runColor = color;
                }
            }
            g.fill(runStart, sy, x2, py, runColor);
        }
    }
    private int terrainCellColor(Level level, int x, int z, boolean refresh) {
        long key = ((x & 0xffffffffL) << 32) | (z & 0xffffffffL);
        Integer color = terrainColors.get(key);
        if (refresh || !terrainColors.containsKey(key)) {
            color = loadedSurfaceColor(level, x, z);
            terrainColors.put(key, color == null ? -1 : color);
        }
        return color == null || color < 0 ? 0xFF111C24 : color | 0xFF000000;
    }
    private static Integer loadedSurfaceColor(Level level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return null;
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return null;
        BlockPos pos = new BlockPos(x, y, z);
        int color = chunk.getBlockState(pos).getMapColor(level, pos).col;
        // Subtle height shading keeps cliffs legible without implying unseen detail.
        int localZ = z & 15;
        int compareZ = localZ < 15 ? localZ + 1 : localZ - 1;
        int adjacent = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, compareZ);
        if (adjacent < y) color = shade(color, 1.12f);
        else if (adjacent > y) color = shade(color, .86f);
        return color;
    }
    private static int mapCoord(int origin, int point, int center, double pixels) {
        double mapped = center + ((long)point - origin) * pixels;
        return (int)Math.max(-1_000_000, Math.min(1_000_000, Math.round(mapped)));
    }
    private static void drawClippedLine(GuiGraphics g, int ax, int ay, int bx, int by, int left, int top, int right, int bottom) {
        double x0 = ax, y0 = ay, x1 = bx, y1 = by;
        double dx = x1 - x0, dy = y1 - y0;
        double[] t = {0, 1};
        if (!clip(-dx, x0 - left, t) || !clip(dx, right - x0, t)
                || !clip(-dy, y0 - top, t) || !clip(dy, bottom - y0, t)) return;
        int sx = (int)Math.round(x0 + t[0] * dx), sy = (int)Math.round(y0 + t[0] * dy);
        int ex = (int)Math.round(x0 + t[1] * dx), ey = (int)Math.round(y0 + t[1] * dy);
        drawLine(g, sx, sy, ex, ey);
    }
    private static boolean clip(double p, double q, double[] t) {
        if (p == 0) return q >= 0;
        double ratio = q / p;
        if (p < 0) {
            if (ratio > t[1]) return false;
            if (ratio > t[0]) t[0] = ratio;
        } else {
            if (ratio < t[0]) return false;
            if (ratio < t[1]) t[1] = ratio;
        }
        return true;
    }
    private static int shade(int rgb, float factor) {
        int r = Math.min(255, (int)(((rgb >> 16) & 255) * factor));
        int g = Math.min(255, (int)(((rgb >> 8) & 255) * factor));
        int b = Math.min(255, (int)((rgb & 255) * factor));
        return (r << 16) | (g << 8) | b;
    }
    private static void heading(GuiGraphics g, int cx, int cy, float yaw) {
        double angle = Math.toRadians(yaw);
        int dx = (int)Math.round(-Math.sin(angle) * 7), dy = (int)Math.round(Math.cos(angle) * 7);
        drawLine(g, cx, cy, cx + dx, cy + dy, PLAYER);
    }
    private static void drawLine(GuiGraphics g, int x, int y, int endX, int endY) {
        drawLine(g, x, y, endX, endY, ROUTE);
    }
    private static void drawLine(GuiGraphics g, int x, int y, int endX, int endY, int color) {
        int count = Math.max(1, Math.max(Math.abs(endX - x), Math.abs(endY - y)));
        for (int i = 0; i <= count; i++) {
            int px = x + Math.round((endX - x) * (float)i / count), py = y + Math.round((endY - y) * (float)i / count);
            g.fill(px, py, px + 2, py + 2, color);
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double dy) {
        if (list) { scroll = Math.max(0, scroll - (int)Math.signum(dy) * 3); return true; }
        return super.mouseScrolled(x, y, dy);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
