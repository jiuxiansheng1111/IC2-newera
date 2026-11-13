package com.ic2newera.field.contracts;

import com.ic2newera.field.EcologyAlbum;
import com.ic2newera.field.board.BoardBlockEntity;
import com.ic2newera.field.FieldMilestones;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent server-authoritative personal contracts shown on public powered notice boards. */
public final class DailyContracts {
    private static final String DATA_NAME = "ic2newera_daily_contracts";
    private static final long ROTATION_MS = 30L * 60L * 1000L;
    private static final int PAGE_SIZE = 8;
    private static final int MAX_ACTIVE = 5;
    private static final int MAX_PLAYERS = 4096;
    private static final int MAX_RECORDS = 128;
    private static final int MAX_ACCEPTED_IDS = 256;
    private static final int MAX_STRING = 512;
    private static final long[] RANK_THRESHOLDS = {0, 3, 8, 18, 35, 65, 110, 180};
    private static final String[] RANKS = {"E", "D", "C", "B", "A", "S", "SS", "SSS"};
    private static final String[] PHOTO_IDS = {"spawn:angler_fish", "spawn:clam", "spawn:tuna"};
    private static final String[] PHOTO_NAMES = {"鮟鱇鱼", "蛤蜊", "金枪鱼"};

    private DailyContracts() { }

    public enum ContractType { DELIVERY, PHOTO }

    public record ContractCard(String id, String title, String text, String objective, ContractType type,
                               int required, int progress, ItemStack reward,
                               boolean accepted, boolean completed, boolean claimed, ItemStack input) {
        public ContractCard {
            id = limit(id, 96); title = limit(title, 96); text = limit(text, 512); objective = limit(objective, 256);
            type = type == null ? ContractType.DELIVERY : type;
            reward = reward == null ? ItemStack.EMPTY : reward.copy();
            input = input == null ? ItemStack.EMPTY : input.copy();
            required = Math.max(0, Math.min(4096, required)); progress = Math.max(0, Math.min(required, progress));
        }
        @Override public ItemStack reward() { return reward.copy(); }
        @Override public ItemStack input() { return input.copy(); }
    }

    public record BoardSnapshot(GlobalPos board, String rank, long totalCompleted, long cycle,
                                long secondsRemaining, int page, int pageCount, List<ContractCard> contracts) {
        public BoardSnapshot {
            rank = limit(rank, 8); totalCompleted = Math.max(0, totalCompleted); cycle = Math.max(0, cycle);
            secondsRemaining = Math.max(0, secondsRemaining); page = Math.max(0, page); pageCount = Math.max(1, pageCount);
            contracts = contracts == null ? List.of() : List.copyOf(contracts);
        }
    }

    /** A player's own accepted contract and its original board, for phone-side navigation. */
    public record ActiveContractRoute(String id, String title, GlobalPos board) { }

    private record Spec(String key, ContractType type, String target, String targetName, int baseCount,
                        int difficulty, int minimumRank, String title, String text) { }

    private static final class Progress {
        String id;
        String specKey;
        long cycle;
        long acceptedAtMillis;
        int rankAtAccept;
        int required;
        int progress;
        ContractType type;
        String target;
        String title;
        String text;
        String objective;
        ItemStack reward = ItemStack.EMPTY;
        GlobalPos board;
        boolean completed;
        boolean claimed;
        String evidenceKey = "";
    }

    private static final class PlayerProgress {
        long completedTotal;
        long offerCycle = Long.MIN_VALUE;
        int offerRank;
        final LinkedHashMap<String, Progress> contracts = new LinkedHashMap<>();
        final LinkedHashSet<String> acceptedIds = new LinkedHashSet<>();
    }

    private static final class ContractData extends SavedData {
        final Map<UUID, PlayerProgress> players = new HashMap<>();

        static ContractData load(CompoundTag root) {
            ContractData data = new ContractData();
            ListTag players = root.getList("Players", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(players.size(), MAX_PLAYERS); i++) {
                CompoundTag savedPlayer = players.getCompound(i);
                if (!savedPlayer.hasUUID("Player")) continue;
                UUID id = savedPlayer.getUUID("Player");
                PlayerProgress player = new PlayerProgress();
                player.completedTotal = Math.max(0, savedPlayer.getLong("CompletedTotal"));
                player.offerCycle = savedPlayer.contains("OfferCycle", Tag.TAG_LONG)
                        ? savedPlayer.getLong("OfferCycle") : Long.MIN_VALUE;
                player.offerRank = clampRank(savedPlayer.getInt("OfferRank"));
                ListTag accepted = savedPlayer.getList("Accepted", Tag.TAG_STRING);
                for (int a = Math.max(0, accepted.size() - MAX_ACCEPTED_IDS); a < accepted.size(); a++) {
                    String value = accepted.getString(a);
                    if (validContractId(value)) player.acceptedIds.add(value);
                }
                ListTag records = savedPlayer.getList("Contracts", Tag.TAG_COMPOUND);
                for (int c = Math.max(0, records.size() - MAX_RECORDS); c < records.size(); c++) {
                    Progress record = readProgress(records.getCompound(c));
                    if (record != null) player.contracts.putIfAbsent(record.id, record);
                }
                data.players.put(id, player);
            }
            return data;
        }

        @Override public CompoundTag save(CompoundTag root) {
            ListTag allPlayers = new ListTag();
            players.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_PLAYERS).forEach(entry -> {
                CompoundTag savedPlayer = new CompoundTag(); savedPlayer.putUUID("Player", entry.getKey());
                PlayerProgress progress = entry.getValue();
                savedPlayer.putLong("CompletedTotal", progress.completedTotal);
                savedPlayer.putLong("OfferCycle", progress.offerCycle);
                savedPlayer.putInt("OfferRank", progress.offerRank);
                ListTag accepted = new ListTag();
                progress.acceptedIds.stream().skip(Math.max(0, progress.acceptedIds.size() - MAX_ACCEPTED_IDS))
                        .forEach(value -> accepted.add(StringTag.valueOf(value)));
                savedPlayer.put("Accepted", accepted);
                ListTag records = new ListTag();
                progress.contracts.values().stream().limit(MAX_RECORDS).forEach(record -> records.add(writeProgress(record)));
                savedPlayer.put("Contracts", records); allPlayers.add(savedPlayer);
            });
            root.put("Players", allPlayers); return root;
        }
    }

    private static Progress readProgress(CompoundTag tag) {
        String id = tag.getString("Id"), specKey = tag.getString("Spec");
        ResourceLocation target = ResourceLocation.tryParse(tag.getString("Target"));
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("BoardDimension"));
        int type = tag.getInt("Type"), rank = tag.getInt("Rank"), required = tag.getInt("Required"), count = tag.getInt("Progress");
        if (!validContractId(id) || specKey.length() > 48 || target == null || dimension == null
                || type < 0 || type >= ContractType.values().length || rank < 0 || rank >= RANKS.length
                || required < 1 || required > 4096 || count < 0 || count > required
                || !tag.contains("Reward", Tag.TAG_COMPOUND)) return null;
        Progress record = new Progress(); record.id = id; record.specKey = specKey;
        record.cycle = Math.max(0, tag.getLong("Cycle")); record.acceptedAtMillis = Math.max(0, tag.getLong("AcceptedAt"));
        record.rankAtAccept = rank; record.required = required; record.progress = count;
        record.type = ContractType.values()[type]; record.target = target.toString();
        record.title = limit(tag.getString("Title"), 96); record.text = limit(tag.getString("Text"), 512);
        record.objective = limit(tag.getString("Objective"), 256);
        record.reward = ItemStack.of(tag.getCompound("Reward"));
        if (record.reward.isEmpty() || record.reward.getCount() > record.reward.getMaxStackSize()) return null;
        record.board = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension), BlockPos.of(tag.getLong("BoardPos")));
        record.completed = tag.getBoolean("Completed"); record.claimed = tag.getBoolean("Claimed");
        record.evidenceKey = limit(tag.getString("EvidenceKey"), 64);
        if (record.completed && record.progress < record.required && record.type == ContractType.DELIVERY) return null;
        return record;
    }

    private static CompoundTag writeProgress(Progress record) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", record.id); tag.putString("Spec", record.specKey); tag.putLong("Cycle", record.cycle);
        tag.putLong("AcceptedAt", record.acceptedAtMillis); tag.putInt("Rank", record.rankAtAccept);
        tag.putInt("Required", record.required); tag.putInt("Progress", record.progress); tag.putInt("Type", record.type.ordinal());
        tag.putString("Target", record.target); tag.putString("Title", limit(record.title, 96));
        tag.putString("Text", limit(record.text, 512)); tag.putString("Objective", limit(record.objective, 256));
        tag.put("Reward", record.reward.save(new CompoundTag()));
        tag.putString("BoardDimension", record.board.dimension().location().toString()); tag.putLong("BoardPos", record.board.pos().asLong());
        tag.putBoolean("Completed", record.completed); tag.putBoolean("Claimed", record.claimed);
        tag.putString("EvidenceKey", limit(record.evidenceKey, 64));
        return tag;
    }

    private static ContractData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ContractData::load, ContractData::new, DATA_NAME);
    }

    private static PlayerProgress playerProgress(ServerPlayer player, ContractData data, boolean create) {
        PlayerProgress current = data.players.get(player.getUUID());
        if (current != null || !create || data.players.size() >= MAX_PLAYERS) return current;
        PlayerProgress made = new PlayerProgress(); data.players.put(player.getUUID(), made); data.setDirty(); return made;
    }

    /** Returns the current 30-minute server-wall-clock board page; accepted tasks survive rotation. */
    public static BoardSnapshot view(ServerPlayer player, GlobalPos board, int page) {
        long now = System.currentTimeMillis(), cycle = cycleAt(now);
        if (!validPlayer(player) || board == null || !validBoard(player, board, true))
            return emptySnapshot(board, cycle, secondsRemaining(now), page);
        ContractData data = data(player.server);
        PlayerProgress progress = playerProgress(player, data, true);
        if (progress == null) return emptySnapshot(board, cycle, secondsRemaining(now), page);
        ensureCycle(progress, cycle, data);
        List<ContractCard> all = cards(player, progress, cycle);
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int selectedPage = Math.max(0, Math.min(page, pages - 1));
        int start = selectedPage * PAGE_SIZE, end = Math.min(all.size(), start + PAGE_SIZE);
        return new BoardSnapshot(board, rankName(progress.completedTotal), progress.completedTotal, cycle,
                secondsRemaining(now), selectedPage, pages, all.subList(start, end));
    }

    /** Server-authoritative accept, exact item submission, and retryable reward claim. */
    public static String act(ServerPlayer player, GlobalPos board, String action, String contractId) {
        if (!validPlayer(player)) return "每日任务只可由在线服务端玩家操作。";
        if (action == null || action.length() > 16 || contractId == null || contractId.length() > 96)
            return "任务操作无效。";
        if (!validBoard(player, board, true)) return "请在已加载且供电的公告栏八格内操作，并确认当前有权限。";
        long now = System.currentTimeMillis(), cycle = cycleAt(now);
        ContractData data = data(player.server);
        PlayerProgress progress = playerProgress(player, data, true);
        if (progress == null) return "个人任务记录已达服务器上限，暂时无法建立新记录。";
        ensureCycle(progress, cycle, data);
        String verb = action.toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "accept" -> accept(player, board, contractId, now, cycle, data, progress);
            case "submit" -> submit(player, board, contractId, data, progress);
            case "claim" -> claim(player, board, contractId, data, progress);
            default -> "可用任务操作：accept、submit、claim。";
        };
    }

    /** Last verified location for a photo species, or the original delivery board for materials. */
    public static GlobalPos routeTarget(ServerPlayer player, GlobalPos board, String contractId) {
        if (!validPlayer(player) || board == null || !validContractId(contractId)) return null;
        ContractData data = data(player.server); PlayerProgress playerProgress = playerProgress(player, data, false);
        if (playerProgress == null) return null;
        Progress progress = playerProgress.contracts.get(contractId);
        if (progress == null || !progress.id.equals(contractId) || progress.claimed || !progress.board.equals(board)) return null;
        if (progress.type == ContractType.DELIVERY) return progress.board;
        return EcologyAlbum.destination(player, progress.target);
    }

    /** Personal accepted task list consumed by phone-side journal integrations. */
    public static List<ActiveContractRoute> activeRoutes(ServerPlayer player) {
        if (!validPlayer(player)) return List.of();
        PlayerProgress progress = playerProgress(player, data(player.server), false);
        if (progress == null) return List.of();
        return progress.contracts.values().stream().filter(record -> !record.claimed)
                .sorted(Comparator.comparingLong(record -> record.acceptedAtMillis))
                .map(record -> new ActiveContractRoute(record.id, record.title, record.board)).toList();
    }

    /** Called only by EcologyAlbum after real shutter/frame/subject confirmation. */
    public static void onPhotoEvidence(ServerPlayer player, EcologyAlbum.PhotoEvidence offered) {
        if (!validPlayer(player) || offered == null || offered.evidenceKey() == null
                || offered.evidenceKey().isBlank() || offered.evidenceKey().length() > 64
                || offered.frameId() == null || offered.frameId().isBlank() || offered.frameId().length() > 64
                || offered.cameraPosition() == null || offered.subjectPosition() == null
                || !offered.cameraPosition().dimension().equals(offered.subjectPosition().dimension())) return;
        EcologyAlbum.PhotoEvidence evidence = EcologyAlbum.photoEvidence(player, offered.evidenceKey());
        if (evidence == null || !player.getUUID().equals(evidence.playerId())
                || !evidence.equals(offered) || evidence.serverCaptureTimeMillis() > System.currentTimeMillis()) return;
        WeeklyContracts.onPhotoEvidence(player, evidence);
        ContractData data = data(player.server); PlayerProgress playerProgress = playerProgress(player, data, false);
        if (playerProgress == null) return;
        boolean evidenceUsed = playerProgress.contracts.values().stream()
                .anyMatch(record -> evidence.evidenceKey().equals(record.evidenceKey));
        if (evidenceUsed) return;
        Progress match = playerProgress.contracts.values().stream()
                .filter(record -> !record.claimed && !record.completed && record.type == ContractType.PHOTO
                        && record.target.equals(evidence.speciesId())
                        && evidence.serverCaptureTimeMillis() > record.acceptedAtMillis)
                .min(Comparator.comparingLong(record -> record.acceptedAtMillis)).orElse(null);
        if (match == null) return;
        match.progress = match.required; match.completed = true; match.evidenceKey = evidence.evidenceKey();
        playerProgress.completedTotal = saturatingAdd(playerProgress.completedTotal, 1);
        WeeklyContracts.onDailyContractCompleted(player, match.id, System.currentTimeMillis());
        data.setDirty();
    }

    public static long completedCount(ServerPlayer player) {
        if (!validPlayer(player)) return 0;
        PlayerProgress progress = playerProgress(player, data(player.server), false);
        return progress == null ? 0 : progress.completedTotal;
    }

    public static String rank(ServerPlayer player) { return rankName(completedCount(player)); }

    private static String accept(ServerPlayer player, GlobalPos board, String id, long now, long cycle,
                                 ContractData data, PlayerProgress playerProgress) {
        if (!validContractId(id) || !id.startsWith(cycle + ":")) return "这条任务已轮换，请刷新公告栏查看当前任务。";
        Progress existing = playerProgress.contracts.get(id);
        if (existing != null || playerProgress.acceptedIds.contains(id)) return "本轮这条任务已经接取过，不能重复领取奖励。";
        long active = playerProgress.contracts.values().stream().filter(record -> !record.claimed).count();
        if (active >= MAX_ACTIVE) return "同时最多进行五项任务；请先完成并领取一项奖励。";
        Spec spec = specs(cycle, playerProgress.offerRank).stream()
                .filter(candidate -> contractId(cycle, candidate).equals(id)).findFirst().orElse(null);
        if (spec == null) return "任务不在本轮公告栏清单中，未接取。";
        ResourceLocation target = ResourceLocation.tryParse(spec.target);
        if (target == null || spec.type == ContractType.DELIVERY && !validItem(target))
            return "目标物品当前未注册，任务未接取。";
        int rank = clampRank(playerProgress.offerRank);
        int required = spec.type == ContractType.PHOTO ? 1 : scaledCount(spec.baseCount, rank);
        ItemStack reward = rewardStack(player.getUUID(), cycle, spec, rank);
        if (reward.isEmpty())
            return "奖励物品当前不可用，任务未接取。";
        Progress record = new Progress(); record.id = id; record.specKey = spec.key; record.cycle = cycle;
        record.acceptedAtMillis = now; record.rankAtAccept = rank; record.required = required; record.progress = 0;
        record.type = spec.type; record.target = target.toString(); record.title = spec.title; record.text = spec.text;
        record.objective = objective(spec, target, required); record.reward = reward;
        record.board = board; playerProgress.contracts.put(id, record); playerProgress.acceptedIds.add(id);
        trimPlayer(playerProgress, cycle); data.setDirty();
        FieldMilestones.award(player, "contract_accepted");
        return "已接取「" + record.title + "」；接取时间已记录，已接任务跨轮保留。";
    }

    private static String submit(ServerPlayer player, GlobalPos board, String id,
                                 ContractData data, PlayerProgress playerProgress) {
        Progress record = playerProgress.contracts.get(id);
        if (record == null || !record.board.equals(board)) return "找不到这项公告栏接取记录；请回原公告栏查看。";
        if (record.claimed) return "这项任务奖励已经领取。";
        if (record.completed) return "任务已完成，请领取公告栏上的实物奖励。";
        if (record.type == ContractType.PHOTO) return "拍摄任务由服务器自动核验；接取后拍到指定物种时会更新进度。";
        ResourceLocation target = ResourceLocation.tryParse(record.target);
        Item item = target == null ? null : ForgeRegistries.ITEMS.getValue(target);
        if (item == null || item == net.minecraft.world.item.Items.AIR) return "目标物品当前未注册；未扣除任何物品。";
        if (countItems(player.getInventory(), item) < record.required)
            return "物资不足：需要 " + record.required + " 个" + record.objective + "；未扣除任何物品。";
        removeItems(player.getInventory(), item, record.required);
        record.progress = record.required; record.completed = true;
        playerProgress.completedTotal = saturatingAdd(playerProgress.completedTotal, 1);
        WeeklyContracts.onDailyContractCompleted(player, record.id, System.currentTimeMillis());
        data.setDirty();
        return "已提交并核对 " + record.required + " 个目标物资；任务完成，请领取实物奖励。";
    }

    private static String claim(ServerPlayer player, GlobalPos board, String id,
                                ContractData data, PlayerProgress playerProgress) {
        Progress record = playerProgress.contracts.get(id);
        if (record == null || !record.board.equals(board)) return "找不到这项公告栏接取记录；请回原公告栏查看。";
        if (record.claimed) return "这项任务的奖励已经领取，不能重复领取。";
        if (!record.completed) return "任务尚未完成；物资任务请提交目标数量，拍摄任务等待服务器核验。";
        ItemStack reward = record.reward.copy();
        Inventory inventory = player.getInventory();
        if (!canFit(inventory, reward)) return "背包没有足够空间；奖励保留在公告栏记录中，请整理背包后重试。";
        if (!insertWithoutDropping(inventory, reward.copy()))
            return "背包空间变化，奖励仍保留在公告栏记录中；请整理后重试。";
        record.claimed = true; data.setDirty();
        FieldMilestones.award(player, "contract_completed");
        if (record.rankAtAccept == RANKS.length - 1) FieldMilestones.award(player, "contract_master");
        return "已领取「" + record.title + "」奖励：" + reward.getCount() + " 个" + reward.getHoverName().getString() + "。";
    }

    private static List<ContractCard> cards(ServerPlayer player, PlayerProgress playerProgress, long cycle) {
        LinkedHashMap<String, ContractCard> all = new LinkedHashMap<>();
        playerProgress.contracts.values().stream().filter(record -> !record.claimed)
                .sorted(Comparator.comparingLong(record -> record.acceptedAtMillis))
                .forEach(record -> all.put(record.id, toCard(record)));
        for (Spec spec : specs(cycle, playerProgress.offerRank)) {
            String id = contractId(cycle, spec);
            Progress saved = playerProgress.contracts.get(id);
            if (saved != null) all.putIfAbsent(id, toCard(saved));
            else if (playerProgress.acceptedIds.contains(id)) {
                all.putIfAbsent(id, new ContractCard(id, spec.title, spec.text, objective(spec,
                        ResourceLocation.tryParse(spec.target), scaledCount(spec.baseCount, playerProgress.offerRank)),
                        spec.type, spec.type == ContractType.PHOTO ? 1 : scaledCount(spec.baseCount, playerProgress.offerRank),
                        0, rewardStack(player.getUUID(), cycle, spec, playerProgress.offerRank), true, true, true,
                        inputStack(spec.type, ResourceLocation.tryParse(spec.target))));
            } else {
                int required = spec.type == ContractType.PHOTO ? 1 : scaledCount(spec.baseCount, playerProgress.offerRank);
                all.putIfAbsent(id, new ContractCard(id, spec.title, spec.text,
                        objective(spec, ResourceLocation.tryParse(spec.target), required), spec.type, required, 0,
                        rewardStack(player.getUUID(), cycle, spec, playerProgress.offerRank), false, false, false,
                        inputStack(spec.type, ResourceLocation.tryParse(spec.target))));
            }
        }
        return List.copyOf(all.values());
    }

    private static ContractCard toCard(Progress record) {
        return new ContractCard(record.id, record.title, record.text, record.objective, record.type,
                record.required, record.progress, record.reward, true, record.completed, record.claimed,
                inputStack(record.type, ResourceLocation.tryParse(record.target)));
    }

    private static List<Spec> specs(long cycle, int rank) {
        List<Spec> result = new ArrayList<>();
        int vanilla = Math.floorMod(cycle, 4);
        int project = Math.floorMod(cycle, 3);
        int create = Math.floorMod(cycle, 2);
        int space = Math.floorMod(cycle, 3);
        result.add(delivery("vanilla_" + vanilla, new String[]{"minecraft:oak_log", "minecraft:iron_ingot", "minecraft:cooked_beef", "minecraft:torch"}[vanilla],
                new String[]{"原木", "铁锭", "熟牛肉", "火把"}[vanilla], new int[]{12, 8, 10, 16}[vanilla], 1 + vanilla / 2,
                0, new String[]{"林地补给", "铁料周转", "野外口粮", "照明补充"}[vanilla], "请交付清单所列的原版物资；公告栏会逐个核对，材料不足时不会扣除。"));
        if (rank >= 1) result.add(delivery("create_" + create, new String[]{"create:cogwheel", "create:andesite_alloy"}[create],
                new String[]{"齿轮", "安山合金"}[create], new int[]{5, 8}[create], 2 + create, 1,
                new String[]{"传动零件", "机械合金"}[create], "请交付实际已注册的 Create 材料；不会用其他物品替代目标。"));
        if (rank >= 2) result.add(delivery("ic2_" + project, new String[]{"ic2:bronze_ingot", "ic2:lead_ingot", "ic2:silver_ingot"}[project],
                new String[]{"青铜锭", "铅锭", "银锭"}[project], new int[]{6, 8, 6}[project], 2 + project / 2, 2,
                new String[]{"合金样品", "屏蔽材料", "导电样品"}[project], "请交付实际已注册的工业材料；任务仅接收指定物品编号。"));
        if (rank >= 5) result.add(delivery("space_" + space, new String[]{"ad_astra:desh_ingot", "ad_astra:ostrum_ingot", "ad_astra:calorite_ingot"}[space],
                new String[]{"戴斯锭", "紫金锭", "耐热合金锭"}[space], new int[]{4, 5, 4}[space], 3 + space, 5,
                new String[]{"月面合金", "远征材料", "耐热样品"}[space], "请交付 Ad Astra 注册的真实材料；火箭燃料和氧气只认实际燃料槽、太空服状态。"));
        int photoCount = rank >= 5 ? 3 : rank >= 2 ? 2 : 1;
        int photo = Math.floorMod(cycle, photoCount);
        int photoSpeciesIndex = rank >= 5 ? photo : rank >= 2 ? (photo == 0 ? 1 : 0) : 1;
        String species = PHOTO_IDS[photoSpeciesIndex], display = PHOTO_NAMES[photoSpeciesIndex];
        result.add(new Spec("photo_" + photoSpeciesIndex, ContractType.PHOTO, species, display, 1,
                1 + photoSpeciesIndex, photoSpeciesIndex == 2 ? 5 : photoSpeciesIndex == 0 ? 2 : 0,
                "定向观察：" + display, "接取后使用相机拍摄指定物种。服务器核对真实快门、被摄实体与拍摄时刻，旧照片不计。"));
        return result.stream().filter(spec -> spec.minimumRank <= rank)
                .filter(spec -> spec.type == ContractType.PHOTO || validItem(ResourceLocation.tryParse(spec.target))).toList();
    }

    private static Spec delivery(String key, String target, String targetName, int base, int difficulty,
                                 int minimumRank, String title, String text) {
        return new Spec(key, ContractType.DELIVERY, target, targetName, base, difficulty, minimumRank, title, text);
    }

    private static ItemStack inputStack(ContractType type, ResourceLocation target) {
        ResourceLocation icon = type == ContractType.PHOTO
                ? new ResourceLocation("minecraft", "spyglass") : target;
        if (icon == null || !validItem(icon)) return ItemStack.EMPTY;
        return new ItemStack(BuiltInRegistries.ITEM.get(icon));
    }

    private static String objective(Spec spec, ResourceLocation target, int count) {
        if (spec.type == ContractType.PHOTO) return "服务器核验接取后拍摄：" + spec.targetName + "（" + spec.target + "）";
        return "交付 " + count + " 个" + spec.targetName + "（" + target + "）";
    }

    private static String objective(Progress record) {
        ResourceLocation target = ResourceLocation.tryParse(record.target);
        return record.type == ContractType.PHOTO ? "服务器核验接取后拍摄：" + record.objective : record.objective;
    }

    private static String contractId(long cycle, Spec spec) { return cycle + ":" + spec.key; }

    private static boolean validContractId(String id) {
        return id != null && id.length() <= 96 && id.matches("[0-9]{1,20}:[a-z0-9_]{1,48}");
    }

    private static long cycleAt(long millis) { return Math.max(0, Math.floorDiv(millis, ROTATION_MS)); }
    private static long secondsRemaining(long millis) {
        long remainder = Math.floorMod(millis, ROTATION_MS);
        return Math.max(0, (ROTATION_MS - remainder + 999L) / 1000L);
    }

    private static void ensureCycle(PlayerProgress progress, long cycle, ContractData data) {
        int currentRank = rankIndex(progress.completedTotal);
        boolean changed = false;
        if (progress.offerCycle != cycle) {
            progress.offerCycle = cycle;
            trimPlayer(progress, cycle);
            changed = true;
        }
        if (progress.offerRank != currentRank) {
            progress.offerRank = currentRank;
            changed = true;
        }
        if (changed) data.setDirty();
    }

    private static void trimPlayer(PlayerProgress progress, long currentCycle) {
        progress.acceptedIds.removeIf(id -> {
            int colon = id.indexOf(':');
            if (colon <= 0) return true;
            try { return Long.parseLong(id.substring(0, colon)) < currentCycle - 64; }
            catch (NumberFormatException invalid) { return true; }
        });
        while (progress.acceptedIds.size() > MAX_ACCEPTED_IDS) progress.acceptedIds.remove(progress.acceptedIds.iterator().next());
        if (progress.contracts.size() > MAX_RECORDS) {
            List<String> removable = progress.contracts.values().stream().filter(record -> record.claimed)
                    .sorted(Comparator.comparingLong(record -> record.acceptedAtMillis))
                    .map(record -> record.id).toList();
            for (String id : removable) {
                if (progress.contracts.size() <= MAX_RECORDS) break;
                progress.contracts.remove(id);
            }
        }
    }

    private static int rankIndex(long total) {
        int rank = 0;
        for (int i = 0; i < RANK_THRESHOLDS.length; i++) if (total >= RANK_THRESHOLDS[i]) rank = i;
        return rank;
    }
    private static String rankName(long total) { return RANKS[rankIndex(total)]; }
    private static int clampRank(int rank) { return Math.max(0, Math.min(RANKS.length - 1, rank)); }
    private static int scaledCount(int base, int rank) { return Math.max(1, Math.min(4096, base + (base * rank * 15 + 99) / 100)); }
    private static ItemStack rewardStack(UUID player, long cycle, Spec spec, int rank) {
        int tier = clampRank(rank);
        List<String> pool;
        if (spec.type == ContractType.PHOTO) {
            if (tier < 2) pool = List.of("minecraft:baked_potato", "minecraft:bread", "minecraft:cooked_beef");
            else if (tier < 5) pool = List.of("minecraft:emerald", "ic2:insulated_copper_cable", "create:shaft");
            else pool = List.of("minecraft:emerald", "ic2:advanced_circuit", "create:precision_mechanism");
        } else if (tier == 0) {
            pool = List.of("minecraft:baked_potato", "minecraft:bread", "minecraft:cooked_beef");
        } else if (tier == 1) {
            pool = List.of("minecraft:emerald", "minecraft:bread", "minecraft:iron_ingot");
        } else if (tier < 5) {
            pool = List.of("ic2:insulated_copper_cable", "create:shaft", "minecraft:emerald");
        } else if (tier < 7) {
            pool = List.of("ic2:advanced_circuit", "ic2:insulated_gold_cable", "minecraft:emerald");
        } else {
            pool = List.of("create:precision_mechanism", "ic2:advanced_circuit", "minecraft:emerald");
        }
        int start = Math.floorMod(player.hashCode() * 31 + Long.hashCode(cycle) * 17 + spec.key.hashCode(), pool.size());
        for (int i = 0; i < pool.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(pool.get((start + i) % pool.size()));
            if (id == null || id.toString().equals(spec.target) || !validItem(id)) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            int count = rewardCount(player, cycle, spec, tier, id);
            return new ItemStack(item, Math.min(count, item.getMaxStackSize()));
        }
        return ItemStack.EMPTY;
    }

    private static int rewardCount(UUID player, long cycle, Spec spec, int rank, ResourceLocation item) {
        int variation = Math.floorMod(player.hashCode() * 19 + Long.hashCode(cycle) * 7 + spec.key.hashCode(), 2);
        if (item.equals(new ResourceLocation("minecraft", "emerald")))
            return Math.min(64, 1 + rank * 2 + Math.min(2, spec.difficulty / 2) + variation);
        if (item.getPath().equals("precision_mechanism") || item.getPath().equals("advanced_circuit"))
            return rank >= 6 ? 2 : 1;
        if (item.getPath().contains("cable")) return 1 + Math.min(3, rank / 2) + variation;
        if (item.getNamespace().equals("minecraft") && (item.getPath().equals("bread")
                || item.getPath().equals("cooked_beef") || item.getPath().equals("baked_potato")))
            return 2 + Math.min(3, rank / 2) + variation;
        if (item.equals(new ResourceLocation("minecraft", "iron_ingot"))) return 1 + variation;
        return 1 + Math.min(3, rank / 3) + variation;
    }

    private static boolean validPlayer(ServerPlayer player) {
        return player != null && player.server != null && !player.hasDisconnected() && player.isAlive()
                && !player.isRemoved() && player.serverLevel().getServer() == player.server;
    }

    private static boolean validBoard(ServerPlayer player, GlobalPos board, boolean requirePower) {
        if (board == null || !board.dimension().equals(player.serverLevel().dimension())) return false;
        ServerLevel level = player.serverLevel();
        return BoardBlockEntity.validateInteraction(level, board, player, requirePower);
    }

    private static BoardSnapshot emptySnapshot(GlobalPos board, long cycle, long remaining, int page) {
        return new BoardSnapshot(board, "E", 0, cycle, remaining, 0, 1, List.of());
    }

    private static boolean validItem(ResourceLocation id) {
        return id != null && BuiltInRegistries.ITEM.containsKey(id)
                && BuiltInRegistries.ITEM.get(id) != net.minecraft.world.item.Items.AIR;
    }

    private static int countItems(Inventory inventory, Item item) {
        long count = 0;
        for (ItemStack stack : inventory.items) if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        for (ItemStack stack : inventory.offhand) if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        return (int)Math.min(Integer.MAX_VALUE, count);
    }

    private static void removeItems(Inventory inventory, Item item, int count) {
        int remaining = count;
        for (ItemStack stack : inventory.items) {
            if (remaining == 0) break;
            if (stack.isEmpty() || stack.getItem() != item) continue;
            int removed = Math.min(stack.getCount(), remaining); stack.shrink(removed); remaining -= removed;
        }
        for (ItemStack stack : inventory.offhand) {
            if (remaining == 0) break;
            if (stack.isEmpty() || stack.getItem() != item) continue;
            int removed = Math.min(stack.getCount(), remaining); stack.shrink(removed); remaining -= removed;
        }
        inventory.setChanged();
    }

    private static boolean canFit(Inventory inventory, ItemStack reward) {
        int capacity = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.isEmpty()) capacity += reward.getMaxStackSize();
            else if (ItemStack.isSameItemSameTags(stack, reward)) capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            if (capacity >= reward.getCount()) return true;
        }
        return false;
    }

    private static boolean insertWithoutDropping(Inventory inventory, ItemStack incoming) {
        for (int i = 0; i < inventory.items.size() && !incoming.isEmpty(); i++) {
            ItemStack existing = inventory.items.get(i);
            if (existing.isEmpty()) {
                int moved = Math.min(incoming.getCount(), incoming.getMaxStackSize());
                inventory.items.set(i, incoming.split(moved));
            } else if (ItemStack.isSameItemSameTags(existing, incoming)) {
                int moved = Math.min(incoming.getCount(), Math.max(0, existing.getMaxStackSize() - existing.getCount()));
                if (moved > 0) { existing.grow(moved); incoming.shrink(moved); }
            }
        }
        if (!incoming.isEmpty()) return false;
        inventory.setChanged(); return true;
    }

    private static Progress findProgress(PlayerProgress progress, String id) {
        if (!validContractId(id)) return null;
        return progress.contracts.get(id);
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long saturatingAdd(long value, long amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }
}
