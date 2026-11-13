package com.ic2newera.robot;

import com.ic2newera.industrial.IndustrialMobileAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Owner-bound completed-task snapshots and safe server-side replay for a robot identity. */
public final class RobotTaskBook {
    public static final String LAST = "@last", COLLECT = "@collect", COOK = "@cook", CRAFT = "@craft";
    public static final int MAX_SNAPSHOTS = 8;
    private static final String SNAPSHOTS = "Snapshots";

    public enum Action { LIST, SAVE, RUN, FORGET }
    public record Row(String key, String label, String type, ResourceLocation item, int amount, int mode,
                      ResourceLocation recipe, ResourceLocation tool, ResourceLocation material) {
        /** Keeps source and compatibility callers using the r67 six-field row shape working. */
        public Row(String key, String label, String type, ResourceLocation item, int amount, int mode) {
            this(key, label, type, item, amount, mode, null, null, null);
        }
    }
    public record Result(boolean accepted, String message, List<Row> rows) {
        public Result { rows = List.copyOf(rows == null ? List.of() : rows); }
    }

    private RobotTaskBook() { }

    /** Stable signature used by packets to reject stale selection clicks. */
    public static String signature(Row row) {
        if (row == null) return "";
        String base = row.type() + "|" + row.key() + "|" + row.item() + "|" + row.amount() + "|" + row.mode();
        // Preserve the exact r67 signature for collection/cooking rows while
        // binding every craft selection to all three new recipe identifiers.
        if (row.recipe() == null && row.tool() == null && row.material() == null) return base;
        return base + "|" + id(row.recipe()) + "|" + id(row.tool()) + "|" + id(row.material());
    }

    public static Result act(ServerPlayer player, RobotEntity robot, boolean remote, Action action,
                             String source, String name) {
        return act(player, robot, remote, action, source, name, "");
    }

    /** expected is optional for chat; UI callers pass the signature captured when the list was shown. */
    public static Result act(ServerPlayer player, RobotEntity robot, boolean remote, Action action,
                             String source, String name, String expected) {
        if (!authorized(player, robot, remote)) return fail("机器人未加载、离线或权限已改变。");
        if (action == null) return fail("任务簿操作无效。");
        CompoundTag state = RobotMemory.taskBookFor(player, robot);
        List<Row> rows = listRows(state);
        if (action == Action.LIST) {
            if (remote && !hasReadCharge(player)) return fail(IndustrialPhoneItem.EMPTY_MESSAGE);
            if (remote) chargeRead(player);
            return ok("已读取任务簿。", rows);
        }

        String selectedSource = source == null ? "" : source;
        String selectedName = name == null ? "" : name;
        if (action == Action.SAVE) {
            Row selected = findSource(state, selectedSource);
            if (selected == null) return fail("没有可保存的已完成任务。");
            if (!matchesExpected(selected, expected)) return fail("任务列表已更新，请重新选择。");
            String normalized = RobotTaskIntent.normalizedName(selectedName);
            if (normalized.isEmpty() || normalized.startsWith("@")) return fail("名称无效；请用1～16个中英文字符、数字、空格、下划线或短横线。");
            if (remote && !hasReadCharge(player)) return fail(IndustrialPhoneItem.EMPTY_MESSAGE);
            ListTag snapshots = state.getList(SNAPSHOTS, Tag.TAG_COMPOUND);
            int existing = snapshotIndex(snapshots, normalized);
            if (existing >= 0) {
                Row prior = loadRow(snapshots.getCompound(existing).getCompound("Row"), normalized);
                if (sameOrder(prior, selected)) {
                    if (remote) chargeRead(player);
                    return ok("相同任务快照已保存。", rows);
                }
                return fail("已有同名任务快照；为避免覆盖，未更改。");
            }
            if (snapshots.size() >= MAX_SNAPSHOTS) return fail("任务簿最多保存8个命名快照，请先删除一个。");
            CompoundTag snapshot = new CompoundTag(); snapshot.putString("Name", normalized);
            snapshot.put("Row", saveRow(selected)); snapshots.add(snapshot); state.put(SNAPSHOTS, snapshots);
            if (!RobotMemory.storeTaskBook(player, robot, state)) return fail("机器人身份状态已变化，未保存。");
            if (remote) chargeRead(player);
            return ok("已保存任务快照“" + normalized + "”。", listRows(state));
        }

        if (action == Action.FORGET) {
            String normalized = RobotTaskIntent.normalizedName(selectedSource);
            if (normalized.isEmpty() || normalized.startsWith("@")) return fail("只能删除命名任务快照。");
            ListTag snapshots = state.getList(SNAPSHOTS, Tag.TAG_COMPOUND);
            int index = snapshotIndex(snapshots, normalized);
            if (index < 0) return fail("没有这个命名任务快照。");
            Row selected = loadRow(snapshots.getCompound(index).getCompound("Row"), snapshots.getCompound(index).getString("Name"));
            if (selected == null || !matchesExpected(selected, expected)) return fail("任务列表已更新，请重新选择。");
            if (remote && !hasReadCharge(player)) return fail(IndustrialPhoneItem.EMPTY_MESSAGE);
            snapshots.remove(index); state.put(SNAPSHOTS, snapshots);
            if (!RobotMemory.storeTaskBook(player, robot, state)) return fail("机器人身份状态已变化，未删除。");
            if (remote) chargeRead(player);
            return ok("已删除任务快照“" + normalized + "”。", listRows(state));
        }

        Row selected = findSource(state, selectedSource);
        if (selected == null) return fail(CRAFT.equals(selectedSource)
                ? "没有完整的最近制作记录；旧记录缺少配方、工具或材料时不能安全复做。"
                : "没有可重放的已完成任务。");
        if (!matchesExpected(selected, expected)) return fail("任务列表已更新，请重新选择。");
        if (!BuiltInRegistries.ITEM.containsKey(selected.item()) || BuiltInRegistries.ITEM.get(selected.item()) == net.minecraft.world.item.Items.AIR)
            return fail("任务目标物品当前不可用，未派发。");
        if (selected.type().equals("craft")) {
            String missing = craftMissingReason(selected);
            if (!missing.isEmpty()) return fail(missing);
            String blocked = replayBlock(robot);
            if (!blocked.isEmpty()) return fail(blocked);
            if (remote && !IndustrialPhoneItem.hasHeldCharge(player, IndustrialPhoneItem.COMMAND_COST))
                return fail(IndustrialPhoneItem.EMPTY_MESSAGE);
            String started = com.ic2newera.robot.workshop.ToolWork.beginSaved(
                    player, robot, selected.recipe(), selected.item(), selected.tool(), selected.material());
            if (!started.isEmpty()) return fail(started);
            // ToolWork owns the one 200 EU robot charge at tool contact. The task-book
            // replay charges only the phone, after ToolWork has accepted the exact job.
            if (remote) chargeCommand(player);
            return ok("已开始复做一批 " + itemName(selected.item()) + "；严格沿用记录的配方、工具和材料。", rows);
        }
        String blocked = replayBlock(robot);
        if (!blocked.isEmpty()) return fail(blocked);
        if (remote && !IndustrialPhoneItem.hasHeldCharge(player, IndustrialPhoneItem.COMMAND_COST))
            return fail(IndustrialPhoneItem.EMPTY_MESSAGE);
        RobotCollectionTask.Result dispatched = selected.type().equals("collect")
                ? RobotCollectionOrders.assign(player, robot, remote, false, selected.item().toString(), selected.amount(), selected.mode())
                : RobotCookingOrders.assign(player, robot, remote, false, selected.item().toString(), selected.amount());
        if (!dispatched.accepted()) return fail(dispatched.message());
        return ok("已重新派发：" + dispatched.message(), rows);
    }

    /** Called only at the actual successful full-order completion points. */
    static void completedCollection(RobotEntity robot, ResourceLocation item, int amount, int mode) {
        if (item == null || amount < 1 || amount > 256 || mode < RobotCollectionTask.AUTO || mode > RobotCollectionTask.PICKUP_ONLY) return;
        RobotMemory.recordCompletedTask(robot, new Row(COLLECT, display("collect", item, amount, mode), "collect", item, amount, mode));
    }

    static void completedCooking(RobotEntity robot, ResourceLocation item, int amount) {
        if (item == null || amount < 1 || amount > 256) return;
        RobotMemory.recordCompletedTask(robot, new Row(COOK, display("cook", item, amount, 0), "cook", item, amount, 0));
    }

    /** Called by the server only after one exact recipe batch has actually completed. */
    public static void completedCraft(RobotEntity robot, ResourceLocation output, ResourceLocation recipe,
                                      ResourceLocation tool, ResourceLocation material) {
        if (robot == null || output == null || recipe == null || tool == null || material == null
                || !validItem(output) || !validItem(tool) || !validItem(material)) return;
        RobotMemory.recordCompletedTask(robot, new Row(CRAFT,
                displayCraft(output, recipe, tool, material), "craft", output, 1, 0, recipe, tool, material));
    }

    private static boolean authorized(ServerPlayer player, RobotEntity robot, boolean remote) {
        if (player == null || player.hasDisconnected() || !player.isAlive() || robot == null
                || !(robot.level() instanceof ServerLevel level) || level.getServer() != player.server
                || !robot.isAlive() || robot.isRemoved() || robot.getOwnerUUID() == null
                || !robot.getOwnerUUID().equals(player.getUUID()) || !RobotMemory.isRegistered(robot)) return false;
        return remote ? IndustrialMobileAccess.canReachRobot(player, robot)
                : player.level() == robot.level() && robot.canManage(player);
    }

    private static boolean hasReadCharge(ServerPlayer player) {
        return IndustrialPhoneItem.hasHeldCharge(player, IndustrialPhoneItem.READ_COST);
    }

    private static void chargeRead(ServerPlayer player) {
        IndustrialPhoneItem.consumeHeld(player, IndustrialPhoneItem.READ_COST);
        player.getInventory().setChanged();
    }

    public static String replayBlock(RobotEntity robot) {
        if (robot == null) return "机器人状态不可用，暂不能复做。";
        if (robot.collectionTask() != null || robot.hasPausedCollectionTask()) return "机器人有进行中或已暂停的采集任务，请先完成或取消。";
        if (robot.hasCookingTask()) return "机器人有进行中或已暂停的烹饪任务，请先完成或安全取消。";
        if (robot.movementIntent() != RobotMovementIntent.NONE && robot.movementIntent() != RobotMovementIntent.FOLLOW)
            return "机器人有待完成的移动指令，请先处理。";
        if (robot.movementBusy() || robot.isWorking() || robot.workTaskActive()) return "机器人正在执行其他工作，暂不能重放。";
        if (robot.buildTask() != null || robot.lineRefitTask() != null || robot.industrialRecoveryBlocked()
                || robot.industrialGoal() != null && robot.industrialGoal().hasSavedState()
                || robot.hasInvalidBuildTaskRecord() || robot.hasSavedSmeltingOrder()) return "机器人保留着施工、生产线、改造或烧矿记录，请先处理后再重放。";
        int mode = robot.mode();
        if (mode != RobotEntity.STAY && mode != RobotEntity.FOLLOW)
            return "机器人当前模式正在工作，请先切换到待命或跟随。";
        return "";
    }

    private static List<Row> listRows(CompoundTag state) {
        ArrayList<Row> rows = new ArrayList<>(11);
        ArrayList<Row> recent = new ArrayList<>(3);
        addRecent(state, recent, "LatestCollect", COLLECT);
        addRecent(state, recent, "LatestCook", COOK);
        addRecent(state, recent, "LatestCraft", CRAFT);
        recent.sort(Comparator.comparingLong((Row row) -> completedAt(state, row.key())).reversed());
        rows.addAll(recent);
        ListTag snapshots = state.getList(SNAPSHOTS, Tag.TAG_COMPOUND);
        ArrayList<Row> named = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            CompoundTag snapshot = snapshots.getCompound(i);
            String name = snapshot.getString("Name");
            Row row = loadRow(snapshot.getCompound("Row"), name);
            if (row != null) named.add(row);
        }
        named.sort(Comparator.comparing(Row::key, String.CASE_INSENSITIVE_ORDER));
        rows.addAll(named);
        return List.copyOf(rows);
    }

    private static Row findSource(CompoundTag state, String source) {
        if (LAST.equals(source)) {
            List<Row> latest = listRows(state).stream().filter(row -> row.key().equals(COLLECT)
                    || row.key().equals(COOK) || row.key().equals(CRAFT)).toList();
            if (latest.isEmpty()) return null;
            return latest.get(0);
        }
        if (COLLECT.equals(source)) return loadRow(state.getCompound("LatestCollect"), COLLECT);
        if (COOK.equals(source)) return loadRow(state.getCompound("LatestCook"), COOK);
        if (CRAFT.equals(source)) return loadRow(state.getCompound("LatestCraft"), CRAFT);
        String normalized = RobotTaskIntent.normalizedName(source);
        if (normalized.isEmpty()) return null;
        ListTag snapshots = state.getList(SNAPSHOTS, Tag.TAG_COMPOUND);
        int index = snapshotIndex(snapshots, normalized);
        return index < 0 ? null : loadRow(snapshots.getCompound(index).getCompound("Row"), snapshots.getCompound(index).getString("Name"));
    }

    private static int snapshotIndex(ListTag snapshots, String name) {
        for (int i = 0; i < snapshots.size(); i++)
            if (snapshots.getCompound(i).getString("Name").equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static boolean matchesExpected(Row row, String expected) {
        return expected == null || expected.isEmpty() || signature(row).equals(expected);
    }

    private static boolean sameOrder(Row a, Row b) {
        return a != null && b != null && a.type().equals(b.type()) && a.item().equals(b.item())
                && a.amount() == b.amount() && a.mode() == b.mode()
                && java.util.Objects.equals(a.recipe(), b.recipe()) && java.util.Objects.equals(a.tool(), b.tool())
                && java.util.Objects.equals(a.material(), b.material());
    }

    private static String display(String type, ResourceLocation item, int amount, int mode) {
        String itemName = BuiltInRegistries.ITEM.getOptional(item)
                .map(RobotChatItems::displayName).orElse(item.toString());
        String label = (type.equals("collect") ? "采集 " : "烹饪 ") + itemName + "×" + amount
                + (type.equals("collect") ? (mode == RobotCollectionTask.PICKUP_ONLY ? "（仅拾取）" : "（自动）") : "");
        return label.codePointCount(0, label.length()) <= 64 ? label
                : label.substring(0, label.offsetByCodePoints(0, 64));
    }

    static CompoundTag saveRow(Row row) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Key", row.key()); tag.putString("Type", row.type());
        tag.putString("Item", row.item().toString()); tag.putInt("Amount", row.amount()); tag.putInt("Mode", row.mode());
        if (row.recipe() != null) tag.putString("Recipe", row.recipe().toString());
        if (row.tool() != null) tag.putString("Tool", row.tool().toString());
        if (row.material() != null) tag.putString("Material", row.material().toString());
        return tag;
    }

    static Row loadRow(CompoundTag tag, String displayKey) {
        try {
            String type = tag.getString("Type");
            String key = displayKey == null || displayKey.isEmpty() ? tag.getString("Key") : displayKey;
            ResourceLocation item = ResourceLocation.tryParse(tag.getString("Item"));
            int amount = tag.getInt("Amount"), mode = tag.getInt("Mode");
            ResourceLocation recipe = tag.contains("Recipe", Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString("Recipe")) : null;
            ResourceLocation tool = tag.contains("Tool", Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString("Tool")) : null;
            ResourceLocation material = tag.contains("Material", Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString("Material")) : null;
            boolean malformedOptional = tag.contains("Recipe") && recipe == null || tag.contains("Tool") && tool == null
                    || tag.contains("Material") && material == null;
            if (!(type.equals("collect") || type.equals("cook") || type.equals("craft")) || item == null
                    || BuiltInRegistries.ITEM.getOptional(item).orElse(null) == net.minecraft.world.item.Items.AIR
                    || malformedOptional || tool != null && !validItem(tool) || material != null && !validItem(material)
                    || amount < 1 || amount > 256 || (type.equals("collect")
                    ? mode < RobotCollectionTask.AUTO || mode > RobotCollectionTask.PICKUP_ONLY
                    : type.equals("craft") ? mode != 0 || amount != 1 : mode != 0)) return null;
            if (!(key.equals(LAST) || key.equals(COLLECT) || key.equals(COOK) || key.equals(CRAFT) || !RobotTaskIntent.normalizedName(key).isEmpty())) return null;
            String label = type.equals("craft") ? displayCraft(item, recipe, tool, material) : display(type, item, amount, mode);
            return new Row(key, label, type, item, amount, mode, recipe, tool, material);
        } catch (RuntimeException invalid) { return null; }
    }

    static boolean validName(String name) {
        String normalized = RobotTaskIntent.normalizedName(name);
        return !normalized.isEmpty() && !normalized.startsWith("@");
    }

    private static void addRecent(CompoundTag state, List<Row> rows, String field, String key) {
        if (!state.contains(field, Tag.TAG_COMPOUND)) return;
        Row row = loadRow(state.getCompound(field), key);
        if (row != null) rows.add(row);
    }

    private static long completedAt(CompoundTag state, String key) {
        String field = key.equals(COLLECT) ? "LatestCollect" : key.equals(COOK) ? "LatestCook" : "LatestCraft";
        return state.getCompound(field).getLong("CompletedAt");
    }

    private static void chargeCommand(ServerPlayer player) {
        IndustrialPhoneItem.consumeHeld(player, IndustrialPhoneItem.COMMAND_COST);
        player.getInventory().setChanged();
    }

    private static String craftMissingReason(Row row) {
        if (row.recipe() == null) return "制作记录缺少配方编号，无法安全复做。";
        if (row.tool() == null || !validItem(row.tool())) return "制作记录缺少有效工具，无法安全复做。";
        if (row.material() == null || !validItem(row.material())) return "制作记录缺少有效材料，无法安全复做。";
        return "";
    }

    private static boolean validItem(ResourceLocation id) {
        return id != null && BuiltInRegistries.ITEM.containsKey(id)
                && BuiltInRegistries.ITEM.get(id) != net.minecraft.world.item.Items.AIR;
    }

    private static String id(ResourceLocation value) { return value == null ? "" : value.toString(); }

    private static String itemName(ResourceLocation id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(RobotChatItems::displayName).orElse(id.toString());
    }

    private static String displayCraft(ResourceLocation output, ResourceLocation recipe,
                                       ResourceLocation tool, ResourceLocation material) {
        String label = "制作 " + itemName(output) + "（按一批；配方 " + recipe + "；工具 " + itemName(tool)
                + "；材料 " + itemName(material) + "）";
        return label.codePointCount(0, label.length()) <= 160 ? label
                : label.substring(0, label.offsetByCodePoints(0, 160));
    }

    private static Result ok(String message, List<Row> rows) { return new Result(true, message, rows); }
    private static Result fail(String message) { return new Result(false, message, List.of()); }
}
