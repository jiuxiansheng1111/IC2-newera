package com.ic2newera.industrialhostile;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** Retired entity ID; no encounter controller, targeting, attacks or rewards. */
public final class PatrolDrone extends EncounterHostile {
    public static final int RECOVERY_TICKS = 45;
    public PatrolDrone(EntityType<? extends PatrolDrone> type, Level level) { super(type, level); setNoGravity(true); }
    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 80).add(Attributes.ARMOR, 8)
                .add(Attributes.MOVEMENT_SPEED, 0);
    }
}
