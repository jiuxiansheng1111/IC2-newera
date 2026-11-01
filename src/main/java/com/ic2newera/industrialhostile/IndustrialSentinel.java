package com.ic2newera.industrialhostile;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** Retired entity ID; no encounter controller, targeting, attacks or rewards. */
public final class IndustrialSentinel extends EncounterHostile {
    public static final int WINDUP_TICKS = 16, RECOVERY_TICKS = 36;
    public IndustrialSentinel(EntityType<? extends IndustrialSentinel> type, Level level) { super(type, level); }
    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 240).add(Attributes.ARMOR, 14)
                .add(Attributes.MOVEMENT_SPEED, 0);
    }
}
