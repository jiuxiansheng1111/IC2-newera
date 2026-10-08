package com.ic2newera.machine;

/** Machine layouts and recipe identifiers shared by blocks, menus, and server processing. */
public enum ProcessorKind {
    NON_METAL("non_metal", 1),
    ELECTRONIC("electronic", 6);

    private final String serializedName;
    private final int inputSlots;

    ProcessorKind(String serializedName, int inputSlots) {
        this.serializedName = serializedName;
        this.inputSlots = inputSlots;
    }

    public String serializedName() { return serializedName; }
    public int inputSlots() { return inputSlots; }
    public int outputSlot() { return inputSlots; }
    public int machineSlots() { return inputSlots + 1; }

    public static ProcessorKind fromSerializedName(String value) {
        for (ProcessorKind kind : values()) if (kind.serializedName.equals(value)) return kind;
        throw new IllegalArgumentException("Unknown processor kind: " + value);
    }
}
