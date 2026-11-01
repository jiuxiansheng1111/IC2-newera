package com.ic2newera.robot.building;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.BitSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent per-robot construction progress, bounded to the immutable plan. */
public final class RobotBuildTask {
    private final RobotBuildPlan plan;
    private final UUID owner;
    private final ResourceLocation dimension;
    private final BlockPos origin;
    private final BitSet done;
    private final Set<BlockPos> plannedPositions;
    private final Map<BlockPos, Integer> plannedIndices;

    public RobotBuildTask(RobotBuildPlan plan, UUID owner, ResourceLocation dimension, BlockPos origin) {
        if (plan == null || owner == null || dimension == null || origin == null)
            throw new IllegalArgumentException("incomplete construction task");
        if (plan.parts().size() > RobotBuildPlan.MAX_PARTS) throw new IllegalArgumentException("construction is too large");
        this.plan = plan; this.owner = owner; this.dimension = dimension; this.origin = origin.immutable();
        this.done = new BitSet(plan.parts().size());
        this.plannedPositions = new HashSet<>(plan.parts().size());
        this.plannedIndices = new HashMap<>(plan.parts().size());
        for (int i = 0; i < plan.parts().size(); i++) {
            BlockPos position = this.origin.offset(plan.parts().get(i).offset());
            plannedPositions.add(position); plannedIndices.put(position, i);
        }
    }

    private RobotBuildTask(RobotBuildPlan plan, UUID owner, ResourceLocation dimension, BlockPos origin, BitSet done) {
        this(plan, owner, dimension, origin);
        if (done.length() > plan.parts().size()) throw new IllegalArgumentException("construction progress exceeds plan size");
        this.done.or(done);
    }

    public RobotBuildPlan plan() { return plan; }
    public UUID owner() { return owner; }
    public ResourceLocation dimension() { return dimension; }
    public BlockPos origin() { return origin; }
    public boolean done(int index) { return index >= 0 && index < plan.parts().size() && done.get(index); }
    public void markDone(int index) {
        if (index < 0 || index >= plan.parts().size()) throw new IndexOutOfBoundsException("construction part index " + index);
        done.set(index);
    }
    public boolean completed() { return done.cardinality() >= plan.parts().size(); }
    public int finished() { return done.cardinality(); }
    public boolean plannedPosition(BlockPos pos) { return plannedPositions.contains(pos); }
    public boolean unfinishedPlannedPosition(BlockPos pos) {
        Integer index = plannedIndices.get(pos);
        return index != null && !done.get(index);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.put("Plan", plan.save());
        tag.putUUID("Owner", owner); tag.putString("Dimension", dimension.toString());
        tag.putLong("Origin", origin.asLong()); tag.putLongArray("Done", done.toLongArray());
        return tag;
    }

    public static RobotBuildTask load(CompoundTag tag) {
        RobotBuildPlan plan = RobotBuildPlan.load(tag.getCompound("Plan"));
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null || !tag.hasUUID("Owner")) throw new IllegalArgumentException("invalid construction task identity");
        long[] bits = tag.getLongArray("Done");
        if (bits.length > (RobotBuildPlan.MAX_PARTS + 63) / 64) throw new IllegalArgumentException("construction progress is too large");
        return new RobotBuildTask(plan, tag.getUUID("Owner"), dimension, BlockPos.of(tag.getLong("Origin")), BitSet.valueOf(bits));
    }
}
