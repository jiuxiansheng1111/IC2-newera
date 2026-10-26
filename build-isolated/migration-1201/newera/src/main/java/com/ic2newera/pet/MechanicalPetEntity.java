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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Durable, server-authoritative companion with nonlethal cat/dog play behavior. */
public final class MechanicalPetEntity extends net.minecraft.world.entity.TamableAnimal implements MechanicalPet {
    private static final EntityDataAccessor<Boolean> CAT = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> PEACEFUL = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> PANCAKE = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> ACTION = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ACTION_TICKS = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HUNGER = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<ItemStack> PET_CHEST = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> PET_BATTERY = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> PET_ARMOR = SynchedEntityData.defineId(MechanicalPetEntity.class, EntityDataSerializers.ITEM_STACK);
    public static final int MAX_HUNGER = 100;
    private static final int HUNGER_DRAIN_THRESHOLD = 32400;
    private static final int STARVATION_DAMAGE_INTERVAL = 200;
    private static final int PLAYER_GRACE_TICKS = 60;
    private static final int POWER_UNIT = 5400;
    private static final int ACTIVE_EU_PER_TICK_NUMERATOR = 250;
    private static final int IDLE_EU_PER_TICK_NUMERATOR = 25;
    private static final int MIRROR_HISS_COOLDOWN = 1200;
    private static final UUID PET_ARMOR_MODIFIER = UUID.fromString("C6718CA0-96D5-4F84-9A11-2DA3B976BB6F");
    private static final ResourceLocation IC2_RE_BATTERY = new ResourceLocation("ic2", "re_battery");
    private static final ResourceLocation LAOWU_PANCAKE_SOUND = new ResourceLocation("laowu", "logistics_arrive");
    private static final ResourceLocation LAOWU_CATNIP = new ResourceLocation("laowu", "cat_catnip_pouch");
    private static final ResourceLocation MIRROR_BLOCK = new ResourceLocation("ic2newera", "floor_mirror");
    private static final TagKey<Item> SHOVELS = TagKey.create(Registries.ITEM, new ResourceLocation("forge", "tools/shovels"));
    private final Set<UUID> panelViewers = new HashSet<>();
    private long interactionGraceUntil;
    private UUID playPartner;
    private int playPhaseTicks;
    private int playPhase;
    private boolean leadAttacks = true;
    private int jumpCooldown;
    private int hungerElapsedTicks;
    private int starvationTicks;
    private final PetInventory petInventory = new PetInventory(this);
    private int energyRemainder;
    private int mirrorHissCooldown;
    private int mirrorSearchCooldown;
    private BlockPos mirrorTarget;
    private int enemyAttackCooldown;

    public MechanicalPetEntity(EntityType<? extends MechanicalPetEntity> type, Level level) {
        super(type, level);
        entityData.set(CAT, false);
        setPersistenceRequired();
    }
    public static AttributeSupplier.Builder attributes() {
        return Animal.createMobAttributes().add(Attributes.MAX_HEALTH, 40).add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 16).add(Attributes.ATTACK_DAMAGE, 3.0);
    }
    @Override public TamableAnimal asEntity() { return this; }
    @Override public boolean isCat() { return false; }
    @Override public boolean isNativeBusy() { return false; }
    public boolean isFollowing() { return !isOrderedToSit(); }
    public boolean isPeaceful() { return entityData.get(PEACEFUL); }
    public boolean isPancake() { return entityData.get(PANCAKE); }
    public int getHunger() { return entityData.get(HUNGER); }
    public PetInventory getPetInventory() { return petInventory; }
    public ItemStack getPetChestStack() { return entityData.get(PET_CHEST); }
    public ItemStack getPetBatteryStack() { return entityData.get(PET_BATTERY); }
    public boolean hasPetChest() { return getPetChestStack().is(net.minecraft.world.item.Items.CHEST); }
    public boolean hasCargo() {
        for (int slot = PetInventory.CARGO_START; slot < PetInventory.SIZE; slot++)
            if (!petInventory.getStackInSlot(slot).isEmpty()) return true;
        return false;
    }
    public ItemStack getPetArmorStack() { return entityData.get(PET_ARMOR); }
    public int getPetArmorProtection() { return getPetArmorStack().getItem() instanceof HorseArmorItem armor ? armor.getProtection() : 0; }
    public int getBatteryCharge() {
        ItemStack battery = level().isClientSide ? getPetBatteryStack() : petInventory.getStackInSlot(PetInventory.BATTERY_SLOT);
        return PetInventory.isRechargeableBattery(battery) && ic2.api.item.ElectricItem.manager != null
                ? (int) Math.floor(Math.max(0, ic2.api.item.ElectricItem.manager.getCharge(battery))) : 0;
    }
    public boolean isPowered() { return getBatteryCharge() > 0; }
    public void setPetBattery(ItemStack stack) {
        if (stack.isEmpty() || !PetInventory.isRechargeableBattery(stack)) {
            petInventory.setStackInSlot(PetInventory.BATTERY_SLOT, ItemStack.EMPTY);
            return;
        }
        petInventory.setStackInSlot(PetInventory.BATTERY_SLOT, stack.copyWithCount(1));
    }
    public void onPetInventoryChanged(int slot) {
        if (slot == PetInventory.BATTERY_SLOT && !petInventory.getStackInSlot(slot).isEmpty()
                && !PetInventory.isRechargeableBattery(petInventory.getStackInSlot(slot))) {
            petInventory.setStackInSlot(slot, ItemStack.EMPTY);
            return;
        }
        if (slot == PetInventory.ARMOR_SLOT) {
            ItemStack armor = petInventory.getStackInSlot(slot);
            if (!armor.isEmpty() && !PetInventory.isVanillaHorseArmor(armor)) {
                petInventory.setStackInSlot(slot, ItemStack.EMPTY);
                return;
            }
            entityData.set(PET_ARMOR, armor.copy());
            var attribute = getAttribute(Attributes.ARMOR);
            if (attribute != null) {
                attribute.removeModifier(PET_ARMOR_MODIFIER);
                int protection = armor.getItem() instanceof HorseArmorItem horseArmor ? horseArmor.getProtection() : 0;
                if (protection > 0) attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        PET_ARMOR_MODIFIER, "Mechanical pet horse armor", protection,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
            }
        }
        if (slot == PetInventory.CHEST_SLOT) entityData.set(PET_CHEST, petInventory.getStackInSlot(slot).copy());
        if (slot == PetInventory.BATTERY_SLOT) entityData.set(PET_BATTERY, petInventory.getStackInSlot(slot).copy());
    }
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
            case BARK -> PetSounds.DURATION_TICKS; case DANCE -> 100; case TILT -> 40; case PAW -> 50; case STRETCH -> 70; case ROLL -> 60; case BOW -> 50; case YAWN -> 70; case EAT -> 30; case HISS -> 40; default -> 0;
        };
    }
    @Override protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(CAT, false); entityData.define(PEACEFUL, false); entityData.define(PANCAKE, false);
        entityData.define(ACTION, PetAction.IDLE.ordinal()); entityData.define(ACTION_TICKS, 0);
        entityData.define(HUNGER, MAX_HUNGER);
        entityData.define(PET_CHEST, ItemStack.EMPTY);
        entityData.define(PET_BATTERY, ItemStack.EMPTY);
        entityData.define(PET_ARMOR, ItemStack.EMPTY);
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
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, true) {
            @Override public boolean canUse() { return canFight() && super.canUse(); }
            @Override public boolean canContinueToUse() { return canFight() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(3, new SafeFollowOwnerGoal());
        goalSelector.addGoal(4, new MirrorHissGoal());
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6F) {
            @Override public boolean canUse() { return !busy() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !busy() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(7, new RandomLookAroundGoal(this) {
            @Override public boolean canUse() { return !busy() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !busy() && super.canContinueToUse(); }
        });
        targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false,
                target -> validMonsterTarget(target)) {
            @Override public boolean canUse() { return canFight() && super.canUse(); }
            @Override public boolean canContinueToUse() { return canFight() && super.canContinueToUse(); }
        });
    }
    private boolean busy() {
        PetAction action = getPetAction();
        return hasPanelViewers() || isPancake() || isNativeBusy() || action.isInteraction() || action.isPlayAction()
                || action == PetAction.FLATTEN || action == PetAction.RESTORE;
    }
    private boolean canFight() {
        if (!isAlive() || !isPowered() || !isFollowing() || isPancake() || hasPanelViewers()
                || level().getGameTime() < interactionGraceUntil || getPetAction().isInteraction()
                || getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE) return false;
        Player owner = level() instanceof ServerLevel server && getOwnerUUID() != null
                ? server.getServer().getPlayerList().getPlayer(getOwnerUUID()) : null;
        return owner != null && owner.isAlive() && owner.level() == level() && distanceToSqr(owner) <= 18 * 18;
    }
    private boolean validMonsterTarget(LivingEntity target) {
        if (!(target instanceof net.minecraft.world.entity.monster.Enemy) || target == this || !target.isAlive()
                || target instanceof MechanicalPet || distanceToSqr(target) > 16 * 16
                || !hasLineOfSight(target) || getOwnerUUID() != null && getOwnerUUID().equals(target.getUUID())) return false;
        if (target instanceof net.minecraft.world.entity.NeutralMob neutral && !neutral.isAngryAt(this)) {
            Player owner = getOwnerUUID() == null ? null : level() instanceof ServerLevel server
                    ? server.getServer().getPlayerList().getPlayer(getOwnerUUID()) : null;
            return owner != null && neutral.isAngryAt(owner);
        }
        return true;
    }
    private final class SafeFollowOwnerGoal extends Goal {
        private Player owner;
        SafeFollowOwnerGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            if (!isFollowing() || !isPowered() || busy() || getOwnerUUID() == null) return false;
            owner = level() instanceof ServerLevel server ? server.getServer().getPlayerList().getPlayer(getOwnerUUID()) : null;
            return owner != null && owner.level() == level() && distanceToSqr(owner) >= 9 && distanceToSqr(owner) <= 32 * 32;
        }
        @Override public boolean canContinueToUse() {
            return owner != null && owner.isAlive() && isFollowing() && isPowered() && !busy() && distanceToSqr(owner) > 4 && distanceToSqr(owner) <= 32 * 32;
        }
        @Override public void tick() {
            if (owner == null) return;
            getLookControl().setLookAt(owner, 10F, getMaxHeadXRot());
            if (tickCount % 10 == 0) getNavigation().moveTo(owner, 1.05);
        }
        @Override public void stop() { owner = null; getNavigation().stop(); }
    }
    private final class MirrorHissGoal extends Goal {
        private BlockPos mirror;
        MirrorHissGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
        @Override public boolean canUse() {
            if (!isCat() || !isPowered() || !isFollowing() || hasPanelViewers() || isPancake()
                    || !isAlive() || getTarget() != null || getPetAction().isInteraction()
                    || level().getGameTime() < interactionGraceUntil || mirrorHissCooldown > 0) return false;
            if (mirrorSearchCooldown-- > 0) return false;
            mirrorSearchCooldown = 20;
            mirror = findMirror();
            return mirror != null;
        }
        @Override public boolean canContinueToUse() {
            return mirror != null && isCat() && isPowered() && isFollowing() && !hasPanelViewers()
                    && !isPancake() && getTarget() == null && !getPetAction().isInteraction()
                    && level().getGameTime() >= interactionGraceUntil && mirrorHissCooldown <= 0
                    && isMirror(mirror) && isMirrorFront(mirror) && hasMirrorSight(mirror)
                    && distanceToSqr(mirror.getX() + 0.5, mirror.getY(), mirror.getZ() + 0.5) <= 64;
        }
        @Override public void tick() {
            if (mirror == null) return;
            Direction facing = mirrorFacing(mirror);
            Vec3 center = Vec3.atCenterOf(mirror);
            Vec3 stand = center.add(facing.getStepX() * 1.25, 0, facing.getStepZ() * 1.25);
            getLookControl().setLookAt(center.x, center.y, center.z, 20F, 20F);
            if (distanceToSqr(stand.x, stand.y, stand.z) > 1.6) {
                getNavigation().moveTo(stand.x, stand.y, stand.z, 0.8);
                return;
            }
            getNavigation().stop();
            if (isMirrorFront(mirror) && hasMirrorSight(mirror)
                    && getViewVector(1F).dot(center.subtract(getEyePosition()).normalize()) > 0.68) {
                setAction(PetAction.HISS, 40);
                mirrorHissCooldown = MIRROR_HISS_COOLDOWN;
                playLaowuSound(new ResourceLocation("laowu", "hissing_intro"), net.minecraft.sounds.SoundEvents.CAT_HISS, 0.9F);
                mirror = null;
            }
        }
        @Override public void stop() { mirror = null; getNavigation().stop(); }
    }
    private BlockPos findMirror() {
        BlockPos origin = blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int x = -5; x <= 5; x++) for (int y = -1; y <= 2; y++) for (int z = -5; z <= 5; z++) {
            BlockPos candidate = origin.offset(x, y, z);
            if (!level().hasChunkAt(candidate) || !isMirror(candidate)) continue;
            if (level().getBlockState(candidate).hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && level().getBlockState(candidate).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) candidate = candidate.below();
            double distance = distanceToSqr(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
            if (distance <= 36 && distance < nearestDistance && isMirrorFront(candidate) && hasMirrorSight(candidate)) {
                nearest = candidate; nearestDistance = distance;
            }
        }
        return nearest;
    }
    private boolean isMirror(BlockPos pos) {
        BlockState state = level().getBlockState(pos);
        if (!MIRROR_BLOCK.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()))) return false;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
            return MIRROR_BLOCK.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level().getBlockState(pos.below()).getBlock()));
        return true;
    }
    private Direction mirrorFacing(BlockPos pos) {
        BlockState state = level().getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
            state = level().getBlockState(pos.below());
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
    }
    private boolean isMirrorFront(BlockPos pos) {
        Vec3 mirrorCenter = Vec3.atCenterOf(pos);
        Vec3 eyeFromMirror = getEyePosition().subtract(mirrorCenter);
        Direction facing = mirrorFacing(pos);
        return eyeFromMirror.x * facing.getStepX() + eyeFromMirror.z * facing.getStepZ() > 0.1;
    }
    private boolean hasMirrorSight(BlockPos pos) {
        Vec3 from = getEyePosition();
        Vec3 to = Vec3.atCenterOf(pos);
        net.minecraft.world.phys.BlockHitResult hit = level().clip(new net.minecraft.world.level.ClipContext(
                from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, this));
        BlockPos upper = pos.above();
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && (hit.getBlockPos().equals(pos) || hit.getBlockPos().equals(upper) && isMirror(upper));
    }
    private void playLaowuSound(ResourceLocation id, net.minecraft.sounds.SoundEvent fallback, float volume) {
        if (!(level() instanceof ServerLevel server)) return;
        net.minecraft.sounds.SoundEvent sound = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(fallback);
        server.playSound(null, getX(), getY(), getZ(), sound, net.minecraft.sounds.SoundSource.NEUTRAL, volume, 0.9F + random.nextFloat() * 0.2F);
    }
    @Override public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        if (isNativeBusy()) {
            if (!isAlive() || !isTame() || !isPowered() || isPancake() || hasPanelViewers()
                    || getPetAction().isInteraction() || getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE) return false;
            return super.doHurtTarget(target);
        }
        if (!(target instanceof LivingEntity living) || !validMonsterTarget(living)
                || !hasLineOfSight(living) || !canFight() || enemyAttackCooldown > 0) return false;
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            enemyAttackCooldown = 18;
            setAction(isCat() ? PetAction.SWIPE : PetAction.POUNCE, isCat() ? 12 : 18);
            if (isCat()) playLaowuSound(LAOWU_PANCAKE_SOUND, net.minecraft.sounds.SoundEvents.CAT_HISS, 0.8F);
            else playLaowuSound(new ResourceLocation("minecraft", "entity.wolf.growl"),
                    net.minecraft.sounds.SoundEvents.WOLF_GROWL, 0.8F);
        }
        return hit;
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
        tickPower();
        if (jumpCooldown > 0) jumpCooldown--;
        if (enemyAttackCooldown > 0) enemyAttackCooldown--;
        if (mirrorHissCooldown > 0) mirrorHissCooldown--;
        if (!panelViewers.isEmpty() && level() instanceof ServerLevel server) {
            panelViewers.removeIf(viewer -> server.getServer().getPlayerList().getPlayer(viewer) == null);
        }
        if (getTarget() != null && !isNativeBusy() && (!canFight() || !validMonsterTarget(getTarget()))) setTarget(null);
        if (getActionTicks() > 0) {
            entityData.set(ACTION_TICKS, getActionTicks() - 1);
            if (getActionTicks() <= 1) {
                if (getPetAction() == PetAction.FLATTEN) { entityData.set(PANCAKE, true); setAction(PetAction.IDLE, 0); }
                else if (getPetAction() == PetAction.RESTORE) { entityData.set(PANCAKE, false); setAction(PetAction.IDLE, 0); }
                else setAction(PetAction.IDLE, 0);
            }
            if (getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE) refreshDimensions();
        }
        if (hasPanelViewers() || isPancake() || !isPowered() || getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE) {
            getNavigation().stop();
            if (!isPowered()) {
                setTarget(null);
                if (getPetAction().isInteraction() && getPetAction() != PetAction.EAT) setAction(PetAction.IDLE, 0);
            }
        }
        tickPlay();
    }
    private void tickPower() {
        ItemStack battery = petInventory.getStackInSlot(PetInventory.BATTERY_SLOT);
        if (!PetInventory.isRechargeableBattery(battery) || ic2.api.item.ElectricItem.manager == null
                || ic2.api.item.ElectricItem.manager.getCharge(battery) < 1.0) {
            energyRemainder = 0;
            return;
        }
        boolean active = isNativeBusy() || getTarget() != null || getNavigation().isInProgress() || getPetAction().isInteraction()
                || getPetAction().isPlayAction() || getDeltaMovement().horizontalDistanceSqr() > 0.0004;
        energyRemainder += active ? ACTIVE_EU_PER_TICK_NUMERATOR : IDLE_EU_PER_TICK_NUMERATOR;
        if (energyRemainder < POWER_UNIT) return;
        int spend = energyRemainder / POWER_UNIT;
        energyRemainder %= POWER_UNIT;
        double used = ic2.api.item.ElectricItem.manager.discharge(battery, spend, 1, true, false, false);
        petInventory.setStackInSlot(PetInventory.BATTERY_SLOT, battery.copy());
        if (used < spend) energyRemainder = 0;
        if (ic2.api.item.ElectricItem.manager.getCharge(battery) < 1.0) {
            energyRemainder = 0;
            getNavigation().stop();
            setTarget(null);
        }
    }
    private void tickHunger() {
        if (isPancake()) return;
        if (getHunger() > 0) {
            starvationTicks = 0;
            boolean active = isPowered() && (isNativeBusy() || getTarget() != null || getNavigation().isInProgress()
                    || getPetAction().isInteraction() || getPetAction().isPlayAction()
                    || getDeltaMovement().horizontalDistanceSqr() > 0.0004);
            hungerElapsedTicks += active ? 10 : 1;
            if (hungerElapsedTicks >= HUNGER_DRAIN_THRESHOLD) {
                hungerElapsedTicks -= HUNGER_DRAIN_THRESHOLD;
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
    private MechanicalPet findPartner(UUID id) {
        if (!(level() instanceof ServerLevel server)) return null;
        return server.getEntity(id) instanceof MechanicalPet other ? other : null;
    }
    private void tickPlay() {
        if (hasPanelViewers() || isOrderedToSit() || isPeaceful() || isPancake() || !isPowered() || getTarget() != null || level().getGameTime() < interactionGraceUntil
                || getPetAction().isInteraction() || getPetAction() == PetAction.FLATTEN || getPetAction() == PetAction.RESTORE
                || isNativeBusy()) {
            if (playPartner != null) interruptPlay(0);
            return;
        }
        if (playPartner != null) {
            MechanicalPet other = findPartner(playPartner);
            if (other == null || !other.asEntity().isAlive() || other.isPeaceful() || other.isPancake() || other.isCat() == isCat()
                    || distanceToSqr(other.asEntity()) > 16 || other.getPlayPartnerId() == null || !other.getPlayPartnerId().equals(getUUID())) {
                interruptPlay(0); return;
            }
            TamableAnimal otherEntity = other.asEntity();
            if (other.getPanelViewerId() != null || otherEntity.isOrderedToSit() || level().getGameTime() < other.getInteractionGraceUntil()
                    || other.getPetAction().isInteraction() || other.getPetAction() == PetAction.FLATTEN || other.getPetAction() == PetAction.RESTORE
                    || !hasLineOfSight(otherEntity) || !otherEntity.hasLineOfSight(this)) { interruptPlay(0); return; }
            getLookControl().setLookAt(otherEntity, 30F, 30F);
            if (getUUID().compareTo(otherEntity.getUUID()) < 0) {
                if (distanceToSqr(otherEntity) > 2.25) getNavigation().moveTo(otherEntity, 0.8); else getNavigation().stop();
                if (--playPhaseTicks <= 0) advancePlay(other);
            }
            return;
        }
        if (tickCount % 10 != 0) return;
        AABB bounds = getBoundingBox().inflate(4.0);
        MechanicalPet candidate = level().getEntitiesOfClass(TamableAnimal.class, bounds,
                entity -> entity != this && entity instanceof MechanicalPet other && other.asEntity().isAlive()
                        && other.isCat() != isCat() && !other.isPeaceful() && !other.isPancake()
                        && other.getPanelViewerId() == null && other.getPlayPartnerId() == null && !entity.isOrderedToSit()
                        && other.isPowered() && entity.getTarget() == null
                        && !other.getPetAction().isInteraction() && other.getPetAction() != PetAction.FLATTEN && other.getPetAction() != PetAction.RESTORE
                        && level().getGameTime() >= other.getInteractionGraceUntil() && hasLineOfSight(entity) && entity.hasLineOfSight(this)
                        && !other.isNativeBusy())
                .stream().map(entity -> (MechanicalPet) entity).filter(other -> getUUID().compareTo(other.asEntity().getUUID()) < 0)
                .min(java.util.Comparator.comparingDouble(other -> distanceToSqr(other.asEntity()))).orElse(null);
        if (candidate == null || distanceToSqr(candidate.asEntity()) > 16) return;
        if (!faces(candidate) || candidate.asEntity().getLookAngle().normalize().dot(position().subtract(candidate.asEntity().position()).normalize()) <= 0.15) return;
        playPartner = candidate.asEntity().getUUID(); candidate.setPlayPartnerId(getUUID());
        startPlayPair(candidate);
    }
    private boolean faces(MechanicalPet other) {
        Vec3 toward = other.asEntity().position().subtract(position()).normalize();
        return getLookAngle().normalize().dot(toward) > 0.15;
    }
    private void startPlayPair(MechanicalPet other) {
        playPhaseTicks = 30; other.setPlayPhaseTicks(30);
        playPhase = 1; other.setPlayPhase(1);
        leadAttacks = true; other.setLeadAttacks(true);
        setAction(PetAction.STANDOFF, 30); other.setPetAction(PetAction.STANDOFF, 30);
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
    private void advancePlay(MechanicalPet other) {
        PetAction action = switch (playPhase) { case 1 -> PetAction.POUNCE; case 2 -> PetAction.SWIPE; case 3 -> PetAction.RETREAT; default -> PetAction.STANDOFF; };
        int nextPhase = playPhase == 3 ? 0 : (playPhase == 0 ? 1 : playPhase + 1);
        playPhase = nextPhase; other.setPlayPhase(nextPhase);
        boolean lead = leadAttacks;
        if (action == PetAction.POUNCE) {
            MechanicalPet attacker = lead ? this : other, retreating = lead ? other : this;
            TamableAnimal attackerEntity = attacker.asEntity(), retreatingEntity = retreating.asEntity();
            Vec3 toward = retreatingEntity.position().subtract(attackerEntity.position()).normalize().scale(0.32).add(0, 0.18, 0);
            if (attacker.canSafelyStep(new Vec3(toward.x, 0, toward.z))) {
                attackerEntity.setDeltaMovement(toward); attackerEntity.hasImpulse = true;
            }
            attacker.setPetAction(PetAction.POUNCE, 18); retreating.setPetAction(PetAction.RETREAT, 20);
            playPhaseTicks = 20; other.setPlayPhaseTicks(20);
        } else if (action == PetAction.SWIPE) {
            MechanicalPet attacker = lead ? this : other, retreating = lead ? other : this;
            TamableAnimal attackerEntity = attacker.asEntity(), retreatingEntity = retreating.asEntity();
            Vec3 pushMotion = retreatingEntity.position().subtract(attackerEntity.position()).normalize().scale(0.25);
            if (attackerEntity.distanceToSqr(retreatingEntity) <= 5 && retreating.canSafelyStep(pushMotion)) {
                retreatingEntity.setDeltaMovement(pushMotion.x, Math.max(0.08, retreatingEntity.getDeltaMovement().y), pushMotion.z);
                retreatingEntity.hasImpulse = true;
            }
            attacker.setPetAction(PetAction.SWIPE, 12); retreating.setPetAction(PetAction.RETREAT, 20);
            playPhaseTicks = 20; other.setPlayPhaseTicks(20);
        } else if (action == PetAction.RETREAT) {
            setAction(PetAction.RETREAT, 20); other.setPetAction(PetAction.RETREAT, 20);
            Vec3 away = position().subtract(other.asEntity().position()).normalize().scale(0.2);
            Vec3 otherAway = other.asEntity().position().subtract(position()).normalize().scale(0.2);
            if (canSafelyStep(away)) { setDeltaMovement(away.x, 0.1, away.z); hasImpulse = true; }
            if (other.canSafelyStep(otherAway)) { other.asEntity().setDeltaMovement(otherAway.x, 0.1, otherAway.z); other.asEntity().hasImpulse = true; }
            playPhaseTicks = 20; other.setPlayPhaseTicks(20);
        } else {
            leadAttacks = !leadAttacks; other.setLeadAttacks(leadAttacks);
            setAction(PetAction.STANDOFF, 30); other.setPetAction(PetAction.STANDOFF, 30);
            playPhaseTicks = 30; other.setPlayPhaseTicks(30);
        }
    }
    private void setAction(PetAction action, int ticks) {
        if (getPetAction() == PetAction.BARK && action != PetAction.BARK) PetSounds.stop(this);
        entityData.set(ACTION, action.ordinal()); entityData.set(ACTION_TICKS, ticks);
    }
    @Override public void setPetAction(PetAction action, int ticks) { setAction(action, ticks); }
    @Override public UUID getPlayPartnerId() { return playPartner; }
    @Override public void setPlayPartnerId(UUID id) { playPartner = id; }
    @Override public UUID getPanelViewerId() { return panelViewers.stream().findFirst().orElse(null); }
    @Override public boolean hasPanelViewers() { return !panelViewers.isEmpty(); }
    @Override public long getInteractionGraceUntil() { return interactionGraceUntil; }
    @Override public void setInteractionGraceUntil(long tick) { interactionGraceUntil = tick; }
    @Override public int getPlayPhaseTicks() { return playPhaseTicks; }
    @Override public void setPlayPhaseTicks(int ticks) { playPhaseTicks = ticks; }
    @Override public int getPlayPhase() { return playPhase; }
    @Override public void setPlayPhase(int phase) { playPhase = phase; }
    @Override public boolean isLeadAttacks() { return leadAttacks; }
    @Override public void setLeadAttacks(boolean lead) { leadAttacks = lead; }
    @Override public boolean canSafelyStep(Vec3 motion) { return safeStep(motion); }
    @Override public void setPanelViewer(UUID viewer, boolean open) {
        if (level().isClientSide) return;
        if (open && viewer != null) { panelViewers.add(viewer); interruptPlay(PLAYER_GRACE_TICKS); }
        else if (viewer != null) panelViewers.remove(viewer);
    }
    private void interruptPlay(int grace) {
        if (grace > 0) interactionGraceUntil = level().getGameTime() + grace;
        MechanicalPet other = playPartner == null ? null : findPartner(playPartner);
        if (other != null && getUUID().equals(other.getPlayPartnerId())) {
            other.setPlayPartnerId(null); other.setPlayPhaseTicks(0); other.setPlayPhase(0);
            if (grace > 0) other.setInteractionGraceUntil(interactionGraceUntil);
            other.setPetAction(PetAction.IDLE, 0); other.asEntity().getNavigation().stop();
        }
        playPartner = null; playPhaseTicks = 0; playPhase = 0; getNavigation().stop();
        if (getPetAction() == PetAction.STANDOFF || getPetAction() == PetAction.POUNCE || getPetAction() == PetAction.SWIPE || getPetAction() == PetAction.RETREAT)
            setAction(PetAction.IDLE, 0);
    }
    @Override public void command(Player player, int id) {
        if (level().isClientSide || !isOwnedBy(player) || distanceToSqr(player) > 64) return;
        if (id < 0 || id > 13 || (isPancake() && id != 0 && id != 5)) return;
        if (!isPowered() && id != 0 && id != 5) return;
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
    @Override public void nativeHiss() { }
    @Override public void nativeFlatten() { }
    @Override public void nativeRestore() { }
    @Override public void beginRestore() {
        if (level().isClientSide || !isCat() || !isPancake() || getPetAction() == PetAction.RESTORE) return;
        AABB standing = new AABB(getX() - 0.3, getY(), getZ() - 0.3, getX() + 0.3, getY() + 0.7, getZ() + 0.3);
        if (!level().getWorldBorder().isWithinBounds(standing) || !level().noCollision(this, standing)) return;
        setAction(PetAction.RESTORE, 40);
        getNavigation().stop();
    }
    /** Called by the mechanical press integration when this cat is flattened. */
    @Override public void flatten() {
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
        if (stack.is(PetRegistry.PANCAKE_ITEM.get()) || MeowchanicsPancakeBridge.isMechanical(stack)) return InteractionResult.PASS;
        ResourceLocation heldId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (isCat() && LAOWU_CATNIP.equals(heldId)) {
            if (level().isClientSide) return InteractionResult.SUCCESS;
            interruptPlay(PLAYER_GRACE_TICKS);
            setAction(PetAction.DANCE, 70);
            return InteractionResult.CONSUME;
        }
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
        if (!isCat() && key.equals(PetFeeding.ORDINARY_CAT_PANCAKE)) return 40;
        if (isCat()) return stack.is(ItemTags.FISHES) ? 20 : 0;
        net.minecraft.world.food.FoodProperties food = stack.getItem().getFoodProperties();
        return food != null && food.isMeat() ? 20 : 0;
    }
    /** Called by placement-item code after spawning; owner UUID is saved by TamableAnimal's normal NBT. */
    @Override public void bindTo(Player owner) { if (!level().isClientSide) tame(owner); }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean damaged = super.hurt(source, amount);
        if (damaged && !level().isClientSide && getPetAction() == PetAction.BARK) setAction(PetAction.IDLE, 0);
        return damaged;
    }
    @Override protected void dropCustomDeathLoot(net.minecraft.world.damagesource.DamageSource source,
                                                  int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
        if (level().isClientSide) return;
        // Cargo first releases the chest removal lock. Clear each real slot before
        // spawning its drop so death/re-entry cannot duplicate the same inventory.
        for (int slot = PetInventory.CARGO_START; slot < PetInventory.SIZE; slot++) dropPetSlot(slot);
        for (int slot = 0; slot < PetInventory.CARGO_START; slot++) dropPetSlot(slot);
    }
    private void dropPetSlot(int slot) {
        ItemStack stack = petInventory.getStackInSlot(slot).copy();
        petInventory.setStackInSlot(slot, ItemStack.EMPTY);
        if (!stack.isEmpty() && !net.minecraft.world.item.enchantment.EnchantmentHelper.hasVanishingCurse(stack))
            spawnAtLocation(stack);
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
        tag.putInt("MechanicalPetSchema", 3);
        tag.putBoolean("MechanicalCat", isCat()); tag.putBoolean("MechanicalPetPeaceful", isPeaceful());
        tag.putBoolean("MechanicalPetFollowing", isFollowing()); tag.putBoolean("MechanicalPetPancake", isPancake());
        tag.putInt("MechanicalPetAction", getPetAction().ordinal()); tag.putInt("MechanicalPetActionTicks", getActionTicks());
        tag.putInt("MechanicalPetHunger", getHunger()); tag.putInt("MechanicalPetHungerElapsed", hungerElapsedTicks);
        tag.putInt("MechanicalPetStarvationTicks", starvationTicks);
        tag.putInt("MechanicalPetEnergyRemainder", energyRemainder);
        tag.putInt("MechanicalPetMirrorHissCooldown", mirrorHissCooldown);
        tag.put("MechanicalPetInventory", petInventory.serializeNBT());
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(CAT, tag.getBoolean("MechanicalCat")); entityData.set(PEACEFUL, tag.getBoolean("MechanicalPetPeaceful"));
        setOrderedToSit(!tag.getBoolean("MechanicalPetFollowing")); entityData.set(PANCAKE, tag.getBoolean("MechanicalPetPancake"));
        int schema = tag.getInt("MechanicalPetSchema");
        var maxHealth = getAttribute(Attributes.MAX_HEALTH);
        float savedMaxHealth = Math.max(1F, getMaxHealth());
        float savedHealth = getHealth();
        if (maxHealth != null) maxHealth.setBaseValue(40.0);
        var attackDamage = getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamage != null) attackDamage.setBaseValue(3.0);
        if (schema < 2) {
            float oldMaximum = savedMaxHealth;
            float newMaximum = Math.max(1F, getMaxHealth());
            setHealth(Math.max(0F, Math.min(newMaximum, savedHealth * newMaximum / oldMaximum)));
        }
        int savedHunger = tag.contains("MechanicalPetHunger") ? tag.getInt("MechanicalPetHunger") : 20;
        int savedElapsed = Math.max(0, tag.getInt("MechanicalPetHungerElapsed"));
        if (schema < 2) {
            int oldHunger = Math.max(0, Math.min(20, savedHunger));
            int oldElapsed = Math.max(0, Math.min(2399, savedElapsed));
            long rawHungerNumerator = (long) oldHunger * 12000L - (long) oldElapsed * 5L;
            long rawHungerDenominator = 2400L;
            int migratedHunger = (int) Math.max(0L, Math.min(MAX_HUNGER,
                    (rawHungerNumerator + rawHungerDenominator - 1L) / rawHungerDenominator));
            long progressNumerator = (long) migratedHunger * rawHungerDenominator - rawHungerNumerator;
            savedHunger = migratedHunger;
            savedElapsed = (int) Math.max(0L, Math.min(HUNGER_DRAIN_THRESHOLD - 1L,
                    progressNumerator * HUNGER_DRAIN_THRESHOLD / rawHungerDenominator));
        }
        setHunger(savedHunger);
        hungerElapsedTicks = Math.max(0, Math.min(HUNGER_DRAIN_THRESHOLD - 1, savedElapsed));
        starvationTicks = getHunger() == 0 ? Math.max(0, Math.min(STARVATION_DAMAGE_INTERVAL - 1, tag.getInt("MechanicalPetStarvationTicks"))) : 0;
        energyRemainder = Math.max(0, Math.min(POWER_UNIT - 1, tag.getInt("MechanicalPetEnergyRemainder")));
        mirrorHissCooldown = Math.max(0, Math.min(MIRROR_HISS_COOLDOWN, tag.getInt("MechanicalPetMirrorHissCooldown")));
        if (tag.contains("MechanicalPetInventory", 10)) {
            CompoundTag savedInventory = tag.getCompound("MechanicalPetInventory").copy();
            if (schema < 3) {
                net.minecraft.nbt.ListTag items = savedInventory.getList("Items", 10);
                for (int i = 0; i < items.size(); i++) {
                    CompoundTag item = items.getCompound(i);
                    int slot = item.getInt("Slot") & 255;
                    if (slot >= 2 && slot < 29) item.putByte("Slot", (byte) (slot + 1));
                }
            }
            petInventory.deserializeNBT(savedInventory);
            if (!PetInventory.isRechargeableBattery(petInventory.getStackInSlot(PetInventory.BATTERY_SLOT)))
                petInventory.setStackInSlot(PetInventory.BATTERY_SLOT, ItemStack.EMPTY);
            if (!petInventory.getStackInSlot(PetInventory.CHEST_SLOT).is(net.minecraft.world.item.Items.CHEST))
                petInventory.setStackInSlot(PetInventory.CHEST_SLOT, ItemStack.EMPTY);
            ItemStack armor = petInventory.getStackInSlot(PetInventory.ARMOR_SLOT);
            if (!armor.isEmpty() && !PetInventory.isVanillaHorseArmor(armor))
                petInventory.setStackInSlot(PetInventory.ARMOR_SLOT, ItemStack.EMPTY);
        } else {
            Item batteryItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(IC2_RE_BATTERY);
            ItemStack battery = batteryItem == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(batteryItem);
            // One-time compatibility for already-existing pets; all newly assembled pets keep the inserted charge.
            if (schema < 2 && !battery.isEmpty() && ic2.api.item.ElectricItem.manager != null)
                ic2.api.item.ElectricItem.manager.charge(battery, 10000, 1, true, false);
            petInventory.setStackInSlot(PetInventory.BATTERY_SLOT, battery);
        }
        int ordinal = tag.getInt("MechanicalPetAction");
        PetAction action = ordinal >= 0 && ordinal < PetAction.values().length ? PetAction.values()[ordinal] : PetAction.IDLE;
        if (action == PetAction.POUNCE || action == PetAction.SWIPE || action == PetAction.STANDOFF || action == PetAction.RETREAT
                || action == PetAction.BARK || action == PetAction.DANCE || action == PetAction.HISS) action = PetAction.IDLE;
        int ticks = Math.max(0, Math.min(duration(action), tag.getInt("MechanicalPetActionTicks")));
        setAction(action, ticks); refreshDimensions(); playPartner = null; playPhaseTicks = 0; playPhase = 0;
    }
}





















