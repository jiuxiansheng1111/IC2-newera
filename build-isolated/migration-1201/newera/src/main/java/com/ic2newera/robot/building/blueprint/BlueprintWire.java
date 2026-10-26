package com.ic2newera.robot.building.blueprint;

import com.ic2newera.robot.building.RobotBuildPlan;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import java.io.*;
import java.util.zip.GZIPInputStream;

/** Compressed display plans have independent wire and inflated-memory limits. */
public final class BlueprintWire {
    public static final int MAX_WIRE = 1_000_000;
    private BlueprintWire() { }
    public static byte[] encodePreview(RobotBuildPlan plan) {
        try { return encode(plan); } catch (IllegalArgumentException tooLarge) { return new byte[0]; }
    }
    public static byte[] encode(RobotBuildPlan plan) {
        if (plan == null) return new byte[0];
        try {
            var out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(plan.save(), out);
            if (out.size() > MAX_WIRE) throw new IllegalArgumentException("预览数据过大，请将建筑分为多份图纸。");
            return out.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException("无法编码施工预览。", e); }
    }
    public static RobotBuildPlan decode(byte[] bytes) {
        if (bytes.length == 0) return null;
        if (bytes.length > MAX_WIRE) throw new IllegalArgumentException("预览数据超过限制。");
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] plain = gzip.readNBytes(16 * 1024 * 1024 + 1);
            if (plain.length > 16 * 1024 * 1024) throw new IllegalArgumentException("预览解压数据过大。");
            return RobotBuildPlan.load(NbtIo.read(new DataInputStream(new ByteArrayInputStream(plain)), new NbtAccounter(32L * 1024 * 1024)));
        } catch (IOException e) { throw new IllegalArgumentException("施工预览格式损坏。", e); }
    }
}
