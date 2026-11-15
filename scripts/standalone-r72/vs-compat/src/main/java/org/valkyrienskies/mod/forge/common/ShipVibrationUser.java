package org.valkyrienskies.mod.forge.common;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Presents a ship-mounted vibration listener in world coordinates for one ticker call.
 * The original user is returned unchanged unless its block position belongs to a ship.
 */
public final class ShipVibrationUser implements VibrationSystem.User {
    private final VibrationSystem.User user;
    private final Level level;

    private ShipVibrationUser(VibrationSystem.User user, Level level) {
        this.user = user;
        this.level = level;
    }

    public static VibrationSystem.User wrap(Level level, BlockPos pos, VibrationSystem.User user) {
        return VSGameUtilsKt.getShipManagingPos(level, pos) == null ? user : new ShipVibrationUser(user, level);
    }

    @Override
    public PositionSource getPositionSource() {
        PositionSource source = user.getPositionSource();
        Optional<Vec3> pos = source.getPosition(level);
        if (pos.isEmpty() || VSGameUtilsKt.getShipManagingPos(level, pos.get()) == null) {
            return source;
        }
        return new BlockPositionSource(BlockPos.containing(VSGameUtilsKt.toWorldCoordinates(level, pos.get())));
    }

    @Override public int getListenerRadius() { return user.getListenerRadius(); }
    @Override public boolean canReceiveVibration(ServerLevel level, BlockPos pos, GameEvent event, GameEvent.Context context) {
        return user.canReceiveVibration(level, pos, event, context);
    }
    @Override public void onReceiveVibration(ServerLevel level, BlockPos pos, GameEvent event,
            @Nullable Entity entity, @Nullable Entity projectileOwner, float distance) {
        user.onReceiveVibration(level, pos, event, entity, projectileOwner, distance);
    }
    @Override public TagKey<GameEvent> getListenableEvents() { return user.getListenableEvents(); }
    @Override public boolean canTriggerAvoidVibration() { return user.canTriggerAvoidVibration(); }
    @Override public boolean requiresAdjacentChunksToBeTicking() { return user.requiresAdjacentChunksToBeTicking(); }
    @Override public int calculateTravelTimeInTicks(float distance) { return user.calculateTravelTimeInTicks(distance); }
    @Override public boolean isValidVibration(GameEvent event, GameEvent.Context context) {
        return user.isValidVibration(event, context);
    }
    @Override public void onDataChanged() { user.onDataChanged(); }
}

