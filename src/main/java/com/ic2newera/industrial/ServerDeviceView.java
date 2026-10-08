package com.ic2newera.industrial;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Public status row. Server-issued identity replaces user-entered coordinates. */
public record ServerDeviceView(UUID id, int number, String nameKey, String connection, String runState, String problem,
                               boolean faulty, boolean controllable, boolean paused) {
    public Component displayName() { return Component.translatable(nameKey); }
    public String statusText() {
        if (faulty) return problem + (paused ? "（已暂停）" : "");
        return switch (runState) {
            case "RUNNING" -> "运行中";
            case "EXTERNAL" -> "外部动力驱动";
            case "PAUSED" -> "已暂停";
            case "IDLE" -> "停止/待机";
            default -> "运行状态未知";
        };
    }
    CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id); tag.putInt("Number", number); tag.putString("NameKey", nameKey); tag.putString("Connection", connection);
        tag.putString("RunState", runState); tag.putString("Problem", problem); tag.putBoolean("Faulty", faulty);
        tag.putBoolean("Controllable", controllable); tag.putBoolean("Paused", paused);
        return tag;
    }
    static ServerDeviceView read(CompoundTag tag) {
        return new ServerDeviceView(tag.getUUID("Id"), tag.getInt("Number"), tag.getString("NameKey"), tag.getString("Connection"),
                tag.getString("RunState"), tag.getString("Problem"), tag.getBoolean("Faulty"),
                tag.getBoolean("Controllable"), tag.getBoolean("Paused"));
    }
}
