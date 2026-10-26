package com.ic2newera.robot.cooking;

import com.ic2newera.robot.RobotEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.common.util.FakePlayer;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Optional-mod-safe coordinator; Farmer's Delight API references stay in its adapter class. */
public final class RobotCookingGoal extends Goal {
    private record PotKey(MinecraftServer server, ResourceLocation dimension, BlockPos pos) { }
    private record RobotKey(MinecraftServer server, UUID uuid) { }
    private static final Map<PotKey, UUID> ACTIVE_POTS = new HashMap<>();
    private static final Map<RobotKey, PotKey> ROBOT_CLAIMS = new HashMap<>();
    private final RobotEntity robot;
    private long retryAt;
    private boolean active;
    private String pendingBackend = RobotCookingState.FARMERS_DELIGHT;
    private BlockPos pendingPot;
    private BlockPos pendingStand;

    public RobotCookingGoal(RobotEntity robot) {
        this.robot = robot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public boolean canUse() {
        if (robot.mode() != RobotEntity.COOKING || robot.getOwnerUUID() == null
                || !(robot.level() instanceof ServerLevel level) || robot.getTarget() != null) return false;
        if (level.getGameTime() < retryAt) return false;
        if (!robot.powered()) { block("烹饪暂停：工作电量不足"); return false; }
        if (!ModList.get().isLoaded("farmersdelight") && !ModList.get().isLoaded("kaleidoscope_cookery")) {
            block("未检测到 Farmer's Delight 或森罗物语；烹饪任务无法启动");
            return false;
        }
        RobotCookingOrder order = robot.cookingOrder();
        if (order != null && !order.owner().equals(robot.getOwnerUUID())) {
            block("指定菜品订单的所有者与机器人主人不一致；订单保留供检查");
            return false;
        }
        if (order != null && order.complete()) {
            robot.workStatus("指定菜品订单已达到目标数量；等待服务端完成收尾");
            return false;
        }
        RobotCookingState state = robot.cookingState();
        if (state != null && !backendLoaded(state)) {
            block("原烹饪任务所需模组未安装；任务与暂存物品保留，恢复对应模组后继续");
            return false;
        }
        if (order != null && !supportsTarget(level, order.target())) {
            block("当前支持的原生料理锅配方中不存在指定菜品；订单保留，未投入原料");
            return false;
        }
        if (state == null) {
            ResourceLocation target = order == null ? null : order.target();
            pendingBackend = RobotCookingState.FARMERS_DELIGHT;
            pendingPot = null;
            if (ModList.get().isLoaded("kaleidoscope_cookery")) {
                pendingPot = KaleidoStockpotCookingAdapter.findCandidate(robot, level, target);
                if (pendingPot != null) pendingBackend = RobotCookingState.KALEIDO_STOCKPOT;
                else {
                    pendingPot = KaleidoPotCookingAdapter.findCandidate(robot, level, target);
                    if (pendingPot != null) pendingBackend = RobotCookingState.KALEIDO_POT;
                }
            }
            if (pendingPot == null && ModList.get().isLoaded("farmersdelight"))
                pendingPot = FarmerDelightCookingAdapter.findCandidate(robot, level, target);
            if (pendingPot == null) { retryAt = level.getGameTime() + 40; return false; }
        } else {
            pendingPot = state.potPos();
            if (order != null && !order.target().equals(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(state.expectedOutput().getItem()))) {
                block("原锅批次与指定菜品订单不一致；已停止并保留锅内物料");
                return false;
            }
        }
        BlockPos target = state == null ? pendingPot : state.potPos();
        if (state != null && !sameDimension(level, state) || !withinWorkArea(robot, target)) {
            block("烹饪任务绑定的原锅不在当前维度或附近范围；请回到原锅附近继续");
            return false;
        }
        if (!level.hasChunkAt(target)) {
            block("烹饪任务绑定的原锅尚未加载；走近原锅后会继续，不会强制加载区块");
            return false;
        }
        ItemEntity remainder = nativeRemainder(level, state);
        pendingStand = findStand(robot, level, target, remainder);
        if (pendingStand == null) {
            block(remainder == null ? "料理锅附近没有可见且可到达的站位；请清理锅边通路"
                    : "锅旁没有能看见本批空桶的可达站位；请清理锅侧通路，实物与任务保留");
            return false;
        }
        if (state != null && !tryClaim(robot, state)) {
            block("该锅正由另一台机器人操作；等待锅空出后再继续");
            return false;
        }
        return true;
    }

    @Override public boolean canContinueToUse() {
        if (!active || robot.mode() != RobotEntity.COOKING || robot.getOwnerUUID() == null
                || robot.getTarget() != null || !robot.powered()
                || !(robot.level() instanceof ServerLevel level)) return false;
        RobotCookingState state = robot.cookingState();
        RobotCookingOrder order = robot.cookingOrder();
        if (order != null && (!order.owner().equals(robot.getOwnerUUID()) || order.complete()
                || state != null && !order.target().equals(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(state.expectedOutput().getItem())))) return false;
        BlockPos target = state == null ? pendingPot : state.potPos();
        if (target == null || pendingStand == null) return false;
        return target != null && (robot.isWorking() || !robot.getNavigation().isDone())
                && (state == null || sameDimension(level, state) && ownsClaim(robot, state))
                && withinWorkArea(robot, target) && level.hasChunkAt(target);
    }

    @Override public void start() {
        active = true;
        robot.setWorking(true);
        robot.setWorkTaskActive(true);
        RobotCookingOrder order = robot.cookingOrder();
        robot.workStatus(order == null ? "正在使用附近已加载的空锅准备原生料理锅配方"
                : "指定烹饪订单：" + order.summary() + "；仅使用原生完整配方批次，末批可能超过需求");
    }

    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level)) return;
        RobotCookingState state = robot.cookingState();
        BlockPos target = state == null ? pendingPot : state.potPos();
        if (target == null) { block("烹饪任务状态缺失，已暂停并保留安全状态"); return; }
        BlockPos stand = pendingStand;
        ItemEntity remainder = nativeRemainder(level, state);
        if (stand == null || !level.hasChunkAt(stand) || !level.hasChunkAt(stand.above())
                || remainder != null && !canSeeRemainderFrom(robot, level, stand, remainder)) {
            stand = findStand(robot, level, target, remainder);
            pendingStand = stand;
        }
        if (stand == null) { block("料理锅边没有可见且可到达的站位；请清理锅边通路"); return; }
        double distance = robot.distanceToSqr(Vec3.atBottomCenterOf(stand));
        if (distance > 2.25 || !canSeePot(robot, level, target)
                || remainder != null && !robot.hasLineOfSight(remainder)) {
            robot.setWorking(false);
            if (robot.getNavigation().isDone()) {
                var path = robot.getNavigation().createPath(stand, 0);
                if (path == null || !path.canReach()) { block("原锅边站位不可到达；请清出通路后再继续"); return; }
                robot.getNavigation().moveTo(path, 1.0);
            }
            robot.workStatus("正在前往任务绑定原锅旁的可达站位");
            return;
        }
        robot.getNavigation().stop();
        robot.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
        robot.setWorking(true);
        robot.setWorkTaskActive(true);
        if (state == null) {
            RobotCookingOrder order = robot.cookingOrder();
            ResourceLocation dish = order == null ? null : order.target();
            state = switch (pendingBackend) {
                case RobotCookingState.KALEIDO_STOCKPOT -> KaleidoStockpotCookingAdapter.prepareAt(robot, level, target, dish);
                case RobotCookingState.KALEIDO_POT -> KaleidoPotCookingAdapter.prepareAt(robot, level, target, dish);
                default -> FarmerDelightCookingAdapter.prepareAt(robot, level, target, dish);
            };
            if (state == null) pause();
            else pendingPot = null;
        } else if (RobotCookingState.KALEIDO_STOCKPOT.equals(state.backend()))
            KaleidoStockpotCookingAdapter.tick(robot, level, state);
        else if (RobotCookingState.KALEIDO_POT.equals(state.backend()))
            KaleidoPotCookingAdapter.tick(robot, level, state);
        else FarmerDelightCookingAdapter.tick(robot, level, state);
    }

    private void block(String reason) {
        robot.workBlocked(reason);
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        active = false;
        if (robot.level() instanceof ServerLevel level) retryAt = level.getGameTime() + 40;
    }

    private void pause() {
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        active = false;
        if (robot.level() instanceof ServerLevel level) retryAt = level.getGameTime() + 40;
    }

    @Override public void stop() {
        RobotCookingState state = robot.cookingState();
        boolean keepKaleidoClaim = state != null && isKaleido(state);
        if (state != null && !keepKaleidoClaim) release(robot, state);
        if (!keepKaleidoClaim) releaseAll(robot);
        active = false;
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        if (robot.level() instanceof ServerLevel level) retryAt = level.getGameTime() + 40;
    }

    private static boolean sameDimension(ServerLevel level, RobotCookingState state) {
        return level.dimension().location().equals(state.dimension());
    }

    private static boolean backendLoaded(RobotCookingState state) {
        return switch (state.backend()) {
            case RobotCookingState.KALEIDO_STOCKPOT, RobotCookingState.KALEIDO_POT -> ModList.get().isLoaded("kaleidoscope_cookery");
            case RobotCookingState.FARMERS_DELIGHT -> ModList.get().isLoaded("farmersdelight");
            default -> false;
        };
    }

    private static boolean isKaleido(RobotCookingState state) {
        return RobotCookingState.KALEIDO_STOCKPOT.equals(state.backend()) || RobotCookingState.KALEIDO_POT.equals(state.backend());
    }

    /** Checks only native recipe outputs; ingredients and a heated pot are not required yet. */
    public static boolean supportsTarget(ServerLevel level, ResourceLocation target) {
        return level != null && target != null && (ModList.get().isLoaded("farmersdelight")
                && FarmerDelightCookingAdapter.supportsTarget(level, target)
                || ModList.get().isLoaded("kaleidoscope_cookery")
                && (KaleidoStockpotCookingAdapter.supportsTarget(level, target)
                || KaleidoPotCookingAdapter.supportsTarget(level, target)));
    }

    private static boolean withinWorkArea(RobotEntity robot, BlockPos pos) {
        BlockPos origin = robot.workOrigin();
        return Math.abs(pos.getX() - origin.getX()) <= 6 && Math.abs(pos.getY() - origin.getY()) <= 4
                && Math.abs(pos.getZ() - origin.getZ()) <= 6;
    }

    static boolean withinWorkAreaForAdapter(RobotEntity robot, BlockPos pos) { return withinWorkArea(robot, pos); }

    private ItemEntity nativeRemainder(ServerLevel level, RobotCookingState state) {
        return state != null && RobotCookingState.FARMERS_DELIGHT.equals(state.backend())
                && ModList.get().isLoaded("farmersdelight")
                ? FarmerDelightCookingAdapter.remainderToCollect(robot, level, state) : null;
    }

    private static boolean canSeeRemainderFrom(RobotEntity robot, ServerLevel level, BlockPos stand, ItemEntity item) {
        Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, robot.getEyeHeight(), 0);
        Vec3 target = item.getEyePosition();
        return eye.distanceToSqr(target) <= 20.25
                && level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, robot)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private static BlockPos findStand(RobotEntity robot, ServerLevel level, BlockPos pot, ItemEntity remainder) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stand = pot.relative(direction);
            BlockPos head = stand.above();
            BlockPos floor = stand.below();
            if (!level.hasChunkAt(stand) || !level.hasChunkAt(head) || !level.hasChunkAt(floor)) continue;
            BlockState feetState = level.getBlockState(stand);
            BlockState headState = level.getBlockState(head);
            if (!feetState.getCollisionShape(level, stand).isEmpty()
                    || !headState.getCollisionShape(level, head).isEmpty()
                    || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
            var path = robot.getNavigation().createPath(stand, 0);
            if (!withinWorkArea(robot, stand) || path == null || !path.canReach()) continue;
            if (remainder != null && !canSeeRemainderFrom(robot, level, stand, remainder)) continue;
            Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, robot.getEyeHeight(), 0);
            Vec3 target = Vec3.atCenterOf(pot);
            var hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, robot));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pot)) return stand;
        }
        return null;
    }

    private static boolean canSeePot(RobotEntity robot, ServerLevel level, BlockPos pot) {
        Vec3 eye = robot.getEyePosition();
        Vec3 target = Vec3.atCenterOf(pot);
        var hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, robot));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pot);
    }

    private static PotKey key(RobotEntity robot, RobotCookingState state) {
        if (!(robot.level() instanceof ServerLevel level)) return null;
        return new PotKey(level.getServer(), state.dimension(), state.potPos().immutable());
    }

    static boolean tryClaim(RobotEntity robot, RobotCookingState state) {
        PotKey key = key(robot, state);
        if (key == null || !(robot.level() instanceof ServerLevel level)) return false;
        RobotKey robotKey = new RobotKey(level.getServer(), robot.getUUID());
        UUID current = ACTIVE_POTS.get(key);
        if (current != null && !current.equals(robot.getUUID())) return false;
        ACTIVE_POTS.put(key, robot.getUUID());
        ROBOT_CLAIMS.put(robotKey, key);
        return true;
    }

    private static boolean ownsClaim(RobotEntity robot, RobotCookingState state) {
        PotKey key = key(robot, state);
        return key != null && robot.getUUID().equals(ACTIVE_POTS.get(key));
    }

    static void release(RobotEntity robot, RobotCookingState state) {
        PotKey key = key(robot, state);
        if (!(robot.level() instanceof ServerLevel level)) return;
        RobotKey robotKey = new RobotKey(level.getServer(), robot.getUUID());
        if (key != null && robot.getUUID().equals(ACTIVE_POTS.get(key))) ACTIVE_POTS.remove(key);
        ROBOT_CLAIMS.remove(robotKey);
    }

    private static void releaseAll(RobotEntity robot) {
        if (!(robot.level() instanceof ServerLevel level)) return;
        RobotKey robotKey = new RobotKey(level.getServer(), robot.getUUID());
        PotKey key = ROBOT_CLAIMS.remove(robotKey);
        if (key != null && robot.getUUID().equals(ACTIVE_POTS.get(key))) ACTIVE_POTS.remove(key);
    }

    /** Cancels a bound batch only when the pot snapshot proves escrow is still outside it. */
    public static boolean cancelAndReturnEscrow(RobotEntity robot) {
        if (robot == null || robot.level().isClientSide) return false;
        RobotCookingState state = robot.cookingState();
        if (state == null) return true;
        if (isKaleido(state)) {
            if (!backendLoaded(state)) {
                robot.workBlocked("未安装森罗物语，无法核对原锅操作；任务与暂存物品保留，请恢复对应模组");
                return false;
            }
            boolean cancelled = RobotCookingState.KALEIDO_POT.equals(state.backend())
                    ? KaleidoPotCookingAdapter.cancelAndReturnEscrow(robot, state)
                    : KaleidoStockpotCookingAdapter.cancelAndReturnEscrow(robot, state);
            if (cancelled) release(robot, state);
            return cancelled;
        }
        if (!ModList.get().isLoaded("farmersdelight")) {
            if (state.hasAnyEscrow() || state.nativeBatchId() != null || !state.inFlight().isEmpty()) {
                robot.workBlocked("未安装 Farmer's Delight，无法核对待投原料；烹饪状态保留，请检查原锅");
                return false;
            }
            robot.clearCooking("烹饪任务已取消；原锅内容留在原地");
            release(robot, state);
            return true;
        }
        boolean cancelled = FarmerDelightCookingAdapter.cancelAndReturnEscrow(robot, state);
        if (cancelled) release(robot, state);
        return cancelled;
    }

    @Mod.EventBusSubscriber(modid = "ic2newera")
    public static final class BusyPotGuard {
        private BusyPotGuard() { }

        @SubscribeEvent
        public static void protectBusyPot(PlayerInteractEvent.RightClickBlock event) {
            if (event.getLevel().isClientSide || event.getEntity() instanceof FakePlayer) return;
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            if (ACTIVE_POTS.containsKey(new PotKey(level.getServer(), level.dimension().location(), event.getPos().immutable()))) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            }
        }

        @SubscribeEvent
        public static void releaseOnEntityLeave(EntityLeaveLevelEvent event) {
            if (event.getEntity() instanceof RobotEntity robot) releaseAll(robot);
        }

        @SubscribeEvent
        public static void clearClaimsOnServerStop(ServerStoppedEvent event) {
            MinecraftServer server = event.getServer();
            ACTIVE_POTS.keySet().removeIf(key -> key.server() == server);
            ROBOT_CLAIMS.keySet().removeIf(key -> key.server() == server);
        }
    }
}
