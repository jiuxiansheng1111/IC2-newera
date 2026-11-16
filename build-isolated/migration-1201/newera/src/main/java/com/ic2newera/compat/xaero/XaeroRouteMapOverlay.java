package com.ic2newera.compat.xaero;

import com.ic2newera.client.robot.FieldRouteScreen;
import com.ic2newera.field.FieldRoutes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import java.nio.ByteBuffer;

/** Draws route segments in Xaero's pre-mask in-minimap framebuffer layer. */
public final class XaeroRouteMapOverlay {
    private static final int OUTLINE = 0xF0221520;
    private static final int ROUTE = 0xF6FFD34D;

    private XaeroRouteMapOverlay() {}

    public static void draw(GuiGraphics graphics, MinimapElementRenderInfo info,
                            double sin, double cos, double zoom, float halfView) {
        if (graphics == null || info == null || info.renderPos == null || info.renderEntityDimension == null
                || !Double.isFinite(zoom) || zoom <= 0 || !Float.isFinite(halfView) || halfView <= 2) return;
        FieldRoutes.Snapshot route = FieldRouteScreen.currentRoute();
        if (route == null || !route.active() || route.points().size() < 2
                || !route.dimension().equals(info.renderEntityDimension.location().toString())) return;

        double sourceToMap = info.renderEntityDimensionScale / info.backgroundCoordinateScale;
        if (!Double.isFinite(sourceToMap) || sourceToMap <= 0) return;
        double clipRadius = halfView - 2.0;
        Vec3 center = info.renderPos;
        graphics.drawManaged(() -> {
            drawRoute(graphics, route, center, sourceToMap, sin, cos, zoom, clipRadius, 3, OUTLINE);
            drawRoute(graphics, route, center, sourceToMap, sin, cos, zoom, clipRadius, 2, ROUTE);
        });
    }

    /** Safe Mode's CPU raster path: draw into the upload buffer, never its persistent source pixels. */
    public static void drawSafeMode(ByteBuffer upload, FieldRoutes.Snapshot route, int size, int mapSize, double centerX, double centerZ,
                                    double sin, double cos, double zoom) {
        if (!Double.isFinite(zoom) || zoom <= 0) return;
        drawSafeStroke(upload, route, size, mapSize, centerX, centerZ, sin, cos, zoom, true);
        drawSafeStroke(upload, route, size, mapSize, centerX, centerZ, sin, cos, zoom, false);
    }

    private static void drawSafeStroke(ByteBuffer upload, FieldRoutes.Snapshot route, int size, int mapSize,
                                       double centerX, double centerZ, double sin, double cos, double zoom,
                                       boolean outline) {
        int previousX = 0, previousY = 0;
        boolean hasPrevious = false;
        double pixelCenter = (mapSize / 2) - 0.5;
        for (var point : route.points()) {
            double dx = point.getX() + 0.5 - centerX;
            double dz = point.getZ() + 0.5 - centerZ;
            double mapX = sin * dx - cos * dz;
            double mapY = cos * dx + sin * dz;
            int x = (int)Math.round(mapX * zoom + pixelCenter);
            int y = size - 1 - (int)Math.round(mapY * zoom + pixelCenter);
            if (hasPrevious) drawPixelLine(upload, size, previousX, previousY, x, y, outline);
            previousX = x;
            previousY = y;
            hasPrevious = true;
        }
    }

    private static void drawPixelLine(ByteBuffer pixels, int size, int ax, int ay, int bx, int by, boolean outline) {
        double x0 = ax, y0 = ay, dx = (double)bx - ax, dy = (double)by - ay;
        double[] t = {0, 1};
        if (!clip(-dx, x0, t) || !clip(dx, size - 1 - x0, t)
                || !clip(-dy, y0, t) || !clip(dy, size - 1 - y0, t)) return;
        double sx = x0 + t[0] * dx, sy = y0 + t[0] * dy;
        double ex = x0 + t[1] * dx, ey = y0 + t[1] * dy;
        dx = ex - sx; dy = ey - sy;
        int steps = Math.max(1, (int)Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) * 1.5));
        for (int i = 0; i <= steps; i++) {
            int x = (int)Math.round(sx + dx * i / steps), y = (int)Math.round(sy + dy * i / steps);
            if (outline) {
                for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++)
                    putPixel(pixels, size, x + ox, y + oy, 25, 18, 32);
            } else {
                putPixel(pixels, size, x, y, 255, 211, 77);
            }
        }
    }

    private static void putPixel(ByteBuffer pixels, int size, int x, int y, int r, int g, int b) {
        if (x < 0 || x >= size || y < 0 || y >= size) return;
        int offset = (y * size + x) * 3;
        pixels.put(offset, (byte)r);
        pixels.put(offset + 1, (byte)g);
        pixels.put(offset + 2, (byte)b);
    }

    private static void drawRoute(GuiGraphics graphics, FieldRoutes.Snapshot route, Vec3 center,
                                  double sourceToMap, double sin, double cos, double zoom, double clipRadius,
                                  int lineRadius, int color) {
        int previousX = 0, previousY = 0;
        boolean hasPrevious = false;
        for (var point : route.points()) {
            double dx = (point.getX() + 0.5) * sourceToMap - center.x;
            double dz = (point.getZ() + 0.5) * sourceToMap - center.z;
            double mapX = (sin * dx - cos * dz) * zoom;
            double mapY = (cos * dx + sin * dz) * zoom;
            if (!Double.isFinite(mapX) || !Double.isFinite(mapY)) {
                hasPrevious = false;
                continue;
            }
            int x = (int)Math.max(-1_000_000, Math.min(1_000_000, Math.round(mapX)));
            int y = (int)Math.max(-1_000_000, Math.min(1_000_000, Math.round(mapY)));
            if (hasPrevious && x == previousX && y == previousY) continue;
            if (hasPrevious) drawClipped(graphics, previousX, previousY, x, y, clipRadius, lineRadius, color);
            previousX = x;
            previousY = y;
            hasPrevious = true;
        }
    }

    private static void drawClipped(GuiGraphics graphics, int ax, int ay, int bx, int by, double radius, int lineRadius, int color) {
        double x0 = ax, y0 = ay, dx = (double)bx - ax, dy = (double)by - ay;
        double[] t = {0, 1};
        if (!clip(-dx, x0 + radius, t) || !clip(dx, radius - x0, t)
                || !clip(-dy, y0 + radius, t) || !clip(dy, radius - y0, t)) return;
        double sx = x0 + t[0] * dx, sy = y0 + t[0] * dy;
        double ex = x0 + t[1] * dx, ey = y0 + t[1] * dy;
        double clippedDx = ex - sx, clippedDy = ey - sy;
        int steps = Math.min(2048, Math.max(1, (int)Math.ceil(Math.max(Math.abs(clippedDx), Math.abs(clippedDy)) * 1.5)));
        for (int i = 0; i <= steps; i++) {
            int x = (int)Math.round(sx + clippedDx * i / steps);
            int y = (int)Math.round(sy + clippedDy * i / steps);
            graphics.fill(RenderType.guiOverlay(), x - lineRadius, y - lineRadius, x + lineRadius + 1, y + lineRadius + 1, color);
        }
    }

    private static boolean clip(double p, double q, double[] t) {
        if (p == 0) return q >= 0;
        double value = q / p;
        if (p < 0) {
            if (value > t[1]) return false;
            if (value > t[0]) t[0] = value;
        } else {
            if (value < t[0]) return false;
            if (value < t[1]) t[1] = value;
        }
        return true;
    }
}
