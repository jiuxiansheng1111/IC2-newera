package com.ic2newera.gametest;

import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import com.ic2newera.robot.building.RobotBuildPlan;
import com.ic2newera.robot.building.RobotBuildTask;
import com.ic2newera.robot.building.RobotPowerCable;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** Shared native world setup and assertions for isolated GameTest fixtures. */
public final class RobotFixtureSupport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int WIDTH = 32;
    private static final int DEPTH = 24;
    private static final int HEIGHT = 12;

    private RobotFixtureSupport() { }

    /**
     * Clears the template volume above the marker and creates a native support floor at local y=0.
     * GameTest structure loading places the structure relative to StructureBlockEntity.structurePos
     * (default (0,1,0)); helper.absolutePos is relative to the marker itself, so template floor blocks
     * can be one block above the helper's reported local coordinates. Creating/verifying the floor
     * through the live level avoids relying on that template offset and prevents robots spawning
     * inside the support block.
     */
    public static void prepareGround(GameTestHelper helper, String label, BlockPos robotSpawn) {
        ServerLevel level = helper.getLevel();
        for (int y = 1; y < HEIGHT; y++) {
            for (int z = 0; z < DEPTH; z++) for (int x = 0; x < WIDTH; x++)
                level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), 3);
        }
        for (int z = 0; z < DEPTH; z++) for (int x = 0; x < WIDTH; x++) {
            BlockPos local = new BlockPos(x, 0, z);
            if (local.equals(BlockPos.ZERO)) continue; // Keep the GameTest structure origin marker intact.
            level.setBlock(helper.absolutePos(local), Blocks.STONE.defaultBlockState(), 3);
        }

        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos feet = helper.absolutePos(robotSpawn);
        BlockPos support = helper.absolutePos(robotSpawn.below());
        BlockState supportState = helper.getBlockState(robotSpawn.below());
        LOGGER.info("[RobotGameTest] {} setup origin={} feet={} support={} state={}", label,
                origin.toShortString(), feet.toShortString(), support.toShortString(), supportState);
        if (!supportState.isFaceSturdy(level, support, Direction.UP))
            throw new GameTestAssertException(label + " 测试地面未铺好：origin=" + origin.toShortString()
                    + " feet=" + feet.toShortString() + " support=" + support.toShortString()
                    + " state=" + supportState);
    }

    /** Supplies planned construction cargo over time, respecting non-stackable items such as water buckets. */
    public static void refillCargo(RobotEntity robot, Map<String, Integer> stock) {
        for (var entry : stock.entrySet()) {
            int remaining = entry.getValue();
            if (remaining <= 0) continue;
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item == null) throw new GameTestAssertException("施工材料未注册：" + entry.getKey());
            for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE && remaining > 0; slot++) {
                if (!robot.inventory().getStackInSlot(slot).isEmpty()) continue;
                int amount = Math.min(item.getMaxStackSize(), remaining);
                robot.inventory().setStackInSlot(slot, new ItemStack(item, amount));
                remaining -= amount;
            }
            stock.put(entry.getKey(), remaining);
        }
    }

    /** Builds a staged stock map in first-use order from the plan's actual placed parts. */
    public static LinkedHashMap<String, Integer> orderedMaterials(RobotBuildPlan plan) {
        LinkedHashMap<String, Integer> ordered = new LinkedHashMap<>();
        for (RobotBuildPlan.Part part : plan.parts()) {
            Item item = part.materialItem();
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) throw new GameTestAssertException("施工方案方块没有物品注册ID：" + part.blockId());
            ordered.merge(id.toString(), 1, Integer::sum);
        }
        if (!ordered.equals(plan.materials()))
            throw new GameTestAssertException("按施工顺序累计的物料数量与方案清单不同：ordered=" + ordered
                    + " plan=" + plan.materials());
        return ordered;
    }

    /** Stages a construction plan for production fixtures; cable parts use their native placement items. */
    public static void stagePlan(ServerLevel level, BlockPos origin, RobotBuildPlan plan, RobotBuildTask task) {
        for (int i = 0; i < plan.parts().size(); i++) {
            RobotBuildPlan.Part part = plan.parts().get(i);
            BlockPos target = origin.offset(part.offset());
            if (RobotPowerCable.forPart(part) != null)
                placeNativeCable(level, target, part);
            else level.setBlock(target, part.state(), 3);
            BlockState actual = level.getBlockState(target);
            BlockState expected = part.state();
            if (actual.getBlock() != expected.getBlock())
                throw new GameTestAssertException("测试场站原生放置失败 part=" + i + " block=" + part.blockId()
                        + " pos=" + target.toShortString() + " actual=" + actual);
            for (Map.Entry<String, String> property : part.properties().entrySet()) {
                Property<?> key = actual.getBlock().getStateDefinition().getProperty(property.getKey());
                if (key == null || !Objects.equals(actual.getValues().get(key), expected.getValues().get(key)))
                    throw new GameTestAssertException("测试场站属性不符 part=" + i + " block=" + part.blockId()
                            + " property=" + property.getKey() + " expected=" + property.getValue()
                            + " actual=" + (key == null ? "<missing>" : actual.getValues().get(key)));
            }
            task.markDone(i);
        }
    }

    /** Uses the selected native IC2 cable item and normal FakePlayer placement to initialize its tile. */
    public static void placeNativeCable(ServerLevel level, BlockPos target, RobotBuildPlan.Part part) {
        RobotPowerCable cable = RobotPowerCable.forPart(part);
        if (cable == null) throw new GameTestAssertException("计划部件不是受支持的原生IC2电缆：" + part);
        Item item = part.materialItem();
        if (item != cable.materialItem()) throw new GameTestAssertException("电缆材料物品与原生型号不一致：part="
                + part + " expected=" + cable.itemId() + " actual=" + ForgeRegistries.ITEMS.getKey(item));
        if (!level.getBlockState(target).isAir()) throw new GameTestAssertException("原生电缆目标已被占用：" + target);
        BlockPos support = target.below();
        if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP))
            throw new GameTestAssertException("原生电缆缺少实体支撑面：" + support);
        FakePlayer placer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "InsulatedCableFixture"));
        placer.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        // RobotBuildGoal uses sneaking for its native insulated-cable placement context.
        placer.setShiftKeyDown(true);
        placer.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 1.5);
        placer.setOnGround(true);
        ItemStack held = new ItemStack(item);
        placer.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0),
                Direction.UP, support, false);
        InteractionResult result = placer.gameMode.useItemOn(placer, level, held, InteractionHand.MAIN_HAND, hit);
        BlockState expected = part.state();
        BlockState actual = level.getBlockState(target);
        boolean matches = actual.getBlock() == expected.getBlock();
        for (Map.Entry<String, String> property : part.properties().entrySet()) {
            Property<?> key = actual.getBlock().getStateDefinition().getProperty(property.getKey());
            matches &= key != null && Objects.equals(actual.getValues().get(key), expected.getValues().get(key));
        }
        if (!result.consumesAction() || !matches)
            throw new GameTestAssertException("IC2原生电缆物品放置失败或计划属性错误：result=" + result
                    + " cable=" + cable + " expected=" + expected + " actual=" + actual + " item=" + item);
        placer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /** Retained for the shared-bus repair fixture, whose intentionally removed cable is copper. */
    public static void placeNativeInsulatedCable(ServerLevel level, BlockPos target, RobotBuildPlan.Part part) {
        if (RobotPowerCable.forPart(part) != RobotPowerCable.COPPER)
            throw new GameTestAssertException("断线恢复夹具需要铜缆部件：" + part);
        placeNativeCable(level, target, part);
    }

    public static int cargoCount(RobotEntity robot, Item item) {
        int total = 0;
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (!stack.isEmpty() && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    public static int cargoTotal(RobotEntity robot) {
        int total = 0;
        for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++)
            total += robot.inventory().getStackInSlot(slot).getCount();
        return total;
    }

    /** Checks each planned block and every explicit BlockState property after real RobotBuildGoal placement. */
    public static void assertPlanPlaced(ServerLevel level, BlockPos origin, RobotBuildPlan plan, String label) {
        for (int i = 0; i < plan.parts().size(); i++) {
            RobotBuildPlan.Part part = plan.parts().get(i);
            BlockPos pos = origin.offset(part.offset());
            BlockState actual = level.getBlockState(pos);
            BlockState expected = part.state();
            ResourceLocation actualId = ForgeRegistries.BLOCKS.getKey(actual.getBlock());
            if (!part.blockId().equals(String.valueOf(actualId)))
                throw new GameTestAssertException(label + " 方块类型不匹配 part=" + i + " expected="
                        + part.blockId() + " actual=" + actualId + " pos=" + pos.toShortString()
                        + " expectedState=" + expected + " actualState=" + actual);
            for (Map.Entry<String, String> property : part.properties().entrySet()) {
                Property<?> key = actual.getBlock().getStateDefinition().getProperty(property.getKey());
                if (key == null || !Objects.equals(actual.getValues().get(key), expected.getValues().get(key)))
                    throw new GameTestAssertException(label + " 属性不匹配 part=" + i + " block=" + part.blockId()
                            + " property=" + property.getKey() + " expected=" + property.getValue()
                            + " actual=" + (key == null ? "<missing>" : actual.getValues().get(key))
                            + " pos=" + pos.toShortString());
            }
            if (expected.getBlock() == Blocks.WATER && !actual.getFluidState().isSource())
                throw new GameTestAssertException(label + " 计划水方块不是源水：part=" + i + " pos=" + pos.toShortString()
                        + " actual=" + actual);
        }
    }
}
