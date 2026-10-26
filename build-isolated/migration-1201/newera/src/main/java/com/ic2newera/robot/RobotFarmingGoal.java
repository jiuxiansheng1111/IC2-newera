package com.ic2newera.robot;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Native, owner-authorized tending of vanilla crops in the robot's loaded work area. */
public final class RobotFarmingGoal extends Goal {
    private static final int SCAN_BUDGET = 96;
    private static final int RETRY_TICKS = 40;
    private static final int ACTION_RETRY_TICKS = 10;
    private static final int PATH_BUDGET_PER_SCAN = 24;

    private enum Action { HARVEST, PLANT, TILL }
    private record Crop(Block crop, ItemStack seed) { }

    private static final Crop WHEAT = new Crop(Blocks.WHEAT, new ItemStack(Items.WHEAT_SEEDS));
    private static final Crop CARROTS = new Crop(Blocks.CARROTS, new ItemStack(Items.CARROT));
    private static final Crop POTATOES = new Crop(Blocks.POTATOES, new ItemStack(Items.POTATO));
    private static final Crop BEETROOTS = new Crop(Blocks.BEETROOTS, new ItemStack(Items.BEETROOT_SEEDS));

    private final RobotEntity robot;
    private FakePlayer farmer;
    private BlockPos scanOrigin;
    private List<BlockPos> scan = List.of();
    private int scanIndex;
    private BlockPos target;
    private BlockPos targetStand;
    private BlockPos pendingReplant;
    private BlockState targetState;
    private Action action;
    private Crop crop;
    private Crop pendingReplantCrop;
    private boolean pendingReserveSeed;
    private boolean reserveSeedForPlant;
    private int seedSlot = -1;
    private int hoeSlot = -1;
    private int ticks;
    private long retryAt;
    private String lastFailure = "";

    public RobotFarmingGoal(RobotEntity robot) {
        this.robot = robot;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public boolean canUse() {
        if (robot.level().isClientSide || !(robot.level() instanceof ServerLevel level)) {
            clearScan();
            return false;
        }
        if (robot.mode() != RobotEntity.FARMING) {
            clearScan();
            clearPendingReplant();
            return false;
        }
        if (robot.getOwnerUUID() == null || !robot.isAlive() || robot.getTarget() != null
                || robot.collectionTask() != null) {
            clearScan();
            return false;
        }
        if (!robot.powered()) {
            block("种植暂停：工作电量不足，请更换电池或补电");
            return false;
        }
        long now = level.getGameTime();
        if (now < retryAt) return false;
        int[] pathBudget = {PATH_BUDGET_PER_SCAN};
        if (pendingReplant != null) {
            BlockPos pos = pendingReplant;
            Crop selected = pendingReplantCrop;
            if (level.hasChunkAt(pos) && level.hasChunkAt(pos.below()) && withinWorkArea(pos)
                    && level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND)
                    && selected != null) {
                int slot = findSeedSlot(selected, pendingReserveSeed);
                BlockPos stand = slot < 0 ? null : findReachableStand(level, pos.below(), pathBudget);
                if (slot >= 0 && stand != null) {
                    target = pos.immutable();
                    targetStand = stand;
                    targetState = level.getBlockState(pos);
                    action = Action.PLANT;
                    crop = selected;
                    seedSlot = slot;
                    reserveSeedForPlant = pendingReserveSeed;
                    clearPendingReplant();
                    return true;
                }
            }
            clearPendingReplant();
        }
        if (!robot.workOrigin().equals(scanOrigin)) resetScan(level);
        if (scan.isEmpty()) resetScan(level);

        int checked = 0;
        while (scanIndex < scan.size() && checked++ < SCAN_BUDGET && pathBudget[0] > 0) {
            BlockPos pos = scan.get(scanIndex++);
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            Action found = null;
            Crop foundCrop = null;
            int foundSeed = -1;
            int foundHoe = -1;

            Crop cropAt = cropFor(state);
            if (cropAt != null && mature(state, cropAt)) {
                found = Action.HARVEST;
                foundCrop = cropAt;
            } else if (state.isAir() && level.hasChunkAt(pos.below())
                    && level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
                Crop seedCrop = cropForSeed(false);
                if (seedCrop != null) {
                    found = Action.PLANT;
                    foundCrop = seedCrop;
                    foundSeed = findSeedSlot(seedCrop, false);
                }
            } else if (tillable(level, pos) && enoughSeedForNewFarmland()) {
                int tool = findHoeSlot();
                if (tool >= 0) {
                    found = Action.TILL;
                    foundCrop = cropForSeed(true);
                    foundSeed = foundCrop == null ? -1 : findSeedSlot(foundCrop, true);
                    foundHoe = tool;
                }
            }

            if (found == null) continue;
            if (found == Action.HARVEST && !robot.inventory().hasCargoSpace()) {
                block("货物槽已满，先取出作物和工具再继续收获");
                return false;
            }
            BlockPos interactPos = found == Action.PLANT ? pos.below() : pos;
            BlockPos stand = findReachableStand(level, interactPos, pathBudget);
            if (stand == null) {
                rememberFailure("作物或耕地附近没有可达且无遮挡的站位");
                continue;
            }
            target = pos.immutable();
            targetStand = stand;
            targetState = state;
            action = found;
            crop = foundCrop;
            seedSlot = foundSeed;
            hoeSlot = foundHoe;
            reserveSeedForPlant = false;
            return true;
        }

        if (scanIndex >= scan.size()) {
            clearScan();
            retryAt = now + RETRY_TICKS;
            if (!lastFailure.isBlank()) block(lastFailure);
            else block("附近已加载范围没有成熟作物、空耕地或适合翻整的土块；请放入对应种子和锄头");
        }
        return false;
    }

    @Override public boolean canContinueToUse() {
        if (!(robot.level() instanceof ServerLevel level) || robot.mode() != RobotEntity.FARMING
                || robot.getOwnerUUID() == null || !robot.isAlive() || !robot.powered()
                || robot.getTarget() != null || robot.collectionTask() != null
                || target == null || targetStand == null || ticks > 240) return false;
        if (!level.hasChunkAt(target) || !level.hasChunkAt(targetStand)
                || !withinWorkArea(target) || !withinWorkArea(targetStand)) return false;
        if (action == Action.HARVEST) {
            Crop currentCrop = cropFor(level.getBlockState(target));
            return level.getBlockState(target) == targetState && currentCrop != null && mature(targetState, currentCrop);
        }
        if (!level.getBlockState(target).equals(targetState)) return false;
        if (action == Action.PLANT) return level.getBlockState(target).isAir()
                && level.getBlockState(target.below()).is(Blocks.FARMLAND)
                && seedSlot >= 0 && stillHasSeed(seedSlot, crop, reserveSeedForPlant);
        return action == Action.TILL && tillable(level, target) && seedSlot >= 0
                && stillHasSeed(seedSlot, crop, true) && stillHasHoe(hoeSlot);
    }

    @Override public void start() {
        ticks = 0;
        robot.setWorkTaskActive(true);
        robot.workStatus(switch (action) {
            case HARVEST -> "正在前往成熟" + cropName(crop) + "旁收获";
            case PLANT -> "正在前往空耕地播种" + cropName(crop);
            case TILL -> "正在前往水源附近整理耕地";
        });
    }

    @Override public void tick() {
        if (!(robot.level() instanceof ServerLevel level) || target == null || targetStand == null) return;
        ticks++;
        if (!level.hasChunkAt(target) || !level.hasChunkAt(targetStand)) return;
        Vec3 standPoint = Vec3.atBottomCenterOf(targetStand);
        boolean inPlace = robot.distanceToSqr(standPoint) <= 2.25;
        BlockPos interactPos = action == Action.PLANT ? target.below() : target;
        boolean visible = canSee(level, interactPos);
        if (!inPlace || !visible) {
            robot.setWorking(false);
            if (robot.getNavigation().isDone()) {
                var path = robot.getNavigation().createPath(targetStand, 0);
                if (path == null || !path.canReach()) {
                    reject(level, "目标旁站位不可达，请清理通路；工作任务保留");
                    return;
                }
                robot.getNavigation().moveTo(path, 1.0);
            }
            if (ticks > 160 && robot.getNavigation().isDone())
                reject(level, "目标不可达或被遮挡，请清理通路；工作任务保留");
            return;
        }

        robot.getNavigation().stop();
        robot.getLookControl().setLookAt(interactPos.getX() + .5, interactPos.getY() + .5, interactPos.getZ() + .5);
        if (!authorized(level, interactPos) || !authorized(level, target)) {
            reject(level, "出生点、领地保护或世界规则禁止机器人耕作");
            return;
        }
        if (action == Action.HARVEST) harvest(level);
        else if (action == Action.PLANT) plant(level, target, seedSlot, crop);
        else till(level);
    }

    private void harvest(ServerLevel level) {
        BlockState state = level.getBlockState(target);
        Crop ripe = cropFor(state);
        if (ripe == null || !mature(state, ripe) || state != targetState) {
            finishAction(level, false, "作物状态改变，重新扫描后继续");
            return;
        }
        if (!robot.inventory().hasCargoSpace()) {
            reject(level, "货物槽已满，成熟作物未破坏；请取出物品后继续");
            return;
        }
        FakePlayer fake = farmer(level);
        Set<UUID> before = new HashSet<>();
        level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.25))
                .forEach(item -> before.add(item.getUUID()));
        try {
            robot.setWorking(true);
            robot.setWorkTaskActive(true);
            // Native survival destruction fires Forge break hooks and the crop's real loot table.
            fake.gameMode.destroyBlock(target);
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Robot crop harvest failed at {} ({})", target, state.getBlock(), failure);
            reject(level, "原生收获接口发生错误；作物任务保留，请检查模组日志");
            return;
        }
        if (level.getBlockState(target) == state) {
            reject(level, "领地或模组事件取消了收获；请检查耕作权限");
            return;
        }
        int collected = collectNewDrops(level, before);
        // Defer replanting until the next goal tick, where protection and block state are checked again.
        BlockPos soil = target.below();
        if (level.hasChunkAt(soil) && level.getBlockState(soil).is(Blocks.FARMLAND)
                && level.getBlockState(target).isAir()) {
            int slot = findSeedSlot(ripe, false);
            if (slot >= 0) {
                pendingReplant = target.immutable();
                pendingReplantCrop = ripe;
                pendingReserveSeed = false;
            }
        }
        robot.workStatus(collected > 0 ? "已按原生掉落收获并入库，继续照料农田" : "已完成原生收获；没有产生可入库掉落");
        retryAt = level.getGameTime() + ACTION_RETRY_TICKS;
        resetScan(level);
        clearTarget(false);
    }

    private int collectNewDrops(ServerLevel level, Set<UUID> before) {
        int collected = 0;
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.25))) {
            if (!drop.isAlive() || before.contains(drop.getUUID()) || !allowedDrop(drop)) continue;
            ItemStack offered = drop.getItem().copy();
            ItemStack remaining = robot.inventory().insertCargo(offered);
            int accepted = offered.getCount() - remaining.getCount();
            if (accepted <= 0) continue;
            collected += accepted;
            ItemStack world = drop.getItem();
            world.shrink(accepted);
            if (world.isEmpty()) drop.discard(); else drop.setItem(world);
        }
        return collected;
    }

    private void plant(ServerLevel level, BlockPos cropPos, int slot, Crop selected) {
        if (selected == null || slot < 0 || !level.hasChunkAt(cropPos) || !level.hasChunkAt(cropPos.below())
                || !level.getBlockState(cropPos).isAir() || !level.getBlockState(cropPos.below()).is(Blocks.FARMLAND)
                || !stillHasSeed(slot, selected, reserveSeedForPlant)) {
            reject(level, "种子不足或耕地已被占用；请补充种子并保留一份重种");
            return;
        }
        useOnBlock(level, cropPos.below(), cropPos, slot, false, "播种");
    }

    private void till(ServerLevel level) {
        if (crop == null || seedSlot < 0 || !stillHasSeed(seedSlot, crop, true) || !stillHasHoe(hoeSlot)
                || !tillable(level, target)) {
            reject(level, "缺少耐久足够的锄头或可重种的种子，未整理土地");
            return;
        }
        useOnBlock(level, target, target, hoeSlot, true, "翻整耕地");
    }

    private void useOnBlock(ServerLevel level, BlockPos pos, BlockPos expectedChange, int slot, boolean hoe, String verb) {
        if (!withinWorkArea(pos) || !withinWorkArea(expectedChange)
                || !level.hasChunkAt(pos) || !level.hasChunkAt(expectedChange)
                || !authorized(level, pos) || !authorized(level, expectedChange)) {
            reject(level, "耕作时保护或工作范围发生变化，物品未使用");
            return;
        }
        FakePlayer fake = farmer(level);
        ItemStack original = robot.inventory().getStackInSlot(slot);
        if (original.isEmpty()) {
            reject(level, "所需物品已离开货物槽，请重新补充");
            return;
        }
        ItemStack held = original.copy();
        fake.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockHitResult hit = new BlockHitResult(targetPoint(level, pos), Direction.UP, pos, false);
        BlockState before = level.getBlockState(expectedChange);
        InteractionResult result = InteractionResult.PASS;
        RuntimeException interactionFailure = null;
        try {
            robot.setWorking(true);
            robot.setWorkTaskActive(true);
            // ServerPlayerGameMode routes through Forge RightClickBlock and native Item.useOn.
            result = fake.gameMode.useItemOn(fake, level, held, InteractionHand.MAIN_HAND, hit);
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Robot farming interaction failed at {} ({})", pos, before.getBlock(), failure);
            interactionFailure = failure;
        } finally {
            ItemStack after = fake.getMainHandItem().copy();
            try {
                if (!ItemStack.matches(after, original)) writeBackUse(slot, original, after);
            } finally {
                fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        boolean changed = level.getBlockState(expectedChange) != before;
        if (interactionFailure != null) {
            reject(level, "原生耕作接口发生错误；物品变更已回写，任务保留，请检查模组日志");
        } else if (changed && result.consumesAction()) {
            if (hoe && action == Action.TILL && crop != null && withinWorkArea(target.above())
                    && level.hasChunkAt(target.above()) && level.getBlockState(target.above()).isAir()) {
                pendingReplant = target.above().immutable();
                pendingReplantCrop = crop;
                pendingReserveSeed = true;
            }
            robot.workStatus("已完成" + verb + (hoe ? "，锄头耐久已更新" : "，已消耗一份种子"));
            retryAt = level.getGameTime() + ACTION_RETRY_TICKS;
            resetScan(level);
            clearTarget(false);
        } else {
            reject(level, hoe ? "该土块无法原生锄耕或遭到保护，未消耗锄头" : "种植受到保护或方块状态异常；种子已保留");
        }
    }

    private void writeBackUse(int slot, ItemStack original, ItemStack after) {
        if (slot == 1 || slot == 2) robot.inventory().syncEquipment();
        ItemStack current = robot.inventory().getStackInSlot(slot);
        if (!ItemStack.matches(current, original)) return;
        if (slot == 1) {
            robot.setItemSlot(EquipmentSlot.MAINHAND, after.isEmpty() ? ItemStack.EMPTY : after.copy());
            robot.inventory().syncEquipment();
            return;
        }
        if (slot == 2) {
            robot.setItemSlot(EquipmentSlot.OFFHAND, after.isEmpty() ? ItemStack.EMPTY : after.copy());
            robot.inventory().syncEquipment();
            return;
        }
        robot.inventory().setStackInSlot(slot, after.isEmpty() ? ItemStack.EMPTY : after.copy());
    }

    private boolean authorized(ServerLevel level, BlockPos pos) {
        return level.getWorldBorder().isWithinBounds(pos)
                && ForgeEventFactory.getMobGriefingEvent(level, robot)
                && level.mayInteract(farmer(level), pos);
    }

    private FakePlayer farmer(ServerLevel level) {
        if (farmer == null || farmer.level() != level || !farmer.getUUID().equals(robot.getOwnerUUID())) {
            var profile = (level.getServer().getProfileCache() == null ? java.util.Optional.<GameProfile>empty() : level.getServer().getProfileCache().get(robot.getOwnerUUID()))
                    .orElseGet(() -> new GameProfile(robot.getOwnerUUID(), "RobotFarmer"));
            farmer = new FakePlayer(level, profile);
        }
        farmer.setPos(robot.getX(), robot.getY(), robot.getZ());
        farmer.setOnGround(robot.onGround());
        return farmer;
    }

    private boolean allowedDrop(ItemEntity item) {
        return RobotItemOwnership.mayPickUp(robot, item);
    }

    private boolean tillable(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.getBlockState(pos.above()).isAir()) return false;
        BlockState soil = level.getBlockState(pos);
        return (soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK)) && hasNearbyWater(level, pos);
    }

    private boolean hasNearbyWater(ServerLevel level, BlockPos soil) {
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > 4) continue;
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos water = soil.offset(dx, dy, dz);
                if (!level.hasChunkAt(water)) continue;
                var fluid = level.getFluidState(water);
                if (fluid.isSource() && fluid.getType() == net.minecraft.world.level.material.Fluids.WATER) return true;
            }
        }
        return false;
    }

    private boolean enoughSeedForNewFarmland() { return cropForSeed(true) != null; }

    private static Crop cropFor(BlockState state) {
        if (state.is(Blocks.WHEAT)) return WHEAT;
        if (state.is(Blocks.CARROTS)) return CARROTS;
        if (state.is(Blocks.POTATOES)) return POTATOES;
        if (state.is(Blocks.BEETROOTS)) return BEETROOTS;
        return null;
    }

    private boolean mature(BlockState state, Crop candidate) {
        return state.getBlock() == candidate.crop() && state.getBlock() instanceof CropBlock cropBlock
                && cropBlock.isMaxAge(state);
    }

    private Crop cropForSeed(boolean reserveOne) {
        if (findSeedSlot(WHEAT, reserveOne) >= 0) return WHEAT;
        if (findSeedSlot(CARROTS, reserveOne) >= 0) return CARROTS;
        if (findSeedSlot(POTATOES, reserveOne) >= 0) return POTATOES;
        if (findSeedSlot(BEETROOTS, reserveOne) >= 0) return BEETROOTS;
        return null;
    }

    private int findSeedSlot(Crop selected, boolean reserveOne) {
        if (selected == null) return -1;
        int total = 0, first = -1;
        for (int slot : cargoAndOffhandSlots()) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (stack.is(selected.seed().getItem())) {
                total += stack.getCount();
                if (first < 0) first = slot;
            }
        }
        return first >= 0 && total > (reserveOne ? 1 : 0) ? first : -1;
    }

    private boolean stillHasSeed(int slot, Crop selected, boolean reserveOne) {
        if (slot < 0 || slot >= RobotInventory.SIZE || selected == null) return false;
        ItemStack stack = robot.inventory().getStackInSlot(slot);
        return stack.is(selected.seed().getItem()) && !stack.isEmpty()
                && totalSeedCount(selected) > (reserveOne ? 1 : 0);
    }

    private int totalSeedCount(Crop selected) {
        int total = 0;
        for (int slot : cargoAndOffhandSlots()) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (stack.is(selected.seed().getItem())) total += stack.getCount();
        }
        return total;
    }

    private int findHoeSlot() {
        robot.inventory().syncEquipment();
        for (int slot : cargoAndOffhandSlots()) {
            ItemStack tool = robot.inventory().getStackInSlot(slot);
            if (!tool.isEmpty() && tool.canPerformAction(ToolActions.HOE_TILL)
                    && (!tool.isDamageableItem() || tool.getDamageValue() < tool.getMaxDamage())) return slot;
        }
        return -1;
    }

    private boolean stillHasHoe(int slot) {
        if (slot < 0 || slot >= RobotInventory.SIZE) return false;
        ItemStack tool = robot.inventory().getStackInSlot(slot);
        return !tool.isEmpty() && tool.canPerformAction(ToolActions.HOE_TILL)
                && (!tool.isDamageableItem() || tool.getDamageValue() < tool.getMaxDamage());
    }

    private static int[] cargoAndOffhandSlots() {
        int[] slots = new int[RobotInventory.SIZE - RobotInventory.CARGO_START + 2];
        slots[0] = 1;
        slots[1] = 2;
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE; i++)
            slots[i - RobotInventory.CARGO_START + 2] = i;
        return slots;
    }

    private BlockPos findReachableStand(ServerLevel level, BlockPos block, int[] pathBudget) {
        List<BlockPos> stands = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(block.offset(-1, -1, -1), block.offset(1, 1, 1))) {
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above()) || !level.hasChunkAt(pos.below())
                    || !withinWorkArea(pos)) continue;
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    || !level.getBlockState(pos).getFluidState().isEmpty()) continue;
            BlockState floor = level.getBlockState(pos.below());
            if (!floor.is(Blocks.FARMLAND) && !floor.is(Blocks.DIRT_PATH)
                    && !floor.isFaceSturdy(level, pos.below(), Direction.UP)) continue;
            Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, robot.getEyeHeight(), 0);
            BlockHitResult hit = level.clip(new ClipContext(eye, targetPoint(level, block),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, robot));
            if (!hit.getBlockPos().equals(block)) continue;
            stands.add(pos.immutable());
        }
        stands.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        for (BlockPos stand : stands) {
            if (pathBudget[0] <= 0) return null;
            pathBudget[0]--;
            var path = robot.getNavigation().createPath(stand, 0);
            if (path != null && path.canReach()) return stand;
        }
        return null;
    }

    private boolean canSee(ServerLevel level, BlockPos pos) {
        Vec3 eye = robot.getEyePosition();
        Vec3 target = targetPoint(level, pos);
        BlockHitResult hit = level.clip(new ClipContext(eye, target,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, robot));
        return hit.getBlockPos().equals(pos) && eye.distanceToSqr(target) <= 4.25 * 4.25;
    }

    private Vec3 targetPoint(ServerLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos, CollisionContext.empty());
        return shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().move(pos).getCenter();
    }

    private boolean withinWorkArea(BlockPos pos) {
        BlockPos origin = robot.workOrigin();
        return Math.abs(pos.getX() - origin.getX()) <= 6 && Math.abs(pos.getZ() - origin.getZ()) <= 6
                && pos.getY() >= origin.getY() - 2 && pos.getY() <= origin.getY() + 3;
    }

    private void resetScan(ServerLevel level) {
        scanOrigin = robot.workOrigin();
        scanIndex = 0;
        lastFailure = "";
        List<BlockPos> positions = new ArrayList<>();
        BlockPos origin = scanOrigin;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -2, -6), origin.offset(6, 3, 6))) {
            if (level.hasChunkAt(pos)) positions.add(pos.immutable());
        }
        positions.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atCenterOf(pos))));
        scan = positions;
    }

    private void clearScan() {
        scan = List.of();
        scanIndex = 0;
        scanOrigin = null;
    }

    private void clearPendingReplant() {
        pendingReplant = null;
        pendingReplantCrop = null;
        pendingReserveSeed = false;
    }

    private void rememberFailure(String reason) { if (lastFailure.isBlank()) lastFailure = reason; }

    private void block(String reason) {
        robot.workBlocked(reason);
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        if (robot.level() instanceof ServerLevel level) retryAt = level.getGameTime() + RETRY_TICKS;
    }

    private void reject(ServerLevel level, String reason) {
        rememberFailure(reason);
        block(reason);
        retryAt = level.getGameTime() + RETRY_TICKS;
        clearTarget(true);
    }

    private void finishAction(ServerLevel level, boolean changed, String status) {
        robot.workStatus(status);
        retryAt = level.getGameTime() + ACTION_RETRY_TICKS;
        resetScan(level);
        clearTarget(false);
    }

    private String cropName(Crop selected) {
        if (selected == WHEAT) return "小麦";
        if (selected == CARROTS) return "胡萝卜";
        if (selected == POTATOES) return "马铃薯";
        if (selected == BEETROOTS) return "甜菜";
        return "作物";
    }

    @Override public void stop() {
        clearTarget(true);
        robot.setWorking(false);
        robot.setWorkTaskActive(false);
        robot.getNavigation().stop();
        if (farmer != null) farmer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        if (robot.level() instanceof ServerLevel level)
            retryAt = Math.max(retryAt, level.getGameTime() + ACTION_RETRY_TICKS);
    }

    private void clearTarget(boolean stopNavigation) {
        target = null;
        targetStand = null;
        targetState = null;
        action = null;
        crop = null;
        seedSlot = -1;
        hoeSlot = -1;
        reserveSeedForPlant = false;
        if (stopNavigation) robot.getNavigation().stop();
    }
}
