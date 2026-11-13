package com.ic2newera.field.nav;

/** Minecraft uses positive X east and positive Z south. Map north is always up. */
public final class RouteDirections {
    private static final String[] COMPASS = {"南", "东南", "东", "东北", "北", "西北", "西", "西南"};
    private RouteDirections() {}
    public static String heading(int dx, int dy, int dz) {
        String horizontal = dx == 0 && dz == 0 ? "" : "向" + COMPASS[Math.floorMod(
                (int)Math.round(Math.atan2(dx, dz) / (Math.PI / 4)), 8)];
        String vertical = dy > 0 ? "上行" : dy < 0 ? "下行" : "";
        return horizontal.isEmpty() && vertical.isEmpty() ? "原地" : horizontal + vertical;
    }
    public static int mapX(int origin, int blocks, int center, double pixels) {
        return center + (int)Math.round(((long)blocks - origin) * pixels);
    }
    public static int mapY(int origin, int blocks, int center, double pixels) {
        return mapX(origin, blocks, center, pixels);
    }
}
