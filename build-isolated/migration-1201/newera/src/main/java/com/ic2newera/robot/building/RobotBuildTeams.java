package com.ic2newera.robot.building;

import com.ic2newera.robot.RobotEntity;
import com.ic2newera.robot.RobotInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Persistent authority for cooperative construction projects. Block leases are deliberately transient. */
public final class RobotBuildTeams extends SavedData {
    private static final String DATA_NAME = "ic2newera_robot_build_teams";
    public static final int MAX_MEMBERS = 8;
    private static final int MAX_PROJECTS = 128;
    private static final long LEASE_TICKS = 100;
    private static final int MAX_CANDIDATE_DISTANCE_SQR = 64 * 64;

    private final Map<UUID, Project> projects = new HashMap<>();
    private final List<CompoundTag> invalidProjectRecords = new ArrayList<>();
    private final Map<LeaseKey, Lease> leases = new HashMap<>(); // transient: unload/pause always frees work

    public record StartResult(boolean started, UUID projectId, List<RobotEntity> members,
                              String materialsSummary, String message) {
        public StartResult { members = List.copyOf(members); }
    }

    private enum State { ACTIVE, PAUSED, COMPLETE, CANCELLED }
    private static final class Project {
        final UUID id, owner;
        UUID leader;
        final ResourceLocation dimension;
        final LinkedHashSet<UUID> members;
        RobotBuildTask task;
        State state;
        Project(UUID id, UUID leader, UUID owner, ResourceLocation dimension, Set<UUID> members,
                RobotBuildTask task, State state) {
            this.id = id; this.leader = leader; this.owner = owner; this.dimension = dimension;
            this.members = new LinkedHashSet<>(members); this.task = task; this.state = state;
        }
    }
    private record LeaseKey(ResourceLocation dimension, long position) { }
    private record Lease(UUID project, UUID robot, long expiresAt) { }

    private RobotBuildTeams() { }
    private static RobotBuildTeams data(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                RobotBuildTeams::load, RobotBuildTeams::new, DATA_NAME);
    }

    /** Nearby loaded idle robots for UI preview. The leader is first and counts toward the cap. */
    public static List<RobotEntity> candidates(RobotEntity leader) {
        if (!(leader.level() instanceof ServerLevel level) || !eligibleLeader(leader)) return List.of();
        RobotBuildTeams saved = data(level);
        if (!saved.invalidProjectRecords.isEmpty()) return List.of();
        if (hasOpenMembership(saved, leader.getUUID())) return List.of();
        AABB area = leader.getBoundingBox().inflate(64.0);
        List<RobotEntity> result = new ArrayList<>(level.getEntitiesOfClass(RobotEntity.class, area,
                robot -> eligible(robot, leader, true)));
        result.removeIf(robot -> hasOpenMembership(saved, robot.getUUID()));
        if (!result.contains(leader)) result.add(leader);
        result.sort(Comparator.<RobotEntity>comparingInt(robot -> robot == leader ? 0 : 1)
                .thenComparingDouble(leader::distanceToSqr).thenComparing(robot -> robot.getUUID().toString()));
        if (result.size() > MAX_MEMBERS) result = new ArrayList<>(result.subList(0, MAX_MEMBERS));
        return List.copyOf(result);
    }

    /** Revalidates every selected robot immediately before assigning the shared project. */
    public static StartResult start(RobotEntity leader, RobotBuildTask requested, List<RobotEntity> approvedCandidates) {
        if (!(leader.level() instanceof ServerLevel level) || requested == null || !eligibleLeader(leader))
            return failed("领队机器人必须在当前维度待命、有电且没有其他任务");
        if (requested.projectId() != null || !requested.owner().equals(leader.getOwnerUUID())
                || !requested.dimension().equals(level.dimension().location()))
            return failed("施工任务主人或维度与领队不一致");
        List<RobotEntity> chosen = approvedCandidates == null || approvedCandidates.isEmpty()
                ? candidates(leader) : List.copyOf(approvedCandidates);
        if (chosen.size() < 2 || chosen.size() > MAX_MEMBERS || !chosen.contains(leader))
            return failed("协作人数无效，需2至8台且必须包含领队");
        Set<UUID> unique = new LinkedHashSet<>();
        for (RobotEntity robot : chosen) {
            if (robot == null || !unique.add(robot.getUUID()) || !eligible(robot, leader, true)
                    || robot.energy() < 200)
                return failed("协作名单已变化；所有机器人需在64格内、同主人同维度、待命且有电");
        }
        if (requested.plan().parts().size() > RobotBuildPlan.MAX_PARTS) return failed("施工计划超过安全上限");
        if (!level.getWorldBorder().isWithinBounds(requested.origin())) return failed("施工原点超出世界边界");
        // Validate the full footprint before charging anyone. Per-part checks remain in the goal
        // because the world may change while construction is in progress.
        for (RobotBuildPlan.Part part : requested.plan().parts()) {
            BlockPos target = requested.origin().offset(part.offset());
            if (level.isOutsideBuildHeight(target) || !level.getWorldBorder().isWithinBounds(target)
                    || !level.hasChunkAt(target)) return failed("施工区域必须在已加载区块、建筑高度与世界边界内");
            try {
                BlockState expected = part.state();
                BlockState existing = level.getBlockState(target);
                if (!existing.isAir() && !matches(existing, expected, part))
                    return failed("施工区域已有其他方块，不会覆盖：" + target.toShortString());
            } catch (RuntimeException invalid) { return failed("施工计划含无效方块：" + invalid.getMessage()); }
        }
        String conflict = conflict(level, requested);
        if (!conflict.isBlank()) return failed(conflict);

        RobotBuildTeams data = data(level);
        data.pruneLeases(level.getGameTime());
        if (data.projects.size() + data.invalidProjectRecords.size() >= MAX_PROJECTS) {
            var iterator = data.projects.entrySet().iterator();
            while (data.projects.size() + data.invalidProjectRecords.size() >= MAX_PROJECTS && iterator.hasNext()) {
                State state = iterator.next().getValue().state;
                if (state == State.COMPLETE || state == State.CANCELLED) iterator.remove();
            }
        }
        if (data.projects.size() + data.invalidProjectRecords.size() >= MAX_PROJECTS) return failed("协作项目已达服务器保存上限");
        for (Project project : data.projects.values()) {
            if (project.state == State.ACTIVE || project.state == State.PAUSED) {
                if (!java.util.Collections.disjoint(project.members, unique))
                    return failed("协作名单中有机器人已加入另一施工项目");
            }
        }

        UUID id = UUID.randomUUID();
        RobotBuildTask authority = requested.forProject(id, leader.getUUID());
        Project project = new Project(id, leader.getUUID(), requested.owner(), requested.dimension(), unique,
                authority, State.ACTIVE);
        data.projects.put(id, project);
        // The service does not debit the leader for team starts. Charge every participant exactly
        // once only after the complete preflight has succeeded.
        Map<RobotEntity, ItemStack> batteryBefore = new HashMap<>();
        for (RobotEntity robot : chosen) batteryBefore.put(robot, robot.inventory().getStackInSlot(0).copy());
        for (RobotEntity robot : chosen) {
            if (!robot.spendEnergy(200)) {
                batteryBefore.forEach((member, stack) -> member.inventory().setStackInSlot(0, stack));
                data.projects.remove(id);
                data.setDirty();
                return failed("成员电量不足200 EU，施工尚未启动");
            }
        }
        for (RobotEntity robot : chosen) robot.beginBuild(authority.forProject(id, leader.getUUID()));
        data.setDirty();
        return new StartResult(true, id, chosen, materialSummary(level.getServer(), project),
                "共享施工已启动，" + chosen.size() + "台机器人按方块分工");
    }

    public static boolean pause(RobotEntity leader) {
        Project project = projectForActor(leader);
        if (project == null || project.state != State.ACTIVE) return false;
        RobotBuildTeams data = data((ServerLevel) leader.level());
        project.state = State.PAUSED; data.releaseProject(project.id); data.setDirty();
        forEachLoadedMember((ServerLevel) leader.level(), project,
                member -> member.setTeamBuildState(RobotEntity.STAY, "团队施工已暂停，共享进度与已放方块保留"));
        return true;
    }

    public static boolean resume(RobotEntity leader) {
        Project project = projectForActor(leader);
        if (project == null || project.state != State.PAUSED || !(leader.level() instanceof ServerLevel level)
                || !leader.isAlive() || !leader.powered() || leader.hunger() <= 0 || !project.owner.equals(leader.getOwnerUUID())
                || !level.dimension().location().equals(project.dimension)) return false;
        RobotBuildTeams data = data(level); project.state = State.ACTIVE; data.setDirty();
        forEachLoadedMember(level, project, member -> {
            if (eligibleForResume(member, project)) member.beginBuild(project.task.forProject(project.id, project.leader));
            else member.setTeamBuildState(RobotEntity.STAY, "团队任务已恢复；此机器人暂不满足施工条件，任务仍保留");
        });
        return true;
    }

    public static boolean cancel(RobotEntity leader) {
        Project project = projectForActor(leader);
        if (project == null || project.state == State.COMPLETE || project.state == State.CANCELLED
                || !(leader.level() instanceof ServerLevel level)) return false;
        RobotBuildTeams data = data(level); project.state = State.CANCELLED; data.releaseProject(project.id); data.setDirty();
        forEachLoadedMember(level, project, member -> member.clearTeamBuild("团队施工已取消；已放置方块和未用材料保留"));
        return true;
    }

    public static String status(RobotEntity leader) {
        Project project = projectForActor(leader);
        if (project == null) return "当前没有共享施工项目";
        String state = switch (project.state) {
            case ACTIVE -> "施工中"; case PAUSED -> "已暂停"; case COMPLETE -> "已完成"; case CANCELLED -> "已取消";
        };
        return state + "：" + project.task.plan().title() + " " + project.task.finished() + "/"
                + project.task.plan().parts().size() + "，协作 " + project.members.size() + " 台；"
                + materialSummary(((ServerLevel) leader.level()).getServer(), project);
    }

    /** Resolve each member's saved task to the single persistent project task. */
    public static RobotBuildTask taskFor(RobotEntity robot, RobotBuildTask local) {
        if (local == null || local.projectId() == null || !(robot.level() instanceof ServerLevel level)) return local;
        Project project = data(level).projects.get(local.projectId());
        if (project == null || !project.members.contains(robot.getUUID()) || project.state == State.CANCELLED) return null;
        return project.task;
    }

    public static boolean canWork(RobotEntity robot, RobotBuildTask local) {
        if (local == null || local.projectId() == null) return true;
        Project project = projectFor(local, robot);
        return project != null && project.state == State.ACTIVE && project.members.contains(robot.getUUID());
    }

    public static boolean isTeam(RobotBuildTask task) { return task != null && task.projectId() != null; }

    /** Rebinds a legitimately redeployed doll to its existing team slot without creating a new task. */
    public static boolean restoreMember(RobotEntity robot, UUID sourceId) {
        if (robot == null || sourceId == null || robot.level().isClientSide
                || !(robot.level() instanceof ServerLevel level) || robot.buildTask() == null
                || robot.buildTask().projectId() == null || robot.industrialRecoveryBlocked()) return false;
        RobotBuildTask local = robot.buildTask();
        RobotBuildTeams data = data(level);
        Project project = data.projects.get(local.projectId());
        if (project == null || !project.members.contains(sourceId) || !project.owner.equals(robot.getOwnerUUID())
                || !project.dimension.equals(level.dimension().location())
                || !project.id.equals(local.projectId()) || !project.owner.equals(local.owner())) return false;
        Entity old = findLoaded(level.getServer(), project.dimension, sourceId);
        if (old != null && old.isAlive()) return false;

        if (project.state == State.CANCELLED) {
            robot.clearTeamBuild("团队项目已取消；保留剩余材料和已放置方块");
            return true;
        }
        if (project.state == State.COMPLETE || project.task.completed()) {
            project.state = State.COMPLETE;
            boolean productionLeader = project.task.plan().kind().equals("production") && sourceId.equals(project.leader);
            if (project.task.plan().kind().equals("production") && !productionLeader)
                robot.clearTeamBuild("团队生产线已建成；领队负责生产，协作成员恢复待命");
            else {
                robot.syncTeamBuildTask(project.task);
                robot.finishTeamBuild(productionLeader, project.task.plan().title());
            }
            data.setDirty();
            return true;
        }
        if (project.members.contains(robot.getUUID()) && !sourceId.equals(robot.getUUID())) return false;
        project.members.remove(sourceId);
        project.members.add(robot.getUUID());
        if (sourceId.equals(project.leader)) {
            project.leader = robot.getUUID();
            project.task = project.task.forProject(project.id, project.leader);
        }
        data.releaseClaims(sourceId);
        robot.restoreTeamBuildTask(project.task, project.state == State.ACTIVE && eligibleForResume(robot, project));
        forEachLoadedMember(level, project, member -> {
            boolean active = project.state == State.ACTIVE
                    && (member.mode() == RobotEntity.BUILDING || eligibleForResume(member, project));
            member.restoreTeamBuildTask(project.task, active);
        });
        data.setDirty();
        return true;
    }

    /** Claim a block for a short lease; each active tick refreshes the lease. */
    public static boolean claim(RobotEntity robot, RobotBuildTask task, BlockPos position) {
        if (task == null || task.projectId() == null) return true;
        if (!(robot.level() instanceof ServerLevel level)) return false;
        RobotBuildTeams data = data(level); data.pruneLeases(level.getGameTime());
        Project project = data.projects.get(task.projectId());
        if (project == null || project.state != State.ACTIVE || !project.members.contains(robot.getUUID())) return false;
        LeaseKey key = new LeaseKey(task.dimension(), position.asLong());
        Lease existing = data.leases.get(key);
        if (existing != null && (!existing.project().equals(project.id) || !existing.robot().equals(robot.getUUID()))) return false;
        data.leases.put(key, new Lease(project.id, robot.getUUID(), level.getGameTime() + LEASE_TICKS));
        return true;
    }

    public static boolean ownsClaim(RobotEntity robot, RobotBuildTask task, BlockPos position) {
        if (task == null || task.projectId() == null) return true;
        if (!(robot.level() instanceof ServerLevel level)) return false;
        RobotBuildTeams data = data(level); data.pruneLeases(level.getGameTime());
        Lease lease = data.leases.get(new LeaseKey(task.dimension(), position.asLong()));
        return lease != null && lease.project().equals(task.projectId()) && lease.robot().equals(robot.getUUID());
    }

    public static void markDone(RobotEntity robot, RobotBuildTask task, int index) {
        if (task == null) return;
        task.markDone(index);
        if (task.projectId() != null && robot.level() instanceof ServerLevel level) {
            RobotBuildTeams data = data(level);
            data.releasePosition(task.dimension(), task.origin().offset(task.plan().parts().get(index).offset()), task.projectId());
            data.setDirty();
        }
    }

    public static boolean isDone(RobotEntity robot, RobotBuildTask task, int index) {
        RobotBuildTask authority = taskFor(robot, task);
        return authority != null && authority.done(index);
    }

    public static void releaseClaim(RobotEntity robot, BlockPos position) {
        if (robot.buildTask() != null && robot.buildTask().projectId() != null
                && robot.level() instanceof ServerLevel level) {
            RobotBuildTeams data = data(level);
            data.leases.entrySet().removeIf(entry -> entry.getValue().robot().equals(robot.getUUID())
                    && (position == null || entry.getKey().position() == position.asLong()));
        }
    }
    public static void releaseAllClaims(RobotEntity robot) { releaseClaim(robot, null); }

    /** Called by RobotEntity after loading so active projects resume after chunk/server reload. */
    public static void reconcile(RobotEntity robot) {
        RobotBuildTask local = robot.buildTask();
        if (local == null || local.projectId() == null || !(robot.level() instanceof ServerLevel level)) return;
        Project project = data(level).projects.get(local.projectId());
        if (project == null || !project.members.contains(robot.getUUID())) {
            releaseAllClaims(robot);
            robot.setTeamBuildState(RobotEntity.STAY, "共享施工记录不可用；已暂停并保留原任务、材料与现场"); return;
        }
        if (!project.owner.equals(robot.getOwnerUUID())) {
            releaseAllClaims(robot);
            robot.setTeamBuildState(RobotEntity.STAY, "团队施工主人已变化；已安全暂停并保留原记录"); return;
        }
        if (!project.dimension.equals(robot.level().dimension().location())) {
            releaseAllClaims(robot);
            robot.setTeamBuildState(RobotEntity.STAY, "团队施工暂停：机器人当前不在任务维度；回到原维度可继续");
            return;
        }
        if (project.state == State.CANCELLED) { robot.clearTeamBuild("共享施工已取消"); return; }
        if (project.state == State.COMPLETE || project.task.completed()) {
            if (!local.completed() || robot.mode() == RobotEntity.BUILDING) completeMember(robot, project.task);
            return;
        }
        if (project.state == State.PAUSED) {
            if (robot.mode() != RobotEntity.STAY) robot.setTeamBuildState(RobotEntity.STAY, "团队施工已暂停，共享进度保留");
        } else if (project.state == State.ACTIVE && robot.mode() == RobotEntity.STAY && eligibleForResume(robot, project)) {
            robot.beginBuild(project.task.forProject(project.id, project.leader));
        }
    }

    /** RobotEntity.completeBuild delegates here; only the project leader enters production. */
    public static boolean completeMember(RobotEntity robot, RobotBuildTask local) {
        Project project = projectFor(local, robot);
        if (project == null || !project.task.completed()) return false;
        if (project.state != State.COMPLETE) {
            project.state = State.COMPLETE;
            if (robot.level() instanceof ServerLevel level) { RobotBuildTeams data = data(level); data.releaseProject(project.id); data.setDirty(); }
        }
        forEachLoadedMember((ServerLevel) robot.level(), project, member -> {
            boolean productionLeader = project.task.plan().kind().equals("production")
                    && member.getUUID().equals(project.leader);
            if (project.task.plan().kind().equals("production") && !productionLeader) {
                member.clearTeamBuild("团队生产线已建成；领队负责生产，协作成员恢复待命");
            } else {
                member.syncTeamBuildTask(project.task);
                member.finishTeamBuild(productionLeader, project.task.plan().title());
            }
        });
        return true;
    }

    private static boolean matches(BlockState actual, BlockState expected, RobotBuildPlan.Part part) {
        if (actual.getBlock() != expected.getBlock()) return false;
        if (expected.getBlock() == Blocks.WATER && !actual.getFluidState().isSource()) return false;
        for (Map.Entry<String, String> entry : part.properties().entrySet()) {
            var property = expected.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null || !actual.hasProperty(property)
                    || !java.util.Objects.equals(actual.getValue(property), expected.getValue(property))) return false;
        }
        return true;
    }

    public static String materialsSummary(RobotEntity leader) {
        Project project = projectForActor(leader);
        return project == null ? "" : materialSummary(((ServerLevel) leader.level()).getServer(), project);
    }

    private static String materialSummary(MinecraftServer server, Project project) {
        Map<String, Integer> required = new TreeMap<>();
        Map<String, Item> items = new HashMap<>();
        for (int i = 0; i < project.task.plan().parts().size(); i++) if (!project.task.done(i)) {
            Item item = project.task.plan().parts().get(i).materialItem();
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
            required.merge(id, 1, Integer::sum); items.put(id, item);
        }
        if (required.isEmpty()) return "施工材料已全部使用或不再需要";
        Map<String, Integer> available = new HashMap<>();
        for (UUID member : project.members) {
            Entity entity = findLoaded(server, project.dimension, member);
            if (!(entity instanceof RobotEntity robot) || !project.owner.equals(robot.getOwnerUUID())
                    || robot.buildTask() == null || !project.id.equals(robot.buildTask().projectId())) continue;
            for (int slot = RobotInventory.CARGO_START; slot < RobotInventory.SIZE; slot++) {
                ItemStack stack = robot.inventory().getStackInSlot(slot);
                if (!stack.isEmpty()) available.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        int missingKinds = 0;
        List<String> shortage = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int count = available.getOrDefault(entry.getKey(), 0);
            if (count < entry.getValue()) {
                missingKinds++;
                if (shortage.size() < 4) shortage.add(com.ic2newera.robot.RobotChatItems.displayName(items.get(entry.getKey()))
                        + " " + count + "/" + entry.getValue());
            }
        }
        return missingKinds == 0 ? "协作货物槽材料齐备" : "材料不足 " + missingKinds + " 种（"
                + String.join("、", shortage) + (missingKinds > shortage.size() ? "等" : "") + "）";
    }

    private static boolean eligibleLeader(RobotEntity leader) {
        return leader != null && leader.isAlive() && leader.getOwnerUUID() != null && leader.powered() && leader.hunger() > 0
                && leader.mode() == RobotEntity.STAY && !leader.movementBusy() && !leader.isWorking()
                && !leader.workTaskActive() && leader.getTarget() == null && leader.collectionTask() == null
                && !leader.hasPausedCollectionTask() && reusableBuildRecord(leader) && leader.lineRefitTask() == null
                && !leader.hasCookingTask() && !leader.hasSavedSmeltingOrder()
                && (leader.industrialGoal() == null || !leader.industrialGoal().hasSavedState())
                && !leader.industrialRecoveryBlocked();
    }

    private static boolean eligible(RobotEntity robot, RobotEntity leader, boolean checkRadius) {
        return robot != null && leader != null && robot.level() == leader.level() && robot.isAlive()
                && leader.getOwnerUUID() != null && leader.getOwnerUUID().equals(robot.getOwnerUUID())
                && robot.mode() == RobotEntity.STAY && robot.powered() && robot.hunger() > 0 && !robot.movementBusy()
                && !robot.isWorking() && !robot.workTaskActive() && robot.getTarget() == null
                && robot.collectionTask() == null && !robot.hasPausedCollectionTask()
                && reusableBuildRecord(robot) && robot.lineRefitTask() == null && !robot.hasCookingTask()
                && !robot.hasSavedSmeltingOrder()
                && (robot.industrialGoal() == null || !robot.industrialGoal().hasSavedState())
                && !robot.industrialRecoveryBlocked()
                && (!checkRadius || robot.distanceToSqr(leader) <= MAX_CANDIDATE_DISTANCE_SQR);
    }

    private static boolean reusableBuildRecord(RobotEntity robot) {
        RobotBuildTask task = robot.buildTask();
        return task == null || task.projectId() == null && task.completed();
    }

    private static boolean overlaps(Project project, RobotBuildTask task) {
        return overlaps(project.task, task);
    }
    /** Empty when a new task can coexist with loaded legacy tasks and saved cooperative projects. */
    public static String conflict(ServerLevel level, RobotBuildTask task) {
        if (level == null || task == null || !level.dimension().location().equals(task.dimension()))
            return "施工任务维度无效";
        RobotBuildTeams data = data(level);
        if (!data.invalidProjectRecords.isEmpty())
            return "共享施工数据含无法解析的原始记录；为避免覆盖未知施工区域，当前不能启动新建造";
        for (Project project : data.projects.values()) {
            if ((project.state == State.ACTIVE || project.state == State.PAUSED)
                    && overlaps(project, task)) return "施工区域与共享项目重叠：" + project.task.plan().title();
        }
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof RobotEntity robot)) continue;
            if (robot.hasInvalidBuildTaskRecord()) return "另一台机器人有无法解析的建造恢复记录；为避免覆盖其区域，当前不能启动新建造";
            RobotBuildTask existing = robot.buildTask();
            if (existing != null && !existing.completed() && overlaps(existing, task))
                return existing.projectId() == null
                        ? "施工区域与另一台机器人的未完成单人任务重叠"
                        : "施工区域与另一团队成员的未完成任务重叠";
        }
        return "";
    }
    private static boolean hasOpenMembership(RobotBuildTeams data, UUID robot) {
        return data.projects.values().stream().anyMatch(project -> (project.state == State.ACTIVE || project.state == State.PAUSED)
                && project.members.contains(robot));
    }
    private static boolean overlaps(RobotBuildTask first, RobotBuildTask second) {
        if (!first.dimension().equals(second.dimension())) return false;
        Set<BlockPos> positions = new java.util.HashSet<>();
        for (RobotBuildPlan.Part part : first.plan().parts()) positions.add(first.origin().offset(part.offset()));
        for (RobotBuildPlan.Part part : second.plan().parts()) if (positions.contains(second.origin().offset(part.offset()))) return true;
        return false;
    }

    private static Project projectForActor(RobotEntity leader) {
        if (leader == null || !(leader.level() instanceof ServerLevel level) || leader.getOwnerUUID() == null) return null;
        RobotBuildTask task = leader.buildTask();
        if (task == null || task.projectId() == null) return null;
        Project project = data(level).projects.get(task.projectId());
        return project != null && project.members.contains(leader.getUUID())
                && project.owner.equals(leader.getOwnerUUID())
                && project.dimension.equals(level.dimension().location()) ? project : null;
    }

    private static boolean eligibleForResume(RobotEntity robot, Project project) {
        RobotBuildTask local = robot.buildTask();
        return robot.isAlive() && robot.powered() && robot.hunger() > 0 && robot.getOwnerUUID() != null
                && project.owner.equals(robot.getOwnerUUID()) && robot.level() instanceof ServerLevel level
                && level.dimension().location().equals(project.dimension) && project.members.contains(robot.getUUID())
                && local != null && project.id.equals(local.projectId()) && robot.mode() == RobotEntity.STAY
                && !robot.movementBusy() && !robot.isWorking() && !robot.workTaskActive()
                && robot.getTarget() == null && robot.collectionTask() == null && !robot.hasPausedCollectionTask()
                && robot.lineRefitTask() == null && !robot.hasCookingTask() && !robot.hasSavedSmeltingOrder()
                && (robot.industrialGoal() == null || !robot.industrialGoal().hasSavedState())
                && !robot.industrialRecoveryBlocked();
    }

    private static Project projectFor(RobotBuildTask task, RobotEntity robot) {
        if (task == null || task.projectId() == null || !(robot.level() instanceof ServerLevel level)) return null;
        Project project = data(level).projects.get(task.projectId());
        return project != null && project.owner.equals(robot.getOwnerUUID()) && project.members.contains(robot.getUUID())
                && project.dimension.equals(level.dimension().location()) ? project : null;
    }

    private static Entity findLoaded(MinecraftServer server, ResourceLocation dimension, UUID id) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        return level == null ? null : level.getEntity(id);
    }

    private static void forEachLoadedMember(ServerLevel context, Project project, java.util.function.Consumer<RobotEntity> action) {
        for (UUID id : project.members) {
            Entity entity = findLoaded(context.getServer(), project.dimension, id);
            if (entity instanceof RobotEntity robot && project.owner.equals(robot.getOwnerUUID())
                    && robot.buildTask() != null && project.id.equals(robot.buildTask().projectId())) action.accept(robot);
        }
    }

    private static StartResult failed(String message) { return new StartResult(false, null, List.of(), "", message); }
    private void pruneLeases(long now) { leases.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now); }
    private void releaseProject(UUID id) { leases.entrySet().removeIf(entry -> entry.getValue().project().equals(id)); }
    private void releaseClaims(UUID robot) {
        leases.entrySet().removeIf(entry -> entry.getValue().robot().equals(robot));
    }
    private void releasePosition(ResourceLocation dimension, BlockPos pos, UUID project) {
        LeaseKey key = new LeaseKey(dimension, pos.asLong());
        Lease lease = leases.get(key);
        if (lease != null && lease.project().equals(project)) leases.remove(key);
    }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Project project : projects.values()) {
            CompoundTag entry = new CompoundTag(); entry.putUUID("Id", project.id); entry.putUUID("Leader", project.leader);
            entry.putUUID("Owner", project.owner); entry.putString("Dimension", project.dimension.toString());
            entry.putString("State", project.state.name()); entry.put("Task", project.task.save());
            ListTag members = new ListTag();
            for (UUID member : project.members) { CompoundTag id = new CompoundTag(); id.putUUID("Id", member); members.add(id); }
            entry.put("Members", members); list.add(entry);
        }
        for (CompoundTag invalid : invalidProjectRecords) list.add(invalid.copy());
        tag.put("Projects", list); return tag;
    }

    private static RobotBuildTeams load(CompoundTag tag) {
        RobotBuildTeams result = new RobotBuildTeams();
        ListTag projects = tag.getList("Projects", 10);
        if (projects.size() > MAX_PROJECTS) throw new IllegalArgumentException("too many saved robot construction projects");
        for (int i = 0; i < projects.size(); i++) {
            CompoundTag entry = projects.getCompound(i).copy();
            try {
            if (!entry.hasUUID("Id") || !entry.hasUUID("Leader") || !entry.hasUUID("Owner")) { retainInvalid(result, entry); continue; }
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("Dimension"));
            ListTag rawMembers = entry.getList("Members", 10);
            if (dimension == null || rawMembers.isEmpty() || rawMembers.size() > MAX_MEMBERS) { retainInvalid(result, entry); continue; }
            Set<UUID> members = new LinkedHashSet<>();
            for (int m = 0; m < rawMembers.size(); m++) if (rawMembers.getCompound(m).hasUUID("Id")) members.add(rawMembers.getCompound(m).getUUID("Id"));
            if (members.size() != rawMembers.size() || !members.contains(entry.getUUID("Leader"))) { retainInvalid(result, entry); continue; }
            RobotBuildTask task = RobotBuildTask.load(entry.getCompound("Task"));
            if (!entry.getUUID("Id").equals(task.projectId()) || !entry.getUUID("Leader").equals(task.leaderId())
                    || !entry.getUUID("Owner").equals(task.owner()) || !dimension.equals(task.dimension())) { retainInvalid(result, entry); continue; }
            State state = State.valueOf(entry.getString("State"));
            UUID id = entry.getUUID("Id");
            if (result.projects.containsKey(id)) { retainInvalid(result, entry); continue; }
            Project candidate = new Project(id, entry.getUUID("Leader"), entry.getUUID("Owner"), dimension, members, task, state);
            boolean overlapsAuthority = (state == State.ACTIVE || state == State.PAUSED)
                    && result.projects.values().stream().anyMatch(existing ->
                    (existing.state == State.ACTIVE || existing.state == State.PAUSED)
                            && (!java.util.Collections.disjoint(existing.members, candidate.members)
                            || overlaps(existing, task)));
            if (!overlapsAuthority) result.projects.put(id, candidate);
            else retainInvalid(result, entry);
            } catch (RuntimeException ignored) { retainInvalid(result, entry); }
        }
        return result;
    }
    private static void retainInvalid(RobotBuildTeams data, CompoundTag entry) {
        if (data.invalidProjectRecords.size() < MAX_PROJECTS) data.invalidProjectRecords.add(entry.copy());
    }
}
