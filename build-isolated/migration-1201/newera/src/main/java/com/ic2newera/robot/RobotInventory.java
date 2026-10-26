package com.ic2newera.robot;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/** Battery, six vanilla equipment slots, and nine cargo slots. */
public final class RobotInventory extends ItemStackHandler {
    public static final int CARGO_START = 7, SIZE = 16;
    private static final EquipmentSlot[] EQUIPMENT = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private final RobotEntity robot;
    public RobotInventory(RobotEntity robot) { super(SIZE); this.robot = robot; }
    @Override public boolean isItemValid(int slot, ItemStack stack) {
        if (slot == 0) return stack.getItem() instanceof RobotBatteryItem;
        if (slot == 1) return isMainHandEquipment(stack);
        if (slot >= 3 && slot <= 6) return stack.canEquip(EQUIPMENT[slot - 1], robot);
        return slot != 1 && slot != 2 || !stack.isEmpty();
    }
    public static boolean isMainHandEquipment(ItemStack stack) {
        return !stack.isEmpty() && (stack.canPerformAction(net.minecraftforge.common.ToolActions.AXE_DIG)
                || stack.getItem() instanceof ic2.api.item.IMiningDrill
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.SHOVEL_DIG)
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.HOE_DIG)
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.SHEARS_DIG)
                || stack.getItem() instanceof ic2.core.item.tool.ItemElectricToolChainsaw
                || stack.getItem() instanceof net.minecraft.world.item.SwordItem
                || !stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).isEmpty());
    }
    @Override public int getSlotLimit(int slot) { return slot < CARGO_START ? 1 : 64; }
    @Override protected void onContentsChanged(int slot) {
        if (slot >= 1 && slot < CARGO_START) robot.setItemSlot(EQUIPMENT[slot - 1], getStackInSlot(slot));
    }
    public void syncEquipment() {
        for (int i = 0; i < EQUIPMENT.length; i++) super.setStackInSlot(i + 1, robot.getItemBySlot(EQUIPMENT[i]));
    }
    public void applyEquipment() { for (int i = 1; i < CARGO_START; i++) onContentsChanged(i); }
    /** Server-thread swap: the old tool goes into the selected tool's slot, even when cargo is full. */
    public boolean equipWorkTool(int source) {
        syncEquipment();
        if (source == 1) return true;
        if (source != 2 && (source < CARGO_START || source >= SIZE)) return false;
        ItemStack next = getStackInSlot(source), old = getStackInSlot(1);
        if (next.getCount() != 1 || !isMainHandEquipment(next)) return false;
        setStackInSlot(source, old);
        setStackInSlot(1, next);
        return true;
    }
    public void tickPowerBanks() {
        if (robot.level().isClientSide || !robot.isAlive()) return;
        for (int slot = CARGO_START; slot < SIZE; slot++) {
            ItemStack stack = getStackInSlot(slot);
            if (stack.getItem() instanceof PowerBankItem bank) bank.tickRobotCargo(stack, robot);
        }
    }
    public ItemStack insertCargo(ItemStack stack) {
        ItemStack remaining = stack;
        for (int i = CARGO_START; i < SIZE && !remaining.isEmpty(); i++) remaining = insertItem(i, remaining, false);
        return remaining;
    }
    public boolean hasCargoSpace() {
        for (int i = CARGO_START; i < SIZE; i++) {
            ItemStack stack = getStackInSlot(i);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) return true;
        }
        return false;
    }
    public String hint(int slot) {
        if (slot == 0) return "电池槽：仅机器人电池，需三级及以上充电；货物格放高级/核能充电宝可自动补电，死亡销毁电池。";
        if (slot == 1) return "主手：镐、斧、锹、锄、剪刀或武器；按实际掉落与采掘等级自动选工具，保留耐久/电量消耗。";
        if (slot == 2) return "副手：可携带物品；合适的采集工具可自动换到主手，原主手换回本槽。";
        if (slot < CARGO_START) return "防具槽：仅允许对应部位的装备；提供原版防护并消耗耐久。";
        return "货物槽：放工具、种子、建材、设备、烧矿原料/燃料或食材，接收成品。施工按清单扣料；生产线开始须留3个空格暂存，原矿和煤放方案进料箱。高级/核能充电宝可补电；满仓或物品变化时暂停并说明。";
    }
}
