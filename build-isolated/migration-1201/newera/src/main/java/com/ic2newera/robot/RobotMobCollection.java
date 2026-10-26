package com.ic2newera.robot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Set;

/** Vanilla living-entity sources for exact-item collection. Only real world drops count. */
public final class RobotMobCollection {
    private static final Map<String, Set<String>> SOURCES = Map.ofEntries(
            Map.entry("minecraft:chicken", Set.of("minecraft:chicken", "minecraft:feather")),
            Map.entry("minecraft:pig", Set.of("minecraft:porkchop")),
            Map.entry("minecraft:cow", Set.of("minecraft:beef", "minecraft:leather")),
            Map.entry("minecraft:sheep", Set.of("minecraft:mutton")),
            Map.entry("minecraft:rabbit", Set.of("minecraft:rabbit", "minecraft:rabbit_hide", "minecraft:rabbit_foot")),
            Map.entry("minecraft:enderman", Set.of("minecraft:ender_pearl")),
            Map.entry("minecraft:skeleton", Set.of("minecraft:arrow", "minecraft:bone")),
            Map.entry("minecraft:stray", Set.of("minecraft:arrow", "minecraft:bone", "minecraft:tipped_arrow")),
            Map.entry("minecraft:zombie", Set.of("minecraft:rotten_flesh")),
            Map.entry("minecraft:husk", Set.of("minecraft:rotten_flesh")),
            Map.entry("minecraft:drowned", Set.of("minecraft:rotten_flesh")),
            Map.entry("minecraft:spider", Set.of("minecraft:string", "minecraft:spider_eye")),
            Map.entry("minecraft:cave_spider", Set.of("minecraft:string", "minecraft:spider_eye")),
            Map.entry("minecraft:blaze", Set.of("minecraft:blaze_rod")),
            Map.entry("minecraft:witch", Set.of("minecraft:glass_bottle", "minecraft:glowstone_dust", "minecraft:gunpowder",
                    "minecraft:redstone", "minecraft:spider_eye", "minecraft:stick", "minecraft:sugar"))
    );
    private static final Set<String> RAW_MEAT = Set.of(
            "minecraft:chicken", "minecraft:porkchop", "minecraft:beef", "minecraft:mutton", "minecraft:rabbit");
    private static final Map<String, Set<String>> PLAYER_KILL_DROPS = Map.of(
            "minecraft:rabbit", Set.of("minecraft:rabbit_foot"),
            "minecraft:stray", Set.of("minecraft:tipped_arrow"),
            "minecraft:spider", Set.of("minecraft:spider_eye"),
            "minecraft:cave_spider", Set.of("minecraft:spider_eye"),
            "minecraft:blaze", Set.of("minecraft:blaze_rod")
    );

    private RobotMobCollection() { }

    /** Includes egg collection: eggs are laid by chickens and never authorize killing one. */
    public static boolean supports(RobotCollectionTask task) {
        if (task == null) return false;
        if (task.matches(new ItemStack(Items.EGG))) return true;
        String item = ForgeRegistries.ITEMS.getKey(task.target().getItem()).toString();
        return SOURCES.values().stream().anyMatch(items -> items.contains(item));
    }

    public static boolean isEggTask(RobotCollectionTask task) {
        return task != null && task.matches(new ItemStack(Items.EGG));
    }

    /** Whether the entity is a mapped source for this exact collection target, regardless of attack eligibility. */
    public static boolean isSource(RobotCollectionTask task, LivingEntity target) {
        if (task == null || target == null) return false;
        var entityKey = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        var itemKey = ForgeRegistries.ITEMS.getKey(task.target().getItem());
        return entityKey != null && itemKey != null
                && SOURCES.getOrDefault(entityKey.toString(), Set.of()).contains(itemKey.toString());
    }

    /** True only for a supported source in the active AUTO task's local, loaded work area. */
    public static boolean mayAttack(RobotEntity robot, LivingEntity target) {
        if (robot == null || target == null || target.level() != robot.level() || target.level().isClientSide
                || !target.isAlive() || !(robot.level() instanceof ServerLevel level)) return false;
        RobotCollectionTask task = robot.collectionTask();
        if (task == null || task.kind() != RobotCollectionTask.AUTO || isEggTask(task) || !supports(task)
                || task.remaining() <= 0 || !robot.powered() || robot.getOwnerUUID() == null) return false;
        if (!isSource(task, target)) return false;
        String entityId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType()).toString();
        String itemId = ForgeRegistries.ITEMS.getKey(task.target().getItem()).toString();
        BlockPos origin = robot.workOrigin(), pos = target.blockPosition();
        if (!level.hasChunkAt(pos) || pos.getX() < origin.getX() - 6 || pos.getX() > origin.getX() + 6
                || pos.getZ() < origin.getZ() - 6 || pos.getZ() > origin.getZ() + 6
                || pos.getY() < origin.getY() - 3 || pos.getY() > origin.getY() + 5) return false;
        ItemStack one = task.target();
        one.setCount(1);
        ItemStack left = one;
        for (int i = RobotInventory.CARGO_START; i < RobotInventory.SIZE && !left.isEmpty(); i++)
            left = robot.inventory().insertItem(i, left, true);
        if (!left.isEmpty()) return false;
        // Let the existing pickup goal consume a real drop before another mob is killed.
        AABB bounds = new AABB(origin.offset(-6, -3, -6), origin.offset(7, 6, 7));
        if (!level.getEntitiesOfClass(ItemEntity.class, bounds, item -> item.isAlive()
                && RobotItemOwnership.mayPickUp(robot, item) && task.matches(item.getItem())).isEmpty()) return false;

        if (PLAYER_KILL_DROPS.getOrDefault(entityId, Set.of()).contains(itemId)
                && !(robot.getOwner() instanceof Player)) {
            robot.workBlocked("该目标掉落需要主人在线参与击杀判定；主人上线前只拾取地面已有物品");
            return false;
        }
        if (RAW_MEAT.contains(itemId) && (EnchantmentHelper.getFireAspect(robot) > 0 || target.isOnFire())) {
            robot.workBlocked("生肉采集遇到火焰来源，可能只掉熟肉；请更换无火焰附加武器或等生物熄火后继续");
            return false;
        }
        return true;
    }
}
