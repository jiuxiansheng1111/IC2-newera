package com.ic2newera.robot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.FakePlayer;
import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItem;
import ic2.core.item.tool.ItemDrill;
import ic2.core.item.tool.ItemElectricToolChainsaw;
import java.util.ArrayList;
import java.util.List;

/** Loot previews only select a target. Native survival breaking is the sole producer of items. */
final class RobotHarvesting {
    record Choice(int slot, ItemStack tool, float speed, String reason, int previews) {
        boolean usable() { return slot >= 0; }
    }
    private RobotHarvesting() { }

    static boolean correctKind(ItemStack tool, BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return tool.canPerformAction(ToolActions.PICKAXE_DIG)
                || tool.getItem() instanceof ItemDrill;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return tool.canPerformAction(ToolActions.SHOVEL_DIG);
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return tool.canPerformAction(ToolActions.AXE_DIG)
                || tool.getItem() instanceof ItemElectricToolChainsaw;
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return tool.canPerformAction(ToolActions.HOE_DIG)
                || tool.canPerformAction(ToolActions.SHEARS_DIG) && tool.getDestroySpeed(state) > 1;
        return !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
    }
    private static LootParams.Builder lootParams(ServerLevel level, BlockPos pos, ItemStack tool, FakePlayer fake) {
        return new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, tool)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, fake)
                .withLuck(fake.getLuck());
    }
    static String requiredTool(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "镐";
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "锹";
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return "斧";
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return "锄或适用剪刀";
        return "采集工具";
    }
    static String toolProblem(ItemStack tool, BlockState state, ServerLevel level, BlockPos pos, FakePlayer fake) {
        boolean drill = tool.getItem() instanceof ItemDrill && state.is(BlockTags.MINEABLE_WITH_PICKAXE);
        if (!drill && !correctKind(tool, state)) return "缺少合适的" + requiredTool(state) + "，请放进机器人装备或货物槽";
        if (tool.getItem() instanceof ItemDrill mining) {
            if (ElectricItem.manager == null || !mining.breakBlock(tool.copy(), level, pos, state))
                return "电钻电量不足以完成这一方块的采掘，请充电或补充另一把工具";
        }
        if (tool.getItem() instanceof ItemElectricToolChainsaw saw
                && (ElectricItem.manager == null || !ElectricItem.manager.canUse(tool, saw.operationEnergyCost)))
            return "电锯电量不足以完成这一方块的采集，请充电或补充另一把工具";
        if (!tool.isEmpty() && tool.getItem() instanceof IElectricItem
                && ElectricItem.manager != null && ElectricItem.manager.getCharge(tool) <= 0)
            return "合适的电动工具没电，请充电或补充另一把工具";
        if (tool.isDamageableItem() && tool.getDamageValue() >= tool.getMaxDamage()) return "合适工具已损坏，请补充工具";
        if (!state.canHarvestBlock(level, pos, fake)) return "工具采掘等级不足，无法取得该方块掉落物";
        float speed = state.getDestroyProgress(fake, level, pos);
        return Float.isFinite(speed) && speed > 0 ? "" : "工具无法破坏这个方块，请检查工具模式、电量或方块限制";
    }
    static Choice choose(RobotEntity robot, ServerLevel level, BlockPos pos, FakePlayer fake, RobotCollectionTask task) {
        BlockState state = level.getBlockState(pos);
        List<Integer> slots = new ArrayList<>();
        slots.add(1);
        for (int slot = 2; slot < RobotInventory.SIZE; slot++) {
            if (slot > 2 && slot < RobotInventory.CARGO_START) continue;
            ItemStack stack = robot.inventory().getStackInSlot(slot);
            if (stack.getCount() == 1 && RobotInventory.isMainHandEquipment(stack)) slots.add(slot);
        }
        Choice best = null;
        String reason = "";
        int previews = 0;
        try {
            for (int slot : slots) {
                // Never let a mod's preview mutate the real tool or inventory.
                ItemStack tool = robot.inventory().getStackInSlot(slot).copy();
                fake.setItemInHand(InteractionHand.MAIN_HAND, tool);
                boolean matches = task == null;
                if (task != null) {
                    // One non-mutating preview per candidate; the actual block break remains the sole drop producer.
                    previews++;
                    matches = state.getDrops(lootParams(level, pos, tool, fake)).stream().anyMatch(task::matches);
                }
                if (!matches) continue;
                String problem = toolProblem(tool, state, level, pos, fake);
                if (!problem.isEmpty()) { reason = problem; continue; }
                float speed = state.getDestroyProgress(fake, level, pos);
                if (best == null || speed > best.speed()) best = new Choice(slot, tool.copy(), speed, "", 0);
            }
            if (best != null) return new Choice(best.slot(), best.tool(), best.speed(), "", previews);
            // Do not re-sample with an empty hand: that could disagree with the candidate preview and actual break.
            if (task != null && reason.isEmpty()) {
                if (task.matches(new ItemStack(state.getBlock().asItem())))
                    reason = "发现目标方块，但单方块掉落预览未命中；请检查工具等级、附魔和电量";
                else reason = "现有工具的单方块掉落预览未命中目标；任务保留，未重复抽样";
            }
            return new Choice(-1, ItemStack.EMPTY, 0, reason, previews);
        } catch (RuntimeException failure) {
            return new Choice(-1, ItemStack.EMPTY, 0, "该方块的掉落或工具接口无法读取，已跳过；任务保留", previews);
        } finally {
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }
}
