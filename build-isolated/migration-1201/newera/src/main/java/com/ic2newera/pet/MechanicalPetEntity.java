package com.ic2newera.pet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import java.util.EnumSet;
import java.util.UUID;

/** Durable, server-authoritative companion with nonlethal cat/dog play behavior. */
public final class MechanicalPetEntity extends net.minecraft.world.entity.TamableAnimal {
    private static final EntityDataAccessor<Boolean> CAT = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> PEACEFUL = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> PANCAKE = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> ACTION = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ACTION_TICKS = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HUNGER = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.INT);
    public static final int MAX_HUNGER = 20;
    private static final int HUNGER_DRAIN_INTERVAL = 2400;
    private static final int STARVATION_DAMAGE_INTERVAL = 200;
    private static final int PLAYER_GRACE_TICKS = 60;
    private static final TagKey<Item> SHOVELS = TagKey.create(Registries.ITEM, new ResourceLocation("forge", "tools/shovels"));
    private UUID panelViewer;
    private long interactionGraceUntil;
    private UUID playPartner;
    private int playPhaseTicks;
    private int playPhase;
    private boolean leadAttacks = true;
    private int jumpCooldown;
    private int hungerElapsedTicks;
    private int starvationTicks;

    public MechanicalPetEntity(EntityType<? extends MechanicalPetEntity> type, Level level, boolean cat) {
        super(type, level);
        entityData.set(CAT, cat);
        setPersistenceRequired();
    }
    public static AttributeSupplier.Builder attributes() {
        return Animal.createMobAttributes().add(Attributes.MAX_HEALTH, 12).add(Attributes.MOVEMENT_SPEED, 0.25).add(Attributes.FOLLOW_RANGE, 16);
    }
    public boolean isCat() { return entityData.get(CAT); }
    public boolean isFollowing() { return !isOrderedToSit(); }
    public boolean isPeaceful() { return entityData.get(PEACEFUL); }
    public boolean isPancake() { return entityData.get(PANCAKE); }
    public int getHunger() { return entityData.get(HUNGER); }
    public PetAction getPetAction() { int id = entityData.get(ACTION); return id >= 0 && id < PetAction.values().length ? PetAction.values()[id] : PetAction.IDLE; }
    public int getActionTicks() { return entityData.get(ACTION_TICKS); }
    public Component getPetActionName() { return Component.translatable(getPetAction().translationKey()); }
    public int getActionDuration() { return duration(getPetAction()); }
    public float getActionProgress(float partialTick) {
        int duration = getActionDuration();
        return duration <= 0 ? 0 : clamp(1F - (getActionTicks() - partialTick) / duration);
    }
    /** One means fully flattened. During restoration this value eases back toward zero. */
    public float getFlattenProgress(float partialTick) {
        PetAction action = getPetAction();
        if (action == PetAction.FLATTEN) return clamp((20F - getActionTicks() + partialTick) / 20F);
        if (action == PetAction.RESTORE) return 1F - clamp((40F - getActionTicks() + partialTick) / 40F);
        return isPancake() ? 1F : 0F;
    }
    private static float clamp(float value) { return Math.max(0F, Math.min(1F, value)); }
    private static int duration(PetAction action) {
        return switch (action) {
            case PET -> 50; case JUMP -> 16; case NOD, SHAKE -> 24; case STANDOFF -> 30;
            case POUNCE -> 18; case SWIPE -> 12; case RETREAT -> 20; case FLATTEN -> 20; case RESTORE -> 40;
            case BARK -> PetSounds.DURATION_TICKS; case DANCE -> 100; case TILT -> 40; case PAW -> 50; case STRETCH -> 70; case ROLL -> 60; case BOW -> 50; case YAWN -> 70; case EAT -> 30; default -> 0;
        };
    }
    @Override protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(CAT, true); entityData.define(PEACEFUL, false); entityData.define(PANCAKE, false);
        entityData.define(ACTION, PetAction.IDLE.ordinal()); entityData.define(ACTION_TICKS, 0);
        entityData.define(HUNGER, MAX_HUNGER);
    }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == ACTION || key == ACTION_TICKS || key == PANCAKE) refreshDimensions();
    }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new SitWhenOrderedToGoal(this) {
            @Override public boolean canUse() { return getPetAction() != PetAction.JUMP && super.canUse(); }
            @Override public boolean canContinueToUse() { return getPetAction() != PetAction.JUMP && super.canContinueToUse(); }
        });
        goalSelector.addGoal(2, new SafeFollowOwnerGoal());
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6F) {
            @Override public boolean canUse() { return !busy() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !busy() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(7, new RandomLookAroundGoal(this) {
            @Override public boolean canUse() { return !busy() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !busy() && super.canContinueToUse(); }
        });
    }
    private boolean busy() {
        PetAction action = getPetAction();
        return panelViewer != null || isPancake() || action.isInteraction() || action.isPlayAction()
                || action == PetAction.FLATTEN || action == PetAction.RESTORE;
    }
    private final class SafeFollowOwnerGoal extends Goal {
        private Player owner;
        SafeFollowOwnerGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            if (!isFollowing() || busy() || getOwnerUUID() == null) return false;
            owner = level() instanceof ServerLevel server ? server.getServer().getPlayerList().getPlayer(getOwnerUUID()) : null;
            return owner != null && owner.level() == level() && distanceToSqr(owner) >= 9 && distanceToSqr(owner) <= 18 * 18;
        }
        @Override public boolean canContinueToUse() {
            return owner != null && owner.isAlive() && isFollowing() && !busy() && distanceToSqr(owner) > 4 && distanceToSqr(owner) <= 18 * 18;
        }
        @Override public void tick() {
            if (owner == null) return;
            getLookControl().setLookAt(owner, 10F, getMaxHeadXRot());
            if (tickCount % 10 == 0) getNavigation().moveTo(owner, 1.05);
        }
        @Override public void stop() { owner = null; getNavigation().stop(); }
    }
    @Override public EntityDimensions getDimensions(Pose pose) {
        EntityDimensions base = super.getDimensions(pose);
        float flatten = getFlattenProgress(0F);
        if (flatten <= 0F) return base;
        return EntityDimensions.scalable(base.width, Math.max(0.12F, base.height * (1F - 0.78F * flatten)));
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) return;
        tickHunger();
        if (jumpCooldown > 0) jumpCooldown--;
        if (panelViewer != null && (level() instanceof ServerLevel server ? server.getServer().getPlayerList().getPlayer(panelViewer) : null) == null)
            setPanelViewer(panelViewer, false);
        if (getActionTicks() > 0) {
            entityData.set(ACTION_TICKS, getActionTicks() - 1);
            if (getActionTicks() <= 1) {
                if (getPetAction() == PetAction.FLATTEN) { entityData.set(PANCAKE, true); setAction(PetAction.IDLE, 0); }
                else if (getPetAction() == PetAction.RESTORE) { entityData.set(PANCAKE, false); setAction(PetAction.IDLE, 0); }
                else setAction(PetAction.IDLE, 0);
            }
            if (getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE) refreshDimensions();
        }
        if (panelViewer != null || isPancake() || getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE) getNavigation().stop();
        tickPlay();
    }
    private void tickHunger() {
        if (isPancake()) return;
        if (getHunger() > 0) {
            starvationTicks = 0;
            if (++hungerElapsedTicks >= HUNGER_DRAIN_INTERVAL) {
                hungerElapsedTicks = 0;
                entityData.set(HUNGER, getHunger() - 1);
                if (getHunger() == 0) starvationTicks = 0;
            }
        } else {
            hungerElapsedTicks = 0;
            if (++starvationTicks >= STARVATION_DAMAGE_INTERVAL) {
                starvationTicks = 0;
                hurt(level().damageSources().starve(), 1F);
            }
        }
    }
    private void setHunger(int hunger) {
        entityData.set(HUNGER, Math.max(0, Math.min(MAX_HUNGER, hunger)));
        if (getHunger() > 0) starvationTicks = 0;
    }
    private MechanicalPetEntity findPartner(UUID id) {
        return level() instanceof ServerLevel server && server.getEntity(id) instanceof MechanicalPetEntity other ? other : null;
    }
    private void tickPlay() {
        if (panelViewer != null || isOrderedToSit() || isPeaceful() || isPancake() || level().getGameTime() < interactionGraceUntil
                || getPetAction().isInteraction() || getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE) {
            if (playPartner != null) interruptPlay(0);
            return;
        }
        if (playPartner != null) {
            MechanicalPetEntity other = findPartner(playPartner);
            if (other == null || !other.isAlive() || other.isPeaceful() || other.isPancake() || other.isCat() == isCat()
                    || distanceToSqr(other) > 16 || other.playPartner == null || !other.playPartner.equals(getUUID())) {
                interruptPlay(0); return;
            }
            if (other.panelViewer != null || other.isOrderedToSit() || level().getGameTime() < other.interactionGraceUntil
                    || other.getPetAction().isInteraction() || other.getPetAction() == PetAction.FLATTEN || other.getPetAction() == PetAction.RESTORE
                    || !hasLineOfSight(other) || !other.hasLineOfSight(this)) { interruptPlay(0); return; }
            getLookControl().setLookAt(other, 30F, 30F);
            if (getUUID().compareTo(other.getUUID()) < 0) {
                if (distanceToSqr(other) > 2.25) getNavigation().moveTo(other, 0.8); else getNavigation().stop();
                if (--playPhaseTicks <= 0) advancePlay(other);
            }
            return;
        }
        if (tickCount % 10 != 0) return;
        AABB bounds = getBoundingBox().inflate(4.0);
        MechanicalPetEntity candidate = level().getEntitiesOfClass(MechanicalPetEntity.class, bounds,
                other -> other != this && other.isAlive() && other.isCat() != isCat() && !other.isPeaceful() && !other.isPancake()
                        && other.panelViewer == null && other.playPartner == null && !other.isOrderedToSit()
                        && !other.getPetAction().isInteraction() && other.getPetAction() != PetAction.FLATTEN && other.getPetAction() != PetAction.RESTORE
                        && level().getGameTime() >= other.interactionGraceUntil && hasLineOfSight(other) && other.hasLineOfSight(this)
                       )
                .stream().filter(other -> getUUID().compareTo(other.getUUID()) < 0)
                .min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
        if (candidate == null || distanceToSqr(candidate) > 16) return;
        if (!faces(candidate) || !candidate.faces(this)) return;
        playPartner = candidate.getUUID(); candidate.playPartner = getUUID();
        startPlayPair(candidate);
    }
    private boolean faces(MechanicalPetEntity other) {
        Vec3 toward = other.position().subtract(position()).normalize();
        return getLookAngle().normalize().dot(toward) > 0.15;
    }
    private void startPlayPair(MechanicalPetEntity other) {
        playPhaseTicks = other.playPhaseTicks = 30;
        playPhase = other.playPhase = 1;
        leadAttacks = true;
        setAction(PetAction.STANDOFF, 30); other.setAction(PetAction.STANDOFF, 30);
    }
    private boolean safeFlatGround() {
        if (!onGround() || !safeStep(Vec3.ZERO)) return false;
        net.minecraft.core.BlockPos center = blockPosition().below();
        if (!level().getFluidState(blockPosition()).isEmpty()) return false;
        for (net.minecraft.core.Direction direction : new net.minecraft.core.Direction[]{
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST}) {
            net.minecraft.core.BlockPos neighbor = center.relative(direction);
            if (!level().hasChunkAt(neighbor) || !level().getBlockState(neighbor).isFaceSturdy(level(), neighbor, net.minecraft.core.Direction.UP)
                    || !level().getFluidState(neighbor.above()).isEmpty()) return false;
        }
        return true;
    }
    private boolean safeStep(Vec3 motion) {
        Vec3 next = position().add(motion);
        net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(next.x, getY(), next.z);
        net.minecraft.core.BlockPos floor = feet.below();
        Vec3 horizontal = new Vec3(motion.x, 0, motion.z);
        Vec3 future = horizontal.lengthSqr() > 1.0E-6 ? next.add(horizontal.normalize()) : next;
        net.minecraft.core.BlockPos futureFeet = net.minecraft.core.BlockPos.containing(future.x, getY(), future.z);
        net.minecraft.core.BlockPos futureFloor = futureFeet.below();
        return level().getWorldBorder().isWithinBounds(feet) && level().hasChunkAt(feet) && level().hasChunkAt(floor)
                && level().getFluidState(feet).isEmpty() && !level().getBlockState(feet).is(Blocks.LAVA)
                && level().getBlockState(feet).getCollisionShape(level(), feet).isEmpty()
                && level().getBlockState(floor).isFaceSturdy(level(), floor, net.minecraft.core.Direction.UP)
                && level().getWorldBorder().isWithinBounds(futureFeet) && level().hasChunkAt(futureFeet) && level().hasChunkAt(futureFloor)
                && level().getFluidState(futureFeet).isEmpty() && !level().getBlockState(futureFeet).is(Blocks.LAVA)
                && level().getBlockState(futureFeet).getCollisionShape(level(), futureFeet).isEmpty()
                && level().getBlockState(futureFloor).isFaceSturdy(level(), futureFloor, net.minecraft.core.Direction.UP)
                && level().noCollision(this, getBoundingBox().move(motion))
                && Math.abs(next.x - getX()) < 1.5 && Math.abs(next.z - getZ()) < 1.5;
    }
    private void advancePlay(MechanicalPetEntity other) {
        PetAction action = switch (playPhase) { case 1 -> PetAction.POUNCE; case 2 -> PetAction.SWIPE; case 3 -> PetAction.RETREAT; default -> PetAction.STANDOFF; };
        int nextPhase = playPhase == 3 ? 0 : (playPhase == 0 ? 1 : playPhase + 1);
        playPhase = other.playPhase = nextPhase;
        boolean lead = leadAttacks;
        if (action == PetAction.POUNCE) {
            MechanicalPetEntity attacker = lead ? this : other, retreating = lead ? other : this;
            Vec3 toward = retreating.position().subtract(attacker.position()).normalize().scale(0.32).add(0, 0.18, 0);
            if (attacker.safeStep(new Vec3(toward.x, 0, toward.z))) {
                attacker.setDeltaMovement(toward);
                attacker.hasImpulse = true;
            }
            attacker.setAction(PetAction.POUNCE, 18); retreating.setAction(PetAction.RETREAT, 20);
            playPhaseTicks = other.playPhaseTicks = 20;
        } else if (action == PetAction.SWIPE) {
            MechanicalPetEntity attacker = lead ? this : other, retreating = lead ? other : this;
            Vec3 pushMotion = retreating.position().subtract(attacker.position()).normalize().scale(0.25);
            if (attacker.distanceToSqr(retreating) <= 5 && retreating.safeStep(pushMotion)) {
                retreating.setDeltaMovement(pushMotion.x, Math.max(0.08, retreating.getDeltaMovement().y), pushMotion.z);
                retreating.hasImpulse = true;
            }
            attacker.setAction(PetAction.SWIPE, 12); retreating.setAction(PetAction.RETREAT, 20);
            playPhaseTicks = other.playPhaseTicks = 20;
        } else if (action == PetAction.RETREAT) {
            setAction(PetAction.RETREAT, 20); other.setAction(PetAction.RETREAT, 20);
            Vec3 away = position().subtract(other.position()).normalize().scale(0.2);
            Vec3 otherAway = other.position().subtract(position()).normalize().scale(0.2);
            if (safeStep(away)) { setDeltaMovement(away.x, 0.1, away.z); hasImpulse = true; }
            if (other.safeStep(otherAway)) { other.setDeltaMovement(otherAway.x, 0.1, otherAway.z); other.hasImpulse = true; }
            playPhaseTicks = other.playPhaseTicks = 20;
        } else {
            leadAttacks = !leadAttacks;
            setAction(PetAction.STANDOFF, 30); other.setAction(PetAction.STANDOFF, 30); playPhaseTicks = other.playPhaseTicks = 30;
        }
    }
    private void setAction(PetAction action, int ticks) {
        if (getPetAction() == PetAction.BARK && action != PetAction.BARK) PetSounds.stop(this);
        entityData.set(ACTION, action.ordinal()); entityData.set(ACTION_TICKS, ticks);
    }
    public void setPanelViewer(UUID viewer, boolean open) {
        if (level().isClientSide) return;
        if (open) { panelViewer = viewer; interruptPlay(PLAYER_GRACE_TICKS); }
        else if (viewer != null && viewer.equals(panelViewer)) panelViewer = null;
    }
    private void interruptPlay(int grace) {
        if (grace > 0) interactionGraceUntil = level().getGameTime() + grace;
        MechanicalPetEntity other = playPartner == null ? null : findPartner(playPartner);
        if (other != null && other.playPartner != null && other.playPartner.equals(getUUID())) {
            other.playPartner = null; other.playPhaseTicks = 0; other.playPhase = 0;
            if (grace > 0) other.interactionGraceUntil = interactionGraceUntil;
            other.setAction(PetAction.IDLE, 0); other.getNavigation().stop();
        }
        playPartner = null; playPhaseTicks = 0; playPhase = 0; getNavigation().stop();
        if (getPetAction() == PetAction.STANDOFF || getPetAction() == PetAction.POUNCE || getPetAction() == PetAction.SWIPE || getPetAction() == PetAction.RETREAT)
            setAction(PetAction.IDLE, 0);
    }
    public void command(Player player, int id) {
        if (level().isClientSide || !isOwnedBy(player) || distanceToSqr(player) > 64) return;
        if (id < 0 || id > 13 || (isPancake() && id != 0 && id != 5)) return;
        PetAction current = getPetAction();
        if (current == PetAction.FLATTEN || current == PetAction.RESTORE) return;
        if (id == 0) { interruptPlay(PLAYER_GRACE_TICKS); setOrderedToSit(isFollowing()); return; }
        if (id == 5) { interruptPlay(PLAYER_GRACE_TICKS); entityData.set(PEACEFUL, !isPeaceful()); return; }
        if (id == 6) {
            if (isCat()) return;
            if (current == PetAction.BARK) { setAction(PetAction.IDLE, 0); return; }
            if (current.isInteraction()) return;
            interruptPlay(PLAYER_GRACE_TICKS);
            if (PetSounds.play(this)) setAction(PetAction.BARK, PetSounds.DURATION_TICKS);
            return;
        }
        if (current.isInteraction()) return;
        if (id == 7) { interruptPlay(PLAYER_GRACE_TICKS); setAction(PetAction.DANCE, 100); return; }
        PetAction gesture = switch (id) {
            case 8 -> PetAction.TILT; case 9 -> PetAction.PAW; case 10 -> PetAction.STRETCH;
            case 11 -> PetAction.ROLL; case 12 -> PetAction.BOW; case 13 -> PetAction.YAWN; default -> null;
        };
        if (gesture != null) {
            if (gesture == PetAction.ROLL && !safeFlatGround()) return;
            interruptPlay(PLAYER_GRACE_TICKS); setAction(gesture, duration(gesture)); return;
        }
        if (id == 2 && (!onGround() || jumpCooldown > 0)) return;
        interruptPlay(PLAYER_GRACE_TICKS);
        switch (id) {
            case 1 -> setAction(PetAction.PET, duration(PetAction.PET));
            case 2 -> {
                jumpCooldown = 30;
                setAction(PetAction.JUMP, duration(PetAction.JUMP));
                setInSittingPose(false);
                setDeltaMovement(getDeltaMovement().x, 0.38, getDeltaMovement().z);
                hasImpulse = true;
            }
            case 3 -> setAction(PetAction.NOD, duration(PetAction.NOD));
            case 4 -> setAction(PetAction.SHAKE, duration(PetAction.SHAKE));
        }
    }
    /** Call only from the Create fan compatibility bridge after a real fan restoration interaction. */
    public void beginRestore() {
        if (level().isClientSide || !isCat() || !isPancake() || getPetAction() == PetAction.RESTORE) return;
        AABB standing = new AABB(getX() - 0.3, getY(), getZ() - 0.3, getX() + 0.3, getY() + 0.7, getZ() + 0.3);
        if (!level().getWorldBorder().isWithinBounds(standing) || !level().noCollision(this, standing)) return;
        setAction(PetAction.RESTORE, 40);
        getNavigation().stop();
    }
    /** Called by the mechanical press integration when this cat is flattened. */
    public void flatten() {
        if (!level().isClientSide && isCat() && !isPancake() && getPetAction() != PetAction.FLATTEN) {
            PetSounds.stop(this);
            interruptPlay(PLAYER_GRACE_TICKS); setAction(PetAction.FLATTEN, 20); getNavigation().stop();
        }
    }
    @Override public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isOwnedBy(player)) return InteractionResult.PASS;
        InteractionResult feeding = tryFeed(player, hand);
        if (feeding != InteractionResult.PASS) return feeding;
        if (isCat() && isPancake() && (player.getItemInHand(hand).is(SHOVELS)
                || player.getItemInHand(hand).getItem() instanceof ShovelItem)) {
            return level().isClientSide ? InteractionResult.SUCCESS : PetCreateCompat.pickUp(this, player, hand);
        }
        if (!isPancake()) interruptPlay(PLAYER_GRACE_TICKS);
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, new net.minecraft.world.SimpleMenuProvider(
                    (id, inventory, owner) -> new PetMenu(id, inventory, this, owner),
                    Component.translatable(isCat() ? "pet.ic2newera.mechanical_cat" : "pet.ic2newera.mechanical_dog")),
                    buffer -> buffer.writeVarInt(getId()));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    private InteractionResult tryFeed(Player player, InteractionHand hand) {
        if (isPancake() || getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(PetRegistry.PANCAKE_ITEM.get())) return InteractionResult.PASS;
        int food = foodValue(stack);
        if (food <= 0) return InteractionResult.PASS;
        if (getPetAction() == PetAction.BARK) {
            if (!level().isClientSide) player.displayClientMessage(Component.translatable("pet.ic2newera.stop_sound_before_feeding"), true);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (getHunger() >= MAX_HUNGER) {
            if (!level().isClientSide) player.displayClientMessage(Component.translatable("pet.ic2newera.hunger.full"), true);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (!player.getAbilities().instabuild) stack.shrink(1);
        interruptPlay(PLAYER_GRACE_TICKS);
        setHunger(getHunger() + food);
        hungerElapsedTicks = 0;
        setAction(PetAction.EAT, duration(PetAction.EAT));
        if (level() instanceof ServerLevel server) server.sendParticles(ParticleTypes.HEART, getX(), getY() + getBbHeight(), getZ(), 3, 0.25, 0.2, 0.25, 0);
        return InteractionResult.CONSUME;
    }
    private int foodValue(ItemStack stack) {
        if (stack.is(PetRegistry.PANCAKE_ITEM.get())) return 0;
        ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!isCat() && key.equals(PetFeeding.ORDINARY_CAT_PANCAKE)) return 8;
        if (isCat()) return stack.is(ItemTags.FISHES) ? 4 : 0;
        net.minecraft.world.food.FoodProperties food = stack.getItem().getFoodProperties();
        return food != null && food.isMeat() ? 4 : 0;
    }
    /** Called by placement-item code after spawning; owner UUID is saved by TamableAnimal's normal NBT. */
    public void bindTo(Player owner) { if (!level().isClientSide) tame(owner); }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean damaged = super.hurt(source, amount);
        if (damaged && !level().isClientSide && getPetAction() == PetAction.BARK) setAction(PetAction.IDLE, 0);
        return damaged;
    }
    @Override public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (!level().isClientSide) PetSounds.stop(this);
        super.remove(reason);
    }
    @Override public boolean isFood(net.minecraft.world.item.ItemStack stack) { return false; }
    @Override public boolean canMate(Animal other) { return false; }
    @Override public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob otherParent) { return null; }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("MechanicalCat", isCat()); tag.putBoolean("MechanicalPetPeaceful", isPeaceful());
        tag.putBoolean("MechanicalPetFollowing", isFollowing()); tag.putBoolean("MechanicalPetPancake", isPancake());
        tag.putInt("MechanicalPetAction", getPetAction().ordinal()); tag.putInt("MechanicalPetActionTicks", getActionTicks());
        tag.putInt("MechanicalPetHunger", getHunger()); tag.putInt("MechanicalPetHungerElapsed", hungerElapsedTicks);
        tag.putInt("MechanicalPetStarvationTicks", starvationTicks);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(CAT, tag.getBoolean("MechanicalCat")); entityData.set(PEACEFUL, tag.getBoolean("MechanicalPetPeaceful"));
        setOrderedToSit(!tag.getBoolean("MechanicalPetFollowing")); entityData.set(PANCAKE, tag.getBoolean("MechanicalPetPancake"));
        setHunger(tag.contains("MechanicalPetHunger") ? tag.getInt("MechanicalPetHunger") : MAX_HUNGER);
        hungerElapsedTicks = Math.max(0, Math.min(HUNGER_DRAIN_INTERVAL - 1, tag.getInt("MechanicalPetHungerElapsed")));
        starvationTicks = getHunger() == 0 ? Math.max(0, Math.min(STARVATION_DAMAGE_INTERVAL - 1, tag.getInt("MechanicalPetStarvationTicks"))) : 0;
        int ordinal = tag.getInt("MechanicalPetAction");
        PetAction action = ordinal >= 0 && ordinal < PetAction.values().length ? PetAction.values()[ordinal] : PetAction.IDLE;
        if (action == PetAction.POUNCE || action == PetAction.SWIPE || action == PetAction.STANDOFF || action == PetAction.RETREAT || action == PetAction.BARK || action == PetAction.DANCE) action = PetAction.IDLE;
        int ticks = Math.max(0, Math.min(duration(action), tag.getInt("MechanicalPetActionTicks")));
        setAction(action, ticks); refreshDimensions(); playPartner = null; playPhaseTicks = 0; playPhase = 0;
    }
}





















