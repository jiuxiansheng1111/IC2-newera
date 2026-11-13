package com.ic2newera.robot.workshop;

/** Short, server-timed workshop motions shown only while the real action is active. */
public enum ToolStroke {
    NONE(0, -1),
    HAMMER(16, 8),
    SHEAR(18, 9),
    POLISH(20, 10),
    CUT(18, 9),
    STIR(18, 9);

    private final int duration;
    private final int contactTick;
    private static final ToolStroke[] IDS = values();

    ToolStroke(int duration, int contactTick) {
        this.duration = duration;
        this.contactTick = contactTick;
    }

    public int duration() { return duration; }
    public int contactTick() { return contactTick; }
    public static ToolStroke byId(int id) { return id >= 0 && id < IDS.length ? IDS[id] : NONE; }
}
