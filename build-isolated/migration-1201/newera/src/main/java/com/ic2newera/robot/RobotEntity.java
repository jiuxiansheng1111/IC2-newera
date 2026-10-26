package com.ic2newera.robot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
import com.ic2newera.robot.companionship.RobotCompanionship;

/** Server-authoritative portable machine. Recall preserves state; death consumes one of five lives. */
public final class RobotEntity extends TamableAnimal {
    public static final int STAY = 0, FOLLOW = 1, LOGGING = 2, MINING = 3, COOKING = 4,
            SMELTING = 5, FARMING = 6, BUILDING = 7, PRODUCTION = 8, REFITTING = 9, MAX_DEATHS = 5;
    public static final int MAX_HUNGER = 200;
    public static final int EATING_DURATION_TICKS = 40;
    private static final int HEALTH_SCHEMA = 2;
    private static final int ACTIVE_TICKS_PER_HUNGER = 12_960;
    private static final int STANDBY_TICKS = 24 * 60 * 60 * 20;
    private static final int ACTIVE_DRAIN_MULTIPLIER = 4;
    private static final EntityDataAccessor<String> CHARACTER = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> MODE = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ENERGY = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DEATHS = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> WORKING = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> COMBAT = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> FACIAL_EXPRESSION = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> EXPRESSION_UNTIL = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> COMBAT_ACTIVE = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> HURT_ACTIVE = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> WORK_ACTIVITY = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> POWERED = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> PETTING = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> HOLDING_HANDS = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> HUNGER = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> EATING = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> EATING_START_TIME = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<ItemStack> EATING_STACK = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Integer> EATING_ARM = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
    private final RobotInventory inventory = new RobotInventory(this);
    private final RobotHomeGreeting homeGreeting = new RobotHomeGreeting();
    private final RobotCompanionship companionship = new RobotCompanionship(this);
    private long nextChat;
    private boolean deathHandled;
    private boolean workTaskActive;
    private boolean activeThisTick;
    private long energyRemainder;
    private int hunger = MAX_HUNGER;
    private int hungerActiveTicks;
    private int hungerStarvationTicks;
    private BlockPos workOrigin = BlockPos.ZERO;
    private BlockPos homePos = BlockPos.ZERO;
    private String homeDimension = "";
    private boolean homeInitialized;
    private RobotMovementIntent movementIntent = RobotMovementIntent.NONE;
    private BlockPos movementTarget;
    private String movementDimension = "";
    private String workStatus = "待命";
    private RobotWorkAlert workAlert;
    private boolean workAlertPhoneNoticeSent;
    private String lastWorkNotice = "";
    private long nextWorkNotice;
    private RobotCollectionTask collectionTask;
    private RobotCollectionTask pausedCollectionTask;
    private com.ic2newera.robot.cooking.RobotCookingState cookingState;
    private com.ic2newera.robot.cooking.RobotCookingOrder cookingOrder;
    private RobotSmeltingGoal smeltingGoal;
    private com.ic2newera.robot.building.RobotBuildTask buildTask;
    private com.ic2newera.robot.building.RobotProductionGoal productionGoal;
    private com.ic2newera.robot.building.RobotIndustrialGoal industrialGoal;
    private com.ic2newera.robot.building.RobotLineRefitTask lineRefitTask;
    private boolean industrialRecoveryBlocked;
    private CompoundTag invalidLineRefitRecord = new CompoundTag();
    private CompoundTag invalidBuildTaskRecord = new CompoundTag();
    public RobotEntity(EntityType<? extends RobotEntity> type, Level level) { super(type, level); setPersistenceRequired(); }
    @Override protected float getStandingEyeHeight(Pose pose, EntityDimensions size) {
        // R16/R17 share the accepted eye placement; match rays/look control to the 1.8-block visible rig.
        return size.height * (1.465F / 1.8F);
    }
    public static AttributeSupplier.Builder attributes() {
        return Animal.createMobAttributes().add(Attributes.MAX_HEALTH, 100).add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 24).add(Attributes.ATTACK_DAMAGE, 1).add(Attributes.ATTACK_KNOCKBACK, 0);
    }
    @Override protected void defineSynchedData() {
        super.defineSynchedData(); entityData.define(CHARACTER, "tomori"); entityData.define(MODE, STAY);
        entityData.define(ENERGY, 0); entityData.define(DEATHS, 0); entityData.define(WORKING, false); entityData.define(COMBAT, false);
        entityData.define(FACIAL_EXPRESSION, RobotExpression.NEUTRAL.id()); entityData.define(EXPRESSION_UNTIL, 0L);
        entityData.define(COMBAT_ACTIVE, false); entityData.define(HURT_ACTIVE, false);
        entityData.define(WORK_ACTIVITY, false); entityData.define(POWERED, false);
        entityData.define(PETTING, false); entityData.define(HOLDING_HANDS, false);
        entityData.define(HUNGER, MAX_HUNGER); entityData.define(EATING, false);
        entityData.define(EATING_START_TIME, 0L); entityData.define(EATING_STACK, ItemStack.EMPTY); entityData.define(EATING_ARM, 1);
    }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.15, true) {
            @Override public boolean canUse() { return mayAttack(getTarget()) && super.canUse(); }
            @Override public boolean canContinueToUse() { return mayAttack(getTarget()) && super.canContinueToUse(); }
        });
        goalSelector.addGoal(1, new RobotCompanionship.HandHoldingGoal(this));
        goalSelector.addGoal(2, new RobotWorkGoal(this));
        goalSelector.addGoal(2, new RobotEggPatrolGoal(this));
        goalSelector.addGoal(2, new com.ic2newera.robot.cooking.RobotCookingGoal(this));
        smeltingGoal = new RobotSmeltingGoal(this);
        goalSelector.addGoal(2, smeltingGoal);
        goalSelector.addGoal(2, new RobotFarmingGoal(this));
        goalSelector.addGoal(2, new com.ic2newera.robot.building.RobotBuildGoal(this));
        productionGoal = new com.ic2newera.robot.building.RobotProductionGoal(this);
        goalSelector.addGoal(2, productionGoal);
        industrialGoal = new com.ic2newera.robot.building.RobotIndustrialGoal(this);
        goalSelector.addGoal(2, industrialGoal);
        goalSelector.addGoal(2, new com.ic2newera.robot.building.RobotLineRefitGoal(this));
        goalSelector.addGoal(3, new RobotMovementGoal(this));
        goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 6));
        goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Mob.class, 20, true, false,
                this::mayAttack));
    }
    public RobotCharacter character() { return RobotCharacter.byId(entityData.get(CHARACTER)); }
    public void setCharacter(RobotCharacter character) {
        if (!entityData.get(CHARACTER).equals(character.id)) clearFacialExpression();
        entityData.set(CHARACTER, character.id);
    }
    public int mode() { return entityData.get(MODE); }
    public boolean following() { return mode() == FOLLOW; }
    public boolean isWorking() { return entityData.get(WORKING); }
    public void setWorking(boolean working) { entityData.set(WORKING, working); if (working) { activeThisTick = true; stopMovement(); } }
    public void setWorkTaskActive(boolean active) { workTaskActive = active; if (active) { activeThisTick = true; stopMovement(); } }
    public boolean workTaskActive() { return workTaskActive; }
    /** Explicit livestock/monster slaughter switch; normal hostile defence is always active. */
    public boolean combatEnabled() { return entityData.get(COMBAT); }
    public void setCombatEnabled(boolean value) { entityData.set(COMBAT, value); if (!value) { setTarget(null); getNavigation().stop(); } }
    public int deaths() { return entityData.get(DEATHS); }
    public int hunger() { return level().isClientSide ? entityData.get(HUNGER) : hunger; }
    public boolean isEating() { return entityData.get(EATING); }
    public float eatingProgress(float partialTick) {
        double elapsed = level().getGameTime() + partialTick - eatingStartTime();
        return Mth.clamp((float) (elapsed / EATING_DURATION_TICKS), 0.0F, 1.0F);
    }
    public boolean canAnimateEating() {
        return isEating() && !entityData.get(HURT_ACTIVE) && !entityData.get(COMBAT_ACTIVE)
                && !entityData.get(WORK_ACTIVITY) && getPose() != Pose.SWIMMING && !isOrderedToSit();
    }
    public long eatingStartTime() { return entityData.get(EATING_START_TIME); }
    public ItemStack eatingStack() { return entityData.get(EATING_STACK); }
    public HumanoidArm eatingArm() { return entityData.get(EATING_ARM) == 0 ? HumanoidArm.LEFT : HumanoidArm.RIGHT; }
    public RobotInventory inventory() { return inventory; }
    public RobotCompanionship companionship() { return companionship; }
    public boolean petting() { return companionship.petting(); }
    public boolean holdingHands() { return companionship.holdingHands(); }
    public boolean pettingSignal() { return entityData.get(PETTING); }
    public boolean holdingHandsSignal() { return entityData.get(HOLDING_HANDS); }
    public void setPettingSignal(boolean value) { if (!level().isClientSide) entityData.set(PETTING, value); }
    public void setHoldingHandsSignal(boolean value) { if (!level().isClientSide) entityData.set(HOLDING_HANDS, value); }
    /** Social interactions require an awake, powered, idle robot outside combat or hurt recovery. */
    public boolean socialInteractionSafe() {
        boolean client = level().isClientSide;
        boolean hasPower = client ? entityData.get(POWERED) : powered();
        boolean inCombat = client ? entityData.get(COMBAT_ACTIVE) : getTarget() != null || swinging;
        boolean hurt = client ? entityData.get(HURT_ACTIVE) : hurtTime > 0;
        boolean working = client ? entityData.get(WORK_ACTIVITY) : isWorking() || workTaskActive;
        return isAlive() && hasPower && !inCombat && !hurt && !working;
    }
    public ServerPlayer ownerPlayerForCompanionship() {
        if (getOwnerUUID() == null || !(level() instanceof ServerLevel serverLevel)) return null;
        return serverLevel.getServer().getPlayerList().getPlayer(getOwnerUUID());
    }
    public BlockPos workOrigin() { return workOrigin; }
    public BlockPos homePos() { ensureHomeAnchor(); return homePos; }
    public String homeDimension() { ensureHomeAnchor(); return homeDimension; }
    public boolean hasHomeAnchor() { ensureHomeAnchor(); return homeInitialized; }
    public RobotMovementIntent movementIntent() { return movementIntent; }
    public BlockPos movementTarget() { return movementTarget; }
    public String movementDimension() { return movementDimension; }
    public net.minecraft.world.entity.ai.navigation.PathNavigation navigation() { return getNavigation(); }
    public boolean movementBusy() {
        return isWorking() || workTaskActive || collectionTask != null
                || mode() == MINING || mode() == LOGGING || mode() == COOKING || mode() == SMELTING || mode() == FARMING
                || mode() == BUILDING || mode() == PRODUCTION || mode() == REFITTING
                // A partial line refit can leave machines, cables, or escrowed materials in an unsafe intermediate state.
                || lineRefitTask != null || industrialRecoveryBlocked;
    }
    public String pausedCollectionSummary() { return pausedCollectionTask == null ? "" : pausedCollectionTask.summary(); }
    public boolean hasPausedCollectionTask() { return pausedCollectionTask != null; }
    public boolean pauseWorkPreservingTask() {
        if (level().isClientSide || !movementBusy()) return false;
        if (collectionTask != null) { pausedCollectionTask = collectionTask; collectionTask = null; }
        movementIntent = RobotMovementIntent.NONE; movementTarget = null; movementDimension = "";
        if (lineRefitTask != null) pauseLineRefit();
        else if (buildTask != null || mode() == BUILDING || mode() == PRODUCTION || mode() == REFITTING) pauseBuild();
        else {
            entityData.set(MODE, STAY); entityData.set(WORKING, false); workTaskActive = false; activeThisTick = false;
            setOrderedToSit(false); getNavigation().stop(); workStatus = "工作已暂停，任务记录保留";
        }
        entityData.set(WORKING, false); workTaskActive = false; activeThisTick = false;
        return true;
    }
    private void ensureHomeAnchor() {
        if (homeInitialized || level().isClientSide) return;
        homePos = blockPosition().immutable(); homeDimension = level().dimension().location().toString(); homeInitialized = true;
    }
    public boolean setHomeAnchor(BlockPos pos) {
        if (level().isClientSide || pos == null || !level().hasChunkAt(pos) || !level().hasChunkAt(pos.above())
                || !level().getBlockState(pos.below()).isSolidRender(level(), pos.below())
                || !level().getBlockState(pos).getCollisionShape(level(), pos).isEmpty()
                || !level().getBlockState(pos.above()).getCollisionShape(level(), pos.above()).isEmpty()) return false;
        homePos = pos.immutable(); homeDimension = level().dimension().location().toString(); homeInitialized = true;
        homeGreeting.reset(); return true;
    }
    public void beginMovement(RobotMovementIntent intent, String dimension, BlockPos target) {
        if (level().isClientSide || intent == null || intent == RobotMovementIntent.NONE) return;
        movementIntent = intent; movementDimension = dimension == null ? "" : dimension;
        movementTarget = target == null ? null : target.immutable();
        entityData.set(MODE, intent == RobotMovementIntent.FOLLOW ? FOLLOW : STAY);
        setOrderedToSit(false); getNavigation().stop();
    }
    public void stopMovement() {
        if (level().isClientSide) return;
        movementIntent = RobotMovementIntent.NONE; movementTarget = null; movementDimension = "";
        getNavigation().stop();
        if (mode() == FOLLOW) entityData.set(MODE, STAY);
    }
    public void finishMovement() { stopMovement(); }
    public void movementNotice(String message) {
        ServerPlayer owner = ownerPlayerForCompanionship();
        if (owner != null && message != null && !message.isBlank()) owner.sendSystemMessage(Component.literal(message));
    }
    public int energy() { return level().isClientSide ? entityData.get(ENERGY) : RobotBatteryItem.charge(inventory.getStackInSlot(0)); }
    public boolean powered() { return energy() > 0; }
    /** Current server-synchronized expression. Transient expression state is never written to NBT. */
    public RobotExpression facialExpression() {
        boolean client = level().isClientSide;
        if (!isAlive() || !(client ? entityData.get(POWERED) : powered())
                || hurtTime > 0 || entityData.get(HURT_ACTIVE)
                || entityData.get(COMBAT_ACTIVE) || (!client && (getTarget() != null || swinging)))
            return RobotExpression.NEUTRAL;
        long now = level().getGameTime();
        RobotExpression current = RobotExpression.byId(entityData.get(FACIAL_EXPRESSION));
        if (current != RobotExpression.NEUTRAL && entityData.get(EXPRESSION_UNTIL) > now) return current;
        if (character() == RobotCharacter.ANON) {
            if (entityData.get(WORK_ACTIVITY) || (!client && (activeThisTick || workTaskActive || isWorking())))
                return RobotExpression.SERIOUS;
            return RobotExpression.SOFT_SMILE;
        }
        return RobotExpression.NEUTRAL;
    }

    /** Applies a finite, server-owned expression from the actual spoken reply. */
    public void expressSpeech(String speech) {
        if (level().isClientSide || !isAlive() || !powered() || hurtTime > 0 || getTarget() != null || swinging) return;
        setFacialExpression(RobotExpression.fromSpeech(speech));
    }

    /** Compatibility entry point for existing brief smile behavior on every character. */
    public void smileAtOwner() {
        if (!level().isClientSide && isAlive() && powered() && getTarget() == null && hurtTime == 0 && !swinging)
            setFacialExpression(RobotExpression.SOFT_SMILE);
    }
    public boolean isSmiling() {
        RobotExpression expression = facialExpression();
        return expression == RobotExpression.SOFT_SMILE || expression == RobotExpression.HAPPY;
    }
    private void setFacialExpression(RobotExpression expression) {
        entityData.set(FACIAL_EXPRESSION, expression.id());
        entityData.set(EXPRESSION_UNTIL, expression == RobotExpression.NEUTRAL ? 0L
                : level().getGameTime() + expression.durationTicks());
    }
    private void clearFacialExpression() {
        if (entityData.get(FACIAL_EXPRESSION) != RobotExpression.NEUTRAL.id())
            entityData.set(FACIAL_EXPRESSION, RobotExpression.NEUTRAL.id());
        if (entityData.get(EXPRESSION_UNTIL) != 0L) entityData.set(EXPRESSION_UNTIL, 0L);
    }
    public boolean spendEnergy(int amount) { return !level().isClientSide && RobotBatteryItem.consume(inventory.getStackInSlot(0), amount); }
    public String modeName() { return collectionTask != null ? "指定采集" : switch (mode()) { case FOLLOW -> "跟随"; case LOGGING -> "伐木"; case MINING -> "采矿"; case COOKING -> "烹饪"; case SMELTING -> "烧矿"; case FARMING -> "种地"; case BUILDING -> "施工"; case PRODUCTION -> "生产线"; case REFITTING -> "生产线改造"; default -> "待命"; }; }
    public String status() { return !powered() ? "无电池或电量不足" : modeName() + " / "
            + (cookingOrder != null && mode() == COOKING ? cookingOrder.summary() + " / " : "") + workStatus; }
    public void workStatus(String status) {
        workStatus = status.length() > 100 ? status.substring(0, 100) : status;
        if (workAlert != null && indicatesWorkResumed(workStatus)) clearWorkAlert();
    }
    /** Empty when clear; otherwise includes type, cause, suggested action, and elapsed in-game seconds. */
    public String workAlertSummary() {
        return workAlert == null ? "" : workAlert.summary(level().getGameTime());
    }
    public boolean hasWorkAlert() { return workAlert != null; }
    public RobotWorkAlert workAlert() { return workAlert; }

    /** Call only after reaching a safe position with a clear interaction ray to the target. */
    public void workPathRestored() {
        if (workAlert != null && "堵路".equals(workAlert.type())) clearWorkAlert();
    }

    private void clearWorkAlert() {
        workAlert = null;
        workAlertPhoneNoticeSent = false;
    }

    private static boolean indicatesWorkResumed(String status) {
        // Navigation and waiting statuses refresh frequently; they do not prove that the blocked
        // operation recovered. Clear only after an explicit action/result was reported.
        return status.startsWith("已领取") || status.startsWith("已投入") || status.startsWith("已取回")
                || status.startsWith("已存入") || status.startsWith("已完成") || status.startsWith("已采集")
                || status.startsWith("生产完成") || status.startsWith("施工完成") || status.startsWith("加工完成")
                || status.startsWith("烹饪完成") || status.startsWith("正在砍伐")
                || status.startsWith("正在挖矿") || status.startsWith("正在收获") || status.startsWith("已按原生")
                || status.startsWith("加工 ") || status.contains("周期成品已入箱")
                || status.startsWith("当前周期已完成且成品/余料已入箱");
    }
    /** Owner-only chat feedback also reaches a remote phone owner; repeated scans never flood chat. */
    public void workBlocked(String reason) {
        String safeReason = reason == null || reason.isBlank() ? "工作条件暂不可用" : reason;
        if (safeReason.length() > 160) safeReason = safeReason.substring(0, 160);
        workStatus = safeReason.length() > 100 ? safeReason.substring(0, 100) : safeReason;
        if (workAlert == null || !workAlert.reason().equals(safeReason)) {
            workAlert = RobotWorkAlert.create(safeReason, level().getGameTime());
            workAlertPhoneNoticeSent = false;
        }
        if (!(level() instanceof ServerLevel server) || getOwnerUUID() == null) return;
        ServerPlayer owner = server.getServer().getPlayerList().getPlayer(getOwnerUUID());
        if (!workAlertPhoneNoticeSent && owner != null && com.ic2newera.industrial.IndustrialMobileAccess.hasPhone(owner)) {
            String notice = character().chineseName + "：工作受阻（" + workAlert.type() + "）";
            owner.displayClientMessage(Component.literal(localizeOwnerNotice(notice)), true);
            workAlertPhoneNoticeSent = true;
        }
        long now = level().getGameTime();
        if (now < nextWorkNotice || safeReason.equals(lastWorkNotice)) return;
        if (owner == null) return;
        owner.sendSystemMessage(Component.literal(localizeOwnerNotice(character().chineseName + "：" + workStatus + "。任务保留，条件满足后继续。")));
        lastWorkNotice = safeReason; nextWorkNotice = now + 200;
    }
    public RobotCollectionTask collectionTask() { return collectionTask; }
    public com.ic2newera.robot.building.RobotBuildTask buildTask() { return buildTask; }
    public com.ic2newera.robot.building.RobotLineRefitTask lineRefitTask() { return lineRefitTask; }
    public boolean industrialRecoveryBlocked() { return industrialRecoveryBlocked; }
    public com.ic2newera.robot.building.RobotIndustrialGoal industrialGoal() { return industrialGoal; }
    public void beginLineRefit(com.ic2newera.robot.building.RobotLineRefitTask task) {
        if (level().isClientSide || task == null || buildTask == null) return;
        lineRefitTask = task;
        setMode(REFITTING);
        workStatus("生产线改造已启动；原生产记录保留");
    }
    public void queueLineRefit(com.ic2newera.robot.building.RobotLineRefitTask task) {
        if (level().isClientSide || task == null || buildTask == null) return;
        lineRefitTask = task;
        if (industrialGoal == null) { setMode(STAY); workStatus("改产暂停：工业加工记录不可用，恢复任务保留"); return; }
        if (mode() == PRODUCTION || mode() == STAY) {
            industrialGoal.requestPauseAfterCycle();
            if (mode() == STAY && industrialGoal.pauseAfterCyclePending()) setMode(PRODUCTION);
            else if (mode() == STAY) activatePendingLineRefit();
        } else activatePendingLineRefit();
    }
    public void activatePendingLineRefit() {
        if (level().isClientSide || lineRefitTask == null || lineRefitTask.kind() != com.ic2newera.robot.building.RobotLineRefitTask.REPLAN
                || !(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        if (industrialGoal == null || !industrialGoal.replanSafeBoundary(server)) {
            setMode(STAY); workStatus("改产已排队；周转箱、机器或加工暂存仍有物品，移出并确认后会继续，所有物品保留"); return;
        }
        setMode(REFITTING); workStatus("已到生产周期边界，开始回收入料箱中的旧原料");
    }
    public void finishLineRefit() {
        if (level().isClientSide || lineRefitTask == null) return;
        var refit = lineRefitTask;
        if (refit.kind() == com.ic2newera.robot.building.RobotLineRefitTask.CABLE) {
            var metadata = buildTask.plan().metadata();
            metadata.putString("PowerCable", refit.targetPlan().metadata().getString("PowerCable"));
            var oldPlan = buildTask.plan();
            var cableParts = new java.util.HashMap<BlockPos, com.ic2newera.robot.building.RobotBuildPlan.Part>();
            for (var part : refit.targetPlan().parts()) if (part.phase().equals("power_cable")) cableParts.put(part.offset(), part);
            var parts = oldPlan.parts().stream().map(part -> part.phase().equals("power_cable") ? cableParts.get(part.offset()) : part).toList();
            var updated = new com.ic2newera.robot.building.RobotBuildPlan(oldPlan.kind(), oldPlan.title(), parts, refit.targetPlan().notes(), metadata);
            buildTask = new com.ic2newera.robot.building.RobotBuildTask(updated, buildTask.owner(), buildTask.dimension(), buildTask.origin());
            for (int i = 0; i < updated.parts().size(); i++) buildTask.markDone(i);
            if (industrialGoal != null) industrialGoal.updatePowerCable(metadata.getString("PowerCable"));
            lineRefitTask = null;
            setMode(refit.resumeMode());
            workStatus("电缆已更换；工业加工进度、已备物料和燃料记录原样保留");
        } else if (refit.kind() == com.ic2newera.robot.building.RobotLineRefitTask.REPLAN
                && level() instanceof net.minecraft.server.level.ServerLevel server
                && refit.inputsMoved() && industrialGoal != null && industrialGoal.replanSafeBoundary(server)) {
            var replacement = new com.ic2newera.robot.building.RobotBuildTask(refit.targetPlan(), refit.owner(), refit.dimension(), refit.origin());
            for (int i = 0; i < refit.targetPlan().parts().size(); i++) {
                var part = refit.targetPlan().parts().get(i);
                BlockPos pos = refit.origin().offset(part.offset());
                if (!server.hasChunkAt(pos)) { workStatus("改产切换暂停：目标部件区块未加载，进度与旧线记录保留"); return; }
                var actual = server.getBlockState(pos);
                boolean matches = actual.getBlock() == part.state().getBlock();
                if (matches) for (var entry : part.properties().entrySet()) {
                    var property = part.state().getBlock().getStateDefinition().getProperty(entry.getKey());
                    if (property == null || !actual.hasProperty(property)
                            || !java.util.Objects.equals(actual.getValue(property), part.state().getValue(property))) matches = false;
                }
                if (matches) replacement.markDone(i);
                else if (!actual.isAir()) { workStatus("改产切换暂停：新部件位置已有其他方块 " + pos.toShortString() + "；旧线与进料恢复记录保留"); return; }
            }
            industrialGoal.resetForReplan();
            buildTask = replacement;
            lineRefitTask = null;
            setMode(BUILDING);
            workStatus("旧进料已收入成品箱；改产设备与新电缆开始施工，旧机器保留。新目标原料请放入进料箱");
        }
    }
    public void pauseLineRefit() { if (!level().isClientSide) { setMode(STAY); workStatus("改造已暂停，恢复记录保留；完成或修复前生产保持暂停"); } }
    public void beginBuild(com.ic2newera.robot.building.RobotBuildTask task) {
        if (level().isClientSide || task == null || industrialRecoveryBlocked || !invalidBuildTaskRecord.isEmpty()) return;
        if (productionGoal != null) productionGoal.reset();
        if (industrialGoal != null) industrialGoal.reset();
        buildTask = task; setMode(BUILDING); workStatus("施工准备：" + task.plan().title());
    }
    public void completeBuild() {
        if (level().isClientSide || buildTask == null) return;
        if (buildTask.projectId() != null) {
            var shared = com.ic2newera.robot.building.RobotBuildTeams.taskFor(this, buildTask);
            if (shared != null && shared.completed())
                com.ic2newera.robot.building.RobotBuildTeams.completeMember(this, shared);
            return;
        }
        if (!buildTask.completed()) return;
        if (buildTask.plan().kind().equals("production")) {
            setMode(PRODUCTION); workStatus("设备搭建完成，检查供能、水洗与物流；尚未确认试产");
        } else { setMode(STAY); workStatus("房屋施工完成：" + buildTask.plan().title()); }
    }
    /** Keeps a loaded team's shared plan while stopping only this entity's active goal. */
    public void setTeamBuildState(int mode, String status) {
        if (level().isClientSide) return;
        getNavigation().stop(); setWorking(false); setWorkTaskActive(false); activeThisTick = false;
        setMode(mode);
        workStatus(status == null ? "团队施工状态已更新" : status);
    }
    public void clearTeamBuild(String status) {
        if (level().isClientSide) return;
        if (buildTask != null && buildTask.projectId() != null) buildTask = null;
        lineRefitTask = null; getNavigation().stop(); setWorking(false); setWorkTaskActive(false);
        activeThisTick = false; setMode(STAY);
        workStatus(status == null ? "团队施工已停止" : status);
    }
    public void finishTeamBuild(boolean productionLeader, String title) {
        if (level().isClientSide) return;
        getNavigation().stop(); setWorking(false); setWorkTaskActive(false); activeThisTick = false;
        setMode(productionLeader ? PRODUCTION : STAY);
        workStatus(productionLeader ? "团队生产线施工完成，领队进入生产协作待命：" + title
                : "团队施工完成：" + title + "；此成员保持待命");
    }
    public void syncTeamBuildTask(com.ic2newera.robot.building.RobotBuildTask authority) {
        if (level().isClientSide || authority == null || buildTask == null
                || !java.util.Objects.equals(buildTask.projectId(), authority.projectId())) return;
        buildTask = authority.withoutProject();
    }
    public void restoreTeamBuildTask(com.ic2newera.robot.building.RobotBuildTask authority, boolean active) {
        if (level().isClientSide || authority == null || industrialRecoveryBlocked || !invalidBuildTaskRecord.isEmpty()) return;
        if (productionGoal != null) productionGoal.reset();
        if (industrialGoal != null) industrialGoal.reset();
        buildTask = authority.forProject(authority.projectId(), authority.leaderId());
        setMode(active ? BUILDING : STAY);
        workStatus(active ? "团队施工恢复：" + authority.plan().title() : "团队施工记录已恢复；等待团队继续");
    }
    public void pauseBuild() {
        if (level().isClientSide) return;
        if (buildTask != null && buildTask.projectId() != null
                && com.ic2newera.robot.building.RobotBuildTeams.pause(this)) return;
        setMode(STAY); workStatus("施工/生产已暂停，任务记录保留");
    }
    public void resumeBuild() {
        if (buildTask != null && buildTask.projectId() != null) {
            if (!level().isClientSide && com.ic2newera.robot.building.RobotBuildTeams.resume(this)) return;
            return;
        }
        if (level().isClientSide || buildTask == null && lineRefitTask == null
                && (industrialGoal == null || !industrialGoal.hasSavedState()) && !industrialRecoveryBlocked) return;
        if (industrialRecoveryBlocked) { setMode(STAY); workStatus("工业恢复记录损坏，已锁定恢复；原记录和实物保留，请先核对修复"); return; }
        if (lineRefitTask != null && lineRefitTask.kind() == com.ic2newera.robot.building.RobotLineRefitTask.REPLAN
                && industrialGoal != null && industrialGoal.pauseAfterCyclePending()) setMode(PRODUCTION);
        else if (lineRefitTask != null) setMode(REFITTING);
        else if (buildTask == null && industrialGoal != null && industrialGoal.hasSavedState()) setMode(PRODUCTION);
        else setMode(buildTask.completed() && buildTask.plan().kind().equals("production") ? PRODUCTION : BUILDING);
        workStatus("继续核对施工/生产任务");
    }
    public void cancelBuild() {
        if (level().isClientSide) return;
        if (buildTask != null && buildTask.projectId() != null) {
            if (!com.ic2newera.robot.building.RobotBuildTeams.cancel(this))
                workStatus("团队施工无法取消；请由项目成员核对当前状态");
            return;
        }
        if (industrialRecoveryBlocked) { setMode(STAY); workStatus("工业恢复记录损坏，保留现场和原记录，不能通过取消绕过恢复检查"); return; }
        if (lineRefitTask != null) { setMode(STAY); workStatus("改造已部分执行，安全恢复记录保留；请继续改造或修复现场，不能取消记录后恢复生产"); return; }
        setMode(STAY); buildTask = null;
        if (productionGoal != null) productionGoal.reset();
        if (industrialGoal != null) industrialGoal.reset();
        workStatus("建造/生产任务已取消，已放方块与机器物料保留");
    }
    public com.ic2newera.robot.cooking.RobotCookingState cookingState() { return cookingState; }
    public com.ic2newera.robot.cooking.RobotCookingOrder cookingOrder() { return cookingOrder; }
    public boolean hasCookingTask() { return cookingState != null || cookingOrder != null; }
    public boolean hasSavedSmeltingOrder() {
        return smeltingGoal != null && smeltingGoal.save().getBoolean("InputBudgetInitialized");
    }
    public boolean hasInvalidBuildTaskRecord() { return !invalidBuildTaskRecord.isEmpty(); }
    public boolean resumeCookingTask() {
        if (level().isClientSide || !hasCookingTask() || industrialRecoveryBlocked || lineRefitTask != null) return false;
        movementIntent = RobotMovementIntent.NONE; movementTarget = null; movementDimension = "";
        getNavigation().stop(); entityData.set(MODE, COOKING); entityData.set(WORKING, false);
        workTaskActive = false; activeThisTick = false; setOrderedToSit(false);
        workStatus = cookingOrder != null ? "继续烹饪：" + cookingOrder.summary() : "继续已保存的烹饪";
        return true;
    }
    public boolean assignCookingOrder(com.ic2newera.robot.cooking.RobotCookingOrder order) {
        if (level().isClientSide || order == null || !order.owner().equals(getOwnerUUID())
                || cookingState != null || cookingOrder != null || order.complete()) return false;
        setMode(COOKING);
        cookingOrder = order;
        workStatus("指定烹饪：" + order.summary());
        return true;
    }
    /** Called only after a complete native batch has actually entered cargo. */
    public void recordCookingCount(int count) {
        if (level().isClientSide || cookingOrder == null || count <= 0) return;
        cookingOrder = cookingOrder.advanced(count);
        if (!cookingOrder.complete()) { workStatus("烹饪进度：" + cookingOrder.summary()); return; }
        String completed = cookingOrder.summary();
        cookingOrder = null;
        setMode(STAY);
        workStatus("烹饪完成：" + completed);
        if (level() instanceof ServerLevel server && getOwnerUUID() != null) {
            ServerPlayer owner = server.getServer().getPlayerList().getPlayer(getOwnerUUID());
            if (owner != null) owner.sendSystemMessage(Component.literal(localizeOwnerNotice(character().chineseName + "：烹饪完成，" + completed + "；整批菜品均已收入货物槽。")));
        }
    }
    public boolean beginCooking(com.ic2newera.robot.cooking.RobotCookingState state) {
        if (level().isClientSide || state == null || cookingState != null) return false;
        cookingState = state; return true;
    }
    public void updateCooking(com.ic2newera.robot.cooking.RobotCookingState state) {
        if (!level().isClientSide && state != null) cookingState = state;
    }
    public void clearCooking(String reason) { if (!level().isClientSide) { cookingState = null; workStatus(reason); } }
    public boolean cancelCooking() {
        if (level().isClientSide || !com.ic2newera.robot.cooking.RobotCookingGoal.cancelAndReturnEscrow(this)) return false;
        cookingOrder = null;
        setMode(STAY);
        workStatus("烹饪已取消，未投入原料已退回，锅内物料保留在原锅");
        return true;
    }
    public String collectionSummary() { return collectionTask == null ? "" : collectionTask.summary(); }
    public boolean resumePausedCollection() {
        if (level().isClientSide || pausedCollectionTask == null || collectionTask != null || movementBusy()) return false;
        stopMovement();
        collectionTask = pausedCollectionTask; pausedCollectionTask = null; workStatus = "等待采集：" + collectionTask.summary();
        getNavigation().stop(); return true;
    }
    public RobotCollectionTask.Result assignCollection(ServerPlayer owner, String itemId, int amount, int kind) {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel serverLevel) || owner == null
                || serverLevel.getServer() != owner.server || !isAlive() || !isOwnedBy(owner))
            return new RobotCollectionTask.Result(false, "只有机器人的所有者可以派发采集任务。");
        if (amount < 1 || amount > 256) return new RobotCollectionTask.Result(false, "采集数量必须为 1～256。");
        if (kind < RobotCollectionTask.AUTO || kind > RobotCollectionTask.PICKUP_ONLY)
            return new RobotCollectionTask.Result(false, "采集模式无效（0 按掉落物自动采集，1 仅拾取）。");
        ResourceLocation id = ResourceLocation.tryParse(itemId == null ? "" : itemId.trim());
        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) return new RobotCollectionTask.Result(false, "目标物品 ID 无效。");
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (item == net.minecraft.world.item.Items.AIR) return new RobotCollectionTask.Result(false, "不能把空气设为采集目标。");
        if (collectionTask != null && collectionTask.matches(new ItemStack(item))
                && collectionTask.requested() == amount && collectionTask.kind() == kind)
            return new RobotCollectionTask.Result(false, "相同采集任务已在进行：" + collectionTask.summary());
        if (pausedCollectionTask != null && pausedCollectionTask.matches(new ItemStack(item))
                && pausedCollectionTask.requested() == amount && pausedCollectionTask.kind() == kind)
            return new RobotCollectionTask.Result(false, "相同采集任务已暂停：" + pausedCollectionTask.summary());
        setMode(STAY);
        // A bounded order must not silently resume indiscriminate slaughter after its final pickup.
        setCombatEnabled(false);
        pausedCollectionTask = null; collectionTask = new RobotCollectionTask(item, amount, kind);
        workOrigin = blockPosition();
        workStatus = "等待采集：" + collectionTask.summary();
        return new RobotCollectionTask.Result(true, "已派发采集任务：" + collectionTask.summary());
    }
    public boolean recordCollectionCount(int count) {
        if (collectionTask == null || count <= 0) return false;
        collectionTask.record(count);
        if (collectionTask.complete()) {
            String completed = collectionTask.target().getHoverName().getString() + "×" + collectionTask.requested();
            collectionTask = null; setMode(STAY); workStatus = "指定采集已完成";
            if (level() instanceof ServerLevel server && getOwnerUUID() != null) {
                ServerPlayer owner = server.getServer().getPlayerList().getPlayer(getOwnerUUID());
                if (owner != null) owner.sendSystemMessage(Component.literal(localizeOwnerNotice(character().chineseName + "：已收集 " + completed + "，物品在我的货物槽里。")));
            }
            return true;
        }
        workStatus = "采集进度：" + collectionTask.summary();
        return false;
    }
    public void cancelCollection(String reason) {
        if (collectionTask == null && pausedCollectionTask == null) return;
        collectionTask = null; pausedCollectionTask = null; setMode(STAY); workStatus = reason == null || reason.isBlank() ? "指定采集已取消" : reason;
    }
    public void stay() { stopMovement(); }
    public void follow() { if (!movementBusy() && powered()) beginMovement(RobotMovementIntent.FOLLOW, level().dimension().location().toString(), null); }
    public void setMode(int mode) {
        if (mode < STAY || mode > REFITTING) return;
        if (mode == FOLLOW) {
            movementIntent = RobotMovementIntent.FOLLOW; movementTarget = null;
            movementDimension = level().dimension().location().toString(); getNavigation().stop();
        } else if (mode > FOLLOW || mode == STAY && mode() == FOLLOW) stopMovement();
        // A paused collection remains a real saved task until explicitly resumed or cancelled.
        if (!level().isClientSide && mode == SMELTING && smeltingGoal != null) smeltingGoal.beginOrder();
        if (mode != mode() || !workOrigin.equals(blockPosition())) homeGreeting.reset();
        collectionTask = null;
        entityData.set(MODE, mode); setOrderedToSit(false); getNavigation().stop(); setWorking(false);
        workOrigin = blockPosition(); workStatus = "等待任务";
        clearWorkAlert();
        lastWorkNotice = ""; nextWorkNotice = 0;
    }
    public boolean canManage(ServerPlayer player) { return isAlive() && isOwnedBy(player) && distanceToSqr(player) <= 64; }
    public boolean allowChat(ServerPlayer player) {
        if (level().getGameTime() < nextChat || !spendEnergy(2000)) return false;
        nextChat = level().getGameTime() + 40; return true;
    }
    private boolean mayAttack(LivingEntity target) {
        if (!powered() || target == null || target == this || !target.isAlive() || target.level() != level()
                || target instanceof Player || target instanceof RobotEntity || isAlliedTo(target)
                || distanceToSqr(target) > 16 * 16 || !level().hasChunkAt(target.blockPosition())) return false;
        if (target instanceof OwnableEntity owned && owned.getOwnerUUID() != null) return false;
        if (target instanceof TamableAnimal pet && pet.isTame()) return false;
        if (target instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse && horse.isTamed()) return false;
        // A specific order takes priority over broad slaughter; eggs never authorize killing chickens.
        if (collectionTask != null) {
            if (RobotMobCollection.mayAttack(this, target)) return true;
            if (RobotMobCollection.isSource(collectionTask, target)) return false;
            // Do not resume killing a matching monster while its real drop is waiting for pickup.
            return target instanceof Enemy && target instanceof Mob mob
                    && (mob.getTarget() == this || getOwner() != null && mob.getTarget() == getOwner());
        }
        if (combatEnabled()) return target instanceof Animal || target instanceof Enemy;
        if (!(target instanceof Enemy)) return false;
        return !(target instanceof NeutralMob neutral) || neutral.isAngryAt(this)
                || getOwner() != null && neutral.isAngryAt(getOwner());
    }
    @Override public boolean doHurtTarget(Entity target) {
        if (!(target instanceof LivingEntity living) || !mayAttack(living) || !hasLineOfSight(living)) return false;
        activeThisTick = true;
        swing(InteractionHand.MAIN_HAND);
        boolean hit = super.doHurtTarget(target);
        if (hit && !getMainHandItem().isEmpty()) getMainHandItem().getItem().hurtEnemy(getMainHandItem(), living, this);
        return hit;
    }
    @Override protected void hurtArmor(DamageSource source, float amount) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = getItemBySlot(slot);
            if (!armor.isEmpty() && (!source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || !armor.getItem().isFireResistant()))
                armor.hurtAndBreak(Math.max(1, (int) amount / 4), this, owner -> owner.broadcastBreakEvent(slot));
        }
    }
    @Override public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!level().isClientSide && buildTask != null && buildTask.projectId() != null)
            com.ic2newera.robot.building.RobotBuildTeams.reconcile(this);
    }
    @Override public void remove(Entity.RemovalReason reason) {
        if (!level().isClientSide) com.ic2newera.robot.building.RobotBuildTeams.releaseAllClaims(this);
        super.remove(reason);
    }
    @Override public void tick() {
        // Recharge before AI checks power, so a discharged robot can resume without battery removal.
        if (!level().isClientSide && isAlive()) {
            ensureHomeAnchor();
            inventory.tickPowerBanks();
            activeThisTick = workTaskActive || isWorking()
                    || mayAttack(getTarget());
        }
        super.tick();
        if (level().isClientSide || !isAlive()) return;
        if (buildTask != null && buildTask.projectId() != null)
            com.ic2newera.robot.building.RobotBuildTeams.reconcile(this);
        if (getTarget() != null && !mayAttack(getTarget())) { setTarget(null); getNavigation().stop(); }
        tickHunger();
        if (entityData.get(EATING) && level().getGameTime() - entityData.get(EATING_START_TIME) >= EATING_DURATION_TICKS) {
            entityData.set(EATING, false);
            entityData.set(EATING_STACK, ItemStack.EMPTY);
        }
        if (powered()) {
            boolean active = activeThisTick || workTaskActive || isWorking() || getTarget() != null
                    || getDeltaMovement().horizontalDistanceSqr() > 0.0001;
            // Integer EU with a persisted fractional carry: one full battery lasts 24 h / 6 h at 20 TPS.
            long amount = energyRemainder + (long) RobotBatteryItem.CAPACITY * (active ? ACTIVE_DRAIN_MULTIPLIER : 1);
            int cost = (int) (amount / STANDBY_TICKS);
            energyRemainder = amount % STANDBY_TICKS;
            spendEnergy(Math.min(cost, energy()));
        }
        if (!powered()) {
            getNavigation().stop(); setTarget(null); setWorking(false); setSpeed(0);
        }
        boolean combatActive = getTarget() != null || swinging;
        boolean hurtActive = hurtTime > 0;
        entityData.set(COMBAT_ACTIVE, combatActive);
        entityData.set(HURT_ACTIVE, hurtActive);
        entityData.set(WORK_ACTIVITY, activeThisTick || workTaskActive || isWorking());
        entityData.set(POWERED, powered());
        companionship.tick();
        if (combatActive || hurtActive || !powered()
                || entityData.get(FACIAL_EXPRESSION) != RobotExpression.NEUTRAL.id()
                && entityData.get(EXPRESSION_UNTIL) <= level().getGameTime()) clearFacialExpression();
        if (tickCount % 10 == 0) { inventory.syncEquipment(); entityData.set(ENERGY, energy()); }
        if (tickCount % 20 == 0) homeGreeting.tick(this);
    }

    private void tickHunger() {
        boolean active = activeThisTick || workTaskActive || isWorking() || getTarget() != null
                || getDeltaMovement().horizontalDistanceSqr() > 0.0001;
        hungerActiveTicks += active ? 4 : 1;
        if (hunger > 0) {
            hungerStarvationTicks = 0;
            if (hungerActiveTicks >= ACTIVE_TICKS_PER_HUNGER) {
                hunger--;
                hungerActiveTicks -= ACTIVE_TICKS_PER_HUNGER;
                entityData.set(HUNGER, hunger);
            }
        } else {
            hungerActiveTicks = 0;
            if (++hungerStarvationTicks >= 80) {
                hungerStarvationTicks = 0;
                hurt(damageSources().starve(), 1.0F);
            }
        }
    }
    public ItemStack asDoll() {
        inventory.syncEquipment();
        ItemStack stack = new ItemStack(RobotRegistry.DOLLS.get(character()).get());
        stack.getOrCreateTag().putFloat("RobotHealth", Math.max(1, getHealth()));
        stack.getOrCreateTag().putInt("RobotHealthVersion", HEALTH_SCHEMA);
        CompoundTag state = new CompoundTag(); state.putInt("Deaths", deaths()); state.put("Inventory", inventory.serializeNBT());
        state.putInt("Hunger", hunger()); state.putInt("HungerActiveTicks", hungerActiveTicks);
        state.putInt("HungerStarvationTicks", hungerStarvationTicks);
        state.putLong("EnergyRemainder", energyRemainder);
        state.putBoolean("Slaughter", combatEnabled());
        state.putLong("WorkOrigin", workOrigin.asLong());
        state.putLong("HomePos", homePos().asLong()); state.putString("HomeDimension", homeDimension());
        state.putBoolean("HomeInitialized", homeInitialized);
        state.putBoolean("NewEraWormholeModule", getPersistentData().getBoolean("NewEraWormholeModule"));
        state.putUUID("RobotSourceUUID", getUUID());
        if (getOwnerUUID() != null) state.putUUID("Owner", getOwnerUUID());
        if (buildTask != null) state.put("BuildTask", buildTask.save());
        else if (!invalidBuildTaskRecord.isEmpty()) state.put("InvalidBuildTask", invalidBuildTaskRecord.copy());
        if (industrialGoal != null) {
            CompoundTag industrial = industrialGoal.save();
            if (!industrial.isEmpty()) state.put("IndustrialTask", industrial);
        }
        if (lineRefitTask != null) state.put("LineRefitTask", lineRefitTask.save());
        else if (!invalidLineRefitRecord.isEmpty()) state.put("LineRefitTask", invalidLineRefitRecord.copy());
        if (industrialRecoveryBlocked) state.putBoolean("IndustrialRecoveryBlocked", true);
        if (collectionTask != null) state.put("CollectionTask", collectionTask.save());
        if (pausedCollectionTask != null) state.put("PausedCollectionTask", pausedCollectionTask.save());
        if (cookingState != null) state.put("CookingTask", cookingState.save());
        if (cookingOrder != null) state.put("CookingOrder", cookingOrder.save());
        state.put("Companionship", companionship.saveForDoll());
        stack.getOrCreateTag().put("RobotState", state);
        if (hasCustomName()) stack.setHoverName(getCustomName());
        return stack;
    }
    public void restoreDoll(ItemStack doll) {
        if (!doll.hasTag()) return;
        CompoundTag tag = doll.getTag(), state = tag.getCompound("RobotState");
        entityData.set(DEATHS, Math.max(0, Math.min(MAX_DEATHS, state.getInt("Deaths"))));
        if (state.hasUUID("Owner")) setOwnerUUID(state.getUUID("Owner"));
        companionship.loadFromDoll(state.getCompound("Companionship"));
        movementIntent = RobotMovementIntent.NONE; movementTarget = null; movementDimension = "";
        energyRemainder = Math.max(0, state.getLong("EnergyRemainder")) % STANDBY_TICKS;
        hunger = state.contains("Hunger") ? Mth.clamp(state.getInt("Hunger"), 0, MAX_HUNGER) : MAX_HUNGER;
        hungerActiveTicks = Mth.clamp(state.getInt("HungerActiveTicks"), 0, ACTIVE_TICKS_PER_HUNGER - 1);
        hungerStarvationTicks = Mth.clamp(state.getInt("HungerStarvationTicks"), 0, 79);
        if (hunger > 0) hungerStarvationTicks = 0;
        entityData.set(HUNGER, hunger);
        if (state.contains("Inventory")) { inventory.deserializeNBT(state.getCompound("Inventory")); inventory.applyEquipment(); }
        setMode(STAY);
        if (state.getBoolean("HomeInitialized") && state.contains("HomePos")) {
            homePos = BlockPos.of(state.getLong("HomePos")); homeDimension = state.getString("HomeDimension"); homeInitialized = !homeDimension.isBlank();
        } else { homePos = blockPosition().immutable(); homeDimension = level().dimension().location().toString(); homeInitialized = true; }
        getPersistentData().putBoolean("NewEraWormholeModule", state.getBoolean("NewEraWormholeModule"));
        setCombatEnabled(state.getBoolean("Slaughter"));
        // A redeployed doll resumes its remaining amount around the new deployment position.
        workOrigin = blockPosition();
        boolean invalidSavedTask = false;
        collectionTask = null;
        pausedCollectionTask = null;
        if (state.contains("CollectionTask")) try { collectionTask = RobotCollectionTask.load(state.getCompound("CollectionTask")); }
        catch (RuntimeException invalid) { invalidSavedTask = true; }
        if (state.contains("PausedCollectionTask")) try { pausedCollectionTask = RobotCollectionTask.load(state.getCompound("PausedCollectionTask")); }
        catch (RuntimeException invalid) { invalidSavedTask = true; }
        cookingState = null;
        if (state.contains("CookingTask")) try { cookingState = com.ic2newera.robot.cooking.RobotCookingState.load(state.getCompound("CookingTask")); }
        catch (RuntimeException invalid) { invalidSavedTask = true; }
        cookingOrder = null;
        if (state.contains("CookingOrder")) try {
            cookingOrder = com.ic2newera.robot.cooking.RobotCookingOrder.load(state.getCompound("CookingOrder"));
            if (!cookingOrder.owner().equals(getOwnerUUID())) { cookingOrder = null; invalidSavedTask = true; }
        } catch (RuntimeException invalid) { invalidSavedTask = true; }
        buildTask = null; lineRefitTask = null; invalidBuildTaskRecord = new CompoundTag();
        industrialRecoveryBlocked = state.getBoolean("IndustrialRecoveryBlocked");
        if (state.contains("InvalidBuildTask", 10)) {
            invalidBuildTaskRecord = state.getCompound("InvalidBuildTask").copy();
            industrialRecoveryBlocked = true; invalidSavedTask = true;
        }
        invalidLineRefitRecord = new CompoundTag();
        if (industrialGoal != null) industrialGoal.reset();
        if (productionGoal != null) productionGoal.reset();
        if (state.contains("BuildTask")) try {
            CompoundTag saved = boundedDollTask(state, "BuildTask", 2_000_000);
            buildTask = com.ic2newera.robot.building.RobotBuildTask.load(saved);
        } catch (RuntimeException invalid) {
            invalidBuildTaskRecord = state.getCompound("BuildTask").copy();
            invalidSavedTask = true; industrialRecoveryBlocked = true;
        }
        if (state.contains("IndustrialTask") && industrialGoal != null) try {
            industrialGoal.load(boundedDollTask(state, "IndustrialTask", 1_000_000));
        } catch (RuntimeException invalid) { industrialGoal.reset(); invalidSavedTask = true; industrialRecoveryBlocked = true; }
        if (state.contains("LineRefitTask")) try {
            lineRefitTask = com.ic2newera.robot.building.RobotLineRefitTask.load(boundedDollTask(state, "LineRefitTask", 4_000_000));
        } catch (RuntimeException invalid) {
            invalidSavedTask = true; industrialRecoveryBlocked = true;
            invalidLineRefitRecord = state.getCompound("LineRefitTask").copy();
        }
        if (collectionTask != null) workStatus = "等待采集：" + collectionTask.summary();
        else if (pausedCollectionTask != null) workStatus = "采集已暂停：" + pausedCollectionTask.summary();
        if (buildTask != null || lineRefitTask != null || industrialGoal != null && industrialGoal.hasSavedState())
            workStatus = invalidSavedTask
                    ? "娃娃已部署待命；部分任务记录无效，货物已保留，请在原主人、维度与场站核对后处理"
                    : "娃娃已部署待命；施工、生产或改造记录保留在原主人、维度与场站，核对后可继续";
        else if (invalidSavedTask) workStatus = "娃娃已部署待命；任务记录无效，角色货物已保留";
        if (!(inventory.getStackInSlot(0).getItem() instanceof RobotBatteryItem)) inventory.setStackInSlot(0, ItemStack.EMPTY);
        float health = tag.contains("RobotHealth") ? tag.getFloat("RobotHealth") : getMaxHealth();
        if (Float.isFinite(health)) {
            if (!tag.contains("RobotHealthVersion") && tag.contains("RobotHealth"))
                health *= getMaxHealth() / 20.0F;
            setHealth(Mth.clamp(health, 0, getMaxHealth()));
        }
        tag.putInt("RobotHealthVersion", HEALTH_SCHEMA);
        entityData.set(ENERGY, energy());
        if (buildTask != null && buildTask.projectId() != null && state.hasUUID("RobotSourceUUID"))
            com.ic2newera.robot.building.RobotBuildTeams.restoreMember(this, state.getUUID("RobotSourceUUID"));
    }

    private static CompoundTag boundedDollTask(CompoundTag state, String key, int maxChars) {
        CompoundTag saved = state.getCompound(key);
        if (saved.toString().length() > maxChars) throw new IllegalArgumentException("娃娃任务记录过大");
        return saved;
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
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (canManage(serverPlayer)) {
                ItemStack offered = player.getItemInHand(hand);
                net.minecraft.world.food.FoodProperties food = offered.getFoodProperties(this);
                if (food != null && food.getNutrition() > 0 && hunger < MAX_HUNGER) {
                    ItemStack serving = offered.copyWithCount(1);
                    if (!player.getAbilities().instabuild) {
                        ItemStack remainder = offered.getCraftingRemainingItem();
                        // Vanilla soup/honey return their container in finishUsingItem,
                        // rather than declaring a crafting remainder. Feeding is instant
                        // here, so preserve those containers explicitly as well.
                        if (remainder.isEmpty() && offered.getItem() instanceof net.minecraft.world.item.BowlFoodItem)
                            remainder = new ItemStack(net.minecraft.world.item.Items.BOWL);
                        if (remainder.isEmpty() && offered.getItem() instanceof net.minecraft.world.item.HoneyBottleItem)
                            remainder = new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE);
                        offered.shrink(1);
                        player.getInventory().setChanged();
                        if (!remainder.isEmpty() && !player.getInventory().add(remainder))
                            player.drop(remainder, false);
                    }
                    hunger = Mth.clamp(hunger + food.getNutrition() * 10, 0, MAX_HUNGER);
                    hungerActiveTicks = 0;
                    hungerStarvationTicks = 0;
                    entityData.set(HUNGER, hunger);
                    entityData.set(EATING_STACK, serving);
                    HumanoidArm mainArm = getMainArm();
                    ItemStack mainHand = getMainHandItem();
                    ItemStack offHand = getOffhandItem();
                    HumanoidArm servingArm = mainHand.isEmpty() ? mainArm
                            : offHand.isEmpty() ? (mainArm == HumanoidArm.LEFT ? HumanoidArm.RIGHT : HumanoidArm.LEFT)
                            : mainArm;
                    entityData.set(EATING_ARM, servingArm == HumanoidArm.LEFT ? 0 : 1);
                    entityData.set(EATING_START_TIME, level().getGameTime());
                    entityData.set(EATING, true);
                    return InteractionResult.CONSUME;
                }
                smileAtOwner();
                RobotPackets.open(serverPlayer, this, 0);
            }
            else player.displayClientMessage(Component.literal("只有放置这台机器人的玩家可以操作。"), true);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }
    @Override protected Component getTypeName() { return character().title(); }
    @Override public boolean isFood(ItemStack stack) { return false; }
    @Override public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) { return null; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
    // Suppress Mob's random equipment drops: these same items live in the recovered doll.
    @Override protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) { }
    @Override protected void dropEquipment() {
        if (level().isClientSide || deathHandled) return;
        deathHandled = true; inventory.syncEquipment(); inventory.setStackInSlot(0, ItemStack.EMPTY);
        entityData.set(DEATHS, deaths() + 1);
        for (int i = 1; i < inventory.getSlots(); i++)
            if (EnchantmentHelper.hasVanishingCurse(inventory.getStackInSlot(i))) inventory.setStackInSlot(i, ItemStack.EMPTY);
        if (deaths() < MAX_DEATHS) {
            ItemStack doll = asDoll(); doll.getOrCreateTag().putFloat("RobotHealth", getMaxHealth()); spawnAtLocation(doll);
        } else {
            if (getPersistentData().getBoolean("NewEraWormholeModule")) {
                getPersistentData().remove("NewEraWormholeModule");
                spawnAtLocation(new ItemStack(com.ic2newera.wormhole.WormholeContent.WORMHOLE_MODULE.get()));
            }
            for (int i = 1; i < inventory.getSlots(); i++) {
                ItemStack stack = inventory.getStackInSlot(i); if (!stack.isEmpty()) spawnAtLocation(stack.copy());
            }
            // On the final death no doll persists held cooking items. Drop only actual
            // escrow, never expected pot snapshots or an ambiguous in-flight record.
            if (cookingState != null) {
                for (ItemStack stack : cookingState.escrowSlots())
                    if (!stack.isEmpty()) spawnAtLocation(stack.copy());
                for (ItemStack stack : cookingState.extraEscrow())
                    if (!stack.isEmpty()) spawnAtLocation(stack.copy());
                cookingState = null;
            }
        }
        for (int i = 0; i < inventory.getSlots(); i++) inventory.setStackInSlot(i, ItemStack.EMPTY);
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag); inventory.syncEquipment();
        tag.putInt("RobotHealthSchema", HEALTH_SCHEMA);
        tag.putInt("RobotHunger", hunger); tag.putInt("RobotHungerActiveTicks", hungerActiveTicks);
        tag.putInt("RobotHungerStarvationTicks", hungerStarvationTicks);
        tag.putString("RobotCharacter", character().id); tag.putInt("RobotMode", mode());
        tag.putBoolean("RobotFollowing", following()); tag.putInt("RobotDeaths", deaths()); tag.putBoolean("RobotSlaughter", combatEnabled());
        tag.put("RobotInventory", inventory.serializeNBT()); tag.putLong("RobotWorkOrigin", workOrigin.asLong());
        tag.putLong("RobotHomePos", homePos().asLong()); tag.putString("RobotHomeDimension", homeDimension());
        tag.putBoolean("RobotHomeInitialized", homeInitialized);
        tag.putInt("RobotMovementIntent", movementIntent.ordinal());
        if (movementTarget != null) tag.putLong("RobotMovementTarget", movementTarget.asLong());
        tag.putString("RobotMovementDimension", movementDimension);
        tag.putLong("RobotEnergyRemainder", energyRemainder);
        tag.put("RobotHomeGreeting", homeGreeting.save());
        tag.put("RobotCompanionship", companionship.saveWorld());
        if (smeltingGoal != null) tag.put("RobotSmeltingTask", smeltingGoal.save());
        if (buildTask != null) tag.put("RobotBuildTask", buildTask.save());
        else if (!invalidBuildTaskRecord.isEmpty()) tag.put("RobotBuildTask", invalidBuildTaskRecord.copy());
        if (productionGoal != null) tag.put("RobotProductionTask", productionGoal.save());
        if (industrialGoal != null) tag.put("RobotIndustrialTask", industrialGoal.save());
        if (lineRefitTask != null) tag.put("RobotLineRefitTask", lineRefitTask.save());
        else if (!invalidLineRefitRecord.isEmpty()) tag.put("RobotLineRefitTask", invalidLineRefitRecord.copy());
        if (industrialRecoveryBlocked) tag.putBoolean("RobotIndustrialRecoveryBlocked", true);
        if (collectionTask != null) tag.put("RobotCollectionTask", collectionTask.save());
        if (pausedCollectionTask != null) tag.put("RobotPausedCollectionTask", pausedCollectionTask.save());
        if (cookingState != null) tag.put("RobotCookingTask", cookingState.save());
        if (cookingOrder != null) tag.put("RobotCookingOrder", cookingOrder.save());
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag); setCharacter(RobotCharacter.byId(tag.getString("RobotCharacter")));
        if (tag.getInt("RobotHealthSchema") < HEALTH_SCHEMA) {
            // Legacy entity NBT includes an Attributes list whose max-health base
            // can still be 20 after changing the entity's default attributes.
            float legacyMaximum = getMaxHealth();
            float legacyHealth = getHealth();
            if (!tag.contains("Attributes") && Math.abs(legacyMaximum - 100.0F) < 0.001F) legacyMaximum = 20.0F;
            var maxHealthAttribute = getAttribute(Attributes.MAX_HEALTH);
            if (maxHealthAttribute != null) maxHealthAttribute.setBaseValue(100.0D);
            if (tag.contains("Health") && Float.isFinite(legacyHealth) && legacyMaximum > 0.0F)
                setHealth(Mth.clamp(legacyHealth / legacyMaximum * getMaxHealth(), 0.0F, getMaxHealth()));
        }
        hunger = tag.contains("RobotHunger") ? Mth.clamp(tag.getInt("RobotHunger"), 0, MAX_HUNGER) : MAX_HUNGER;
        hungerActiveTicks = Mth.clamp(tag.getInt("RobotHungerActiveTicks"), 0, ACTIVE_TICKS_PER_HUNGER - 1);
        hungerStarvationTicks = Mth.clamp(tag.getInt("RobotHungerStarvationTicks"), 0, 79);
        if (hunger > 0) hungerStarvationTicks = 0;
        entityData.set(HUNGER, hunger);
        companionship.loadWorld(tag.getCompound("RobotCompanionship"));
        setMode(tag.contains("RobotMode") ? tag.getInt("RobotMode") : tag.getBoolean("RobotFollowing") ? FOLLOW : STAY);
        homeInitialized = tag.getBoolean("RobotHomeInitialized") && tag.contains("RobotHomePos") && !tag.getString("RobotHomeDimension").isBlank();
        if (homeInitialized) { homePos = BlockPos.of(tag.getLong("RobotHomePos")); homeDimension = tag.getString("RobotHomeDimension"); }
        else { homePos = blockPosition().immutable(); homeDimension = level().dimension().location().toString(); homeInitialized = true; }
        movementIntent = RobotMovementIntent.NONE; movementTarget = null; movementDimension = "";
        // Persisted FOLLOW is restored as a normal walking order; one-shot destinations resume only when explicitly saved.
        if (tag.contains("RobotMovementIntent")) {
            int saved = tag.getInt("RobotMovementIntent");
            if (saved >= 0 && saved < RobotMovementIntent.values().length) movementIntent = RobotMovementIntent.values()[saved];
            movementDimension = tag.getString("RobotMovementDimension");
            if (tag.contains("RobotMovementTarget")) movementTarget = BlockPos.of(tag.getLong("RobotMovementTarget"));
        } else if (mode() == FOLLOW) { movementIntent = RobotMovementIntent.FOLLOW; movementDimension = level().dimension().location().toString(); }
        entityData.set(DEATHS, Math.max(0, Math.min(MAX_DEATHS, tag.getInt("RobotDeaths"))));
        // The old combat flag meant hostile-only defence; never migrate it into animal slaughter.
        setCombatEnabled(tag.getBoolean("RobotSlaughter"));
        energyRemainder = Math.max(0, tag.getLong("RobotEnergyRemainder")) % STANDBY_TICKS;
        if (tag.contains("RobotInventory")) { inventory.deserializeNBT(tag.getCompound("RobotInventory")); inventory.applyEquipment(); }
        workOrigin = tag.contains("RobotWorkOrigin") ? BlockPos.of(tag.getLong("RobotWorkOrigin")) : blockPosition();
        boolean invalidTaskRecord = false;
        collectionTask = null;
        pausedCollectionTask = null;
        if (tag.contains("RobotCollectionTask")) try { collectionTask = RobotCollectionTask.load(tag.getCompound("RobotCollectionTask")); }
        catch (RuntimeException invalid) { invalidTaskRecord = true; recordTaskLoadFailure("采集"); }
        if (tag.contains("RobotPausedCollectionTask")) try { pausedCollectionTask = RobotCollectionTask.load(tag.getCompound("RobotPausedCollectionTask")); }
        catch (RuntimeException invalid) { invalidTaskRecord = true; recordTaskLoadFailure("已暂停采集"); }
        cookingState = null;
        if (tag.contains("RobotCookingTask")) try { cookingState = com.ic2newera.robot.cooking.RobotCookingState.load(tag.getCompound("RobotCookingTask")); }
        catch (RuntimeException invalid) { invalidTaskRecord = true; recordTaskLoadFailure("烹饪"); }
        cookingOrder = null;
        if (tag.contains("RobotCookingOrder")) try {
            cookingOrder = com.ic2newera.robot.cooking.RobotCookingOrder.load(tag.getCompound("RobotCookingOrder"));
            if (!cookingOrder.owner().equals(getOwnerUUID())) { cookingOrder = null; invalidTaskRecord = true; recordTaskLoadFailure("指定烹饪归属"); }
        } catch (RuntimeException invalid) { invalidTaskRecord = true; recordTaskLoadFailure("指定烹饪"); }
        if (collectionTask != null) workStatus = "等待采集：" + collectionTask.summary();
        entityData.set(ENERGY, energy());
        homeGreeting.load(tag.getCompound("RobotHomeGreeting"));
        if (smeltingGoal != null) try { smeltingGoal.load(tag.getCompound("RobotSmeltingTask")); }
        catch (RuntimeException invalid) { invalidTaskRecord = true; recordTaskLoadFailure("烧矿"); }
        buildTask = null;
        invalidBuildTaskRecord = new CompoundTag();
        lineRefitTask = null;
        industrialRecoveryBlocked = tag.getBoolean("RobotIndustrialRecoveryBlocked");
        invalidLineRefitRecord = new CompoundTag();
        if (tag.contains("RobotBuildTask")) try { buildTask = com.ic2newera.robot.building.RobotBuildTask.load(tag.getCompound("RobotBuildTask")); }
        catch (RuntimeException invalid) {
            invalidBuildTaskRecord = tag.getCompound("RobotBuildTask").copy();
            invalidTaskRecord = true; industrialRecoveryBlocked = true; recordTaskLoadFailure("建造");
        }
        if (productionGoal != null) try { productionGoal.load(tag.getCompound("RobotProductionTask")); }
        catch (RuntimeException invalid) { productionGoal.reset(); invalidTaskRecord = true; recordTaskLoadFailure("旧生产"); }
        if (industrialGoal != null) try { industrialGoal.load(tag.getCompound("RobotIndustrialTask")); }
        catch (RuntimeException invalid) { industrialGoal.reset(); invalidTaskRecord = true; industrialRecoveryBlocked = true; recordTaskLoadFailure("工业生产"); }
        if (tag.contains("RobotLineRefitTask")) try { lineRefitTask = com.ic2newera.robot.building.RobotLineRefitTask.load(tag.getCompound("RobotLineRefitTask")); }
        catch (RuntimeException invalid) {
            invalidTaskRecord = true; industrialRecoveryBlocked = true;
            invalidLineRefitRecord = tag.getCompound("RobotLineRefitTask").copy();
            recordTaskLoadFailure("产线改造");
        }
        if (invalidTaskRecord || industrialRecoveryBlocked) {
            RobotCollectionTask validCollection = collectionTask;
            setMode(STAY); collectionTask = validCollection;
            workStatus = "部分任务记录无效，机器人已待命；角色货物、机器内物料和其他有效任务记录已保留";
        }
    }

    private void recordTaskLoadFailure(String taskName) {
        workStatus = taskName + "任务记录无效，已暂停；角色货物与其他任务记录保留";
    }

    private String localizeOwnerNotice(String message) {
        return com.ic2newera.robot.companionship.RobotReplyLocalization.localize(companionship.language(), message);
    }
}
