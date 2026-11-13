package com.ic2newera.robot.workshop;

import com.ic2newera.robot.RobotChatItems;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import com.ic2newera.robot.RobotTaskBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** One owner-ordered batch. Walking and anticipation never reserve physical items. */
public final class ToolWork {
    public record Plan(ResourceLocation recipe, ResourceLocation output, int materialCount, ToolStroke stroke) {}
    public record Outcome(ItemStack remainingTool, List<ItemStack> products) {
        public Outcome { products = List.copyOf(products); }
    }
    private record Saved(ResourceLocation recipe, ResourceLocation output, ResourceLocation tool, ResourceLocation material) {}
    private record Job(UUID owner, BlockPos bench, BlockPos stand, int hand, int input, ItemStack tool,
                       ItemStack material, Plan plan, boolean board, long deadline) {}
    private static final ResourceLocation BOARD = new ResourceLocation("farmersdelight", "cutting_board");
    private static final int COST = 200;
    private final RobotEntity robot;
    private Job job;
    private long started;
    private boolean committed;

    public ToolWork(RobotEntity robot) { this.robot = robot; }
    public boolean active() { return job != null; }

    public static boolean acceptsTool(ItemStack stack) {
        return !stack.isEmpty() && (HandToolRecipes.acceptsTool(stack)
                || ModList.get().isLoaded("farmersdelight") && BoardToolRecipes.acceptsTool(stack));
    }

    public static String beginSaved(ServerPlayer owner, RobotEntity robot, ResourceLocation recipe,
                                   ResourceLocation output, ResourceLocation tool, ResourceLocation material) {
        return robot.toolWork().begin(owner, new Saved(recipe, output, tool, material));
    }

    public String begin(ServerPlayer owner) { return begin(owner, null); }

    private String begin(ServerPlayer owner, Saved saved) {
        if (!(robot.level() instanceof ServerLevel level) || owner == null || owner.server != level.getServer()
                || !owner.isAlive() || owner.hasDisconnected() || !robot.isAlive() || !robot.isOwnedBy(owner))
            return "只有主人可以让在线的机器人制作。";
        String occupied = RobotTaskBook.replayBlock(robot);
        if (!occupied.isEmpty()) return occupied;
        if (robot.isEating() || robot.holdingHands() || robot.getTarget() != null || robot.hurtTime > 0)
            return "机器人正在互动或战斗，请稍后再制作。";
        if (robot.energy() < COST) return "制作一批需要机器人至少有 200 EU。";
        var inventory = robot.inventory();
        inventory.syncEquipment();
        List<Integer> hands = new ArrayList<>(2);
        for (int hand = 1; hand <= 2; hand++) {
            ItemStack item = inventory.getStackInSlot(hand);
            if (item.getCount() == 1 && acceptsTool(item) && (saved == null || id(item).equals(saved.tool()))) hands.add(hand);
        }
        if (hands.isEmpty()) return saved == null ? "把可用的锻造锤、板材剪、砂纸或砧板工具放在机器人主手或副手。"
                : "请给机器人装备这单记住的工具：" + display(saved.tool()) + "。";
        List<BlockPos> benches = benches(level);
        if (benches.isEmpty()) return "请在机器人附近放置工作台或农夫乐事砧板。";
        String error = saved == null ? "货物栏原料与手持工具没有匹配的配方。请核对原料数量、台面及工具。"
                : "没有找到原任务的材料和配方。请备好" + display(saved.material()) + "并检查台面。";
        // A wall of identical tables must not multiply expensive recipe lookups.
        BitSet testedTables = new BitSet();
        for (int input = RobotInventory.CARGO_START; input < RobotInventory.SIZE; input++) {
            ItemStack material = inventory.getStackInSlot(input);
            if (material.isEmpty() || saved != null && !id(material).equals(saved.material())) continue;
            for (int hand : hands) {
                ItemStack tool = inventory.getStackInSlot(hand);
                for (BlockPos bench : benches) {
                    boolean board = !level.getBlockState(bench).is(Blocks.CRAFTING_TABLE);
                    int pair = input * 2 + hand - 1;
                    if (!board && testedTables.get(pair)) continue;
                    Plan plan = board ? BoardToolRecipes.find(level, bench, tool, material)
                            : HandToolRecipes.find(level, bench, tool, material);
                    if (!board && (plan == null || saved != null && !saved.recipe().equals(plan.recipe()))) testedTables.set(pair);
                    if (plan == null || plan.materialCount() <= 0 || material.getCount() < plan.materialCount()
                            || saved != null && (!saved.recipe().equals(plan.recipe()) || !saved.output().equals(plan.output()))) continue;
                    BlockPos stand = standFor(level, bench);
                    if (stand == null) { error = "台面旁缺少可到达的站位，请留出两格高的空间。"; continue; }
                    if (!level.getWorldBorder().isWithinBounds(bench)) { error = "这张台面位于世界边界之外。"; continue; }
                    job = new Job(owner.getUUID(), bench, stand, hand, input, tool.copy(), material.copyWithCount(1),
                            plan, board, level.getGameTime() + 160);
                    started = -1; committed = false;
                    robot.stopMovement();
                    robot.setWorkTaskActive(true);
                    robot.workStatus("正在到台边制作" + display(plan.output()));
                    return "";
                }
            }
        }
        return error;
    }

    private List<BlockPos> benches(ServerLevel level) {
        BlockPos center = robot.blockPosition();
        var positions = new ArrayList<BlockPos>();
        boolean boards = ModList.get().isLoaded("farmersdelight");
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -2, -4), center.offset(4, 2, 4))) {
            if (!level.hasChunkAt(pos)) continue;
            var block = level.getBlockState(pos);
            if (block.is(Blocks.CRAFTING_TABLE) || boards && BOARD.equals(BuiltInRegistries.BLOCK.getKey(block.getBlock())))
                positions.add(pos.immutable());
        }
        positions.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atCenterOf(pos))));
        return positions;
    }

    private Vec3 workPoint(ServerLevel level, BlockPos bench) {
        var shape = level.getBlockState(bench).getShape(level, bench);
        double top = shape.isEmpty() ? 0.1 : shape.max(Direction.Axis.Y);
        return Vec3.atBottomCenterOf(bench).add(0, Math.max(0.01, top - 0.015), 0);
    }

    private boolean visible(ServerLevel level, Vec3 eye, BlockPos bench) {
        Vec3 target = workPoint(level, bench);
        if (eye.distanceToSqr(target) > 9) return false;
        var hit = level.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, robot));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(bench);
    }

    private BlockPos standFor(ServerLevel level, BlockPos bench) {
        var candidates = new ArrayList<BlockPos>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 0; dy++) {
                var pos = bench.relative(side).offset(0, dy, 0);
                if (!level.hasChunkAt(pos) || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                        || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                        || !level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) continue;
                if (visible(level, Vec3.atBottomCenterOf(pos).add(0, robot.getEyeHeight(), 0), bench)) candidates.add(pos);
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> robot.distanceToSqr(Vec3.atBottomCenterOf(pos))));
        for (var pos : candidates) {
            if (robot.distanceToSqr(Vec3.atBottomCenterOf(pos)) < 0.64) return pos;
            var path = robot.getNavigation().createPath(pos, 0);
            if (path != null && path.canReach()) return pos;
        }
        return null;
    }

    public void tick(ServerLevel level) {
        if (job == null) return;
        long now = level.getGameTime();
        if (committed) {
            if (now - started >= job.plan().stroke().duration() || robot.getTarget() != null || robot.isEating()
                    || !level.hasChunkAt(job.bench()) || !stationMatches(level))
                finish(null);
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(job.owner());
        if (owner == null || !owner.isAlive() || !robot.isOwnedBy(owner) || !robot.isAlive() || !robot.powered()
                || robot.mode() != RobotEntity.STAY || robot.getTarget() != null || robot.hurtTime > 0 || robot.isVehicle() || robot.isPassenger()
                || robot.isEating() || robot.holdingHands() || !level.hasChunkAt(job.bench())) {
            finish("制作中断，原料和工具保留。"); return;
        }
        if (now > job.deadline()) { finish("未能及时走到台面，制作已取消。"); return; }
        // Stop just outside the bench face, then make a small final approach. PathNavigation
        // alone accepts almost a block of error, which lets tools strike empty air.
        BlockPos feet = job.stand(), bench = job.bench();
        Vec3 tile = Vec3.atBottomCenterOf(feet);
        Vec3 stand = tile.add((bench.getX() - feet.getX()) * 0.17, 0, (bench.getZ() - feet.getZ()) * 0.17);
        if (robot.distanceToSqr(stand) > (started >= 0 ? 0.0256 : 0.0036) || !visible(level, robot.getEyePosition(), bench)) {
            if (started >= 0) { finish("台面或机器人位置已变化，制作已取消。"); return; }
            if (robot.blockPosition().equals(feet) && visible(level, robot.getEyePosition(), bench)) {
                robot.getNavigation().stop();
                robot.getMoveControl().setWantedPosition(stand.x, stand.y, stand.z, 0.55);
            } else if (robot.getNavigation().isDone() || robot.tickCount % 12 == 0) {
                robot.getNavigation().moveTo(stand.x, stand.y, stand.z, 1.05);
            }
            return;
        }
        robot.getNavigation().stop();
        Vec3 target = workPoint(level, job.bench());
        float facing = (float)Math.toDegrees(Math.atan2(target.z - robot.getZ(), target.x - robot.getX())) - 90;
        robot.setYRot(Mth.approachDegrees(robot.getYRot(), facing, 20));
        robot.setYBodyRot(Mth.approachDegrees(robot.yBodyRot, facing, 20));
        robot.getLookControl().setLookAt(target.x, target.y, target.z, 32, 28);
        if (started < 0) {
            if (Math.abs(Mth.wrapDegrees(robot.yBodyRot - facing)) > 12
                    || robot.getDeltaMovement().horizontalDistanceSqr() > 0.008) return;
            if (!stillEquipped() || !stationMatches(level)) { finish("工具、材料或台面已变化，请重新派单。"); return; }
            started = now;
            robot.startToolStroke(job.plan().stroke(), job.tool(), job.hand() == 1 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            robot.showWorkpiece(job.bench(), (float)(target.y - job.bench().getY() + 0.015), job.material());
            robot.workStatus("正在制作" + display(job.plan().output()));
            return;
        }
        if (now - started < job.plan().stroke().contactTick()) return;
        String problem = settle(level, owner);
        if (!problem.isEmpty()) { finish(problem); return; }
        committed = true;
        if (job.board()) {
            BoardToolRecipes.playContact(level, job.bench(), job.plan().recipe(), job.tool(), job.material());
            return;
        }
        level.playSound(null, job.bench(), switch (job.plan().stroke()) {
            case HAMMER -> SoundEvents.ANVIL_USE;
            case SHEAR, CUT -> SoundEvents.SHEEP_SHEAR;
            case POLISH -> com.simibubi.create.AllSoundEvents.SANDING_SHORT.getMainEvent();
            default -> SoundEvents.GRINDSTONE_USE;
        }, SoundSource.BLOCKS, 0.36F, 1.05F);
    }

    private boolean stationMatches(ServerLevel level) {
        var state = level.getBlockState(job.bench());
        return job.board() ? BOARD.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock())) : state.is(Blocks.CRAFTING_TABLE);
    }

    private boolean stillEquipped() {
        var inv = robot.inventory();
        inv.syncEquipment();
        ItemStack input = inv.getStackInSlot(job.input());
        return ItemStack.matches(inv.getStackInSlot(job.hand()), job.tool()) && input.getCount() >= job.plan().materialCount()
                && ItemStack.isSameItemSameTags(input, job.material());
    }

    private boolean mayWork(ServerLevel level, ServerPlayer owner) {
        var worker = FakePlayerFactory.get(level, owner.getGameProfile());
        if (!level.getWorldBorder().isWithinBounds(job.bench())) return false;
        var hand = job.hand() == 1 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        var previous = worker.getItemInHand(hand);
        Vec3 pos = worker.position();
        try {
            worker.setPos(robot.getX(), robot.getY(), robot.getZ());
            worker.setItemInHand(hand, job.tool().copy());
            if (!level.mayInteract(worker, job.bench())) return false;
            var hit = new BlockHitResult(workPoint(level, job.bench()), Direction.UP, job.bench(), false);
            var event = ForgeHooks.onRightClickBlock(worker, hand, job.bench(), hit);
            return !event.isCanceled() && event.getUseBlock() != Event.Result.DENY && event.getUseItem() != Event.Result.DENY;
        } finally {
            worker.setItemInHand(hand, previous);
            worker.setPos(pos.x, pos.y, pos.z);
        }
    }

    private String settle(ServerLevel level, ServerPlayer owner) {
        if (!stationMatches(level) || !mayWork(level, owner)) return "台面已变化，或该处禁止机器人制作。";
        if (!stillEquipped() || robot.energy() < COST) return "工具、原料或电量已变化，请检查后再派单。";
        var inv = robot.inventory();
        ItemStack tool = inv.getStackInSlot(job.hand()).copy(), input = inv.getStackInSlot(job.input()).copy();
        if (job.board()) {
            var possible = BoardToolRecipes.maximumProducts(level, job.bench(), tool, input, job.plan());
            if (possible == null) return "砧板配方、原料或工具已变化，请重新派单。";
            if (packedCargo(possible) == null) return "货物栏放不下本批成品和可能的副产物，请腾出空间。";
        }
        Outcome made = job.board() ? BoardToolRecipes.craft(level, job.bench(), owner, robot, tool, input, job.plan())
                : HandToolRecipes.craft(level, job.bench(), owner, robot, tool, input, job.plan());
        if (made == null) return "配方、工具或台面状态已变化，本批未制作。";
        if (!stillEquipped() || !stationMatches(level)) return "工具、材料或台面已变化，本批未制作。";
        List<ItemStack> cargo = packedCargo(made.products());
        if (cargo == null) return "货物栏放不下本批成品或余件，请腾出空间。";
        if (!robot.spendEnergy(COST)) return "电量不足 200 EU，原料和工具保留。";
        inv.setStackInSlot(job.hand(), made.remainingTool());
        for (int i = 0; i < cargo.size(); i++) inv.setStackInSlot(RobotInventory.CARGO_START + i, cargo.get(i));
        ItemStack shown = made.products().stream().filter(product -> !product.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
        robot.showWorkpiece(job.bench(), robot.toolBenchTop(), shown);
        if (made.remainingTool().isEmpty()) robot.broadcastBreakEvent(job.hand() == 1 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        String notice;
        if (made.products().stream().anyMatch(product -> !product.isEmpty() && id(product).equals(job.plan().output()))) {
            RobotTaskBook.completedCraft(robot, job.plan().output(), job.plan().recipe(), id(job.tool()), id(job.material()));
            notice = "已完成一批" + display(job.plan().output()) + "，成品在货物栏。";
        } else {
            notice = "已加工一批，这次没有产出目标物品。";
        }
        robot.workStatus(notice);
        owner.displayClientMessage(Component.literal(notice), true);
        return "";
    }

    private List<ItemStack> packedCargo(List<ItemStack> products) {
        var cargo = new ArrayList<ItemStack>(RobotInventory.SIZE - RobotInventory.CARGO_START);
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE; i++) cargo.add(robot.inventory().getStackInSlot(i).copy());
        cargo.get(job.input() - RobotInventory.CARGO_START).shrink(job.plan().materialCount());
        for (var product : products) {
            if (product.isEmpty()) continue;
            int left = product.getCount();
            for (var slot : cargo) {
                if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, product)) {
                    int take = Math.min(left, Math.max(0, Math.min(64, slot.getMaxStackSize()) - slot.getCount()));
                    slot.grow(take); left -= take;
                }
            }
            for (int i = 0; left > 0 && i < cargo.size(); i++) {
                if (!cargo.get(i).isEmpty()) continue;
                int take = Math.min(left, Math.min(64, product.getMaxStackSize()));
                cargo.set(i, product.copyWithCount(take)); left -= take;
            }
            if (left > 0) return null;
        }
        return cargo;
    }

    public void finish(String reason) {
        if (job == null) return;
        if (committed) reason = null;
        var owner = robot.level() instanceof ServerLevel level ? level.getServer().getPlayerList().getPlayer(job.owner()) : null;
        job = null; robot.clearToolStroke(); robot.setWorkTaskActive(false); robot.getNavigation().stop();
        if (reason != null) {
            robot.workStatus(reason);
            if (owner != null) owner.displayClientMessage(Component.literal(reason), true);
        }
    }

    private static ResourceLocation id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()); }
    private static String display(ResourceLocation id) { return RobotChatItems.displayName(BuiltInRegistries.ITEM.get(id)); }
}
