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
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)
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
    public void tickPowerBanks() {
        if (robot.level.isClientSide || !robot.isAlive()) return;
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
        if (slot == 1) return "主手：放入斧、镐或武器；工作需要正确工具并消耗工具耐久/电量。";
        if (slot == 2) return "副手：可携带物品；不自动使用物品。";
        if (slot < CARGO_START) return "防具槽：仅允许对应部位的装备；提供原版防护并消耗耐久。";
        return "货物槽：接收掉落物；可放高级/核能充电宝自动给电池槽补电，初级/中级输出等级不足；满仓暂停工作。";
    }
}
