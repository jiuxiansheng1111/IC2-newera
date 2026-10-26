package com.ic2newera.robot.cooking;

import com.ic2newera.robot.RobotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.utility.ItemUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Tracks the real empty bucket emitted by a robot-bound Farmer's Delight reverse recipe. */
public final class FarmerRemainderTracking {
    private static final String POT_KEY = "IC2NewEraFDWaterRemainder";
    private static final String ENTITY_KEY = com.ic2newera.robot.RobotItemOwnership.NATIVE_COOKING_REMAINDER;
    private static final int DATA_VERSION = 1;
    private static final ThreadLocal<Deque<SpawnContext>> SPAWN_CONTEXT =
            ThreadLocal.withInitial(ArrayDeque::new);

    private FarmerRemainderTracking() { }

    private static final class SpawnContext {
        final CookingPotBlockEntity pot;
        final ServerLevel level;
        final UUID ticket;
        final UUID robot;
        final UUID owner;
        final ResourceLocation dimension;
        final BlockPos pos;
        final ResourceLocation recipe;
        final ItemStack expectedStack;
        final double x, y, z, dx, dy, dz;
        ItemEntity observed;

        SpawnContext(CookingPotBlockEntity pot, ServerLevel level, CompoundTag ticket, ItemStack expectedStack,
                     double x, double y, double z, double dx, double dy, double dz) {
            this.pot = pot;
            this.level = level;
            this.ticket = ticket.getUUID("Ticket");
            this.robot = ticket.getUUID("Robot");
            this.owner = ticket.getUUID("Owner");
            this.dimension = ResourceLocation.tryParse(ticket.getString("Dimension"));
            this.pos = BlockPos.of(ticket.getLong("PotPos"));
            this.recipe = ResourceLocation.tryParse(ticket.getString("Recipe"));
            this.expectedStack = expectedStack.copy();
            this.x = x;
            this.y = y;
            this.z = z;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    /** True only for a live server pot that has no existing robot ticket. */
    public static boolean canUsePot(CookingPotBlockEntity pot) {
        if (pot == null || pot.isRemoved() || !pot.hasLevel()
                || !(pot.getLevel() instanceof ServerLevel level)
                || !level.hasChunkAt(pot.getBlockPos())) return false;
        return !pot.getPersistentData().contains(POT_KEY);
    }

    /** Installs an identity-only ticket before robot cargo is withdrawn. */
    public static boolean bind(RobotEntity robot, CookingPotBlockEntity pot, RobotCookingState state) {
        if (!canUsePot(pot) || robot == null || state == null
                || !(pot.getLevel() instanceof ServerLevel level)
                || robot.level() != level || state.nativeBatchId() == null
                || state.phase() != 1
                || !RobotCookingState.FARMERS_DELIGHT.equals(state.backend())
                || !state.dimension().equals(level.dimension().location())
                || !state.potPos().equals(pot.getBlockPos())
                || robot.getOwnerUUID() == null) return false;

        for (int slot = 0; slot < CookingPotBlockEntity.INVENTORY_SIZE; slot++)
            if (!pot.getInventory().getStackInSlot(slot).isEmpty()) return false;

        ItemStack[] planned = state.loadedSlots();
        if (!planned[CookingPotBlockEntity.MEAL_DISPLAY_SLOT].isEmpty()
                || !planned[CookingPotBlockEntity.OUTPUT_SLOT].isEmpty()
                || state.expectedOutput().getCount() < 1 || state.expectedOutput().getCount() > 9
                || !sameStack(planned[CookingPotBlockEntity.CONTAINER_SLOT],
                        new ItemStack(Items.BOWL, state.expectedOutput().getCount())))
            return false;
        int waterBuckets = 0;
        for (int slot = 0; slot < CookingPotBlockEntity.MEAL_DISPLAY_SLOT; slot++) {
            ItemStack stack = planned[slot];
            if (stack.isEmpty()) continue;
            if (stack.is(Items.WATER_BUCKET)) {
                if (stack.getCount() != 1 || Items.WATER_BUCKET.getCraftingRemainingItem() != Items.BUCKET)
                    return false;
                waterBuckets++;
            } else if (stack.hasCraftingRemainingItem()
                    || CookingPotBlockEntity.INGREDIENT_REMAINDER_OVERRIDES.containsKey(stack.getItem())) {
                return false;
            }
        }
        if (waterBuckets != 1) return false;
        if (!"ic2newera".equals(state.recipeId().getNamespace())
                || !state.recipeId().getPath().startsWith("kaleido_stockpot/")
                || level.getRecipeManager().byKey(state.recipeId()).isPresent()) return false;
        CookingPotRecipe bridgeRecipe = KaleidoToFarmerRecipeBridge.byId(level, state.recipeId()).orElse(null);
        if (bridgeRecipe == null
                || !sameStack(bridgeRecipe.getResultItem(level.registryAccess()), state.expectedOutput())
                || !sameStack(bridgeRecipe.getOutputContainer(), new ItemStack(Items.BOWL)))
            return false;

        CompoundTag ticket = new CompoundTag();
        ticket.putInt("Version", DATA_VERSION);
        ticket.putUUID("Ticket", state.nativeBatchId());
        ticket.putUUID("Robot", robot.getUUID());
        ticket.putUUID("Owner", robot.getOwnerUUID());
        ticket.putString("Dimension", state.dimension().toString());
        ticket.putLong("PotPos", state.potPos().asLong());
        ticket.putString("Recipe", state.recipeId().toString());
        ticket.put("ExpectedOutput", state.expectedOutput().save(new CompoundTag()));
        ticket.put("LoadedSlots", writeSlots(planned));
        ticket.putBoolean("EmissionStarted", false);
        ticket.putBoolean("Failed", false);
        ticket.putBoolean("Consumed", false);
        ticket.putBoolean("Collected", false);
        pot.getPersistentData().put(POT_KEY, ticket);
        pot.setChanged();
        return true;
    }

    /** Allows ordinary FD matching without a ticket; a ticketed batch is never cooked twice. */
    public static boolean allowMatching(CookingPotBlockEntity pot) {
        if (pot == null || !pot.getPersistentData().contains(POT_KEY)) return true;
        CompoundTag ticket = readTicket(pot);
        return ticket != null && !ticket.getBoolean("EmissionStarted") && !ticket.getBoolean("Failed");
    }

    /** Blocks a ticketed pot from processing a different recipe or altered contents. */
    public static boolean allowProcess(CookingPotBlockEntity pot, CookingPotRecipe recipe) {
        if (pot == null || !pot.getPersistentData().contains(POT_KEY)) return true;
        CompoundTag ticket = readTicket(pot);
        if (ticket == null) {
            markPotFailed(pot);
            return false;
        }
        if (recipe == null || ticket.getBoolean("Failed") || ticket.getBoolean("EmissionStarted")
                || !ticket.getString("Recipe").equals(recipe.getId().toString())
                || !liveInventoryMatches(pot, ticket)) {
            fail(ticket, pot);
            return false;
        }
        return true;
    }

    /** Called only by the exact FD ItemUtils.spawnItemEntity redirect. */
    public static void spawnNative(CookingPotBlockEntity pot, Level level, ItemStack stack,
                                   double x, double y, double z, double dx, double dy, double dz) {
        CompoundTag ticket = readTicket(pot);
        if (ticket == null || !ticketMatchesPot(ticket, pot, level)
                || !(level instanceof ServerLevel serverLevel)
                || stack == null || stack.getCount() != 1 || !stack.is(Items.BUCKET)) {
            if (ticket != null) fail(ticket, pot);
            ItemUtils.spawnItemEntity(level, stack, x, y, z, dx, dy, dz);
            return;
        }
        if (ticket.getBoolean("EmissionStarted") || ticket.getBoolean("Failed")) {
            fail(ticket, pot);
            ItemUtils.spawnItemEntity(level, stack, x, y, z, dx, dy, dz);
            return;
        }

        ticket.putBoolean("EmissionStarted", true);
        ticket.remove("EntityUUID");
        pot.setChanged();
        SpawnContext context = new SpawnContext(pot, serverLevel, ticket, stack, x, y, z, dx, dy, dz);
        Deque<SpawnContext> contexts = SPAWN_CONTEXT.get();
        contexts.push(context);
        try {
            ItemUtils.spawnItemEntity(level, stack, x, y, z, dx, dy, dz);
        } finally {
            contexts.pop();
            if (contexts.isEmpty()) SPAWN_CONTEXT.remove();
            ItemEntity emitted = context.observed;
            if (emitted != null && !emitted.isRemoved()
                    && serverLevel.getEntity(emitted.getUUID()) == emitted
                    && emitted.getItem().getCount() == 1 && emitted.getItem().is(Items.BUCKET)) {
                ticket.putUUID("EntityUUID", emitted.getUUID());
            } else {
                ticket.putBoolean("Failed", true);
            }
            pot.setChanged();
        }
    }

    /** Called from processCooking's successful return after FD has shrunk its input stacks. */
    public static void onNativeProcessed(CookingPotBlockEntity pot) {
        CompoundTag ticket = readTicket(pot);
        if (ticket == null || !ticket.getBoolean("EmissionStarted") || ticket.getBoolean("Failed")) return;
        if (!inputsConsumed(pot) || !mealMatches(pot, ItemStack.of(ticket.getCompound("ExpectedOutput")))) {
            ticket.putBoolean("Failed", true);
            pot.setChanged();
            return;
        }
        ticket.putBoolean("Consumed", true);
        pot.setChanged();
    }

    /** Reports ownership errors first, then whether a real, consumed bucket is available. */
    public static String issue(RobotEntity robot, CookingPotBlockEntity pot, RobotCookingState state) {
        String ownerIssue = ownershipIssue(robot, pot, state);
        if (!ownerIssue.isEmpty()) return ownerIssue;
        CompoundTag ticket = readTicket(pot);
        if (ticket.getBoolean("Failed")) return "原生空桶余物或锅内结果无法核实；任务已停止，不会补造物品";
        if (!ticket.getBoolean("EmissionStarted")) return "尚未观察到原生空桶余物；任务等待真实配方完成";
        if (!ticket.getBoolean("Consumed")) return "原锅尚未同时确认食材消耗与预期成品数量；任务暂停等待核实";
        if (ticket.getBoolean("Collected")) return "";
        ItemEntity bucket = findBucket(robot, pot, state);
        if (bucket == null) return "原生空桶实体未加载或已被外部取走；任务暂停且不会重生";
        return "";
    }

    /** Validates the persisted robot, owner, batch, recipe, dimension, position and input snapshot. */
    public static String ownershipIssue(RobotEntity robot, CookingPotBlockEntity pot, RobotCookingState state) {
        if (robot == null || pot == null || state == null || state.nativeBatchId() == null)
            return "原生空桶票据或机器人烹饪状态缺失";
        if (!RobotCookingState.FARMERS_DELIGHT.equals(state.backend()))
            return "当前烹饪状态不是 Farmer's Delight 原生锅批次";
        CompoundTag ticket = readTicket(pot);
        if (ticket == null) return "原锅原生空桶票据不存在或损坏；不会重新绑定旧批次";
        if (!(robot.level() instanceof ServerLevel level) || pot.getLevel() != level
                || !level.dimension().location().equals(state.dimension())
                || !state.potPos().equals(pot.getBlockPos()))
            return "原生空桶票据与机器人当前维度或锅位置不一致";
        if (!ticketMatchesState(ticket, robot, pot, state))
            return "原生空桶票据的机器人、主人、批次、配方或投入快照不一致";
        return "";
    }

    /** Returns only the exact live empty-bucket entity recorded by this ticket. */
    public static ItemEntity findBucket(RobotEntity robot, CookingPotBlockEntity pot, RobotCookingState state) {
        if (!ownershipIssue(robot, pot, state).isEmpty()) return null;
        CompoundTag ticket = readTicket(pot);
        if (ticket == null || ticket.getBoolean("Failed") || !ticket.getBoolean("Consumed")
                || ticket.getBoolean("Collected") || !ticket.hasUUID("EntityUUID")
                || !(pot.getLevel() instanceof ServerLevel level)) return null;
        ItemEntity entity = level.getEntity(ticket.getUUID("EntityUUID")) instanceof ItemEntity item ? item : null;
        return entity != null && entityMatches(entity, ticket)
                && !entity.isRemoved() && entity.getItem().is(Items.BUCKET) && entity.getItem().getCount() == 1
                ? entity : null;
    }

    /** Records a caller-confirmed successful transfer; it never creates or copies an item. */
    public static void markCollected(CookingPotBlockEntity pot, RobotCookingState state) {
        CompoundTag ticket = readTicket(pot);
        if (ticket == null || state == null || state.nativeBatchId() == null
                || !ticket.getUUID("Ticket").equals(state.nativeBatchId())
                || !ticketMatchesStateWithoutRobot(ticket, pot, state)
                || ticket.getBoolean("Failed") || !ticket.getBoolean("Consumed")
                || !ticket.hasUUID("EntityUUID")) return;
        if (pot.getLevel() instanceof ServerLevel level) {
            ItemEntity entity = level.getEntity(ticket.getUUID("EntityUUID")) instanceof ItemEntity item ? item : null;
            if (entity != null && !entity.getItem().isEmpty()) return;
        } else return;
        ticket.putBoolean("Collected", true);
        pot.setChanged();
    }

    /** Clears only this batch's metadata. The real dropped bucket is left in the world. */
    public static boolean release(RobotEntity robot, CookingPotBlockEntity pot, RobotCookingState state) {
        if (!ownershipIssue(robot, pot, state).isEmpty()) return false;
        CompoundTag ticket = readTicket(pot);
        if (ticket == null || !ticket.getUUID("Ticket").equals(state.nativeBatchId())) return false;
        if (ticket.hasUUID("EntityUUID") && pot.getLevel() instanceof ServerLevel level) {
            ItemEntity entity = level.getEntity(ticket.getUUID("EntityUUID")) instanceof ItemEntity item ? item : null;
            if (entity != null && entityMatches(entity, ticket)) clearEntityMarker(entity);
        }
        pot.getPersistentData().remove(POT_KEY);
        pot.setChanged();
        return true;
    }

    @Mod.EventBusSubscriber(modid = "ic2newera")
    public static final class EntityHooks {
        private EntityHooks() { }

        @SubscribeEvent
        public static void onEntityJoin(EntityJoinLevelEvent event) {
            if (!(event.getLevel() instanceof ServerLevel level)
                    || !(event.getEntity() instanceof ItemEntity item)) return;
            if (!event.loadedFromDisk()) {
                Deque<SpawnContext> contexts = SPAWN_CONTEXT.get();
                SpawnContext context = contexts.peek();
                if (context == null) {
                    SPAWN_CONTEXT.remove();
                    return;
                }
                if (context.level != level || !matchesSpawn(context, item)) return;
                if (context.observed != null) {
                    CompoundTag existingTicket = readTicket(context.pot);
                    if (existingTicket != null) fail(existingTicket, context.pot);
                    return;
                }
                CompoundTag ticket = readTicket(context.pot);
                if (ticket == null || !ticketMatchesContext(ticket, context)
                        || !context.pot.getBlockPos().equals(context.pos)
                        || !level.dimension().location().equals(context.dimension)) return;
                item.setTarget(context.owner);
                item.setUnlimitedLifetime();
                CompoundTag marker = item.getPersistentData().getCompound(ENTITY_KEY);
                marker.putInt("Version", DATA_VERSION);
                marker.putUUID("Ticket", context.ticket);
                marker.putUUID("Batch", context.ticket);
                marker.putUUID("Robot", context.robot);
                marker.putUUID("Owner", context.owner);
                marker.putString("Dimension", context.dimension.toString());
                marker.putLong("PotPos", context.pos.asLong());
                marker.putString("Recipe", context.recipe.toString());
                item.getPersistentData().put(ENTITY_KEY, marker);
                ticket.putUUID("EntityUUID", item.getUUID());
                context.pot.setChanged();
                context.observed = item;
                return;
            }

            CompoundTag marker = entityMarker(item);
            if (marker == null) return;
            if (!ModList.get().isLoaded("farmersdelight")) {
                clearEntityMarker(item);
                return;
            }
            ResourceLocation dimension = ResourceLocation.tryParse(marker.getString("Dimension"));
            if (dimension == null || !dimension.equals(level.dimension().location())) {
                clearEntityMarker(item);
                return;
            }
            BlockPos pos = BlockPos.of(marker.getLong("PotPos"));
            if (!level.hasChunkAt(pos)) return;
            if (!(level.getBlockEntity(pos) instanceof CookingPotBlockEntity pot)) {
                clearEntityMarker(item);
                return;
            }
            CompoundTag ticket = readTicket(pot);
            if (ticket == null || !ticketMatchesEntity(marker, ticket, item)
                    || !ticketMatchesPot(ticket, pot, level)
                    || ticket.getBoolean("Collected")
                    || !ticket.hasUUID("EntityUUID") || !ticket.getUUID("EntityUUID").equals(item.getUUID()))
                clearEntityMarker(item);
            else {
                item.setTarget(ticket.getUUID("Owner"));
                item.setUnlimitedLifetime();
            }
        }
    }

    private static boolean ticketMatchesPot(CompoundTag ticket, CookingPotBlockEntity pot, Level level) {
        ResourceLocation dimension = ResourceLocation.tryParse(ticket.getString("Dimension"));
        return dimension != null && level instanceof ServerLevel
                && dimension.equals(level.dimension().location())
                && BlockPos.of(ticket.getLong("PotPos")).equals(pot.getBlockPos());
    }

    private static boolean ticketMatchesContext(CompoundTag ticket, SpawnContext context) {
        return ticket.getUUID("Ticket").equals(context.ticket)
                && ticket.getUUID("Robot").equals(context.robot)
                && ticket.getUUID("Owner").equals(context.owner)
                && ticket.getString("Recipe").equals(context.recipe.toString())
                && ticketMatchesPot(ticket, context.pot, context.level);
    }

    private static boolean ticketMatchesState(CompoundTag ticket, RobotEntity robot,
                                              CookingPotBlockEntity pot, RobotCookingState state) {
        return robot != null && robot.getOwnerUUID() != null
                && ticketMatchesStateWithoutRobot(ticket, pot, state)
                && ticket.hasUUID("Robot") && ticket.getUUID("Robot").equals(robot.getUUID())
                && ticket.hasUUID("Owner") && ticket.getUUID("Owner").equals(robot.getOwnerUUID());
    }

    private static boolean ticketMatchesStateWithoutRobot(CompoundTag ticket,
                                                          CookingPotBlockEntity pot, RobotCookingState state) {
        if (state == null || !RobotCookingState.FARMERS_DELIGHT.equals(state.backend())
                || ticket.getInt("Version") != DATA_VERSION || !ticket.hasUUID("Ticket")
                || !ticket.hasUUID("Robot") || !ticket.hasUUID("Owner")
                || !ticket.getUUID("Ticket").equals(state.nativeBatchId())
                || !ticket.getString("Recipe").equals(state.recipeId().toString())
                || !ticket.getString("Dimension").equals(state.dimension().toString())
                || ticket.getLong("PotPos") != state.potPos().asLong()
                || !ticketMatchesPot(ticket, pot, pot.getLevel())
                || !ticket.contains("ExpectedOutput", 10) || !ticket.contains("LoadedSlots", 9)) return false;
        ItemStack recordedOutput = ItemStack.of(ticket.getCompound("ExpectedOutput"));
        if (recordedOutput.isEmpty() || !sameStack(recordedOutput, state.expectedOutput())) return false;
        ItemStack[] recordedSlots = readSlots(ticket.getList("LoadedSlots", 10));
        if (recordedSlots == null) return false;
        ItemStack[] expectedSlots = state.loadedSlots();
        for (int i = 0; i < expectedSlots.length; i++)
            if (!sameStack(recordedSlots[i], expectedSlots[i])) return false;
        return true;
    }

    private static boolean ticketMatchesEntity(CompoundTag marker, CompoundTag ticket, ItemEntity item) {
        return marker.getInt("Version") == DATA_VERSION
                && marker.hasUUID("Ticket") && ticket.hasUUID("Ticket")
                && marker.hasUUID("Batch")
                && marker.hasUUID("Robot") && ticket.hasUUID("Robot")
                && marker.hasUUID("Owner") && ticket.hasUUID("Owner")
                && marker.getUUID("Ticket").equals(ticket.getUUID("Ticket"))
                && marker.getUUID("Batch").equals(ticket.getUUID("Ticket"))
                && marker.getUUID("Robot").equals(ticket.getUUID("Robot"))
                && marker.getUUID("Owner").equals(ticket.getUUID("Owner"))
                && marker.getString("Dimension").equals(ticket.getString("Dimension"))
                && marker.getLong("PotPos") == ticket.getLong("PotPos")
                && marker.getString("Recipe").equals(ticket.getString("Recipe"))
                && marker.getUUID("Ticket").equals(ticket.getUUID("Ticket"))
                && item.getAge() == -32768
                && item.getItem().is(Items.BUCKET) && item.getItem().getCount() == 1;
    }

    private static boolean entityMatches(ItemEntity item, CompoundTag ticket) {
        CompoundTag marker = entityMarker(item);
        return marker != null && ticketMatchesEntity(marker, ticket, item);
    }

    private static boolean isTrackedBucket(ItemEntity item) {
        return item.getItem().is(Items.BUCKET) && item.getItem().getCount() == 1;
    }

    private static boolean matchesSpawn(SpawnContext context, ItemEntity item) {
        if (!isTrackedBucket(item) || !sameStack(item.getItem(), context.expectedStack)) return false;
        var movement = item.getDeltaMovement();
        return Double.compare(item.getX(), context.x) == 0
                && Double.compare(item.getY(), context.y) == 0
                && Double.compare(item.getZ(), context.z) == 0
                && Double.compare(movement.x, context.dx) == 0
                && Double.compare(movement.y, context.dy) == 0
                && Double.compare(movement.z, context.dz) == 0;
    }

    private static CompoundTag readTicket(CookingPotBlockEntity pot) {
        if (pot == null || !pot.getPersistentData().contains(POT_KEY, 10)) return null;
        CompoundTag ticket = pot.getPersistentData().getCompound(POT_KEY);
        ResourceLocation dimension = ResourceLocation.tryParse(ticket.getString("Dimension"));
        ResourceLocation recipe = ResourceLocation.tryParse(ticket.getString("Recipe"));
        if (ticket.getInt("Version") != DATA_VERSION || !ticket.hasUUID("Ticket")
                || !ticket.hasUUID("Robot") || !ticket.hasUUID("Owner")
                || dimension == null || recipe == null || !ticket.contains("ExpectedOutput", 10)
                || !ticket.contains("LoadedSlots", 9)
                || !ticket.contains("EmissionStarted", 1) || !ticket.contains("Failed", 1)
                || !ticket.contains("Consumed", 1) || !ticket.contains("Collected", 1)
                || ItemStack.of(ticket.getCompound("ExpectedOutput")).isEmpty()
                || readSlots(ticket.getList("LoadedSlots", 10)) == null) return null;
        return ticket;
    }

    private static CompoundTag entityMarker(ItemEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(ENTITY_KEY, 10)) return null;
        CompoundTag marker = data.getCompound(ENTITY_KEY);
        return marker.getInt("Version") == DATA_VERSION && marker.hasUUID("Ticket")
                && marker.hasUUID("Batch") && marker.hasUUID("Robot") && marker.hasUUID("Owner")
                && ResourceLocation.tryParse(marker.getString("Dimension")) != null
                && ResourceLocation.tryParse(marker.getString("Recipe")) != null ? marker : null;
    }

    private static void clearEntityMarker(ItemEntity entity) {
        entity.getPersistentData().remove(ENTITY_KEY);
        entity.setTarget(null);
        entity.setExtendedLifetime();
    }

    private static boolean inputsConsumed(CookingPotBlockEntity pot) {
        for (int slot = 0; slot < CookingPotBlockEntity.MEAL_DISPLAY_SLOT; slot++)
            if (!pot.getInventory().getStackInSlot(slot).isEmpty()) return false;
        return true;
    }

    private static boolean liveInventoryMatches(CookingPotBlockEntity pot, CompoundTag ticket) {
        ItemStack[] expected = readSlots(ticket.getList("LoadedSlots", 10));
        if (expected == null) return false;
        for (int slot = 0; slot < CookingPotBlockEntity.INVENTORY_SIZE; slot++)
            if (!sameStack(pot.getInventory().getStackInSlot(slot), expected[slot])) return false;
        return true;
    }

    private static boolean mealMatches(CookingPotBlockEntity pot, ItemStack expected) {
        if (expected.isEmpty()) return false;
        ItemStack meal = pot.getInventory().getStackInSlot(CookingPotBlockEntity.MEAL_DISPLAY_SLOT);
        ItemStack output = pot.getInventory().getStackInSlot(CookingPotBlockEntity.OUTPUT_SLOT);
        int total = 0;
        if (!meal.isEmpty()) {
            if (!sameItemAndTags(meal, expected)) return false;
            total += meal.getCount();
        }
        if (!output.isEmpty()) {
            if (!sameItemAndTags(output, expected)) return false;
            total += output.getCount();
        }
        return total == expected.getCount();
    }

    private static boolean sameItemAndTags(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameTags(a, b);
    }

    private static boolean sameStack(ItemStack a, ItemStack b) {
        return a.isEmpty() ? b.isEmpty() : !b.isEmpty()
                && a.getCount() == b.getCount() && sameItemAndTags(a, b);
    }

    private static void fail(CompoundTag ticket, CookingPotBlockEntity pot) {
        ticket.putBoolean("Failed", true);
        pot.setChanged();
    }

    private static void markPotFailed(CookingPotBlockEntity pot) {
        CompoundTag ticket = pot.getPersistentData().getCompound(POT_KEY);
        ticket.putBoolean("Failed", true);
        pot.getPersistentData().put(POT_KEY, ticket);
        pot.setChanged();
    }

    private static ListTag writeSlots(ItemStack[] stacks) {
        ListTag list = new ListTag();
        for (int i = 0; i < stacks.length; i++) {
            CompoundTag slot = stacks[i].save(new CompoundTag());
            slot.putByte("Slot", (byte)i);
            list.add(slot);
        }
        return list;
    }

    private static ItemStack[] readSlots(ListTag list) {
        if (list.size() != RobotCookingState.SLOT_COUNT) return null;
        ItemStack[] stacks = new ItemStack[RobotCookingState.SLOT_COUNT];
        boolean[] seen = new boolean[RobotCookingState.SLOT_COUNT];
        for (int i = 0; i < list.size(); i++) {
            CompoundTag slot = list.getCompound(i);
            int index = slot.getByte("Slot") & 255;
            if (index >= stacks.length || seen[index]) return null;
            seen[index] = true;
            stacks[index] = ItemStack.of(slot);
        }
        for (boolean slotSeen : seen) if (!slotSeen) return null;
        return stacks;
    }
}
