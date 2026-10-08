package com.ic2newera.industrial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 144-slot warehouse endpoint. Fiber carries data; only adjacent item capabilities move materials. */
public final class TerminalBlockEntity extends OwnedIndustrialBlockEntity {
    public static final int SLOTS = 144;
    public static final int FILTERS = 9;
    public static final int CLOSED = 0, INPUT = 1, OUTPUT = 2, BOTH = 3;
    private final FaceRule[] rules = new FaceRule[6];
    private final ItemStackHandler inventory = new ItemStackHandler(SLOTS) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }
    };
    private final List<LazyOptional<IItemHandler>> ports = new ArrayList<>();
    private boolean aiManaged;
    private String connectionType = "未连接";
    private List<String> statusLines = List.of("终端机：12×12，共144格", "等待网络同步");
    private long lastRefresh = Long.MIN_VALUE;
    private int nextDirection;
    private long settingsRevision;

    public TerminalBlockEntity(BlockPos pos, BlockState state) {
        super(IndustrialBlockEntities.TERMINAL.get(), pos, state);
        for (Direction direction : Direction.values()) rules[direction.ordinal()] = new FaceRule(
                direction == Direction.DOWN ? OUTPUT : INPUT);
        createPorts();
    }
    private static final class FaceRule {
        int mode, reserve, limit = 16;
        final ItemStack[] filters = new ItemStack[FILTERS];
        FaceRule(int mode) { this.mode = mode; Arrays.fill(filters, ItemStack.EMPTY); }
        boolean matches(ItemStack stack) {
            boolean any = false;
            for (ItemStack filter : filters) {
                if (filter.isEmpty()) continue;
                any = true;
                if (filter.is(stack.getItem())) return true;
            }
            return !any;
        }
    }
    @Override protected int menuKind() { return 3; }
    @Override public Component getDisplayName() { return Component.literal("终端机"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new TerminalMenu(id, inventory, worldPosition, canManage(player));
    }
    public ItemStackHandler getInventory() { return inventory; }
    public int getFaceMode(Direction direction) { return rules[direction.ordinal()].mode; }
    public ItemStack getFilter(Direction direction, int index) {
        return index >= 0 && index < FILTERS ? rules[direction.ordinal()].filters[index].copy() : ItemStack.EMPTY;
    }
    public int getReserve(Direction direction) { return rules[direction.ordinal()].reserve; }
    public int getTransferLimit(Direction direction) { return rules[direction.ordinal()].limit; }
    public boolean isAiManaged() { return aiManaged; }
    public List<String> getStatusLines() { return statusLines; }
    public String getConnectionType() { return connectionType; }
    public long getSettingsRevision() { return settingsRevision; }
    public boolean setFaceRule(Player player, Direction direction, int mode, int reserve, int limit) {
        if (!canConfigure(player) || mode < CLOSED || mode > BOTH || reserve < 0 || reserve > 9216 || limit < 1 || limit > 64) return false;
        FaceRule rule = rules[direction.ordinal()];
        rule.mode = mode; rule.reserve = reserve; rule.limit = limit;
        settingsRevision++;
        markSynced();
        return true;
    }
    public boolean setFilter(Player player, Direction direction, int index, ItemStack stack) {
        if (!canConfigure(player) || index < 0 || index >= FILTERS) return false;
        // Filters name item types, never copy player NBT or consume a real stack.
        rules[direction.ordinal()].filters[index] = stack.isEmpty() ? ItemStack.EMPTY : new ItemStack(stack.getItem());
        settingsRevision++;
        markSynced();
        return true;
    }
    public boolean setAiManaged(Player player, boolean enabled) {
        if (!canConfigure(player)) return false;
        aiManaged = enabled;
        settingsRevision++;
        markSynced();
        return true;
    }
    /** Called only after the server validates network membership, core, player and proposal age. */
    boolean applyNetworkRule(Player player, long expectedRevision, Direction direction, int mode, int reserve,
                             int limit, List<ItemStack> filters, boolean managed) {
        if (!(level instanceof ServerLevel) || !canManage(player) || settingsRevision != expectedRevision
                || mode < CLOSED || mode > BOTH || reserve < 0 || reserve > 9216 || limit < 1 || limit > 64
                || filters.size() > FILTERS) return false;
        FaceRule rule = rules[direction.ordinal()];
        rule.mode = mode; rule.reserve = reserve; rule.limit = limit;
        Arrays.fill(rule.filters, ItemStack.EMPTY);
        for (int i = 0; i < filters.size(); i++) rule.filters[i] = new ItemStack(filters.get(i).getItem());
        aiManaged = managed;
        settingsRevision++;
        markSynced();
        return true;
    }
    private boolean canConfigure(Player player) {
        return level instanceof ServerLevel && canManage(player)
                && player.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5) <= 64;
    }
    public int count(ItemStack wanted) {
        int count = 0;
        for (int i = 0; i < SLOTS; i++) if (inventory.getStackInSlot(i).is(wanted.getItem())) count += inventory.getStackInSlot(i).getCount();
        return count;
    }
    private void createPorts() {
        for (Direction direction : Direction.values()) ports.add(LazyOptional.of(() -> new IItemHandler() {
            @Override public int getSlots() { return SLOTS; }
            @Override public ItemStack getStackInSlot(int slot) { return inventory.getStackInSlot(slot); }
            @Override public int getSlotLimit(int slot) { return inventory.getSlotLimit(slot); }
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                FaceRule rule = rules[direction.ordinal()];
                return (rule.mode & INPUT) != 0 && rule.matches(stack);
            }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return isItemValid(slot, stack) ? inventory.insertItem(slot, stack, simulate) : stack;
            }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemStack stack = inventory.getStackInSlot(slot);
                FaceRule rule = rules[direction.ordinal()];
                if ((rule.mode & OUTPUT) == 0 || stack.isEmpty() || !rule.matches(stack)) return ItemStack.EMPTY;
                int available = Math.max(0, count(stack) - rule.reserve);
                return inventory.extractItem(slot, Math.min(amount, available), simulate);
            }
        }));
    }
    @Nonnull @Override public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            // A directionless inventory must not bypass configured ports.
            return side == null || isRemoved() ? LazyOptional.empty() : ports.get(side.ordinal()).cast();
        }
        return super.getCapability(capability, side);
    }
    @Override public void invalidateCaps() {
        super.invalidateCaps();
        ports.forEach(LazyOptional::invalidate);
    }
    @Override public void reviveCaps() {
        super.reviveCaps();
        ports.clear(); createPorts();
    }
    @Override public void onLoad() { super.onLoad(); IndustrialNetworkIndex.onEntityLoad(this); }
    @Override public void setRemoved() { IndustrialNetworkIndex.onEntityRemoved(this); super.setRemoved(); }

    public void serverTick() {
        if (!(level instanceof ServerLevel server) || Math.floorMod(server.getGameTime(), 20) != Math.floorMod(worldPosition.asLong(), 20)
                || lastRefresh == server.getGameTime()) return;
        lastRefresh = server.getGameTime();
        var attachment = IndustrialNetworkTopology.attach(server, worldPosition);
        connectionType = attachment == null ? "未连接" : attachment.connectionType();
        List<IndustrialNetworkSwitchBlockEntity> component = attachment == null ? List.of()
                : IndustrialNetworkTopology.component(server, attachment.networkSwitch());
        boolean controller = aiManaged && IndustrialNetworkTopology.endpointsFor(server, component,
                IndustrialControlServerBlockEntity.class, 32).stream().anyMatch(candidate ->
                sharesNetworkWith(candidate) && candidate.isControlAvailable());
        if (controller) {
            // Rotate face priority; a BOTH port pulls OR pushes per cycle, never immediately returns its own input.
            for (int i = 0; i < 6; i++) transfer(server, Direction.values()[(nextDirection + i) % 6]);
            nextDirection = (nextDirection + 1) % 6;
        }
        int occupied = 0, items = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) { occupied++; items += stack.getCount(); }
        }
        List<String> lines = List.of("仓储：12×12，共144格；已用 " + occupied + " 格 / " + items + " 个",
                "网络：" + (attachment == null ? "未连接" : attachment.networkSwitch().getNetworkName()) + "；" + connectionType,
                "AI托管：" + (!aiManaged ? "关闭（被动存取可用）" : controller ? "运行中" : "等待同网服务器启用控制及AI核心"),
                "物料仅经相邻接口；数据线路不运送物品");
        if (!lines.equals(statusLines)) { statusLines = lines; markSynced(); }
    }
    private void transfer(ServerLevel server, Direction face) {
        FaceRule rule = rules[face.ordinal()];
        if (rule.mode == CLOSED) return;
        BlockPos neighborPos = worldPosition.relative(face);
        if (!server.hasChunkAt(neighborPos)) return;
        BlockEntity neighbor = server.getBlockEntity(neighborPos);
        if (neighbor == null || neighbor instanceof TerminalBlockEntity) return;
        IItemHandler other = neighbor.getCapability(ForgeCapabilities.ITEM_HANDLER, face.getOpposite()).orElse(null);
        if (other == null) return;
        int budget = rule.limit;
        // For BOTH, reserve also acts as the per-item input target; zero is output-first.
        if ((rule.mode & INPUT) != 0 && (rule.mode != BOTH || rule.reserve > 0)) {
            for (int i = 0; i < Math.min(other.getSlots(), 256) && budget > 0; i++) {
                ItemStack peek = other.extractItem(i, budget, true);
                if (peek.isEmpty() || !rule.matches(peek)) continue;
                if (rule.mode == BOTH) peek.setCount(Math.min(peek.getCount(), Math.max(0, rule.reserve - count(peek))));
                if (peek.isEmpty()) continue;
                ItemStack simulatedRemainder = ItemHandlerHelper.insertItemStacked(inventory, peek, true);
                int accepted = peek.getCount() - simulatedRemainder.getCount();
                if (accepted <= 0) continue;
                ItemStack extracted = other.extractItem(i, accepted, false);
                if (extracted.isEmpty()) continue;
                // Recheck actual stacks; dynamic adjacent inventories must not evade the face filter.
                ItemStack remainder = rule.matches(extracted) ? ItemHandlerHelper.insertItemStacked(inventory, extracted, false) : extracted;
                budget -= extracted.getCount() - remainder.getCount();
                returnRemainder(other, i, remainder);
            }
            if (budget != rule.limit) return;
        }
        if ((rule.mode & OUTPUT) != 0) {
            for (int i = 0; i < SLOTS && budget > 0; i++) {
                ItemStack peek = ports.get(face.ordinal()).orElseThrow(IllegalStateException::new).extractItem(i, budget, true);
                if (peek.isEmpty()) continue;
                ItemStack simulatedRemainder = ItemHandlerHelper.insertItemStacked(other, peek, true);
                int accepted = peek.getCount() - simulatedRemainder.getCount();
                if (accepted <= 0) continue;
                ItemStack extracted = inventory.extractItem(i, accepted, false);
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(other, extracted, false);
                budget -= extracted.getCount() - remainder.getCount();
                returnRemainder(inventory, i, remainder);
            }
        }
    }
    private void returnRemainder(IItemHandler source, int slot, ItemStack remainder) {
        if (remainder.isEmpty()) return;
        ItemStack left = source.insertItem(slot, remainder, false);
        if (!left.isEmpty()) left = ItemHandlerHelper.insertItemStacked(source, left, false);
        // Conforming item handlers return the simulated amount. Preserve leftovers if a dynamic source changes.
        if (!left.isEmpty()) left = ItemHandlerHelper.insertItemStacked(inventory, left, false);
        if (!left.isEmpty() && level != null) Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), left);
    }
    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Inventory", inventory.serializeNBT());
        writeSettings(tag);
    }
    private void writeSettings(CompoundTag tag) {
        tag.putLong("SettingsRevision", settingsRevision);
        tag.putBoolean("AiManaged", aiManaged);
        tag.putString("ConnectionType", connectionType);
        for (Direction direction : Direction.values()) {
            FaceRule rule = rules[direction.ordinal()];
            CompoundTag face = new CompoundTag();
            face.putInt("Mode", rule.mode); face.putInt("Reserve", rule.reserve); face.putInt("Limit", rule.limit);
            ListTag filters = new ListTag();
            for (ItemStack filter : rule.filters) filters.add(filter.save(new CompoundTag()));
            face.put("Filters", filters); tag.put("Face" + direction.ordinal(), face);
        }
        ListTag lines = new ListTag();
        for (String line : statusLines) { CompoundTag value = new CompoundTag(); value.putString("Text", line); lines.add(value); }
        tag.put("StatusLines", lines);
    }
    @Override public CompoundTag getUpdateTag() {
        // Inventory goes only through the authenticated menu, not to every chunk viewer.
        CompoundTag tag = new CompoundTag();
        saveOwner(tag); writeSettings(tag);
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag);
        settingsRevision = tag.getLong("SettingsRevision");
        if (tag.contains("Inventory", Tag.TAG_COMPOUND)) {
            CompoundTag stored = tag.getCompound("Inventory").copy();
            stored.putInt("Size", SLOTS);
            inventory.deserializeNBT(stored);
        }
        aiManaged = tag.getBoolean("AiManaged");
        connectionType = tag.getString("ConnectionType");
        for (Direction direction : Direction.values()) {
            if (!tag.contains("Face" + direction.ordinal(), Tag.TAG_COMPOUND)) continue;
            CompoundTag face = tag.getCompound("Face" + direction.ordinal());
            FaceRule rule = rules[direction.ordinal()];
            rule.mode = Math.max(CLOSED, Math.min(BOTH, face.getInt("Mode")));
            rule.reserve = Math.max(0, Math.min(9216, face.getInt("Reserve")));
            rule.limit = Math.max(1, Math.min(64, face.getInt("Limit")));
            ListTag filters = face.getList("Filters", Tag.TAG_COMPOUND);
            for (int i = 0; i < FILTERS; i++) {
                ItemStack stack = i < filters.size() ? ItemStack.of(filters.getCompound(i)) : ItemStack.EMPTY;
                rule.filters[i] = stack.isEmpty() ? ItemStack.EMPTY : new ItemStack(stack.getItem());
            }
        }
        ListTag lines = tag.getList("StatusLines", Tag.TAG_COMPOUND);
        if (!lines.isEmpty()) {
            List<String> loaded = new ArrayList<>();
            for (int i = 0; i < Math.min(4, lines.size()); i++) loaded.add(lines.getCompound(i).getString("Text"));
            statusLines = List.copyOf(loaded);
        }
    }
}
