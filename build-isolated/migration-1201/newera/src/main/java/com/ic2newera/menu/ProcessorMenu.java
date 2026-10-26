package com.ic2newera.menu;

import com.ic2newera.IC2NewEra;
import com.ic2newera.machine.ProcessorBlockEntity;
import com.ic2newera.machine.ProcessorKind;
import com.ic2newera.machine.ProcessorSlotRoles;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Shared menu with one/eight machine slots and server-backed Chinese slot roles. */
public final class ProcessorMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, IC2NewEra.MOD_ID);
    public static final RegistryObject<MenuType<ProcessorMenu>> TYPE = MENUS.register(
            "processor", () -> IForgeMenuType.create(ProcessorMenu::new));

    private final ProcessorBlockEntity machine;
    private final ProcessorKind kind;
    private final int machineSlotCount;
    private final int playerInventoryStart;
    private final ContainerData data;

    public ProcessorMenu(int containerId, Inventory playerInventory, FriendlyByteBuf openingData) {
        this(containerId, playerInventory, getMachine(playerInventory, openingData));
    }

    public ProcessorMenu(int containerId, Inventory playerInventory, ProcessorBlockEntity machine) {
        super(TYPE.get(), containerId);
        this.machine = machine;
        this.kind = machine.kind();
        this.machineSlotCount = kind.machineSlots();
        this.data = machine.containerData();
        addMachineSlots();
        playerInventoryStart = machineSlotCount;
        addPlayerInventory(playerInventory);
        addDataSlots(data);
    }

    private static ProcessorBlockEntity getMachine(Inventory inventory, FriendlyByteBuf buffer) {
        var pos = buffer.readBlockPos();
        if (inventory.player.level().getBlockEntity(pos) instanceof ProcessorBlockEntity machine) return machine;
        throw new IllegalStateException("Processor menu opened without a matching block entity at " + pos);
    }

    private void addMachineSlots() {
        if (kind == ProcessorKind.NON_METAL) {
            addSlot(new ProcessorSlot(machine, 0, 43, 51));
            addSlot(new ProcessorSlot(machine, kind.outputSlot(), 115, 51));
            return;
        }
        if (kind == ProcessorKind.ALLOY) {
            for (int slot = 0; slot < kind.inputSlots(); slot++)
                addSlot(new ProcessorSlot(machine, slot, 43 + slot * 18, 51));
            addSlot(new ProcessorSlot(machine, kind.outputSlot(), 128, 51));
            return;
        }
        if (kind == ProcessorKind.ADVANCED) {
            addSlot(new ProcessorSlot(machine, 0, 43, 51));
            addSlot(new ProcessorSlot(machine, kind.outputSlot(), 128, 51));
            return;
        }
        for (int slot = 0; slot < kind.inputSlots(); slot++) {
            int x = 26 + (slot % 3) * 20;
            int y = 42 + (slot / 3) * 18;
            addSlot(new ProcessorSlot(machine, slot, x, y));
        }
        addSlot(new ProcessorSlot(machine, kind.outputSlot(), 128, 51));
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 99 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 8 + column * 18, 153));
    }

    public ProcessorBlockEntity machine() { return machine; }
    public ProcessorKind kind() { return kind; }
    public int machineSlotCount() { return machineSlotCount; }
    public int progress() { return data.get(1); }
    public int duration() { return data.get(2); }
    public int energy() { return data.get(0); }
    public int energyCapacity() { return kind.energyCapacity(); }
    public int statusOrdinal() { return data.get(3); }
    public ProcessorSlotRoles.Role roleForMenuSlot(int index) {
        return index >= 0 && index < machineSlotCount ? ProcessorSlotRoles.role(kind, index) : null;
    }

    @Override
    public boolean stillValid(Player player) {
        if (machine.isRemoved() || player.level().getBlockEntity(machine.getBlockPos()) != machine) return false;
        return player.distanceToSqr(machine.getBlockPos().getX() + 0.5, machine.getBlockPos().getY() + 0.5,
                machine.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int inventoryEnd = playerInventoryStart + 27;
        int totalEnd = slots.size();
        if (index < machineSlotCount) {
            if (!moveItemStackTo(stack, playerInventoryStart, totalEnd, true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, kind.inputSlots(), false)) {
            if (index < inventoryEnd) {
                if (!moveItemStackTo(stack, inventoryEnd, totalEnd, false)) return ItemStack.EMPTY;
            } else if (!moveItemStackTo(stack, playerInventoryStart, inventoryEnd, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    private static final class ProcessorSlot extends Slot {
        private final ProcessorBlockEntity machine;

        private ProcessorSlot(ProcessorBlockEntity machine, int index, int x, int y) {
            super(machine, index, x, y);
            this.machine = machine;
        }

        @Override public boolean mayPlace(ItemStack stack) { return machine.canPlaceItem(getSlotIndex(), stack); }
    }
}
