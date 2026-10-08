package com.ic2newera.industrial;

import com.ic2newera.integration.ic2.IC2Integration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Reads only the public IC2 reader API and Create's confirmed public kinetic status methods. */
final class IndustrialMachineInspector {
    private static final String CREATE_KINETIC_TILE = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
    private static volatile Class<?> createKineticClass;
    private static volatile Method createOverstressed;
    private static volatile Method createSpeed;
    private static volatile boolean createLookupFinished;

    private IndustrialMachineInspector() { }

    static List<MachineReport> inspectAdjacent(ServerLevel level, BlockPos adapterPos) {
        List<MachineReport> reports = new ArrayList<>(6);
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockPos machinePos = adapterPos.relative(direction);
            if (!level.hasChunkAt(machinePos)) continue;
            BlockEntity machine = level.getBlockEntity(machinePos);
            MachineReport report = inspect(level, machinePos, machine);
            if (report != null) reports.add(report);
        }
        return List.copyOf(reports);
    }

    static MachineReport inspect(ServerLevel level, BlockPos pos, BlockEntity machine) {
        if (machine == null) return null;
        String name = level.getBlockState(pos).getBlock().getName().getString();
        List<String> details = new ArrayList<>();
        String issue = "";
        String controlCode = "UNKNOWN";
        boolean supported = false;

        try {
            Optional<IC2Integration.MachineSnapshot> optional = IC2Integration.inspect(machine);
            if (optional.isPresent()) {
                supported = true;
                IC2Integration.MachineSnapshot snapshot = optional.get();
                if (snapshot.storedEU() >= 0 && snapshot.capacityEU() >= 0) {
                    details.add("EU储能 " + snapshot.storedEU() + "/" + snapshot.capacityEU());
                }
                if (snapshot.progress() >= 0 && snapshot.maxProgress() > 0) {
                    details.add("进度 " + Math.round(snapshot.progress()) + "/" + Math.round(snapshot.maxProgress()));
                }
                if (snapshot.progress() >= 0 && snapshot.maxProgress() > 0) {
                    details.add(snapshot.active() ? "IC2活动中" : "IC2未活动");
                }
            }
        } catch (RuntimeException ignored) {
            details.add("IC2状态读取失败");
        }

        CreateStatus create = inspectCreate(machine);
        if (create != null) {
            supported = true;
            details.add("Create转速 " + String.format(Locale.ROOT, "%.2f", create.speed()));
            details.add(create.overstressed() ? "Create机械网络过载" : "Create未报告过载");
            if (create.overstressed()) {
                issue = "机械网络过载";
                controlCode = "OVERSTRESSED";
            }
        }

        if (machine instanceof ControllableMachine controllable) {
            supported = true;
            try {
                controlCode = normalizedCode(controllable.automationProblem());
                issue = formatProblem(controlCode);
            } catch (RuntimeException ignored) {
                controlCode = "UNKNOWN";
                issue = "自动控制状态读取失败";
            }
            details.add(controllable.isAutomationPaused() ? "自动处理已暂停" : "自动处理未暂停");
        }
        if (!supported) {
            var inventory=machine.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).orElse(null);
            var energy=machine.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).orElse(null);
            var fluid=machine.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER).orElse(null);
            if (inventory!=null) { supported=true; details.add("物品接口："+inventory.getSlots()+"槽（未推断配方角色）"); }
            if (energy!=null) { supported=true; details.add("FE储能："+energy.getEnergyStored()+"/"+energy.getMaxEnergyStored()+"；不与EU或转速换算"); }
            if (fluid!=null) { supported=true; details.add("流体接口："+fluid.getTanks()+"个容器（只读）"); }
        }
        if (!supported) return null;
        if (details.isEmpty()) details.add("状态未提供");
        return new MachineReport(pos.immutable(), name, issue, String.join("，", details), controlCode);
    }

    private static CreateStatus inspectCreate(BlockEntity machine) {
        Class<?> type = createType();
        if (type == null || !type.isInstance(machine)) return null;
        try {
            boolean over = Boolean.TRUE.equals(createOverstressed.invoke(machine));
            Object rawSpeed = createSpeed.invoke(machine);
            float speed = rawSpeed instanceof Number number ? number.floatValue() : 0.0F;
            return new CreateStatus(over, speed);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Class<?> createType() {
        if (!createLookupFinished) {
            synchronized (IndustrialMachineInspector.class) {
                if (!createLookupFinished) {
                    try {
                        Class<?> type = Class.forName(CREATE_KINETIC_TILE, false,
                                IndustrialMachineInspector.class.getClassLoader());
                        createOverstressed = type.getMethod("isOverStressed");
                        createSpeed = type.getMethod("getSpeed");
                        createKineticClass = type;
                    } catch (ReflectiveOperationException | LinkageError ignored) {
                        createKineticClass = null;
                    }
                    createLookupFinished = true;
                }
            }
        }
        return createKineticClass;
    }

    static String normalizedCode(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.trim().toUpperCase(Locale.ROOT);
    }

    static String formatProblem(String code) {
        return switch (code) {
            case "NONE" -> "未报告问题";
            case "WAITING_INPUT" -> "缺少输入原料";
            case "OUTPUT_FULL" -> "输出已满";
            case "NO_POWER" -> "缺电";
            case "LOW_POWER" -> "供电不足";
            case "OVERSTRESSED" -> "机械网络过载";
            case "NO_NETWORK" -> "未接入工业网络";
            case "DISABLED" -> "设备已禁用";
            case "MANUAL_OVERRIDE" -> "手动控制";
            case "ERROR" -> "设备报告错误";
            case "UNKNOWN" -> "状态未知";
            default -> "状态未知：" + code;
        };
    }

    record MachineReport(BlockPos pos, String name, String issue, String details, String controlCode) {
        MachineReport {
            pos = pos.immutable();
            name = name == null ? "未知设备" : name;
            issue = issue == null ? "" : issue;
            details = details == null ? "" : details;
            controlCode = controlCode == null ? "UNKNOWN" : controlCode;
        }
    }

    private record CreateStatus(boolean overstressed, float speed) { }
}
