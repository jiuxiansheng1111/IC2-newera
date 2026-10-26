package com.ic2newera.machine;

/** Machine layouts and recipe identifiers shared by blocks, menus, and server processing. */
public enum ProcessorKind {
    NON_METAL("non_metal", 1, "非金属处理机", 4096, 3),
    ELECTRONIC("electronic", 6, "电子材料加工机", 4096, 3),
    ALLOY("alloy", 3, "合金兼容炉", 16384, 3),
    ADVANCED("advanced", 1, "高级熔炼兼容炉", 16384, 3);

    private final String serializedName;
    private final int inputSlots;
    private final String displayName;
    private final int energyCapacity;
    private final int sinkTier;

    ProcessorKind(String serializedName, int inputSlots, String displayName, int energyCapacity, int sinkTier) {
        this.serializedName = serializedName;
        this.inputSlots = inputSlots;
        this.displayName = displayName;
        this.energyCapacity = energyCapacity;
        this.sinkTier = sinkTier;
    }

    public String serializedName() { return serializedName; }
    public int inputSlots() { return inputSlots; }
    public String displayName() { return displayName; }
    public int energyCapacity() { return energyCapacity; }
    public int sinkTier() { return sinkTier; }
    public int outputSlot() { return inputSlots; }
    public int machineSlots() { return inputSlots + 1; }

    public static ProcessorKind fromSerializedName(String value) {
        for (ProcessorKind kind : values()) if (kind.serializedName.equals(value)) return kind;
        throw new IllegalArgumentException("Unknown processor kind: " + value);
    }
}
