package com.ic2newera.robot;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

/** Server-authoritative portable machine. Recall preserves state; death consumes one of five lives. */
public final class RobotEntity extends TamableAnimal {
    public static final int STAY = 0, FOLLOW = 1, LOGGING = 2, MINING = 3, MAX_DEATHS = 5;
    private static final int STANDBY_TICKS = 24 * 60 * 60 * 20;
    private static final int ACTIVE_DRAIN_MULTIPLIER = 4;
    private static final EntityDataAccessor<String> CHARACTER = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> MODE = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ENERGY = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEATHS = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> WORKING = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> COMBAT = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private final RobotInventory inventory = new RobotInventory(this);
    private long nextChat;
    private boolean deathHandled;
    private boolean workTaskActive;
    private boolean activeThisTick;
    private long energyRemainder;
    private BlockPos workOrigin = BlockPos.ZERO;
    private String workStatus = "待命";
    private RobotCollectionTask collectionTask;
    public RobotEntity(EntityType<? extends RobotEntity> type, Level level) { super(type, level); setPersistenceRequired(); }
    public static AttributeSupplier.Builder attributes() {
        return Animal.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 24).add(Attributes.ATTACK_DAMAGE, 1).add(Attributes.ATTACK_KNOCKBACK, 0);
    }
    @Override protected void defineSynchedData() {
        super.defineSynchedData(); entityData.define(CHARACTER, "tomori"); entityData.define(MODE, STAY);
        entityData.define(ENERGY, 0); entityData.define(DEATHS, 0); entityData.define(WORKING, false); entityData.define(COMBAT, false);
    }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.15, true) {
            @Override public boolean canUse() { return combatEnabled() && powered() && super.canUse(); }
            @Override public boolean canContinueToUse() { return combatEnabled() && powered() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(2, new RobotWorkGoal(this));
        goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.1, 4, 2, false) {
            @Override public boolean canUse() { return following() && powered() && super.canUse(); }
            @Override public boolean canContinueToUse() { return following() && powered() && super.canContinueToUse(); }
        });
        goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 6));
        goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Mob.class, 20, true, false,
                target -> combatEnabled() && powered() && hostile(target)));
    }
    public RobotCharacter character() { return RobotCharacter.byId(entityData.get(CHARACTER)); }
    public void setCharacter(RobotCharacter character) { entityData.set(CHARACTER, character.id); }
    public int mode() { return entityData.get(MODE); }
    public boolean following() { return mode() == FOLLOW; }
    public boolean isWorking() { return entityData.get(WORKING); }
    public void setWorking(boolean working) { entityData.set(WORKING, working); if (working) activeThisTick = true; }
    public void setWorkTaskActive(boolean active) { workTaskActive = active; if (active) activeThisTick = true; }
    public boolean combatEnabled() { return entityData.get(COMBAT); }
    public void setCombatEnabled(boolean value) { entityData.set(COMBAT, value); if (!value) { setTarget(null); getNavigation().stop(); } }
    public int deaths() { return entityData.get(DEATHS); }
    public RobotInventory inventory() { return inventory; }
    public BlockPos workOrigin() { return workOrigin; }
    public int energy() { return level.isClientSide ? entityData.get(ENERGY) : RobotBatteryItem.charge(inventory.getStackInSlot(0)); }
    public boolean powered() { return energy() > 0; }
    public boolean spendEnergy(int amount) { return !level.isClientSide && RobotBatteryItem.consume(inventory.getStackInSlot(0), amount); }
    public String modeName() { return collectionTask != null ? "指定采集" : switch (mode()) { case FOLLOW -> "跟随"; case LOGGING -> "伐木"; case MINING -> "采矿"; default -> "待命"; }; }
    public String status() { return !powered() ? "无电池或电量不足" : modeName() + " / " + workStatus; }
    public void workStatus(String status) { workStatus = status; }
    public RobotCollectionTask collectionTask() { return collectionTask; }
    public String collectionSummary() { return collectionTask == null ? "" : collectionTask.summary(); }
    public RobotCollectionTask.Result assignCollection(ServerPlayer owner, String itemId, int amount, int kind) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel) || owner == null
                || serverLevel.getServer() != owner.server || !isAlive() || !isOwnedBy(owner))
            return new RobotCollectionTask.Result(false, "只有机器人的所有者可以派发采集任务。");
        if (amount < 1 || amount > 256) return new RobotCollectionTask.Result(false, "采集数量必须为 1～256。");
        if (kind < RobotCollectionTask.AUTO || kind > RobotCollectionTask.PICKUP_ONLY)
            return new RobotCollectionTask.Result(false, "采集模式无效（0 自动采矿/伐木并拾取，1 仅拾取）。");
        ResourceLocation id = ResourceLocation.tryParse(itemId == null ? "" : itemId.trim());
        if (id == null || !net.minecraft.core.Registry.ITEM.containsKey(id)) return new RobotCollectionTask.Result(false, "目标物品 ID 无效。");
        Item item = net.minecraft.core.Registry.ITEM.get(id);
        if (item == net.minecraft.world.item.Items.AIR) return new RobotCollectionTask.Result(false, "不能把空气设为采集目标。");
        if (collectionTask != null && collectionTask.matches(new ItemStack(item))
                && collectionTask.requested() == amount && collectionTask.kind() == kind)
            return new RobotCollectionTask.Result(false, "相同采集任务已在进行：" + collectionTask.summary());
        setMode(STAY);
        collectionTask = new RobotCollectionTask(item, amount, kind);
        workOrigin = blockPosition();
        workStatus = "等待采集：" + collectionTask.summary();
        return new RobotCollectionTask.Result(true, "已派发采集任务：" + collectionTask.summary());
    }
    public boolean recordCollectionCount(int count) {
        if (collectionTask == null || count <= 0) return false;
        collectionTask.record(count);
        if (collectionTask.complete()) {
            collectionTask = null; setMode(STAY); workStatus = "指定采集已完成";
            return true;
        }
        workStatus = "采集进度：" + collectionTask.summary();
        return false;
    }
    public void cancelCollection(String reason) {
        if (collectionTask == null) return;
        collectionTask = null; setMode(STAY); workStatus = reason == null || reason.isBlank() ? "指定采集已取消" : reason;
    }
    public void stay() { setMode(STAY); }
    public void follow() { setMode(FOLLOW); }
    public void setMode(int mode) {
        if (mode < STAY || mode > MINING) return;
        collectionTask = null;
        entityData.set(MODE, mode); setOrderedToSit(false); getNavigation().stop(); setWorking(false);
        workOrigin = blockPosition(); workStatus = "等待任务";
    }
    public boolean canManage(ServerPlayer player) { return isAlive() && isOwnedBy(player) && distanceToSqr(player) <= 64; }
    public boolean allowChat(ServerPlayer player) {
        if (level.getGameTime() < nextChat || !spendEnergy(2000)) return false;
        nextChat = level.getGameTime() + 40; return true;
    }
    private boolean hostile(LivingEntity target) {
        if (!(target instanceof Enemy) || target instanceof Player || isAlliedTo(target) || !target.isAlive()) return false;
        if (target instanceof NeutralMob neutral && !neutral.isAngryAt(this)
                && (getOwner() == null || !neutral.isAngryAt(getOwner()))) return false;
        return distanceToSqr(target) <= 16 * 16;
    }
    @Override public boolean doHurtTarget(Entity target) {
        if (!(target instanceof LivingEntity living) || !hostile(living) || !combatEnabled() || !powered()) return false;
        activeThisTick = true;
        swing(InteractionHand.MAIN_HAND);
        boolean hit = super.doHurtTarget(target);
        if (hit && !getMainHandItem().isEmpty()) getMainHandItem().getItem().hurtEnemy(getMainHandItem(), living, this);
        return hit;
    }
    @Override protected void hurtArmor(DamageSource source, float amount) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = getItemBySlot(slot);
            if (!armor.isEmpty() && (!source.isFire() || !armor.getItem().isFireResistant()))
                armor.hurtAndBreak(Math.max(1, (int) amount / 4), this, owner -> owner.broadcastBreakEvent(slot));
        }
    }
    @Override public void tick() {
        // Recharge before AI checks power, so a discharged robot can resume without battery removal.
        if (!level.isClientSide && isAlive()) {
            inventory.tickPowerBanks();
            activeThisTick = workTaskActive || isWorking()
                    || (combatEnabled() && getTarget() != null && hostile(getTarget()));
        }
        super.tick();
        if (level.isClientSide || !isAlive()) return;
        if (getTarget() != null && (!combatEnabled() || !hostile(getTarget()) || !powered())) setTarget(null);
        if (powered()) {
            boolean active = activeThisTick || workTaskActive || isWorking() || getTarget() != null;
            // Integer EU with a persisted fractional carry: one full battery lasts 24 h / 6 h at 20 TPS.
            long amount = energyRemainder + (long) RobotBatteryItem.CAPACITY * (active ? ACTIVE_DRAIN_MULTIPLIER : 1);
            int cost = (int) (amount / STANDBY_TICKS);
            energyRemainder = amount % STANDBY_TICKS;
            spendEnergy(Math.min(cost, energy()));
        }
        if (!powered()) {
            getNavigation().stop(); setTarget(null); setWorking(false); setSpeed(0);
        }
        if (tickCount % 10 == 0) { inventory.syncEquipment(); entityData.set(ENERGY, energy()); }
    }
    public ItemStack asDoll() {
        inventory.syncEquipment();
        ItemStack stack = new ItemStack(RobotRegistry.DOLLS.get(character()).get());
        stack.getOrCreateTag().putFloat("RobotHealth", Math.max(1, getHealth()));
        CompoundTag state = new CompoundTag(); state.putInt("Deaths", deaths()); state.put("Inventory", inventory.serializeNBT());
        state.putLong("EnergyRemainder", energyRemainder);
        state.putLong("WorkOrigin", workOrigin.asLong());
        if (collectionTask != null) state.put("CollectionTask", collectionTask.save());
        stack.getOrCreateTag().put("RobotState", state);
        if (hasCustomName()) stack.setHoverName(getCustomName());
        return stack;
    }
    public void restoreDoll(ItemStack doll) {
        if (!doll.hasTag()) return;
        CompoundTag tag = doll.getTag(), state = tag.getCompound("RobotState");
        entityData.set(DEATHS, Math.max(0, Math.min(MAX_DEATHS, state.getInt("Deaths"))));
        energyRemainder = Math.max(0, state.getLong("EnergyRemainder")) % STANDBY_TICKS;
        if (state.contains("Inventory")) { inventory.deserializeNBT(state.getCompound("Inventory")); inventory.applyEquipment(); }
        // A redeployed doll resumes its remaining amount around the new deployment position.
        workOrigin = blockPosition();
        collectionTask = state.contains("CollectionTask") ? RobotCollectionTask.load(state.getCompound("CollectionTask")) : null;
        if (collectionTask != null) workStatus = "等待采集：" + collectionTask.summary();
        if (!(inventory.getStackInSlot(0).getItem() instanceof RobotBatteryItem)) inventory.setStackInSlot(0, ItemStack.EMPTY);
        float health = tag.contains("RobotHealth") ? tag.getFloat("RobotHealth") : getMaxHealth();
        if (Float.isFinite(health)) setHealth(Math.max(1, Math.min(getMaxHealth(), health)));
        entityData.set(ENERGY, energy());
    }
    public boolean recall(ServerPlayer player) {
        if (!canManage(player)) return false;
        ItemStack stack = asDoll();
        if (!player.getInventory().add(stack)) {
            player.displayClientMessage(Component.literal("背包已满，请留出一个空位后收回。"), true); return false;
        }
        discard(); return true;
    }
    @Override public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (canManage(serverPlayer)) RobotPackets.open(serverPlayer, this, 0);
            else player.displayClientMessage(Component.literal("只有放置这台机器人的玩家可以操作。"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected Component getTypeName() { return character().title(); }
    @Override public boolean isFood(ItemStack stack) { return false; }
    @Override public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) { return null; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public Packet<?> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
    // Suppress Mob's random equipment drops: these same items live in the recovered doll.
    @Override protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) { }
    @Override protected void dropEquipment() {
        if (level.isClientSide || deathHandled) return;
        deathHandled = true; inventory.syncEquipment(); inventory.setStackInSlot(0, ItemStack.EMPTY);
        entityData.set(DEATHS, deaths() + 1);
        for (int i = 1; i < inventory.getSlots(); i++)
            if (EnchantmentHelper.hasVanishingCurse(inventory.getStackInSlot(i))) inventory.setStackInSlot(i, ItemStack.EMPTY);
        if (deaths() < MAX_DEATHS) {
            ItemStack doll = asDoll(); doll.getOrCreateTag().putFloat("RobotHealth", getMaxHealth()); spawnAtLocation(doll);
        } else {
            for (int i = 1; i < inventory.getSlots(); i++) {
                ItemStack stack = inventory.getStackInSlot(i); if (!stack.isEmpty()) spawnAtLocation(stack.copy());
            }
        }
        for (int i = 0; i < inventory.getSlots(); i++) inventory.setStackInSlot(i, ItemStack.EMPTY);
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag); inventory.syncEquipment();
        tag.putString("RobotCharacter", character().id); tag.putInt("RobotMode", mode());
        tag.putBoolean("RobotFollowing", following()); tag.putInt("RobotDeaths", deaths()); tag.putBoolean("RobotCombat", combatEnabled());
        tag.put("RobotInventory", inventory.serializeNBT()); tag.putLong("RobotWorkOrigin", workOrigin.asLong());
        tag.putLong("RobotEnergyRemainder", energyRemainder);
        if (collectionTask != null) tag.put("RobotCollectionTask", collectionTask.save());
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag); setCharacter(RobotCharacter.byId(tag.getString("RobotCharacter")));
        setMode(tag.contains("RobotMode") ? tag.getInt("RobotMode") : tag.getBoolean("RobotFollowing") ? FOLLOW : STAY);
        entityData.set(DEATHS, Math.max(0, Math.min(MAX_DEATHS, tag.getInt("RobotDeaths"))));
        setCombatEnabled(tag.getBoolean("RobotCombat"));
        energyRemainder = Math.max(0, tag.getLong("RobotEnergyRemainder")) % STANDBY_TICKS;
        if (tag.contains("RobotInventory")) { inventory.deserializeNBT(tag.getCompound("RobotInventory")); inventory.applyEquipment(); }
        workOrigin = tag.contains("RobotWorkOrigin") ? BlockPos.of(tag.getLong("RobotWorkOrigin")) : blockPosition();
        collectionTask = tag.contains("RobotCollectionTask") ? RobotCollectionTask.load(tag.getCompound("RobotCollectionTask")) : null;
        if (collectionTask != null) workStatus = "等待采集：" + collectionTask.summary();
        entityData.set(ENERGY, energy());
    }
}
