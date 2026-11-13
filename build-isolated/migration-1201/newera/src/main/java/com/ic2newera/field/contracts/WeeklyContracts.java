package com.ic2newera.field.contracts;

import com.ic2newera.field.EcologyAlbum;
import com.ic2newera.field.FieldMilestones;
import com.ic2newera.field.board.BoardBlockEntity;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

/** Personal, persistent weekly work orders. Every mutation is server authoritative. */
public final class WeeklyContracts {
    private static final String DATA_NAME = "ic2newera_weekly_contracts";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_PLAYERS = 4096, MAX_JOBS = 96, MAX_ACTIVE = 6, MAX_INPUTS = 3, MAX_REWARDS = 4;
    private static final String[] PHOTO_TARGETS = {"spawn:angler_fish", "spawn:clam", "spawn:tuna"};
    private static final String[] PHOTO_NAMES = {"鮟鱇鱼", "蛤蜊", "金枪鱼"};

    private WeeklyContracts() { }

    public enum State { OFFERED, ACTIVE, COMPLETED, CLAIMED }
    public enum SideType { DAILY_TASKS, VERIFIED_PHOTO }

    public record StackAmount(ItemStack stack, int required, int delivered) {
        public StackAmount {
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
            required = Math.max(0, Math.min(4096, required));
            delivered = Math.max(0, Math.min(required, delivered));
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public record WeeklyRow(String id, String title, String description, List<StackAmount> inputs,
                            List<ItemStack> rewards, SideType sideType, String sideTarget,
                            int sideRequired, int sideProgress, State state) {
        public WeeklyRow {
            id = safe(id, 96); title = safe(title, 96); description = safe(description, 512);
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            rewards = rewards == null ? List.of() : rewards.stream().map(ItemStack::copy).toList();
            sideType = sideType == null ? SideType.DAILY_TASKS : sideType;
            sideTarget = safe(sideTarget, 128);
            sideRequired = Math.max(0, Math.min(16, sideRequired));
            sideProgress = Math.max(0, Math.min(sideRequired, sideProgress));
            state = state == null ? State.OFFERED : state;
        }
        @Override public List<ItemStack> rewards() { return rewards.stream().map(ItemStack::copy).toList(); }
    }

    public record WeeklyView(GlobalPos board, String theme, long weekStartMillis, long deadlineMillis,
                             List<WeeklyRow> rows, String notice) {
        public WeeklyView {
            theme = safe(theme, 128); rows = rows == null ? List.of() : List.copyOf(rows);
            notice = safe(notice, 512);
        }
    }

    private record InputSpec(String itemId, int amount) { }
    private record JobSpec(String key, String title, String description, int tier, List<InputSpec> inputs,
                           String[] rewards, SideType sideType, String sideTarget, String sideName,
                           int sideRequired) { }

    private static final class Job {
        String id, key, title, description, theme;
        long weekStart, acceptedAt;
        GlobalPos board;
        List<ItemStack> inputs = new ArrayList<>();
        List<Integer> required = new ArrayList<>(), delivered = new ArrayList<>();
        List<ItemStack> rewards = new ArrayList<>();
        SideType sideType;
        String sideTarget = "";
        int sideRequired, sideProgress;
        boolean accepted, claimed;
        LinkedHashSet<String> proofKeys = new LinkedHashSet<>();
    }

    private static final class Person {
        int claimedJobs;
        int tierThisWeek;
        long currentWeek = Long.MIN_VALUE;
        final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
        final LinkedHashSet<String> dailyProofsThisWeek = new LinkedHashSet<>();
        final LinkedHashSet<String> photoProofs = new LinkedHashSet<>();
    }

    private static final class Data extends SavedData {
        final Map<UUID, Person> people = new HashMap<>();
        static Data load(CompoundTag root) {
            Data out = new Data();
            ListTag list = root.getList("People", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(list.size(), MAX_PLAYERS); i++) {
                CompoundTag row = list.getCompound(i);
                if (!row.hasUUID("Player")) continue;
                Person person = new Person(); person.claimedJobs = Math.max(0, row.getInt("ClaimedJobs"));
                person.currentWeek = row.getLong("CurrentWeek"); person.tierThisWeek = Math.max(0, Math.min(3, row.getInt("TierThisWeek")));
                ListTag daily = row.getList("DailyProofs", Tag.TAG_STRING);
                for (int j = Math.max(0, daily.size() - 256); j < daily.size(); j++) person.dailyProofsThisWeek.add(daily.getString(j));
                ListTag photos = row.getList("PhotoProofs", Tag.TAG_STRING);
                for (int j = Math.max(0, photos.size() - 512); j < photos.size(); j++) person.photoProofs.add(photos.getString(j));
                ListTag jobs = row.getList("Jobs", Tag.TAG_COMPOUND);
                for (int j = Math.max(0, jobs.size() - MAX_JOBS); j < jobs.size(); j++) {
                    Job job = readJob(jobs.getCompound(j));
                    if (job != null) person.jobs.putIfAbsent(job.id, job);
                }
                out.people.put(row.getUUID("Player"), person);
            }
            return out;
        }
        @Override public CompoundTag save(CompoundTag root) {
            ListTag all = new ListTag();
            people.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_PLAYERS).forEach(e -> {
                CompoundTag row = new CompoundTag(); row.putUUID("Player", e.getKey());
                Person person = e.getValue(); row.putInt("ClaimedJobs", person.claimedJobs); row.putLong("CurrentWeek", person.currentWeek); row.putInt("TierThisWeek", person.tierThisWeek);
                ListTag daily = new ListTag(); person.dailyProofsThisWeek.stream().skip(Math.max(0, person.dailyProofsThisWeek.size() - 256)).forEach(s -> daily.add(StringTag.valueOf(s)));
                ListTag photos = new ListTag(); person.photoProofs.stream().skip(Math.max(0, person.photoProofs.size() - 512)).forEach(s -> photos.add(StringTag.valueOf(s)));
                ListTag jobs = new ListTag(); person.jobs.values().stream().skip(Math.max(0, person.jobs.size() - MAX_JOBS)).forEach(j -> jobs.add(writeJob(j)));
                row.put("DailyProofs", daily); row.put("PhotoProofs", photos); row.put("Jobs", jobs); all.add(row);
            });
            root.put("People", all); return root;
        }
    }

    /** Current work order board state. Monday 00:00 in Beijing is the rotation boundary. */
    public static WeeklyView view(ServerPlayer player, GlobalPos board) {
        long now = System.currentTimeMillis(), start = weekStart(now), deadline = nextWeek(start);
        if (!validPlayer(player) || !validBoard(player, board)) return new WeeklyView(board, "个人周度联合工单", start, deadline, List.of(), "请在已加载、供电且可操作的公告栏旁查看。");
        Data data = data(player.server); Person person = person(player, data, true);
        if (person == null) return new WeeklyView(board, "个人周度联合工单", start, deadline, List.of(), "服务器个人记录已满，暂时无法建立周单。");
        rollWeek(person, start, data);
        LinkedHashMap<String, WeeklyRow> visible = new LinkedHashMap<>();
        person.jobs.values().stream().filter(j -> j.accepted && !j.claimed).sorted(Comparator.comparingLong(j -> j.acceptedAt))
                .limit(MAX_ACTIVE).forEach(j -> visible.putIfAbsent(j.id, row(j)));
        List<JobSpec> offers = specs(person.tierThisWeek, start).stream().filter(WeeklyContracts::available).toList();
        for (JobSpec spec : offers) {
            String id = jobId(start, spec.key);
            Job saved = person.jobs.get(id);
            if (saved != null) visible.putIfAbsent(id, row(saved));
            else visible.putIfAbsent(id, offerRow(id, start, spec));
        }
        String theme = offers.isEmpty() ? "本周暂无可用工单" : offers.get(0).title + "系列";
        return new WeeklyView(board, theme, start, deadline, new ArrayList<>(visible.values()),
                "每周一 00:00（北京时间）刷新；最多保留六份未结工单，已接未完成单可跨周继续，已完成单可之后领取。");
    }

    /** Actions: accept(jobId), submit(jobId,itemId,count), submit_all(jobId,itemId,0), claim(jobId). */
    public static String act(ServerPlayer player, GlobalPos board, String action, String jobId,
                             ResourceLocation itemId, int count) {
        if (!validPlayer(player)) return "周工单只可由在线服务端玩家操作。";
        if (!validBoard(player, board)) return "请在已加载且供电的公告栏八格内操作。";
        if (action == null || action.length() > 16 || jobId == null || jobId.length() > 96) return "工单操作无效。";
        long now = System.currentTimeMillis(), start = weekStart(now);
        Data data = data(player.server); Person person = person(player, data, true);
        if (person == null) return "服务器个人记录已满。";
        rollWeek(person, start, data);
        Job job = person.jobs.get(jobId);
        String verb = action.toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "accept" -> accept(player, board, jobId, start, now, person, data);
            case "submit" -> submit(player, board, job, itemId, count, data);
            case "submit_all" -> submitAll(player, board, job, itemId, data);
            case "claim" -> claim(player, board, job, person, data);
            default -> "可用周工单操作：accept、submit、claim。";
        };
    }

    /** Adds valid weekly destinations to phone journal APIs without exposing another player's data. */
    public static List<ActiveWeeklyRoute> activeRoutes(ServerPlayer player) {
        if (!validPlayer(player)) return List.of();
        Person person = person(player, data(player.server), false); if (person == null) return List.of();
        return person.jobs.values().stream().filter(j -> j.accepted && !j.claimed)
                .sorted(Comparator.comparingLong(j -> j.acceptedAt))
                .map(j -> new ActiveWeeklyRoute(j.id, j.title, j.board)).toList();
    }
    public record ActiveWeeklyRoute(String id, String title, GlobalPos board) { }

    /** Called by DailyContracts only after a daily job has server-side reached completed state. */
    public static void onDailyContractCompleted(ServerPlayer player, String dailyId, long completedAtMillis) {
        if (!validPlayer(player) || dailyId == null || dailyId.isBlank() || dailyId.length() > 96) return;
        Data data = data(player.server); Person person = person(player, data, true); if (person == null) return;
        long week = weekStart(completedAtMillis);
        if (person.currentWeek != week) {
            person.currentWeek = week; person.tierThisWeek = tierIndex(person.claimedJobs); person.dailyProofsThisWeek.clear();
        }
        if (!person.dailyProofsThisWeek.add(dailyId)) return;
        for (Job job : person.jobs.values()) if (job.accepted && !job.claimed && job.sideType == SideType.DAILY_TASKS
                && completedAtMillis >= job.acceptedAt) {
            job.proofKeys.add("daily:" + dailyId); job.sideProgress = Math.min(job.sideRequired, job.proofKeys.size());
        }
        data.setDirty();
    }

    /** Called only after EcologyAlbum has produced a matching server-confirmed witness. */
    public static void onPhotoEvidence(ServerPlayer player, EcologyAlbum.PhotoEvidence evidence) {
        if (!validPlayer(player) || evidence == null || !player.getUUID().equals(evidence.playerId())
                || evidence.evidenceKey() == null || evidence.evidenceKey().isBlank() || evidence.evidenceKey().length() > 64
                || evidence.frameId() == null || evidence.frameId().isBlank() || evidence.frameId().length() > 64
                || evidence.cameraPosition() == null || evidence.subjectPosition() == null
                || !evidence.cameraPosition().dimension().equals(evidence.subjectPosition().dimension())
                || evidence.serverCaptureTimeMillis() > System.currentTimeMillis()) return;
        EcologyAlbum.PhotoEvidence verified = EcologyAlbum.photoEvidence(player, evidence.evidenceKey());
        if (verified == null || !verified.equals(evidence)) return;
        Data data = data(player.server); Person person = person(player, data, false); if (person == null) return;
        if (!person.photoProofs.add(evidence.evidenceKey())) return;
        Job match = person.jobs.values().stream().filter(j -> j.accepted && !j.claimed && j.sideType == SideType.VERIFIED_PHOTO
                        && j.sideTarget.equals(evidence.speciesId()) && evidence.serverCaptureTimeMillis() > j.acceptedAt
                        && j.sideProgress < j.sideRequired)
                .min(Comparator.comparingLong(j -> j.acceptedAt)).orElse(null);
        if (match != null) { match.proofKeys.add("photo:" + evidence.evidenceKey()); match.sideProgress = 1; }
        data.setDirty();
    }

    private static String accept(ServerPlayer player, GlobalPos board, String id, long week, long now, Person person, Data data) {
        Job already = person.jobs.get(id); if (already != null) return already.accepted ? "这份周工单已经接取过。" : "这份工单已记录。";
        if (person.jobs.values().stream().filter(j -> j.accepted && !j.claimed).count() >= MAX_ACTIVE)
            return "最多同时保留六份未结工单；请先提交物资并领取已完成工单。";
        JobSpec spec = specs(person.tierThisWeek, week).stream().filter(s -> jobId(week, s.key).equals(id)).findFirst().orElse(null);
        if (spec == null) return "工单已过期或不属于当前个人清单。";
        Job job = new Job(); job.id = id; job.key = spec.key; job.title = spec.title; job.description = spec.description;
        job.theme = spec.title; job.weekStart = week; job.acceptedAt = now; job.board = board; job.accepted = true;
        job.sideType = spec.sideType; job.sideTarget = spec.sideTarget; job.sideRequired = spec.sideRequired;
        for (InputSpec input : spec.inputs) {
            ResourceLocation rid = ResourceLocation.tryParse(input.itemId); Item item = rid == null ? null : ForgeRegistries.ITEMS.getValue(rid);
            if (item == null || item == Items.AIR) return "目标物资当前未注册，未接取工单。";
            job.inputs.add(new ItemStack(item)); job.required.add(input.amount); job.delivered.add(0);
        }
        for (String rewardId : spec.rewards) {
            ResourceLocation rid = ResourceLocation.tryParse(rewardId); Item item = rid == null ? null : ForgeRegistries.ITEMS.getValue(rid);
            if (item == null || item == Items.AIR) continue;
            if (spec.inputs.stream().anyMatch(input -> input.itemId.equals(rewardId))) continue;
            int amount = rewardAmount(rewardId, person.tierThisWeek, week);
            job.rewards.add(new ItemStack(item, amount));
        }
        if (job.rewards.isEmpty()) return "本工单奖励当前不可用，未接取。";
        if (job.sideType == SideType.DAILY_TASKS && person.currentWeek == week) {
            for (String proof : person.dailyProofsThisWeek) job.proofKeys.add("daily:" + proof);
            job.sideProgress = Math.min(job.sideRequired, job.proofKeys.size());
        }
        person.jobs.put(id, job); trimJobs(person); data.setDirty();
        FieldMilestones.award(player, "weekly_accepted");
        return "已接取「" + job.title + "」；每种物资可分批提交，已接工单跨周保留。";
    }

    private static String submit(ServerPlayer player, GlobalPos board, Job job, ResourceLocation itemId, int count, Data data) {
        if (job == null || !job.accepted || !job.board.equals(board)) return "找不到这份工单；请回接取时的公告栏。";
        if (job.claimed) return "该工单已领取奖励。";
        if (complete(job)) return "工单已完成，请领取奖励。";
        if (itemId == null || count < 1 || count > 4096) return "请选择一种目标物资和正数量。";
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) return "物资未注册，未扣除任何物品。";
        int slot = -1;
        for (int i = 0; i < job.inputs.size(); i++) if (job.inputs.get(i).getItem() == item) { slot = i; break; }
        if (slot < 0) return "这件物品不属于本工单所需材料，未扣除任何物品。";
        int remaining = job.required.get(slot) - job.delivered.get(slot);
        if (count > remaining) return "本项最多还需 " + remaining + " 个；未扣除物品。";
        Inventory inventory = player.getInventory();
        if (countItems(inventory, item) < count) return "背包中该物资不足 " + count + " 个；未扣除任何物品。";
        removeItems(inventory, item, count); job.delivered.set(slot, job.delivered.get(slot) + count); data.setDirty();
        return complete(job) ? "已精准提交 " + count + " 个；工单完成，可以领取奖励。" : "已精准提交 " + count + " 个；进度已保存，可分批继续。";
    }

    private static String submitAll(ServerPlayer player, GlobalPos board, Job job, ResourceLocation itemId, Data data) {
        if (job == null || !job.accepted || !job.board.equals(board)) return "找不到这份工单；请回接取时的公告栏。";
        if (job.claimed) return "该工单已领取奖励。";
        if (complete(job)) return "工单已完成，请领取奖励。";
        if (itemId == null) return "请选择一种目标物资。";
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) return "物资未注册，未扣除任何物品。";
        int slot = -1;
        for (int i = 0; i < job.inputs.size(); i++) if (job.inputs.get(i).getItem() == item) { slot = i; break; }
        if (slot < 0) return "这件物品不属于本工单所需材料，未扣除任何物品。";
        int remaining = job.required.get(slot) - job.delivered.get(slot);
        int amount = Math.min(remaining, countItems(player.getInventory(), item));
        if (amount < 1) return "背包中没有可提交的目标物资。";
        removeItems(player.getInventory(), item, amount); job.delivered.set(slot, job.delivered.get(slot) + amount); data.setDirty();
        return complete(job) ? "已按库存精准提交 " + amount + " 个；工单完成，可以领取奖励。"
                : "已按库存精准提交 " + amount + " 个；剩余需求已保存。";
    }

    private static String claim(ServerPlayer player, GlobalPos board, Job job, Person person, Data data) {
        if (job == null || !job.accepted || !job.board.equals(board)) return "找不到这份工单；请回接取时的公告栏。";
        if (job.claimed) return "该周工单奖励已领取，不能重复领取。";
        if (!complete(job)) return "工单尚未完成；请完成所有物资与周目标。";
        Inventory inventory = player.getInventory();
        List<ItemStack> simulated = inventory.items.stream().map(ItemStack::copy).toList();
        List<ItemStack> slots = new ArrayList<>(simulated);
        for (ItemStack reward : job.rewards) if (!insert(slots, reward.copy())) return "背包空间不足；奖励保留，可整理后重试。";
        for (int i = 0; i < slots.size(); i++) inventory.items.set(i, slots.get(i));
        inventory.setChanged(); job.claimed = true; person.claimedJobs = Math.min(1_000_000, person.claimedJobs + 1); data.setDirty();
        FieldMilestones.award(player, "weekly_claimed");
        String summary = job.rewards.stream().map(s -> s.getCount() + " 个" + s.getHoverName().getString()).reduce((a,b) -> a + "、" + b).orElse("奖励");
        return "已领取「" + job.title + "」：" + summary + "。";
    }

    private static WeeklyRow row(Job job) {
        List<StackAmount> inputs = new ArrayList<>();
        for (int i = 0; i < job.inputs.size(); i++) inputs.add(new StackAmount(job.inputs.get(i), job.required.get(i), job.delivered.get(i)));
        return new WeeklyRow(job.id, job.title, job.description, inputs, job.rewards, job.sideType, job.sideTarget,
                job.sideRequired, job.sideProgress, job.claimed ? State.CLAIMED : complete(job) ? State.COMPLETED : State.ACTIVE);
    }

    private static WeeklyRow offerRow(String id, long week, JobSpec spec) {
        List<StackAmount> inputs = new ArrayList<>();
        for (InputSpec input : spec.inputs) {
            ResourceLocation rid = ResourceLocation.tryParse(input.itemId); Item item = rid == null ? null : ForgeRegistries.ITEMS.getValue(rid);
            if (item != null && item != Items.AIR) inputs.add(new StackAmount(new ItemStack(item), input.amount, 0));
        }
        List<ItemStack> rewards = new ArrayList<>();
        for (String idReward : spec.rewards) {
            ResourceLocation rid = ResourceLocation.tryParse(idReward); Item item = rid == null ? null : ForgeRegistries.ITEMS.getValue(rid);
            if (item != null && item != Items.AIR) rewards.add(new ItemStack(item, rewardAmount(idReward, spec.tier, week)));
        }
        return new WeeklyRow(id, spec.title, spec.description, inputs, rewards, spec.sideType, spec.sideTarget,
                spec.sideRequired, 0, State.OFFERED);
    }

    private static boolean complete(Job job) {
        if (job == null || !job.accepted || job.sideProgress < job.sideRequired) return false;
        for (int i = 0; i < job.inputs.size(); i++) if (job.delivered.get(i) < job.required.get(i)) return false;
        return true;
    }

    private static List<JobSpec> specs(int claims, long week) {
        int tier = Math.max(0, Math.min(3, claims));
        SideType side = Math.floorMod(week / 604800000L, 2) == 0 ? SideType.DAILY_TASKS : SideType.VERIFIED_PHOTO;
        int photo = Math.floorMod((int)(week / 604800000L), PHOTO_TARGETS.length);
        String target = side == SideType.VERIFIED_PHOTO ? PHOTO_TARGETS[photo] : "";
        String name = side == SideType.VERIFIED_PHOTO ? PHOTO_NAMES[photo] : "";
        if (tier == 0) return List.of(
                new JobSpec("supply", "地面民用补给", "向公共公告栏交付基础建设与食物补给。", tier,
                        List.of(new InputSpec("minecraft:oak_log", 24), new InputSpec("minecraft:bread", 8), new InputSpec("minecraft:iron_ingot", 8)),
                        new String[]{"minecraft:emerald", "minecraft:cooked_beef"}, side, target, name, side == SideType.DAILY_TASKS ? 2 : 1),
                new JobSpec("farm", "农田联运补给", "整理农场收成，支持附近居民与远行队伍。", tier,
                        List.of(new InputSpec("farmersdelight:tomato", 12), new InputSpec("farmersdelight:cabbage", 12), new InputSpec("farmersdelight:onion", 12)),
                        new String[]{"minecraft:emerald", "minecraft:bread"}, side, target, name, side == SideType.DAILY_TASKS ? 2 : 1));
        if (tier == 1) return List.of(
                new JobSpec("mechanical", "机械维护联合工单", "向公告栏交付机械与供能部件，支持工坊维护。", tier,
                        List.of(new InputSpec("create:andesite_alloy", 12), new InputSpec("create:cogwheel", 8), new InputSpec("ic2:insulated_copper_cable", 8)),
                        new String[]{"minecraft:emerald", "ic2:bronze_ingot"}, side, target, name, side == SideType.DAILY_TASKS ? 2 : 1),
                new JobSpec("steel", "钢材周转计划", "集中整理基础钢材与线路材料。", tier,
                        List.of(new InputSpec("ic2:steel_ingot", 12), new InputSpec("minecraft:copper_ingot", 16), new InputSpec("create:andesite_alloy", 8)),
                        new String[]{"minecraft:emerald", "create:precision_mechanism"}, side, target, name, side == SideType.DAILY_TASKS ? 2 : 1));
        if (tier == 2) return List.of(
                new JobSpec("network", "自动化网络扩容", "供应储存网络与控制系统需要的实际零件。", tier,
                        List.of(new InputSpec("ae2:certus_quartz_crystal", 16), new InputSpec("ae2:quartz_fiber", 12), new InputSpec("ic2:circuit", 8)),
                        new String[]{"minecraft:emerald", "ae2:logic_processor"}, side, target, name, side == SideType.DAILY_TASKS ? 2 : 1),
                new JobSpec("foundry", "耐火构件周转", "交付匠魂铸造设施所用的耐火砖料与工业线缆。", tier,
                        List.of(new InputSpec("tconstruct:seared_brick", 24), new InputSpec("tconstruct:scorched_brick", 12), new InputSpec("ic2:insulated_copper_cable", 16)),
                        new String[]{"minecraft:emerald", "ic2:advanced_circuit"}, side, target, name, side == SideType.DAILY_TASKS ? 2 : 1));
        return List.of(
                new JobSpec("lunar", "月面基地建材联运", "为已建立的远征网络集中准备真实航天建材。", tier,
                        List.of(new InputSpec("ad_astra:steel_ingot", 24), new InputSpec("ad_astra:desh_ingot", 12), new InputSpec("ae2:quartz_fiber", 16)),
                        new String[]{"minecraft:emerald", "ae2:engineering_processor"}, side, target, name, side == SideType.DAILY_TASKS ? 2 : 1),
                new JobSpec("expedition", "远征设备维护", "补充远征保障所需的工业线路与航天合金。", tier,
                        List.of(new InputSpec("ad_astra:desh_ingot", 16), new InputSpec("ad_astra:ostrum_ingot", 8), new InputSpec("ic2:advanced_circuit", 8)),
                        new String[]{"minecraft:emerald", "ae2:engineering_processor"}, side, target, name, side == SideType.DAILY_TASKS ? 2 : 1));
    }

    private static int rewardAmount(String id, int tier, long seed) {
        int variation = Math.floorMod((int)(seed >>> 12) + id.hashCode(), 3);
        if (id.equals("minecraft:emerald")) return 3 + tier * 2 + variation;
        if (id.equals("minecraft:bread") || id.equals("minecraft:cooked_beef")) return 4 + variation;
        return 1 + Math.min(2, tier / 2) + variation;
    }
    private static int tierIndex(int claimedJobs) { return claimedJobs < 2 ? 0 : claimedJobs < 5 ? 1 : claimedJobs < 9 ? 2 : 3; }

    private static boolean validBoard(ServerPlayer player, GlobalPos board) {
        return board != null && board.dimension().equals(player.serverLevel().dimension())
                && BoardBlockEntity.validateInteraction(player.serverLevel(), board, player, true);
    }
    private static boolean validPlayer(ServerPlayer player) {
        return player != null && player.server != null && !player.hasDisconnected() && player.isAlive() && !player.isRemoved()
                && player.serverLevel().getServer() == player.server;
    }
    private static Data data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Data::load, Data::new, DATA_NAME);
    }
    private static Person person(ServerPlayer player, Data data, boolean create) {
        Person p = data.people.get(player.getUUID()); if (p != null || !create || data.people.size() >= MAX_PLAYERS) return p;
        p = new Person(); data.people.put(player.getUUID(), p); data.setDirty(); return p;
    }
    private static void rollWeek(Person person, long current, Data data) {
        if (person.currentWeek == Long.MIN_VALUE || person.currentWeek != current) {
            person.currentWeek = current; person.tierThisWeek = tierIndex(person.claimedJobs); person.dailyProofsThisWeek.clear(); data.setDirty();
        }
    }
    private static long weekStart(long millis) {
        ZonedDateTime local = Instant.ofEpochMilli(millis).atZone(ZONE);
        return local.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZONE).toInstant().toEpochMilli();
    }
    private static long nextWeek(long start) { return Instant.ofEpochMilli(start).atZone(ZONE).plusWeeks(1).toInstant().toEpochMilli(); }
    private static String jobId(long week, String key) { return "week:" + week + ":" + key; }
    private static int countItems(Inventory inventory, Item item) {
        long count = 0;
        for (ItemStack stack : inventory.items) if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        for (ItemStack stack : inventory.offhand) if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        return (int)Math.min(Integer.MAX_VALUE, count);
    }
    private static void removeItems(Inventory inventory, Item item, int count) {
        int left = count;
        for (ItemStack stack : inventory.items) { if (left == 0) break; if (!stack.isEmpty() && stack.getItem() == item) { int n = Math.min(left, stack.getCount()); stack.shrink(n); left -= n; } }
        for (ItemStack stack : inventory.offhand) { if (left == 0) break; if (!stack.isEmpty() && stack.getItem() == item) { int n = Math.min(left, stack.getCount()); stack.shrink(n); left -= n; } }
        inventory.setChanged();
    }
    private static boolean insert(List<ItemStack> slots, ItemStack incoming) {
        for (ItemStack stack : slots) if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, incoming)) {
            int n = Math.min(incoming.getCount(), stack.getMaxStackSize() - stack.getCount()); stack.grow(n); incoming.shrink(n);
            if (incoming.isEmpty()) return true;
        }
        for (int i = 0; i < slots.size() && !incoming.isEmpty(); i++) if (slots.get(i).isEmpty()) {
            int n = Math.min(incoming.getCount(), incoming.getMaxStackSize()); slots.set(i, incoming.split(n));
        }
        return incoming.isEmpty();
    }
    private static void trimJobs(Person person) {
        while (person.jobs.size() > MAX_JOBS) {
            String removable = person.jobs.values().stream().filter(j -> !j.accepted || j.claimed).map(j -> j.id).findFirst().orElse(null);
            if (removable == null) break;
            person.jobs.remove(removable);
        }
    }
    private static boolean available(JobSpec spec) {
        for (InputSpec input : spec.inputs) {
            ResourceLocation id = ResourceLocation.tryParse(input.itemId);
            Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR) return false;
        }
        return spec.rewards.length > 0;
    }
    private static Job readJob(CompoundTag tag) {
        String id = tag.getString("Id"), key = tag.getString("Key");
        if (!id.startsWith("week:") || id.length() > 96 || key.length() > 48) return null;
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("BoardDimension"));
        if (dimension == null) return null;
        ListTag inputTags = tag.getList("Inputs", Tag.TAG_COMPOUND), reqTags = tag.getList("Required", Tag.TAG_INT), delTags = tag.getList("Delivered", Tag.TAG_INT);
        if (inputTags.size() < 2 || inputTags.size() > MAX_INPUTS || reqTags.size() != inputTags.size() || delTags.size() != inputTags.size()) return null;
        Job job = new Job(); job.id = id; job.key = key; job.title = safe(tag.getString("Title"), 96); job.description = safe(tag.getString("Description"), 512);
        job.theme = safe(tag.getString("Theme"), 128); job.weekStart = tag.getLong("WeekStart"); job.acceptedAt = Math.max(0, tag.getLong("AcceptedAt"));
        job.board = GlobalPos.of(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension), net.minecraft.core.BlockPos.of(tag.getLong("BoardPos")));
        job.accepted = tag.getBoolean("Accepted"); job.claimed = tag.getBoolean("Claimed");
        int side = tag.getInt("SideType"); if (side < 0 || side >= SideType.values().length) return null; job.sideType = SideType.values()[side];
        job.sideTarget = safe(tag.getString("SideTarget"), 128); job.sideRequired = Math.max(1, Math.min(16, tag.getInt("SideRequired"))); job.sideProgress = Math.max(0, Math.min(job.sideRequired, tag.getInt("SideProgress")));
        for (int i = 0; i < inputTags.size(); i++) {
            ItemStack stack = ItemStack.of(inputTags.getCompound(i)); int required = reqTags.getInt(i), delivered = delTags.getInt(i);
            if (stack.isEmpty() || stack.getCount() != 1 || required < 1 || required > 4096 || delivered < 0 || delivered > required) return null;
            job.inputs.add(stack); job.required.add(required); job.delivered.add(delivered);
        }
        ListTag rewardTags = tag.getList("Rewards", Tag.TAG_COMPOUND);
        if (rewardTags.isEmpty() || rewardTags.size() > MAX_REWARDS) return null;
        for (int i = 0; i < rewardTags.size(); i++) { ItemStack reward = ItemStack.of(rewardTags.getCompound(i)); if (reward.isEmpty()) return null; job.rewards.add(reward); }
        ListTag proofs = tag.getList("Proofs", Tag.TAG_STRING); for (int i = Math.max(0, proofs.size() - 32); i < proofs.size(); i++) { String proof = proofs.getString(i); if (proof.length() <= 128) job.proofKeys.add(proof); }
        return job;
    }
    private static CompoundTag writeJob(Job job) {
        CompoundTag tag = new CompoundTag(); tag.putString("Id", job.id); tag.putString("Key", job.key); tag.putString("Title", job.title); tag.putString("Description", job.description); tag.putString("Theme", job.theme);
        tag.putLong("WeekStart", job.weekStart); tag.putLong("AcceptedAt", job.acceptedAt); tag.putString("BoardDimension", job.board.dimension().location().toString()); tag.putLong("BoardPos", job.board.pos().asLong());
        tag.putBoolean("Accepted", job.accepted); tag.putBoolean("Claimed", job.claimed); tag.putInt("SideType", job.sideType.ordinal()); tag.putString("SideTarget", job.sideTarget); tag.putInt("SideRequired", job.sideRequired); tag.putInt("SideProgress", job.sideProgress);
        ListTag inputs = new ListTag(), required = new ListTag(), delivered = new ListTag(), rewards = new ListTag(), proofs = new ListTag();
        for (int i = 0; i < job.inputs.size(); i++) { inputs.add(job.inputs.get(i).save(new CompoundTag())); required.add(net.minecraft.nbt.IntTag.valueOf(job.required.get(i))); delivered.add(net.minecraft.nbt.IntTag.valueOf(job.delivered.get(i))); }
        for (ItemStack stack : job.rewards) rewards.add(stack.save(new CompoundTag()));
        job.proofKeys.stream().skip(Math.max(0, job.proofKeys.size() - 32)).forEach(s -> proofs.add(StringTag.valueOf(s)));
        tag.put("Inputs", inputs); tag.put("Required", required); tag.put("Delivered", delivered); tag.put("Rewards", rewards); tag.put("Proofs", proofs); return tag;
    }
    private static String safe(String value, int limit) { return value == null ? "" : value.substring(0, Math.min(value.length(), limit)); }
}
