package com.ic2newera.field.places;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/** All hard VS links live here; PersonalPlaces loads this class only when VS is present. */
public final class VsShipPlacesCompat {
    private VsShipPlacesCompat() { }

    public static CompoundTag capture(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        try {
            var mounted = VSGameUtilsKt.getShipMountedToData(player, null);
            if (mounted != null) {
                LoadedShip ship = mounted.getShipMountedTo();
                Vector3d local = new Vector3d(mounted.getMountPosInShip());
                if (validShip(level, ship) && supported(level, ship, player.getX(), player.getY(), player.getZ())
                        && finite(local.x, local.y, local.z)) return anchor(ship, local);
            }
        } catch (RuntimeException ignored) { }

        // VS may not mark a standing player as mounted. Restrict candidates to the player's thin foot volume,
        // then require the inverse-transformed point to sit on a real, safe deck collision surface.
        double x = player.getX(), y = player.getY(), z = player.getZ();
        AABB feet = new AABB(x - .22, y - .12, z - .22, x + .22, y + .08, z + .22);
        try {
            for (Ship candidate : VSGameUtilsKt.getShipsIntersecting(level, feet)) {
                if (!(candidate instanceof LoadedShip ship) || !validShip(level, ship)) continue;
                if (!supported(level, ship, x, y, z)) continue;
                Vector3d local = ship.getWorldToShip().transformPosition(new Vector3d(x, y, z));
                if (finite(local.x, local.y, local.z)) return anchor(ship, local);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** Returns a fresh current-world position, or null if that exact loaded ship cannot be resolved. */
    public static Vec3 resolve(ServerLevel level, long shipId, double localX, double localY, double localZ) {
        if (!finite(localX, localY, localZ)) return null;
        try {
            LoadedShip ship = VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(shipId);
            if (!validShip(level, ship)) return null;
            Vector3d world = ship.getShipToWorld().transformPosition(new Vector3d(localX, localY, localZ));
            return finite(world.x, world.y, world.z) ? new Vec3(world.x, world.y, world.z) : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    /** Legacy world-coordinate markers become ship-relative only when a safe deck is actually under them. */
    public static CompoundTag resolveLegacy(ServerLevel level, BlockPos marker) {
        double x = marker.getX() + .5, y = marker.getY(), z = marker.getZ() + .5;
        AABB feet = new AABB(x - .2, y - .12, z - .2, x + .2, y + .08, z + .2);
        try {
            for (Ship candidate : VSGameUtilsKt.getShipsIntersecting(level, feet)) {
                if (!(candidate instanceof LoadedShip ship) || !validShip(level, ship)
                        || !supported(level, ship, x, y, z)) continue;
                Vector3d local = ship.getWorldToShip().transformPosition(new Vector3d(x, y, z));
                Vector3d world = ship.getShipToWorld().transformPosition(local);
                if (finite(local.x, local.y, local.z, world.x, world.y, world.z)) {
                    CompoundTag result = anchor(ship, local);
                    result.putDouble("WorldX", world.x); result.putDouble("WorldY", world.y); result.putDouble("WorldZ", world.z);
                    return result;
                }
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    public static Vec3 safeLanding(ServerLevel level, Entity entity, long shipId,
                            double localX, double localY, double localZ) {
        if (!finite(localX, localY, localZ)) return null;
        try {
            LoadedShip ship = VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(shipId);
            if (!validShip(level, ship)) return null;
            Vector3d up = ship.getTransform().transformDirectionNoScalingFromShipToWorld(new Vector3d(0, 1, 0), new Vector3d());
            if (!finite(up.x, up.y, up.z) || up.y < .85) return null;
            BlockPos anchor = BlockPos.containing(localX, localY, localZ);
            for (int radius = 0; radius <= 2; radius++) {
                for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy : new int[]{0, -1, 1, -2, 2}) {
                        BlockPos feet = anchor.offset(dx, dy, dz), floor = feet.below();
                        if (level.isOutsideBuildHeight(floor)) continue;
                        BlockState base = level.getBlockState(floor), body = level.getBlockState(feet), head = level.getBlockState(feet.above());
                        if (hazard(base) || hazard(body) || hazard(head)
                                || !level.getFluidState(floor).isEmpty() || !level.getFluidState(feet).isEmpty()
                                || !level.getFluidState(feet.above()).isEmpty()) continue;
                        var support = base.getCollisionShape(level, floor);
                        double top = support.max(Direction.Axis.Y);
                        boolean supportsCenter = support.toAabbs().stream().anyMatch(box ->
                                box.minX <= .5 && box.maxX >= .5 && box.minZ <= .5 && box.maxZ >= .5
                                        && Math.abs(box.maxY - top) < 1.0e-6);
                        if (support.isEmpty() || top <= 0 || !supportsCenter) continue;
                        Vector3d landingLocal = new Vector3d(feet.getX() + .5, floor.getY() + top, feet.getZ() + .5);
                        Vector3d landingWorld = ship.getShipToWorld().transformPosition(landingLocal);
                        Vec3 target = new Vec3(landingWorld.x, landingWorld.y, landingWorld.z);
                        AABB playerBox = entity.getDimensions(net.minecraft.world.entity.Pose.STANDING)
                                .makeBoundingBox(target.x, target.y, target.z);
                        if (level.isOutsideBuildHeight(BlockPos.containing(target.x, target.y + entity.getBbHeight(), target.z))
                                || !level.hasChunkAt(BlockPos.containing(target))
                                || !level.getWorldBorder().isWithinBounds(playerBox)
                                || !shipBodyClear(level, ship, playerBox)
                                || !level.noCollision(entity, playerBox)) continue;
                        return target;
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static boolean supported(ServerLevel level, LoadedShip ship, double worldX, double feetY, double worldZ) {
        if (!validShip(level, ship)) return false;
        Vector3d up = ship.getTransform().transformDirectionNoScalingFromShipToWorld(new Vector3d(0, 1, 0), new Vector3d());
        if (!finite(up.x, up.y, up.z) || up.y < .85) return false;
        Vector3d local = ship.getWorldToShip().transformPosition(new Vector3d(worldX, feetY, worldZ));
        if (!finite(local.x, local.y, local.z)) return false;
        BlockPos floor = BlockPos.containing(local.x, local.y - .04, local.z);
        BlockState state = level.getBlockState(floor);
        if (hazard(state) || !level.getFluidState(floor).isEmpty()) return false;
        var shape = state.getCollisionShape(level, floor);
        double top = shape.max(Direction.Axis.Y);
        boolean underPoint = shape.toAabbs().stream().anyMatch(box ->
                local.x - floor.getX() >= box.minX && local.x - floor.getX() <= box.maxX
                        && local.z - floor.getZ() >= box.minZ && local.z - floor.getZ() <= box.maxZ
                        && Math.abs(floor.getY() + box.maxY - local.y) <= .35);
        return !shape.isEmpty() && top > 0 && underPoint;
    }

    private static boolean validShip(ServerLevel level, LoadedShip ship) {
        return ship != null && VSGameUtilsKt.getDimensionId(level).equals(ship.getChunkClaimDimension());
    }

    private static CompoundTag anchor(LoadedShip ship, Vector3d local) {
        CompoundTag result = new CompoundTag();
        result.putLong("ShipId", ship.getId()); result.putDouble("ShipX", local.x);
        result.putDouble("ShipY", local.y); result.putDouble("ShipZ", local.z);
        return result;
    }

    private static boolean shipBodyClear(ServerLevel level, LoadedShip ship, AABB worldBox) {
        Vector3d[] corners = new Vector3d[8]; int index = 0;
        for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 0; z <= 1; z++) {
            corners[index++] = ship.getWorldToShip().transformPosition(new Vector3d(
                    x == 0 ? worldBox.minX : worldBox.maxX, y == 0 ? worldBox.minY : worldBox.maxY,
                    z == 0 ? worldBox.minZ : worldBox.maxZ));
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Vector3d corner : corners) {
            minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
        }
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(minX, minY, minZ), BlockPos.containing(maxX, maxY, maxZ))) {
            if (!level.hasChunkAt(pos)) return false;
            for (var part : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
                AABB transformed = transformedBlockBox(ship, pos, part);
                if (transformed != null && transformed.intersects(worldBox)) return false;
            }
        }
        return true;
    }

    private static AABB transformedBlockBox(LoadedShip ship, BlockPos pos, AABB local) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) for (int z = 0; z <= 1; z++) {
            Vector3d point = ship.getShipToWorld().transformPosition(new Vector3d(pos.getX() + (x == 0 ? local.minX : local.maxX),
                    pos.getY() + (y == 0 ? local.minY : local.maxY), pos.getZ() + (z == 0 ? local.minZ : local.maxZ)));
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y); minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y); maxZ = Math.max(maxZ, point.z);
        }
        return finite(minX, minY, minZ, maxX, maxY, maxZ) ? new AABB(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    private static boolean hazard(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }
    private static boolean finite(double... values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }
}
