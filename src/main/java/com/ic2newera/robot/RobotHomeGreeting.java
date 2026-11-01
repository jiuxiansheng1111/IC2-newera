package com.ic2newera.robot;

import com.ic2newera.media.MediaServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;

/** Arrival and departure speech for the stationary work area; never loads chunks. */
public final class RobotHomeGreeting {
    public static final String JAPANESE = "おかえりなさい、ご主人様。";
    public static final String FAREWELL_JAPANESE = "ご主人様、いってらっしゃい。";
    private static final long AWAY_TICKS = 200;
    private UUID ownerId;
    private BlockPos home = BlockPos.ZERO;
    private String dimension = "";
    private long awaySince = -1, lastCheck = -1;
    private boolean armed;
    private long homeSince = -1, lastSeen = -1, lastFarewell = -200;
    private double previousDistance = Double.NaN;
    private boolean farewellArmed;

    public void reset() {
        ownerId = null;
        dimension = "";
        awaySince = lastCheck = -1;
        armed = false;
        homeSince = lastSeen = -1;
        lastFarewell = -200;
        previousDistance = Double.NaN;
        farewellArmed = false;
    }

    public static boolean atHome(RobotEntity robot) {
        return (robot.mode() == RobotEntity.STAY || robot.mode() == RobotEntity.COOKING
                || robot.mode() == RobotEntity.SMELTING || robot.mode() == RobotEntity.FARMING)
                && robot.collectionTask() == null
                && robot.blockPosition().distSqr(robot.workOrigin()) <= 64;
    }

    public void tick(RobotEntity robot) {
        if (!(robot.level instanceof ServerLevel level)) return;
        if (!atHome(robot) || robot.getOwnerUUID() == null) { reset(); return; }
        long now = level.getGameTime();
        String currentDimension = level.dimension().location().toString();
        if (!robot.getOwnerUUID().equals(ownerId) || !home.equals(robot.workOrigin())
                || !dimension.equals(currentDimension) || lastCheck > now) {
            reset();
            ownerId = robot.getOwnerUUID();
            home = robot.workOrigin().immutable();
            dimension = currentDimension;
        }
        // A distant home can unload before the next observation. Persisting the last tick
        // lets a later return arm the greeting without keeping the home chunk loaded.
        if (lastCheck >= 0 && now - lastCheck > AWAY_TICKS) {
            armed = true;
            farewellArmed = false;
            homeSince = lastSeen = -1;
            previousDistance = Double.NaN;
        }
        lastCheck = now;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
        departure(robot, owner, now);
        boolean away = owner == null || owner.level != level
                || owner.distanceToSqr(Vec3.atCenterOf(home)) > 24 * 24;
        if (away) {
            if (awaySince < 0) awaySince = now;
            if (now - awaySince >= AWAY_TICKS) armed = true;
            return;
        }
        if (awaySince >= 0 && now - awaySince >= AWAY_TICKS) armed = true;
        awaySince = -1;
        if (!armed || !owner.isAlive() || owner.isSpectator() || !robot.powered()
                || robot.getTarget() != null || robot.distanceToSqr(owner) > 64
                || !robot.hasLineOfSight(owner)) return;
        if (!speak(robot, owner, false)) return;
        armed = false;
    }

    private void departure(RobotEntity robot, ServerPlayer owner, long now) {
        if (owner == null || owner.level != robot.level || !owner.isAlive() || owner.isSpectator()) {
            homeSince = lastSeen = -1;
            previousDistance = Double.NaN;
            farewellArmed = false;
            return;
        }
        double distance = owner.distanceToSqr(Vec3.atCenterOf(home));
        boolean seenAtHome = distance <= 64 && robot.distanceToSqr(owner) <= 64 && robot.hasLineOfSight(owner);
        if (seenAtHome) {
            lastSeen = now;
            if (homeSince < 0) homeSince = now;
            if (now - homeSince >= 100 && now - lastFarewell >= 200) farewellArmed = true;
        } else homeSince = -1;
        // Consume a departure even if power/combat prevents speech: never greet a returning
        // player with "have a good trip". A briefly closed front door may hide a seen owner.
        if (farewellArmed && previousDistance < 144 && distance >= 144) {
            farewellArmed = false;
            if (distance <= 1024 && lastSeen >= 0 && now - lastSeen <= 200
                    && robot.powered() && robot.getTarget() == null && speak(robot, owner, true))
                lastFarewell = now;
        }
        previousDistance = distance;
    }

    private boolean speak(RobotEntity robot, ServerPlayer owner, boolean departure) {
        UUID reply = MediaServer.authorizeHomeSpeech(owner, robot, departure);
        if (reply == null) return false;
        robot.getLookControl().setLookAt(owner, 30, 30);
        RobotPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> owner),
                new RobotPackets.HomeGreeting(robot.getUUID(), robot.character().id, reply, departure));
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (ownerId != null) tag.putUUID("Owner", ownerId);
        tag.putLong("Home", home.asLong());
        tag.putString("Dimension", dimension);
        tag.putLong("AwaySince", awaySince);
        tag.putLong("LastCheck", lastCheck);
        tag.putBoolean("Armed", armed);
        tag.putLong("HomeSince", homeSince);
        tag.putLong("LastSeen", lastSeen);
        tag.putLong("LastFarewell", lastFarewell);
        if (Double.isFinite(previousDistance)) tag.putDouble("PreviousDistance", previousDistance);
        tag.putBoolean("FarewellArmed", farewellArmed);
        return tag;
    }

    public void load(CompoundTag tag) {
        reset();
        if (!tag.hasUUID("Owner")) return;
        ownerId = tag.getUUID("Owner");
        home = BlockPos.of(tag.getLong("Home"));
        dimension = tag.getString("Dimension");
        awaySince = tag.contains("AwaySince") ? tag.getLong("AwaySince") : -1;
        lastCheck = tag.contains("LastCheck") ? tag.getLong("LastCheck") : -1;
        armed = tag.getBoolean("Armed");
        homeSince = tag.contains("HomeSince") ? tag.getLong("HomeSince") : -1;
        lastSeen = tag.contains("LastSeen") ? tag.getLong("LastSeen") : -1;
        lastFarewell = tag.contains("LastFarewell") ? tag.getLong("LastFarewell") : -200;
        previousDistance = tag.contains("PreviousDistance") ? tag.getDouble("PreviousDistance") : Double.NaN;
        farewellArmed = tag.getBoolean("FarewellArmed");
    }
}
