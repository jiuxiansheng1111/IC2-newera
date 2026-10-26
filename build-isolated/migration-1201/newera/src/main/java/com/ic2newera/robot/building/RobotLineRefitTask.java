package com.ic2newera.robot.building;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import ic2.core.block.wiring.AbstractCableBlock;

/** Saved owner-bound change to an existing production site. */
public final class RobotLineRefitTask {
    public static final int CABLE = 0, REPLAN = 1;
    private final int kind;
    private final UUID owner;
    private final ResourceLocation dimension;
    private final BlockPos origin;
    private final RobotBuildPlan sourcePlan, targetPlan;
    private final int resumeMode;
    private final List<BlockPos> cablePositions;
    private int cableIndex, cableStage;
    private int expectedDropCount, collectedDropCount;
    private int inputSlot, transferStage, cargoSlot = -1;
    private ItemStack escrow = ItemStack.EMPTY;
    private final Set<UUID> preexistingDrops = new HashSet<>();
    private final Set<UUID> nativeDropIds = new HashSet<>(), collectedDropIds = new HashSet<>();

    public RobotLineRefitTask(int kind, UUID owner, ResourceLocation dimension, BlockPos origin,
                              RobotBuildPlan sourcePlan, RobotBuildPlan targetPlan, int resumeMode) {
        if ((kind != CABLE && kind != REPLAN) || owner == null || dimension == null || origin == null
                || sourcePlan == null || targetPlan == null || !"production".equals(sourcePlan.kind())
                || !"production".equals(targetPlan.kind()) || resumeMode != RobotEntity.STAY && resumeMode != RobotEntity.PRODUCTION)
            throw new IllegalArgumentException("生产线改造记录不完整");
        this.kind = kind; this.owner = owner; this.dimension = dimension; this.origin = origin.immutable();
        this.sourcePlan = sourcePlan; this.targetPlan = targetPlan; this.resumeMode = resumeMode;
        var oldCables = new java.util.HashMap<BlockPos, RobotBuildPlan.Part>();
        for (var part : sourcePlan.parts()) if (part.phase().equals("power_cable")) oldCables.put(part.offset(), part);
        var newCables = new java.util.HashMap<BlockPos, RobotBuildPlan.Part>();
        for (var part : targetPlan.parts()) if (part.phase().equals("power_cable")) newCables.put(part.offset(), part);
        if (kind == CABLE && !oldCables.keySet().equals(newCables.keySet()))
            throw new IllegalArgumentException("改造方案不能移动或增删现有电缆节点");
        List<BlockPos> changed = new ArrayList<>();
        for (BlockPos pos : oldCables.keySet()) {
            var before = oldCables.get(pos); var after = newCables.get(pos);
            if (after != null && !before.equals(after)) changed.add(pos.immutable());
        }
        if (kind == CABLE && changed.isEmpty()) throw new IllegalArgumentException("所选电缆与现有电缆相同");
        changed.sort(BlockPos::compareTo); cablePositions = List.copyOf(changed);
    }

    private RobotLineRefitTask(int kind, UUID owner, ResourceLocation dimension, BlockPos origin,
                               RobotBuildPlan sourcePlan, RobotBuildPlan targetPlan, int resumeMode,
                               int cableIndex, int cableStage) {
        this(kind, owner, dimension, origin, sourcePlan, targetPlan, resumeMode);
        if (cableIndex < 0 || cableIndex > cablePositions.size() || cableStage < 0 || cableStage > 3
                || cableIndex == cablePositions.size() && cableStage != 0) throw new IllegalArgumentException("电缆改造进度无效");
        this.cableIndex = cableIndex; this.cableStage = cableStage;
    }

    public int kind() { return kind; }
    public UUID owner() { return owner; }
    public ResourceLocation dimension() { return dimension; }
    public BlockPos origin() { return origin; }
    public RobotBuildPlan sourcePlan() { return sourcePlan; }
    public RobotBuildPlan targetPlan() { return targetPlan; }
    public int resumeMode() { return resumeMode; }
    public List<BlockPos> cablePositions() { return cablePositions; }
    public int cableIndex() { return cableIndex; }
    public int cableStage() { return cableStage; }
    public int inputSlot() { return inputSlot; }
    public int transferStage() { return transferStage; }
    public int cargoSlot() { return cargoSlot; }
    public ItemStack escrow() { return escrow.copy(); }
    public boolean inputsMoved() { return transferStage == 0 && escrow.isEmpty() && inputSlot >= 27; }
    public void prepareTransfer(int slot, ItemStack stack, int cargoSlot) {
        if (kind != REPLAN || transferStage != 0 || slot < 0 || slot >= 27 || cargoSlot < RobotInventory.CARGO_START
                || cargoSlot >= RobotInventory.SIZE || stack == null || stack.isEmpty())
            throw new IllegalStateException("旧进料转移记录无效");
        inputSlot = slot; this.cargoSlot = cargoSlot; escrow = stack.copy(); transferStage = 1;
    }
    public void markEscrowed() {
        if (kind != REPLAN || transferStage != 1 || escrow.isEmpty()) throw new IllegalStateException("旧进料暂存阶段无效");
        transferStage = 2;
    }
    public void updateEscrow(ItemStack remaining) {
        if (kind != REPLAN || transferStage != 2 || remaining == null) throw new IllegalStateException("旧进料交付阶段无效");
        escrow = remaining.copy();
    }
    /** Record the real extracted stack; escrow is a mirror of the reserved cargo slot. */
    public void markExtracted(ItemStack actual) {
        if (kind != REPLAN || transferStage != 1 || actual == null || actual.isEmpty())
            throw new IllegalStateException("旧进料实际转运物无效");
        escrow = actual.copy();
        transferStage = 2;
    }
    public void advanceEmptySlot() {
        if (kind != REPLAN || transferStage != 0 || !escrow.isEmpty() || inputSlot >= 27) throw new IllegalStateException("旧进料扫描阶段无效");
        inputSlot++;
    }
    public void clearEscrow() {
        if (kind != REPLAN || transferStage != 2) throw new IllegalStateException("旧进料交付阶段无效");
        // Revisit this input slot: a handler may have supplied only part of the requested stack.
        // Only advanceEmptySlot may move on after observing that the real source slot is empty.
        escrow = ItemStack.EMPTY; transferStage = 0; cargoSlot = -1;
    }
    public void finishInputSlots() { if (kind == REPLAN && transferStage == 0) inputSlot = 27; }
    public boolean cablesComplete() { return cableIndex >= cablePositions.size(); }
    public BlockPos currentCable() { return cablesComplete() ? null : cablePositions.get(cableIndex); }
    public void markCableInstalled() { if (cablesComplete()) throw new IllegalStateException("电缆改造已完成"); cableIndex++; cableStage = 0; }
    public Set<UUID> preexistingDrops() { return Set.copyOf(preexistingDrops); }
    public Set<UUID> nativeDropIds() { return Set.copyOf(nativeDropIds); }
    public Set<UUID> collectedDropIds() { return Set.copyOf(collectedDropIds); }
    public int expectedDropCount() { return expectedDropCount; }
    public int collectedDropCount() { return collectedDropCount; }
    public void snapshotDrops(Set<UUID> ids) { preexistingDrops.clear(); preexistingDrops.addAll(ids); cableStage = 3; }
    public void markCableRemoved(Set<UUID> nativeDrops) {
        if (cablesComplete()) throw new IllegalStateException("电缆改造已完成");
        cableStage = 1; expectedDropCount = 1; collectedDropCount = 0; nativeDropIds.clear();
        if (nativeDrops != null && nativeDrops.size() == 1) nativeDropIds.addAll(nativeDrops);
        collectedDropIds.clear();
    }
    public void recoverNativeDropIds(Set<UUID> ids) {
        if (cableStage != 1 || expectedDropCount != 1 || !nativeDropIds.isEmpty() || ids == null || ids.size() != 1) return;
        nativeDropIds.addAll(ids);
    }
    public void markDropCollected(UUID id) {
        if (cableStage != 1 || !nativeDropIds.contains(id) || !collectedDropIds.add(id))
            throw new IllegalStateException("电缆原生回收物记录无效");
        collectedDropCount++;
    }
    public void markCableCollected() {
        if (cablesComplete() || collectedDropCount < expectedDropCount) throw new IllegalStateException("电缆原生回收物尚未完整收取");
        cableStage = 2; preexistingDrops.clear(); nativeDropIds.clear(); collectedDropIds.clear(); expectedDropCount = collectedDropCount = 0;
    }

    /** IC2R stores cable configuration in blockstate; compare only user-configurable fields to defaults. */
    public static boolean unconfiguredCable(BlockEntity entity, BlockState state) {
        if (state == null || !(state.getBlock() instanceof AbstractCableBlock) || !state.getFluidState().isEmpty()) return false;
        BlockState defaults = state.getBlock().defaultBlockState();
        return state.getValue(AbstractCableBlock.colorProperty) == defaults.getValue(AbstractCableBlock.colorProperty)
                && state.getValue(AbstractCableBlock.foamProperty) == defaults.getValue(AbstractCableBlock.foamProperty)
                && state.getValue(AbstractCableBlock.WATERLOGGED) == defaults.getValue(AbstractCableBlock.WATERLOGGED);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Kind", kind); tag.putUUID("Owner", owner);
        tag.putString("Dimension", dimension.toString()); tag.putLong("Origin", origin.asLong());
        tag.put("Source", sourcePlan.save()); tag.put("Target", targetPlan.save()); tag.putInt("ResumeMode", resumeMode);
        tag.putInt("CableIndex", cableIndex); tag.putInt("CableStage", cableStage);
        tag.putInt("ExpectedDrops", expectedDropCount); tag.putInt("CollectedDrops", collectedDropCount);
        saveIds(tag, "NativeDrops", nativeDropIds); saveIds(tag, "CollectedDropIds", collectedDropIds);
        tag.putInt("InputSlot", inputSlot); tag.putInt("TransferStage", transferStage);
        tag.putInt("TransferCargoSlot", cargoSlot);
        if (!escrow.isEmpty()) tag.put("TransferEscrow", escrow.save(new CompoundTag()));
        ListTag drops = new ListTag(); preexistingDrops.forEach(id -> { CompoundTag entry = new CompoundTag(); entry.putUUID("Id", id); drops.add(entry); });
        tag.put("PreexistingDrops", drops); return tag;
    }
    public static RobotLineRefitTask load(CompoundTag tag) {
        if (!tag.hasUUID("Owner")) throw new IllegalArgumentException("缺少改造主人记录");
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null || tag.getCompound("Source").toString().length() > 1_000_000
                || tag.getCompound("Target").toString().length() > 1_000_000) throw new IllegalArgumentException("生产线改造方案无效");
        RobotLineRefitTask task = new RobotLineRefitTask(tag.getInt("Kind"), tag.getUUID("Owner"), dimension,
                BlockPos.of(tag.getLong("Origin")), RobotBuildPlan.load(tag.getCompound("Source")),
                RobotBuildPlan.load(tag.getCompound("Target")), tag.getInt("ResumeMode"),
                tag.getInt("CableIndex"), tag.getInt("CableStage"));
        task.inputSlot = tag.getInt("InputSlot"); task.transferStage = tag.getInt("TransferStage");
        task.cargoSlot = tag.contains("TransferCargoSlot") ? tag.getInt("TransferCargoSlot") : -1;
        task.escrow = tag.contains("TransferEscrow", Tag.TAG_COMPOUND) ? ItemStack.of(tag.getCompound("TransferEscrow")) : ItemStack.EMPTY;
        task.expectedDropCount = tag.contains("ExpectedDrops") ? tag.getInt("ExpectedDrops") : task.cableStage == 1 ? 1 : 0;
        task.collectedDropCount = tag.getInt("CollectedDrops");
        loadIds(tag.getList("NativeDrops", Tag.TAG_COMPOUND), task.nativeDropIds);
        loadIds(tag.getList("CollectedDropIds", Tag.TAG_COMPOUND), task.collectedDropIds);
        if (task.inputSlot < 0 || task.inputSlot > 27 || task.transferStage < 0 || task.transferStage > 2
                || task.transferStage == 0 && !task.escrow.isEmpty() || task.transferStage != 0 && task.escrow.isEmpty()
                || task.transferStage == 0 && task.cargoSlot != -1 || task.transferStage != 0
                && (task.cargoSlot < RobotInventory.CARGO_START || task.cargoSlot >= RobotInventory.SIZE)
                || task.kind != REPLAN && (task.inputSlot != 0 || task.transferStage != 0 || task.cargoSlot != -1 || !task.escrow.isEmpty()))
            throw new IllegalArgumentException("旧进料转移进度无效");
        ListTag drops = tag.getList("PreexistingDrops", Tag.TAG_COMPOUND);
        if (drops.size() > 512) throw new IllegalArgumentException("电缆掉落恢复记录过大");
        for (int i = 0; i < drops.size(); i++) if (drops.getCompound(i).hasUUID("Id")) task.preexistingDrops.add(drops.getCompound(i).getUUID("Id"));
        if (task.expectedDropCount < 0 || task.expectedDropCount > 1 || task.collectedDropCount < 0
                || task.collectedDropCount > task.expectedDropCount || task.collectedDropIds.size() != task.collectedDropCount
                || !task.nativeDropIds.containsAll(task.collectedDropIds)
                || task.cableStage == 1 && task.expectedDropCount != 1
                || task.cableStage != 1 && (task.expectedDropCount != 0 || task.collectedDropCount != 0
                || !task.nativeDropIds.isEmpty() || !task.collectedDropIds.isEmpty()))
            throw new IllegalArgumentException("电缆原生回收进度无效");
        return task;
    }

    private static void saveIds(CompoundTag target, String key, Set<UUID> ids) {
        ListTag list = new ListTag(); ids.forEach(id -> { CompoundTag entry = new CompoundTag(); entry.putUUID("Id", id); list.add(entry); });
        target.put(key, list);
    }
    private static void loadIds(ListTag list, Set<UUID> target) {
        if (list.size() > 16) throw new IllegalArgumentException("电缆回收实体记录过大");
        for (int i = 0; i < list.size(); i++) if (list.getCompound(i).hasUUID("Id")) target.add(list.getCompound(i).getUUID("Id"));
    }
}
