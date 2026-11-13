package com.ic2newera.field;

import earth.terrarium.adastra.common.entities.vehicles.Rocket;
import earth.terrarium.adastra.common.entities.vehicles.Lander;
import earth.terrarium.adastra.common.items.armor.SpaceSuitItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.ic2newera.robot.IndustrialPhoneItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-owned shared lunar expedition checklist and verified launch/landing journal. */
public final class LunarExpedition {
    private static final String DATA_NAME = "ic2newera_lunar_expeditions";
    private static final ResourceLocation MOON_ID = new ResourceLocation("ad_astra", "moon");
    private static final ResourceLocation COOKED_BEEF = new ResourceLocation("minecraft", "cooked_beef");
    private static final ResourceLocation TORCH = new ResourceLocation("minecraft", "torch");
    private static final int MAX_GROUPS = 512;
    private static final int MAX_SUPPLY_LINES = 64;
    private static final int MAX_SUPPLY_COUNT = 4096;
    private static final int MAX_TEAM_KEY = 96;
    private static final int CACHE_RADIUS = 8;
    private static final int LANDING_CONFIRM_WINDOW = 1200;
    private static final int FLIGHT_WINDOW = 24_000;

    private LunarExpedition() { }

    private static final class Flight {
        final long launchedAt;
        final String originDimension;
        boolean landed;
        Flight(long launchedAt, String originDimension, boolean landed) {
            this.launchedAt = launchedAt; this.originDimension = originDimension; this.landed = landed;
        }
    }

    private static final class Landing {
        final String dimension;
        final BlockPos position;
        final long arrivedAt;
        boolean confirmed;
        Landing(String dimension, BlockPos position, long arrivedAt, boolean confirmed) {
            this.dimension = dimension; this.position = position.immutable();
            this.arrivedAt = arrivedAt; this.confirmed = confirmed;
        }
    }

    private static final class Group {
        GlobalPos earthCache;
        GlobalPos moonCache;
        final LinkedHashMap<ResourceLocation, Integer> supplies = new LinkedHashMap<>();
        final Map<UUID, Flight> flights = new HashMap<>();
        final Map<UUID, Landing> landings = new HashMap<>();

        Group() {
            // Ordinary survival provisions. Rocket fuel and suit oxygen are verified
            // separately from the real Ad Astra rocket/suit state, never inferred here.
            supplies.put(COOKED_BEEF, 16);
            supplies.put(TORCH, 16);
        }
    }

    private static final class ExpeditionData extends SavedData {
        private final Map<String, Group> groups = new HashMap<>();

        static ExpeditionData load(CompoundTag tag) {
            ExpeditionData data = new ExpeditionData();
            ListTag list = tag.getList("Groups", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(list.size(), MAX_GROUPS); i++) {
                CompoundTag row = list.getCompound(i);
                String key = row.getString("Key");
                if (!validGroupKey(key) || data.groups.containsKey(key)) continue;
                Group group = new Group();
                if (row.contains("Supplies", Tag.TAG_LIST)) group.supplies.clear();
                group.earthCache = readGlobal(row, "EarthCache");
                group.moonCache = readGlobal(row, "MoonCache");
                // Preserve the former single binding according to its saved dimension.
                if (group.earthCache == null && group.moonCache == null) {
                    GlobalPos legacy = readGlobal(row, "Cache");
                    if (legacy != null && legacy.dimension().location().equals(MOON_ID)) group.moonCache = legacy;
                    else group.earthCache = legacy;
                }
                ListTag supplies = row.getList("Supplies", Tag.TAG_COMPOUND);
                for (int s = 0; s < Math.min(supplies.size(), MAX_SUPPLY_LINES); s++) {
                    CompoundTag supply = supplies.getCompound(s);
                    ResourceLocation item = ResourceLocation.tryParse(supply.getString("Item"));
                    int count = supply.getInt("Count");
                    if (item != null && validItem(item) && count > 0 && count <= MAX_SUPPLY_COUNT)
                        group.supplies.putIfAbsent(item, count);
                }
                ListTag flights = row.getList("Flights", Tag.TAG_COMPOUND);
                for (int f = 0; f < Math.min(flights.size(), 512); f++) {
                    CompoundTag flight = flights.getCompound(f);
                    ResourceLocation origin = ResourceLocation.tryParse(flight.getString("Origin"));
                    if (flight.hasUUID("Player") && origin != null && flight.getLong("LaunchedAt") >= 0)
                        group.flights.put(flight.getUUID("Player"), new Flight(flight.getLong("LaunchedAt"),
                                origin.toString(), flight.getBoolean("Landed")));
                }
                ListTag landings = row.getList("Landings", Tag.TAG_COMPOUND);
                for (int l = 0; l < Math.min(landings.size(), 512); l++) {
                    CompoundTag landing = landings.getCompound(l);
                    ResourceLocation dimension = ResourceLocation.tryParse(landing.getString("Dimension"));
                    if (landing.hasUUID("Player") && dimension != null && landing.getLong("ArrivedAt") >= 0)
                        group.landings.put(landing.getUUID("Player"), new Landing(dimension.toString(),
                                BlockPos.of(landing.getLong("Position")), landing.getLong("ArrivedAt"), landing.getBoolean("Confirmed")));
                }
                data.groups.put(key, group);
            }
            return data;
        }

        Group get(String key) {
            if (!groups.containsKey(key) && groups.size() < MAX_GROUPS) {
                groups.put(key, new Group()); setDirty();
            }
            return groups.get(key);
        }

        @Override public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_GROUPS).forEach(entry -> {
                CompoundTag row = new CompoundTag(); row.putString("Key", entry.getKey());
                Group group = entry.getValue();
                writeGlobal(row, "EarthCache", group.earthCache);
                writeGlobal(row, "MoonCache", group.moonCache);
                ListTag supplies = new ListTag();
                group.supplies.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_SUPPLY_LINES).forEach(supply -> {
                    CompoundTag saved = new CompoundTag(); saved.putString("Item", supply.getKey().toString());
                    saved.putInt("Count", supply.getValue()); supplies.add(saved);
                });
                row.put("Supplies", supplies);
                ListTag flights = new ListTag();
                group.flights.entrySet().stream().limit(512).forEach(entryFlight -> {
                    CompoundTag saved = new CompoundTag(); saved.putUUID("Player", entryFlight.getKey());
                    saved.putLong("LaunchedAt", entryFlight.getValue().launchedAt);
                    saved.putString("Origin", entryFlight.getValue().originDimension);
                    saved.putBoolean("Landed", entryFlight.getValue().landed); flights.add(saved);
                });
                row.put("Flights", flights);
                ListTag landings = new ListTag();
                group.landings.entrySet().stream().limit(512).forEach(entryLanding -> {
                    Landing landing = entryLanding.getValue(); CompoundTag saved = new CompoundTag();
                    saved.putUUID("Player", entryLanding.getKey()); saved.putString("Dimension", landing.dimension);
                    saved.putLong("Position", landing.position.asLong()); saved.putLong("ArrivedAt", landing.arrivedAt);
                    saved.putBoolean("Confirmed", landing.confirmed); landings.add(saved);
                });
                row.put("Landings", landings); list.add(row);
            });
            tag.put("Groups", list); return tag;
        }
    }

    private static GlobalPos readGlobal(CompoundTag row, String key) {
        if (!row.contains(key, Tag.TAG_COMPOUND)) return null;
        CompoundTag saved = row.getCompound(key);
        ResourceLocation dimension = ResourceLocation.tryParse(saved.getString("Dimension"));
        return dimension == null ? null : GlobalPos.of(dimensionKey(dimension), BlockPos.of(saved.getLong("Position")));
    }

    private static void writeGlobal(CompoundTag row, String key, GlobalPos global) {
        if (global == null) return;
        CompoundTag saved = new CompoundTag(); saved.putString("Dimension", global.dimension().location().toString());
        saved.putLong("Position", global.pos().asLong()); row.put(key, saved);
    }

    private static ExpeditionData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ExpeditionData::load, ExpeditionData::new, DATA_NAME);
    }

    private static boolean validGroupKey(String key) {
        return key != null && key.length() <= MAX_TEAM_KEY
                && (key.startsWith("team:") && key.length() > 5
                || key.startsWith("crew:") && isUuidSuffix(key, "crew:")
                || key.startsWith("solo:") && isUuidSuffix(key, "solo:"));
    }

    private static boolean isUuidSuffix(String value, String prefix) {
        if (value.length() != prefix.length() + 36) return false;
        try { UUID.fromString(value.substring(prefix.length())); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static ResourceKey<Level> dimensionKey(ResourceLocation id) {
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    private static Group group(ServerPlayer player, ExpeditionData data) {
        String key = FieldCrews.key(player);
        return validGroupKey(key) ? data.get(key) : null;
    }

    /** Copies the leader's checklist and coordinate notes into a newly formed expedition crew. */
    public static void sharePlan(ServerPlayer leader, String previousKey, String newKey) {
        if (!validPlayer(leader) || !validGroupKey(previousKey) || !validGroupKey(newKey)
                || !newKey.equals(FieldCrews.key(leader)) || previousKey.equals(newKey)) return;
        ExpeditionData data = data(leader.server);
        Group previous = data.groups.get(previousKey);
        if (previous == null) return;
        boolean newGroup = !data.groups.containsKey(newKey);
        Group next = data.get(newKey);
        if (next == null) return;
        if (newGroup) next.supplies.clear();
        previous.supplies.forEach((item, count) -> {
            if (validItem(item) && next.supplies.size() < MAX_SUPPLY_LINES)
                next.supplies.merge(item, count, Math::max);
        });
        // These are only coordinates/requirements. No container stack or player inventory is moved.
        if (newGroup) {
            next.earthCache = previous.earthCache;
            next.moonCache = previous.moonCache;
        }
        Landing priorLanding = previous.landings.get(leader.getUUID());
        if (priorLanding != null) next.landings.putIfAbsent(leader.getUUID(), new Landing(
                priorLanding.dimension, priorLanding.position, priorLanding.arrivedAt, priorLanding.confirmed));
        data.setDirty();
    }

    /** Returns the current team's persisted manifest and only currently verified coordinates/status. */
    public static List<String> view(ServerPlayer player) {
        if (!validPlayer(player)) return List.of("远征资料只可由在线服务端玩家读取。");
        ExpeditionData data = data(player.server); Group group = group(player, data);
        if (group == null) return List.of("远征资料空间已满，未更改任何记录。");
        List<String> lines = new ArrayList<>();
        lines.add("月球远征 · " + FieldCrews.describe(player));
        lines.add("地球备货箱：" + cacheDescription(player, group.earthCache));
        lines.add("月球补给箱：" + cacheDescription(player, group.moonCache));
        lines.add("输入物品编号和数量，或用另一只手拿材料后只填数量，再点「加备货项」。到箱旁点「查看箱内」。");
        lines.add("共享备货清单：");
        if (group.supplies.isEmpty()) lines.add("  尚无自定义备货项；可添加真实物品编号和数量。");
        group.supplies.forEach((item, count) -> lines.add("  " + displayItem(item) + " × " + count + "（" + item + "）"));
        int visibleLandings = 0;
        for (Map.Entry<UUID, Landing> entry : group.landings.entrySet()) {
            Landing landing = entry.getValue();
            if (!landing.dimension.equals(MOON_ID.toString())) continue;
            lines.add("落月记录：" + playerName(player.server, entry.getKey()) + " · "
                    + landing.position.getX() + "，" + landing.position.getY() + "，" + landing.position.getZ()
                    + (landing.confirmed ? "（本人已确认）" : "（服务器已核实，待本人确认）"));
            if (++visibleLandings >= 8) break;
        }
        if (visibleLandings == 0) lines.add("落月记录：暂无本队已核实坐标。");
        return List.copyOf(lines);
    }

    /** Performs a named server-authoritative expedition operation. Never launches a rocket for the player. */
    public static String act(ServerPlayer player, String action, String argument) {
        if (!validPlayer(player)) return "远征操作只可由在线服务端玩家执行。";
        tick(player);
        if (action == null || action.length() > 16) return "远征操作无效。";
        ExpeditionData data = data(player.server); Group group = group(player, data);
        if (group == null) return "远征资料空间已满，未更改任何记录。";
        String arg = argument == null ? "" : argument.strip();
        return switch (action.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "bind" -> bind(player, data, group, arg, false);
            case "cache" -> bind(player, data, group, arg, true);
            case "stock" -> cacheContents(player, group);
            case "add" -> addSupply(player, data, group, arg);
            case "remove" -> removeSupply(player, data, group, arg);
            case "check" -> check(player, group).message();
            case "launch" -> prepareLaunch(player, group);
            case "landing" -> confirmLanding(player, data, group);
            default -> "可用操作：bind（地球备货箱）、cache（月球补给箱）、stock（近身查看）、add、remove、check、launch、landing。";
        };
    }

    /** Parent calls on server player tick; only checks an occupied rocket and the player's current dimension. */
    public static void tick(ServerPlayer player) {
        if (!validPlayer(player)) return;
        ServerLevel level = player.serverLevel();
        ExpeditionData data = data(player.server); Group group = group(player, data);
        if (group == null) return;
        long now = player.server.overworld().getGameTime();
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof Rocket rocket && rocket.isAlive() && rocket.hasLaunched() && !rocket.isRemoved()) {
            Flight flight = group.flights.get(player.getUUID());
            if (flight == null || flight.landed || now - flight.launchedAt > FLIGHT_WINDOW) {
                group.flights.put(player.getUUID(), new Flight(Math.max(0, now), level.dimension().location().toString(), false));
                data.setDirty();
            }
        }
        Flight flight = group.flights.get(player.getUUID());
        if (flight != null && !flight.landed) {
            long elapsed = now - flight.launchedAt;
            if (elapsed < 0 || elapsed > FLIGHT_WINDOW) {
                group.flights.remove(player.getUUID()); data.setDirty();
            } else if (level.dimension().location().equals(MOON_ID)
                    && !flight.originDimension.equals(MOON_ID.toString())
                    && player.getVehicle() instanceof Lander lander
                    && lander.getPassengers().contains(player)) {
                BlockPos contact = landerGround(lander);
                if (contact == null) return;
                Landing prior = group.landings.get(player.getUUID());
                if (prior == null || prior.arrivedAt < flight.launchedAt) {
                    group.landings.put(player.getUUID(), new Landing(MOON_ID.toString(), contact, now, false));
                    flight.landed = true; data.setDirty();
                } else {
                    flight.landed = true; data.setDirty();
                }
            }
        }
    }

    /** Returns a saved team waypoint without inspecting, loading, or requiring its chunk. */
    public static GlobalPos destination(ServerPlayer player, boolean supply) {
        if (!validPlayer(player)) return null;
        ExpeditionData data = data(player.server); Group group = group(player, data);
        if (group == null) return null;
        if (supply) return group.moonCache;
        Landing landing = group.landings.values().stream()
                .filter(entry -> entry.dimension.equals(MOON_ID.toString()))
                .max(Comparator.comparingLong(entry -> entry.arrivedAt)).orElse(null);
        if (landing == null) return null;
        ResourceLocation dim = ResourceLocation.tryParse(landing.dimension);
        if (dim == null) return null;
        return GlobalPos.of(dimensionKey(dim), landing.position);
    }

    private static String bind(ServerPlayer player, ExpeditionData data, Group group, String argument, boolean lunar) {
        ServerLevel level = player.serverLevel(); BlockPos aimed = aimedBlock(player, level);
        ResourceLocation requiredDimension = lunar ? MOON_ID : Level.OVERWORLD.location();
        if (!level.dimension().location().equals(requiredDimension))
            return lunar ? "月球补给箱必须在 Ad Astra 月球维度登记。" : "地球备货箱必须在主世界登记。";
        if (aimed == null) return lunar ? "请在月球将准星对准补给箱后执行 cache。"
                : "请将准星对准备货箱后执行 bind。";
        BlockPos position = argument.isBlank() ? aimed : parsePosition(argument);
        if (position == null) return "格式为 x y z；输入坐标必须与准星当前命中的箱子一致。";
        if (!position.equals(aimed)) return "坐标与本人准星命中的方块不一致；未登记。";
        if (player.distanceToSqr(Vec3.atCenterOf(position)) > CACHE_RADIUS * CACHE_RADIUS)
            return "备货箱须在本人八格内。";
        Container container = usableContainer(player, level, position);
        if (container == null) return "目标不是已加载、允许访问的安全物品箱；未绑定。";
        GlobalPos requested = GlobalPos.of(level.dimension(), position);
        for (Map.Entry<String, Group> entry : data.groups.entrySet()) {
            GlobalPos other = lunar ? entry.getValue().moonCache : entry.getValue().earthCache;
            if (!entry.getKey().equals(FieldCrews.key(player)) && requested.equals(other))
                return "该箱子已登记为另一队的" + (lunar ? "月球补给箱" : "地球备货箱") + "；未改变登记。";
        }
        if (lunar) group.moonCache = requested; else group.earthCache = requested;
        data.setDirty();
        return "已登记本队" + (lunar ? "月球补给箱：" : "地球备货箱：") + formatGlobal(requested) + "。";
    }

    private static String cacheDescription(ServerPlayer player, GlobalPos cache) {
        if (cache == null) return "未登记";
        String description = formatGlobal(cache);
        if (!cache.dimension().equals(player.level().dimension())) return description + "（异维，保留上次登记）";
        ServerLevel level = player.serverLevel();
        if (!level.hasChunkAt(cache.pos()) || player.distanceToSqr(Vec3.atCenterOf(cache.pos())) > CACHE_RADIUS * CACHE_RADIUS)
            return description + "（尚未近身核验）";
        BlockEntity be = level.getBlockEntity(cache.pos());
        if (!(be instanceof Container)) return description + "（此处已加载且确认箱体已移除；位置记录保留）";
        if (usableContainer(player, level, cache.pos()) == null) return description + "（当前无权读取或箱体无法安全核验）";
        return description + "（箱体当前已加载并可访问）";
    }

    private static String cacheContents(ServerPlayer player, Group group) {
        boolean lunar = player.level().dimension().location().equals(MOON_ID);
        GlobalPos cache = lunar ? group.moonCache : group.earthCache;
        if (cache == null) return lunar ? "本队尚未登记月球补给箱；站在箱边执行 cache。"
                : "本队尚未登记地球备货箱；站在主世界箱边执行 bind。";
        if (!cache.dimension().location().equals(player.level().dimension().location()))
            return "备货箱位于其他维度；请回到箱边核对，系统不会跨维强载。";
        ServerLevel level = player.serverLevel();
        Container container = usableContainer(player, level, cache.pos());
        if (container == null) return "备货箱未加载、超出八格或当前无权访问；未读取内容。";
        Map<ResourceLocation, Integer> counts = countContents(level, player, cache.pos(), container);
        if (counts == null) return "箱体库存无法安全核实；未读取或修改任何物品。";
        return inventorySummary(counts);
    }

    private static String addSupply(ServerPlayer player, ExpeditionData data, Group group, String argument) {
        String[] parts = argument.split("\\s+");
        ItemStack held = manifestItem(player);
        if (parts.length == 1 && held.isEmpty())
            return "简化格式 add <数量> 需要手持非手机物品；也可用 add <物品ID> <数量>。";
        if (parts.length != 1 && parts.length != 2)
            return "格式：add <物品ID> <数量>，或手持非手机物品后 add <数量>。";
        ResourceLocation id = parts.length == 1 ? ForgeRegistries.ITEMS.getKey(held.getItem()) : ResourceLocation.tryParse(parts[0]);
        int count;
        try { count = Integer.parseInt(parts.length == 1 ? parts[0] : parts[1]); }
        catch (NumberFormatException invalid) { return "数量必须是1～4096的整数。"; }
        if (id == null || !validItem(id)) return "物品ID未在当前注册表中找到；未添加。请先核对实际物品ID。";
        if (count < 1 || count > MAX_SUPPLY_COUNT) return "数量必须是1～4096。";
        if (!group.supplies.containsKey(id) && group.supplies.size() >= MAX_SUPPLY_LINES) return "备货清单最多64项。";
        long next = (long) group.supplies.getOrDefault(id, 0) + count;
        if (next > MAX_SUPPLY_COUNT) return "该物品清单总量不能超过4096。";
        group.supplies.put(id, (int) next); data.setDirty();
        return "已将 " + displayItem(id) + "（" + id + "）加入本队备货清单，目标共 " + next + " 个。";
    }

    private static String removeSupply(ServerPlayer player, ExpeditionData data, Group group, String argument) {
        String[] parts = argument.split("\\s+");
        ItemStack held = manifestItem(player);
        if (parts.length == 1 && held.isEmpty())
            return "简化格式 remove <数量> 需要手持非手机物品；也可用 remove <物品ID> [数量]。";
        if (parts.length < 1 || parts.length > 2 || parts[0].isBlank())
            return "格式：remove <物品ID> [数量]，或手持清单物品后 remove <数量>。";
        boolean shorthand = parts.length == 1 && isInteger(parts[0]) && !held.isEmpty();
        ResourceLocation id = shorthand ? ForgeRegistries.ITEMS.getKey(held.getItem()) : ResourceLocation.tryParse(parts[0]);
        Integer current = id == null ? null : group.supplies.get(id);
        if (current == null) return "该物品不在本队备货清单中。";
        if (parts.length == 1 && !shorthand) group.supplies.remove(id);
        else {
            int count;
            try { count = Integer.parseInt(shorthand ? parts[0] : parts[1]); }
            catch (NumberFormatException invalid) { return "移除数量必须是正整数。"; }
            if (count < 1) return "移除数量必须是正整数。";
            if (count >= current) group.supplies.remove(id); else group.supplies.put(id, current - count);
        }
        data.setDirty(); return "已更新本队备货清单：" + displayItem(id) + "。";
    }

    private static ItemStack manifestItem(ServerPlayer player) {
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && !(offhand.getItem() instanceof IndustrialPhoneItem)) return offhand;
        ItemStack mainhand = player.getMainHandItem();
        return !mainhand.isEmpty() && !(mainhand.getItem() instanceof IndustrialPhoneItem) ? mainhand : ItemStack.EMPTY;
    }

    private static boolean isInteger(String value) {
        try { Integer.parseInt(value); return true; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static String prepareLaunch(ServerPlayer player, Group group) {
        List<String> problems = new ArrayList<>();
        if (!SpaceSuitItem.hasFullSet(player)) problems.add("本人未穿齐 Ad Astra 普通太空服四件套。");
        long oxygen = SpaceSuitItem.getOxygenAmount(player);
        if (oxygen <= 0) problems.add("本人太空服中没有可用氧气；检查实际护甲氧量，氧气桶不替代已充氧护甲。");
        Rocket rocket = nearbyTierOneRocket(player);
        if (rocket == null) problems.add("附近16格内没有已加载的 Ad Astra 一级火箭。");
        else {
            if (!rocket.hasEnoughFuel()) problems.add("一级火箭原生燃料槽未达到 Ad Astra 发射所需量；煤、燃料桶库存不算已装火箭燃料。");
            if (!rocket.canLaunch()) problems.add("Ad Astra 当前不允许这枚火箭发射；请检查发射台、遮挡和所在维度。");
        }
        if (group.earthCache == null) problems.add("本队地球备货箱尚未登记。");
        else {
            ServerLevel cacheLevel = player.server.getLevel(group.earthCache.dimension());
            if (cacheLevel == null || !cacheLevel.dimension().location().equals(player.level().dimension().location())) {
                problems.add("地球备货箱位于其他维度；请回到主世界箱边检查，不强载区块。");
            } else {
                Container container = usableContainer(player, cacheLevel, group.earthCache.pos());
                Map<ResourceLocation, Integer> counts = container == null ? null
                        : countContents(cacheLevel, player, group.earthCache.pos(), container);
                if (counts == null) problems.add("地球备货箱未加载、超出八格或无权读取。");
                else for (Map.Entry<ResourceLocation, Integer> required : group.supplies.entrySet()) {
                    int actual = counts.getOrDefault(required.getKey(), 0);
                    if (actual < required.getValue()) problems.add("地球备货箱缺少 " + displayItem(required.getKey()) + "（"
                            + required.getKey() + "）×" + (required.getValue() - actual) + "；未扣物。" );
                }
            }
        }
        if (problems.isEmpty()) return "检查通过：本人太空服与实际氧量合格，附近一级火箭已装足 Ad Astra 原生燃料，备货箱满足本队清单。请玩家本人驾驶发射；系统不会自动装填、起飞或扣除清单物资。";
        return "尚未具备发射准备：\n- " + String.join("\n- ", problems);
    }

    private record CheckResult(boolean ready, String message) { }
    private static CheckResult check(ServerPlayer player, Group group) {
        String message = prepareLaunch(player, group);
        return new CheckResult(message.startsWith("检查通过"), message);
    }

    private static String confirmLanding(ServerPlayer player, ExpeditionData data, Group group) {
        if (!player.level().dimension().location().equals(MOON_ID)) return "本人当前不在 Ad Astra 月球维度；没有登记落月坐标。";
        Flight flight = group.flights.get(player.getUUID());
        Landing landing = group.landings.get(player.getUUID());
        long now = player.server.overworld().getGameTime();
        if (flight != null && !flight.landed && now - flight.launchedAt >= 0 && now - flight.launchedAt <= FLIGHT_WINDOW) {
            // A dimension change alone is not a landing: Ad Astra replaces the rocket with a grounded Lander.
            if (player.getVehicle() instanceof Lander lander && lander.getPassengers().contains(player)) {
                BlockPos contact = landerGround(lander);
                if (contact != null) {
                    landing = new Landing(MOON_ID.toString(), contact, now, false);
                    group.landings.put(player.getUUID(), landing); flight.landed = true; data.setDirty();
                }
            }
        }
        if (landing == null || !landing.dimension.equals(MOON_ID.toString())
                || now < landing.arrivedAt || now - landing.arrivedAt > LANDING_CONFIRM_WINDOW)
            return "未找到本次真实火箭发射后服务器核实的落月记录；不能只凭身处月球伪造着陆。";
        if (landing.confirmed) return "本次月球落点已确认：" + landing.position.getX() + "，"
                + landing.position.getY() + "，" + landing.position.getZ() + "；重复确认不会改写记录。";
        landing = new Landing(landing.dimension, landing.position, landing.arrivedAt, true);
        group.landings.put(player.getUUID(), landing); data.setDirty();
        FieldMilestones.award(player, "moon_landing");
        return "已由本人确认本次月球落点：" + landing.position.getX() + "，" + landing.position.getY() + "，"
                + landing.position.getZ() + "（Ad Astra 月球；队伍共享）。";
    }

    private static BlockPos landerGround(Lander lander) {
        if (lander == null || !lander.isAlive() || lander.isRemoved() || !lander.onGround()
                || !(lander.level() instanceof ServerLevel level)) return null;
        BlockPos ground = BlockPos.containing(lander.getX(), lander.getBoundingBox().minY - 0.01, lander.getZ());
        if (!level.hasChunkAt(ground) || !level.getBlockState(ground).blocksMotion()) return null;
        return ground.immutable();
    }

    private static Rocket nearbyTierOneRocket(ServerPlayer player) {
        AABB bounds = player.getBoundingBox().inflate(16.0);
        return player.serverLevel().getEntitiesOfClass(Rocket.class, bounds,
                        rocket -> rocket.isAlive() && !rocket.isRemoved() && rocket.tier() == 1 && !rocket.hasLaunched())
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    private static Container usableContainer(ServerPlayer player, ServerLevel level, BlockPos position) {
        if (player == null || level == null || player.level() != level || !level.hasChunkAt(position)
                || player.distanceToSqr(Vec3.atCenterOf(position)) > CACHE_RADIUS * CACHE_RADIUS
                || !mayUseBlock(player, level, position)) return null;
        BlockEntity be = level.getBlockEntity(position);
        if (!(be instanceof Container container) || !container.stillValid(player)) return null;
        if (be instanceof BaseContainerBlockEntity lockable && !lockable.canOpen(player)) return null;
        // Reading a loot-table chest can unpack and mutate its inventory. Leave it alone.
        if (be instanceof RandomizableContainerBlockEntity randomizable
                && randomizable.saveWithoutMetadata().contains("LootTable", Tag.TAG_STRING)) return null;
        return container;
    }

    private static boolean mayUseBlock(ServerPlayer player, ServerLevel level, BlockPos position) {
        if (!level.mayInteract(player, position) || !player.mayBuild() || player.isSpectator()) return false;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false);
        var event = ForgeHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, position, hit);
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) return false;
        BlockEntity be = level.getBlockEntity(position);
        return !(be instanceof com.ic2newera.industrial.OwnedIndustrialBlockEntity owned) || owned.canManage(player);
    }

    private static BlockPos aimedBlock(ServerPlayer player, ServerLevel level) {
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(CACHE_RADIUS));
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static Map<ResourceLocation, Integer> countContents(ServerLevel level, ServerPlayer player,
                                                                 BlockPos position, Container container) {
        if (!mayUseBlock(player, level, position)) return null;
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        countContainer(container, counts);
        BlockEntity be = level.getBlockEntity(position);
        // A double chest is one physical cache; inspect its matching loaded half too.
        if (be instanceof ChestBlockEntity && isChestBlock(level.getBlockState(position))) {
            var state = level.getBlockState(position);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos otherPos = position.relative(direction);
                if (!level.hasChunkAt(otherPos)) continue;
                var otherState = level.getBlockState(otherPos);
                if (otherState.getBlock() != state.getBlock() || !otherState.hasProperty(net.minecraft.world.level.block.ChestBlock.FACING)
                        || !otherState.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
                        || otherState.getValue(net.minecraft.world.level.block.ChestBlock.FACING)
                        != state.getValue(net.minecraft.world.level.block.ChestBlock.FACING)
                        || otherState.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                        == state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)) continue;
                if (!(level.getBlockEntity(otherPos) instanceof Container other) || !other.stillValid(player)
                        || !mayUseBlock(player, level, otherPos)) return null;
                BlockEntity otherBe = level.getBlockEntity(otherPos);
                if (otherBe instanceof RandomizableContainerBlockEntity randomizable
                        && randomizable.saveWithoutMetadata().contains("LootTable", Tag.TAG_STRING)) return null;
                countContainer(other, counts); break;
            }
        }
        return counts;
    }

    private static boolean isChestBlock(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
    }

    private static void countContainer(Container container, Map<ResourceLocation, Integer> counts) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null) counts.merge(id, stack.getCount(), Integer::sum);
        }
    }

    private static String inventorySummary(Map<ResourceLocation, Integer> counts) {
        if (counts.isEmpty()) return "备货箱已加载并允许访问；当前物品箱为空。";
        return "备货箱真实库存：" + counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .limit(24).map(entry -> displayItem(entry.getKey()) + " × " + entry.getValue())
                .collect(java.util.stream.Collectors.joining("；"))
                + (counts.size() > 24 ? "；其余物品已省略" : "");
    }

    private static boolean validPlayer(ServerPlayer player) {
        return player != null && !player.hasDisconnected() && player.isAlive() && !player.isRemoved()
                && player.server != null && player.serverLevel().getServer() == player.server;
    }

    private static BlockPos parsePosition(String text) {
        String[] parts = text.trim().split("[,\\s]+", -1);
        if (parts.length != 3) return null;
        try {
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
            return new BlockPos(x, y, z);
        } catch (NumberFormatException invalid) { return null; }
    }

    private static boolean validItem(ResourceLocation id) {
        return id != null && BuiltInRegistries.ITEM.containsKey(id)
                && BuiltInRegistries.ITEM.get(id) != net.minecraft.world.item.Items.AIR;
    }

    private static String displayItem(ResourceLocation id) {
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null || item == net.minecraft.world.item.Items.AIR ? String.valueOf(id)
                : Component.translatable(item.getDescriptionId()).getString();
    }

    private static String formatGlobal(GlobalPos global) {
        BlockPos pos = global.pos();
        return global.dimension().location() + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String playerName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        return server.getProfileCache().get(id).map(profile -> profile.getName()).orElse(id.toString());
    }
}
