package com.ic2newera.industrialhostile;

import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

/** Inert compatibility shell for entities saved before the NPC/adventure rollback. */
public abstract class EncounterHostile extends PathfinderMob {
    public static final int IDLE = 0, WINDUP = 1, RECOVERY = 2;

    protected EncounterHostile(EntityType<? extends EncounterHostile> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        setNoAi(true);
        xpReward = 0;
    }
    @Override protected void registerGoals() { }
    public final int combatPhase() { return IDLE; }
    public final int phaseTicks() { return 0; }
    @Override public boolean canAttack(LivingEntity target) { return false; }
    @Override public boolean doHurtTarget(Entity target) { return false; }
    @Override public void setTarget(LivingEntity target) { super.setTarget(null); }
    @Override protected boolean shouldDropLoot() { return false; }
    @Override public void tick() {
        // Old NBT may contain NoAI=false. Enforce retirement before any AI tick.
        if (!level().isClientSide) setNoAi(true);
        super.tick();
    }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
}
