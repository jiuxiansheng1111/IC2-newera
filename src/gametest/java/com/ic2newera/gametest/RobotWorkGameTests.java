package com.ic2newera.gametest;

import com.mojang.logging.LogUtils;
import com.ic2newera.robot.RobotBatteryItem;
import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotRegistry;
import com.ic2newera.robot.building.RobotBuildPlan;
import com.ic2newera.robot.building.RobotBuildTask;
import com.ic2newera.robot.building.RobotIndustrialPlanner;
import com.ic2newera.robot.building.RobotIndustrialProgram;
import com.ic2newera.robot.building.RobotNativeProductionIO;
import com.ic2newera.robot.building.RobotProductionDiagnostics;
import com.ic2newera.robot.building.RobotPowerCable;
import com.ic2newera.robot.building.RobotHousePlanner;
import ic2.api.items.electric.ElectricItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

@net.minecraftforge.gametest.GameTestHolder("ic2newera")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class RobotWorkGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPLATE = "industrial_test_space";
    private static final BlockPos SITE = new BlockPos(3, 1, 8);
    private static final BlockPos ROBOT_SPAWN = new BlockPos(15, 1, 13);

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void defaultHouseUsesRobotBuildGoalAndBatchedCargo(GameTestHelper helper) {
        RobotFixtureSupport.prepareGround(helper, "default house", ROBOT_SPAWN);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(SITE);
        RobotBuildPlan plan = RobotHousePlanner.plan("建一间木屋");
        Map<String, Integer> stock = RobotFixtureSupport.orderedMaterials(plan);

        RobotEntity robot = helper.spawn(RobotRegistry.ROBOT.get(), ROBOT_SPAWN);
        robot.setNoAi(false);
        Player owner = helper.makeMockPlayer();
        RobotBuildTask task = new RobotBuildTask(plan, owner.getUUID(), level.dimension().location(), origin);
        robot.tame(owner);
        robot.inventory().setStackInSlot(0, chargedBattery());
        robot.beginBuild(task);
        Map<String, Integer> remaining = stock;
        RobotBuildTask activeTask = task;
        boolean[] finished = {false};
        int[] elapsed = {0};
        helper.onEachTick(() -> {
            if (finished[0]) return;
            elapsed[0]++;
            RobotFixtureSupport.refillCargo(robot, remaining);
            if (activeTask.completed() && robot.mode() == RobotEntity.STAY && robot.status().contains("房屋施工完成")) {
                RobotFixtureSupport.assertPlanPlaced(level, origin, plan, "default house");
                CompoundTag tag = robot.saveWithoutId(new CompoundTag());
                if (tag.getCompound("RobotBuildTask").isEmpty()) {
                    // Build task state remains on the entity after completion; fail with useful context if it vanished.
                    throw new GameTestAssertException("房屋完成后施工记录丢失：" + robot.status());
                }
                finished[0] = true;
                helper.succeed();
                return;
            }
            if (elapsed[0] % 400 == 0)
                LOGGER.info("[RobotGameTest] default house tick={} {}", elapsed[0],
                        houseDiagnostic(robot, task, remaining));
            if (elapsed[0] >= 17980)
                throw new GameTestAssertException("默认木屋施工接近超时：" + houseDiagnostic(robot, task, remaining));
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 18000)
    public static void robotBuildGoalConstructsSolarPoweredTinLine(GameTestHelper helper) {
        RobotFixtureSupport.prepareGround(helper, "solar tin construction", ROBOT_SPAWN);
        ServerLevel level = helper.getLevel();
        RobotBuildPlan plan;
        RobotIndustrialProgram program;
        try {
            plan = RobotIndustrialPlanner.plan("搭建一条锡生产线，用低压太阳能和玻璃纤维电缆供电，产出 1 个锡锭");
            program = RobotIndustrialProgram.from(plan.metadata());
        } catch (RuntimeException failure) {
            throw new GameTestAssertException("太阳能锡线施工计划失败：" + failure.getMessage());
        }
        if (!program.target().toString().equals("ic2:ingot_tin") || !program.stillApproved())
            throw new GameTestAssertException("太阳能锡线目标或流程批准状态错误：target=" + program.target());
        for (RobotIndustrialProgram.Step step : program.steps()) {
            if (!RobotNativeProductionIO.recipeMatches(level, step))
                throw new GameTestAssertException("太阳能锡线真实配方预检失败：route=" + step.routeId()
                        + " machine=" + step.machine() + " inputs=" + step.inputs() + " -> " + step.output());
        }
        if (!"ic2:solar_panel_lv".equals(RobotIndustrialPlanner.powerSource(plan.metadata())))
            throw new GameTestAssertException("计划没有解析为低压太阳能供电。");
        if (RobotPowerCable.from(plan.metadata()) != RobotPowerCable.GLASS)
            throw new GameTestAssertException("实际施工用例未选择玻璃纤维电缆。");
        long panels = plan.parts().stream().filter(part -> part.blockId().equals("ic2:solar_panel_lv")).count();
        long water = plan.parts().stream().filter(part -> part.state().getBlock() == Blocks.WATER).count();
        if (panels < 1 || water < 1
                || plan.parts().stream().noneMatch(part -> part.blockId().equals("create:water_wheel"))
                || plan.parts().stream().noneMatch(part -> part.blockId().equals("create:encased_fan")))
            throw new GameTestAssertException("太阳能锡线施工计划缺少预期的发电、洗矿或风扇部件：solar="
                    + panels + " water=" + water);

        BlockPos origin = helper.absolutePos(SITE);
        Player owner = helper.makeMockPlayer();
        RobotBuildTask task = new RobotBuildTask(plan, owner.getUUID(), level.dimension().location(), origin);
        Map<String, Integer> remaining = RobotFixtureSupport.orderedMaterials(plan);
        RobotEntity robot = helper.spawn(RobotRegistry.ROBOT.get(), ROBOT_SPAWN);
        robot.setNoAi(false);
        robot.tame(owner);
        robot.inventory().setStackInSlot(0, chargedBattery());
        if (!robot.powered()) throw new GameTestAssertException("太阳能锡线施工机器人没有 IC2 电力。");
        robot.beginBuild(task);

        int[] elapsed = {0};
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) return;
            elapsed[0]++;
            RobotFixtureSupport.refillCargo(robot, remaining);
            if (task.completed() && robot.mode() == RobotEntity.PRODUCTION) {
                RobotFixtureSupport.assertPlanPlaced(level, origin, plan, "solar tin construction");
                if (remaining.values().stream().anyMatch(amount -> amount != 0))
                    throw new GameTestAssertException("施工报告完成但仍有未供材料：" + remaining
                            + "；" + houseDiagnostic(robot, task, remaining));
                List<RobotBuildPlan.Part> cables = plan.parts().stream()
                        .filter(part -> part.phase().equals("power_cable")).toList();
                if (cables.isEmpty()) throw new GameTestAssertException("太阳能锡线施工计划缺少电缆干线/分支。");
                if (cables.stream().anyMatch(part -> RobotPowerCable.forPart(part) != RobotPowerCable.GLASS
                        || part.materialItem() != RobotPowerCable.GLASS.materialItem()))
                    throw new GameTestAssertException("太阳能锡线施工计划没有统一使用原生玻纤电缆部件。");
                if (RobotFixtureSupport.cargoCount(robot, cables.get(0).materialItem()) != 0)
                    throw new GameTestAssertException("RobotBuildGoal完成后角色货舱仍有待施工的电缆。");
                String wiring = RobotProductionDiagnostics.powerProblem(level, task, "ic2:macerator");
                if (!wiring.isEmpty()) throw new GameTestAssertException("RobotBuildGoal放置的IC2原生玻璃纤维电缆未接通EnergyNet："
                        + wiring + RobotProductionDiagnostics.describe(robot, task, false));
                if (RobotFixtureSupport.cargoCount(robot, Items.WATER_BUCKET) != 0)
                    throw new GameTestAssertException("计划水源放置完成后仍残留水桶："
                            + RobotFixtureSupport.cargoCount(robot, Items.WATER_BUCKET));
                finished[0] = true;
                helper.succeed();
                return;
            }
            if (elapsed[0] % 400 == 0)
                LOGGER.info("[RobotGameTest] solar tin construction tick={} {}", elapsed[0],
                        houseDiagnostic(robot, task, remaining));
            if (elapsed[0] >= 17980)
                throw new GameTestAssertException("太阳能锡线 RobotBuildGoal 施工接近超时："
                        + houseDiagnostic(robot, task, remaining));
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 6000)
    public static void vanillaFurnaceSmeltsCargoIronOre(GameTestHelper helper) {
        RobotFixtureSupport.prepareGround(helper, "vanilla furnace smelting", ROBOT_SPAWN);
        ServerLevel level = helper.getLevel();
        RobotEntity robot = ownedRobot(helper);
        BlockPos furnacePos = helper.absolutePos(new BlockPos(16, 1, 13));
        BlockState furnace = Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.NORTH);
        level.setBlock(furnacePos, furnace, 3);
        robot.inventory().setStackInSlot(7, new ItemStack(Items.IRON_ORE));
        robot.inventory().setStackInSlot(8, new ItemStack(Items.COAL, 2));
        robot.setMode(RobotEntity.SMELTING);
        int[] elapsed = {0};
        int[] stableTicks = {-1};
        helper.onEachTick(() -> {
            elapsed[0]++;
            if (elapsed[0] % 400 == 0)
                LOGGER.info("[RobotGameTest] vanilla furnace smelting tick={} {}", elapsed[0],
                        furnaceDiagnostic(level, robot, furnacePos));
            int output = RobotFixtureSupport.cargoCount(robot, Items.IRON_INGOT);
            if (output > 1)
                throw new GameTestAssertException("原版单矿石配方产生了多于一件铁锭：" + output
                        + "；" + furnaceDiagnostic(level, robot, furnacePos));
            if (output == 1) {
                if (stableTicks[0] < 0) stableTicks[0] = 0;
                assertStandaloneSmeltResult(level, robot, furnacePos);
                if (++stableTicks[0] >= 400) helper.succeed();
            } else if (stableTicks[0] >= 0) {
                throw new GameTestAssertException("已交付的单个铁锭在稳定观察期内消失："
                        + furnaceDiagnostic(level, robot, furnacePos));
            }
            if (elapsed[0] >= 5990 && stableTicks[0] < 400)
                throw new GameTestAssertException("原版熔炼未在超时前交付并稳定观察铁锭：stable="
                        + Math.max(0, stableTicks[0]) + "/400 " + furnaceDiagnostic(level, robot, furnacePos));
        });
    }

    private static void assertStandaloneSmeltResult(ServerLevel level, RobotEntity robot, BlockPos furnacePos) {
        if (!(level.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnaceEntity))
            throw new GameTestAssertException("机器人输出成品时目标已不再是原版熔炉。");
        int oreCargo = RobotFixtureSupport.cargoCount(robot, Items.IRON_ORE);
        int coalCargo = RobotFixtureSupport.cargoCount(robot, Items.COAL);
        if (oreCargo != 0 || coalCargo != 1)
            throw new GameTestAssertException("原版熔炼货舱数量不符：iron_ore=" + oreCargo
                    + " coal=" + coalCargo + "，期望原矿消耗且两煤留一。" + furnaceDiagnostic(level, robot, furnacePos));
        if (!furnaceEntity.getItem(0).isEmpty() || !furnaceEntity.getItem(2).isEmpty())
            throw new GameTestAssertException("成品入货舱后原版熔炉输入/输出槽仍有物品："
                    + furnaceDiagnostic(level, robot, furnacePos));
        AABB nearby = new AABB(robot.blockPosition()).inflate(32.0, 8.0, 24.0);
        List<ItemEntity> droppedIngots = level.getEntitiesOfClass(ItemEntity.class, nearby,
                entity -> entity.isAlive() && entity.getItem().is(Items.IRON_INGOT));
        if (!droppedIngots.isEmpty())
            throw new GameTestAssertException("预期的一件铁锭已入货舱，但附近仍有地面铁锭："
                    + droppedIngots.stream().map(entity -> entity.blockPosition().toShortString()
                    + " x" + entity.getItem().getCount()).toList());
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 4000)
    public static void robotHarvestsMatureWheatThroughForgeFakePlayer(GameTestHelper helper) {
        RobotFixtureSupport.prepareGround(helper, "mature wheat harvest", ROBOT_SPAWN);
        ServerLevel level = helper.getLevel();
        RobotEntity robot = ownedRobot(helper);
        BlockPos farmland = helper.absolutePos(new BlockPos(16, 1, 13));
        BlockPos crop = farmland.above();
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 3);
        level.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE), 3);
        robot.setMode(RobotEntity.FARMING);
        helper.succeedWhen(() -> {
            int wheat = RobotFixtureSupport.cargoCount(robot, Items.WHEAT);
            if (wheat > 0) {
                if (!level.getBlockState(crop).isAir()
                        && !level.getBlockState(crop).is(Blocks.WHEAT))
                    throw new GameTestAssertException("作物收割后出现意外方块：" + level.getBlockState(crop));
                if (level.getBlockState(crop).is(Blocks.WHEAT)
                        && level.getBlockState(crop).getValue(CropBlock.AGE) == CropBlock.MAX_AGE)
                    throw new GameTestAssertException("成熟作物仍留在原位置，尚未完成真实收割");
                return;
            }
            throw new GameTestAssertException("成熟小麦未进入机器人货物槽，状态=" + robot.status()
                    + "，作物=" + level.getBlockState(crop));
        });
    }

    private static RobotEntity ownedRobot(GameTestHelper helper) {
        RobotEntity robot = helper.spawn(RobotRegistry.ROBOT.get(), ROBOT_SPAWN);
        robot.setNoAi(false);
        robot.tame(helper.makeMockPlayer());
        robot.inventory().setStackInSlot(0, chargedBattery());
        if (!robot.powered()) throw new GameTestAssertException("IC2 电池未正常供电。");
        return robot;
    }

    private static ItemStack chargedBattery() {
        ItemStack battery = new ItemStack(RobotRegistry.BATTERY.get());
        if (ElectricItem.MANAGER == null
                || ElectricItem.MANAGER.charge(battery, RobotBatteryItem.CAPACITY, 3, true, false) <= 0)
            throw new GameTestAssertException("IC2 电力管理器无法给机器人电池充电。");
        return battery;
    }

    private static String furnaceSlots(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) return "missing";
        return "input=" + furnace.getItem(0) + ",fuel=" + furnace.getItem(1) + ",output=" + furnace.getItem(2);
    }

    private static String furnaceDiagnostic(ServerLevel level, RobotEntity robot, BlockPos furnacePos) {
        StringBuilder cargo = new StringBuilder();
        for (int slot = 7; slot < 16; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (!stack.isEmpty()) cargo.append(slot).append('=')
                    .append(ForgeRegistries.ITEMS.getKey(stack.getItem())).append('x').append(stack.getCount()).append(',');
        }
        var recipe = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING,
                new net.minecraft.world.SimpleContainer(new ItemStack(Items.IRON_ORE)), level);
        return "状态=" + robot.status() + "，alive=" + robot.isAlive() + "，位置="
                + robot.blockPosition().toShortString() + " xyz="
                + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", robot.getX(), robot.getY(), robot.getZ())
                + "，货物槽=" + cargo + "，furnace=" + furnaceSlots(level, furnacePos)
                + "，iron_ore smelting recipe=" + recipe.map(found -> found.getId().toString()).orElse("<none>");
    }

    private static String houseDiagnostic(RobotEntity robot, RobotBuildTask task, Map<String, Integer> stock) {
        StringBuilder cargo = new StringBuilder();
        String nextPart = "<none>";
        for (int i = 0; i < task.plan().parts().size(); i++) {
            if (task.done(i)) continue;
            RobotBuildPlan.Part part = task.plan().parts().get(i);
            nextPart = i + " offset=" + part.offset().toShortString() + " block=" + part.blockId()
                    + " properties=" + part.properties() + " phase=" + part.phase();
            break;
        }
        var navigation = robot.getNavigation();
        Path path = navigation.getPath();
        for (int slot = 7; slot < 16; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (!stack.isEmpty()) cargo.append(slot).append('=').append(ForgeRegistries.ITEMS.getKey(stack.getItem()))
                    .append('x').append(stack.getCount()).append(',');
        }
        return "状态=" + robot.status() + "，alive=" + robot.isAlive() + "，health=" + robot.getHealth()
                + "，removed=" + robot.isRemoved() + "，NoAI=" + robot.isNoAi()
                + "，模式=" + robot.modeName() + "，位置="
                + robot.blockPosition().toShortString() + " xyz="
                + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", robot.getX(), robot.getY(), robot.getZ())
                + "，navDone=" + navigation.isDone() + " target=" + navigation.getTargetPos()
                + " path=" + (path == null ? "<null>" : "index=" + path.getNextNodeIndex() + "/"
                + path.getNodeCount() + " done=" + path.isDone() + " next="
                + (path.isDone() ? "done" : path.getNextNodePos()))
                + "，origin=" + task.origin().toShortString() + "，nextPart=" + nextPart
                + "，已施工=" + task.finished() + "/" + task.plan().parts().size()
                + "，机器人电量=" + robot.energy() + "，电池槽="
                + ForgeRegistries.ITEMS.getKey(robot.inventory().getStackInSlot(0).getItem()) + "x"
                + robot.inventory().getStackInSlot(0).getCount() + "/IC2charge="
                + RobotBatteryItem.charge(robot.inventory().getStackInSlot(0))
                + "，剩余材料=" + stock + "，货物槽=" + cargo;
    }

}
