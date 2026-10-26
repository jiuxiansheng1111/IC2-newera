package com.ic2newera.wormhole;

import com.ic2newera.robot.IndustrialPhoneItem;
import com.ic2newera.robot.RobotContacts;
import com.ic2newera.robot.RobotEntity;
import ic2.api.item.ElectricItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Mob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.ITeleporter;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import com.mojang.datafixers.util.Either;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Server-authoritative wormhole transport with bounded, temporary server-managed chunk tickets. */
@Mod.EventBusSubscriber(modid = com.ic2newera.IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WormholeService {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int TO_BEACON = 0, PLAYER_TO_ROBOT = 1, ROBOT_TO_PLAYER = 2;
    public static final int COOLDOWN_TICKS = 20 * 20;
    private static final String COOLDOWN_KEY = "NewEraWormholeNextUse";
    private static final String REQUEST_KEY = "NewEraWormholeLastRequest";
    private static final Set<UUID> IN_FLIGHT = new HashSet<>();
    private static final Map<UUID, PendingRequest> PENDING = new HashMap<>();
    private static final int REQUEST_TIMEOUT_TICKS = 20 * 10;
    private static final int TICKET_RADIUS = 2;
    private static final TicketType<UUID> WORMHOLE_TICKET = TicketType.create(
            "ic2newera_wormhole", java.util.Comparator.comparing(UUID::toString), REQUEST_TIMEOUT_TICKS);
    private WormholeService() { }

    public record Result(boolean accepted, String message) { }
    private record Power(ItemStack phone, WormholeDeviceBlockEntity device) { }
    private record Landing(ServerLevel level, Vec3 position) { }
    private record BindingSnapshot(String source, List<String> beacons) { }
    private record ChunkTicket(ServerLevel level, ServerChunkCache source, ChunkPos pos) { }
    private static final class PendingRequest {
        final ServerPlayer player;
        final UUID robot;
        final int action;
        final int beaconIndex;
        final ItemStack phone;
        final BindingSnapshot bindings;
        final BooleanSupplier stillAuthorized;
        final Consumer<Result> completion;
        final long deadline;
        final UUID ticketToken;
        final List<ChunkTicket> tickets;
        final AtomicBoolean ended;
        final CompletableFuture<List<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> chunks;
        PendingRequest(ServerPlayer player, UUID robot, int action, int beaconIndex, ItemStack phone,
                       BindingSnapshot bindings, BooleanSupplier stillAuthorized,
                       Consumer<Result> completion, long deadline, UUID ticketToken, List<ChunkTicket> tickets,
                       AtomicBoolean ended,
                       CompletableFuture<List<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> chunks) {
            this.player = player; this.robot = robot; this.action = action; this.beaconIndex = beaconIndex;
            this.phone = phone; this.bindings = bindings;
            this.stillAuthorized = stillAuthorized; this.completion = completion; this.deadline = deadline;
            this.ticketToken = ticketToken; this.tickets = tickets; this.ended = ended; this.chunks = chunks;
        }
    }

    /** Bounded, ticketed server chunk loading; completion is polled per tick without joining on the server thread. */
    public static void requestAsync(ServerPlayer player, UUID robot, int action, int beaconIndex,
                                    Consumer<Result> completion) {
        requestAsync(player, robot, action, beaconIndex, () -> true, completion);
    }

    public static void requestAsync(ServerPlayer player, UUID robot, int action, int beaconIndex,
                                    BooleanSupplier stillAuthorized, Consumer<Result> completion) {
        if (player == null || player.getServer() == null) {
            completion.accept(fail("玩家状态不可用。")); return;
        }
        if (!player.getServer().isSameThread()) {
            player.getServer().execute(() -> requestAsync(player, robot, action, beaconIndex, stillAuthorized, completion));
            return;
        }
        if (!player.isAlive() || player.isRemoved() || player.isSpectator()) { completion.accept(fail("玩家状态不可用。")); return; }
        long now = player.getServer().overworld().getGameTime();
        if (now < player.getPersistentData().getLong(COOLDOWN_KEY)) { completion.accept(fail("虫洞冷却中，请稍后再试。")); return; }
        long last = player.getPersistentData().getLong(REQUEST_KEY);
        if (IN_FLIGHT.contains(player.getUUID()) || player.getPersistentData().contains(REQUEST_KEY) && now - last < 100) {
            completion.accept(fail("请求过于频繁或仍在处理中，请5秒后重试。")); return;
        }
        player.getPersistentData().putLong(REQUEST_KEY, now);
        IN_FLIGHT.add(player.getUUID());
        ItemStack phone = findPhone(player);
        if (phone.isEmpty()) { IN_FLIGHT.remove(player.getUUID()); completion.accept(fail("需要持有绑定给自己的虫洞手机。")); return; }
        if (!isAuthorized(stillAuthorized)) { IN_FLIGHT.remove(player.getUUID()); completion.accept(fail("请求上下文已结束或权限已变化。")); return; }
        if (action < TO_BEACON || action > ROBOT_TO_PLAYER || (action != TO_BEACON && robot == null)) {
            IN_FLIGHT.remove(player.getUUID()); completion.accept(fail("无效传送目标。")); return;
        }
        BindingSnapshot bindingSnapshot;
        List<ChunkTicket> tickets = new ArrayList<>();
        UUID token = UUID.randomUUID();
        try {
            bindingSnapshot = bindingSnapshot(phone);
            WormholePhoneData.Destination source = WormholePhoneData.source(phone);
            if (source == null || !addChunkTicket(player, source.dimension(), source.pos(), token, tickets)) {
                finishSetupFailure(player, token, tickets, completion, "手机没有绑定有效的供能设备位置。"); return;
            }
            ServerLevel landingLevel = null;
            BlockPos landingCenter = null;
            if (action == TO_BEACON) {
                List<WormholePhoneData.Destination> beacons = WormholePhoneData.beacons(phone);
                if (beaconIndex < 0 || beaconIndex >= beacons.size()) {
                    finishSetupFailure(player, token, tickets, completion, "尚未绑定此信标目的地。"); return;
                }
                WormholePhoneData.Destination destination = beacons.get(beaconIndex);
                landingLevel = player.getServer().getLevel(destination.dimension());
                landingCenter = destination.pos();
            } else if ((action == PLAYER_TO_ROBOT || action == ROBOT_TO_PLAYER) && robot != null) {
                RobotContacts.Contact contact = RobotContacts.forPlayer(player).stream()
                        .filter(value -> value.id().equals(robot)).findFirst().orElse(null);
                RobotEntity loaded = RobotContacts.resolve(player.getServer(), robot);
                if (loaded != null && !loaded.isOwnedBy(player) || loaded == null && contact == null) {
                    finishSetupFailure(player, token, tickets, completion, "机器人离线或不属于你。"); return;
                }
                if (loaded != null) {
                    landingLevel = (ServerLevel) loaded.level(); landingCenter = loaded.blockPosition();
                } else if (contact != null) {
                    net.minecraft.resources.ResourceLocation dimension = net.minecraft.resources.ResourceLocation.tryParse(contact.dimension());
                    if (dimension != null) {
                        landingLevel = player.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION, dimension));
                        landingCenter = contact.pos();
                    }
                }
                if (landingLevel == null || landingCenter == null
                        || !addChunkTicket(player, landingLevel.dimension(), landingCenter, token, tickets)) {
                    finishSetupFailure(player, token, tickets, completion, "机器人没有可用的自有联系位置。"); return;
                }
                if (action == ROBOT_TO_PLAYER) { landingLevel = player.serverLevel(); landingCenter = player.blockPosition(); }
            } else if (action == ROBOT_TO_PLAYER) {
                landingLevel = player.serverLevel(); landingCenter = player.blockPosition();
            }
            if (landingLevel == null || landingCenter == null) {
                finishSetupFailure(player, token, tickets, completion, "无效或不可达的传送目的地。"); return;
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = new BlockPos((((landingCenter.getX() >> 4) + dx) << 4) + 8,
                        landingCenter.getY(), (((landingCenter.getZ() >> 4) + dz) << 4) + 8);
                if (!addChunkTicket(player, landingLevel.dimension(), pos, token, tickets)) {
                    finishSetupFailure(player, token, tickets, completion, "传送区域请求超过11个区块中心或位置无效。"); return;
                }
            }
            List<ChunkTicket> immutableTickets = List.copyOf(tickets);
            AtomicBoolean ended = new AtomicBoolean();
            CompletableFuture<List<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> chunks = CompletableFuture
                    .supplyAsync(() -> {
                        List<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> futures = new ArrayList<>();
                        for (ChunkTicket ticket : immutableTickets) {
                            if (ended.get()) break;
                            futures.add(ticket.source.getChunkFuture(ticket.pos.x, ticket.pos.z, ChunkStatus.FULL, true));
                        }
                        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                                .thenApply(ignored -> futures.stream().map(future -> future.getNow(null)).toList());
                    }, ForkJoinPool.commonPool()).thenCompose(Function.identity());
            PendingRequest pending = new PendingRequest(player, robot, action, beaconIndex, phone, bindingSnapshot, stillAuthorized,
                    completion, now + REQUEST_TIMEOUT_TICKS, token, immutableTickets, ended, chunks);
            PENDING.put(player.getUUID(), pending);
        } catch (RuntimeException error) {
            releaseTickets(token, tickets);
            IN_FLIGHT.remove(player.getUUID());
            LOGGER.error("Wormhole ticket setup failed for player {}", player.getUUID(), error);
            completion.accept(fail("虫洞预检失败，未执行移动。"));
        }
    }

    private static boolean addChunkTicket(ServerPlayer player, net.minecraft.resources.ResourceKey<Level> dimension,
                                          BlockPos blockPos, UUID token, List<ChunkTicket> tickets) {
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null || !level.isInWorldBounds(blockPos) || !level.getWorldBorder().isWithinBounds(blockPos)) return false;
        ChunkPos pos = new ChunkPos(blockPos);
        for (ChunkTicket existing : tickets) if (existing.level == level && existing.pos.equals(pos)) return true;
        if (tickets.size() >= 11) return false;
        level.getChunkSource().addRegionTicket(WORMHOLE_TICKET, pos, TICKET_RADIUS, token);
        tickets.add(new ChunkTicket(level, level.getChunkSource(), pos));
        return true;
    }

    private static BindingSnapshot bindingSnapshot(ItemStack phone) {
        WormholePhoneData.Destination source = WormholePhoneData.source(phone);
        String sourceKey = source == null ? "" : source.dimension().location() + ":" + source.pos().asLong();
        List<String> beacons = WormholePhoneData.beacons(phone).stream()
                .map(destination -> destination.dimension().location() + ":" + destination.pos().asLong())
                .toList();
        return new BindingSnapshot(sourceKey, beacons);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        long now = event.getServer().overworld().getGameTime();
        for (PendingRequest pending : List.copyOf(PENDING.values())) {
            ServerPlayer player = pending.player;
            if (player.isRemoved() || !player.isAlive() || player.connection == null
                    || !player.connection.connection.isConnected() || !isAuthorized(pending.stillAuthorized)) {
                finishPending(pending, fail("请求已取消或玩家连接已结束；虫洞票据已释放。"));
                continue;
            }
            if (now >= pending.deadline) {
                finishPending(pending, fail("已等待10秒仍未完成区块/机器人载入；本次结束且票据已释放。"));
                continue;
            }
            if (!pending.chunks.isDone()) continue;
            List<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> results;
            try {
                results = pending.chunks.getNow(null);
                if (results == null || results.size() != pending.tickets.size()
                        || results.stream().anyMatch(result -> result == null
                        || result.left().filter(chunk -> chunk instanceof net.minecraft.world.level.chunk.LevelChunk).isEmpty())) {
                    finishPending(pending, fail("部分目标区块无法载入；本次结束且票据已释放。"));
                    continue;
                }
            } catch (RuntimeException loadFailure) {
                LOGGER.warn("Wormhole chunk future failed for player {}", player.getUUID(), loadFailure);
                finishPending(pending, fail("目标区块载入失败；本次结束且票据已释放。"));
                continue;
            }
            if (pending.action == PLAYER_TO_ROBOT || pending.action == ROBOT_TO_PLAYER) {
                RobotEntity robot = RobotContacts.resolve(player.getServer(), pending.robot);
                if (robot == null) continue; // Entity load can follow FULL chunk readiness; poll once next tick.
            }
            if (findPhone(player) != pending.phone || !pending.bindings.equals(bindingSnapshot(pending.phone))) {
                finishPending(pending, fail("等待期间持有手机或绑定目的地发生变化；未传送。"));
                continue;
            }
            try {
                finishPending(pending, request(player, pending.robot, pending.action, pending.beaconIndex));
            } catch (RuntimeException unexpected) {
                LOGGER.error("Wormhole execution failed for player {}", player.getUUID(), unexpected);
                finishPending(pending, fail("传送执行异常；请检查实体、手机与设备状态。"));
            }
        }
    }

    private static void finishPending(PendingRequest pending, Result result) {
        if (PENDING.remove(pending.player.getUUID(), pending)) {
            pending.ended.set(true);
            releaseTickets(pending.ticketToken, pending.tickets);
            IN_FLIGHT.remove(pending.player.getUUID());
            try { pending.completion.accept(result); }
            catch (RuntimeException callbackFailure) { LOGGER.warn("Wormhole completion callback failed", callbackFailure); }
        }
    }

    private static void finishSetupFailure(ServerPlayer player, UUID token, List<ChunkTicket> tickets,
                                           Consumer<Result> completion, String message) {
        releaseTickets(token, tickets); IN_FLIGHT.remove(player.getUUID()); completion.accept(fail(message));
    }

    private static void releaseTickets(UUID token, List<ChunkTicket> tickets) {
        for (ChunkTicket ticket : tickets) try {
            ticket.source.removeRegionTicket(WORMHOLE_TICKET, ticket.pos, TICKET_RADIUS, token);
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to release wormhole chunk ticket at {} in {}", ticket.pos, ticket.level.dimension().location(), error);
        }
    }
    private static boolean isAuthorized(BooleanSupplier check) {
        try { return check != null && check.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }

    public static Result request(ServerPlayer player, UUID robotNullable, int action, int beaconIndex) {
        if (player == null || !player.isAlive() || player.isSpectator() || player.getServer() == null)
            return fail("玩家状态不允许传送。");
        if (player.isPassenger() || player.isVehicle() || !player.getPassengers().isEmpty() || player.isSleeping())
            return fail("玩家正处于乘骑、载客或睡眠状态，不能传送。");
        if (action < TO_BEACON || action > ROBOT_TO_PLAYER) return fail("无效传送类型。");
        long now = player.server.overworld().getGameTime();
        if (now < player.getPersistentData().getLong(COOLDOWN_KEY)) return fail("虫洞冷却中，请稍后再试。");
        ItemStack phone = findPhone(player);
        if (phone.isEmpty() || !WormholePhoneData.installed(phone)
                || !player.getUUID().equals(WormholePhoneData.owner(phone))) return fail("需要持有已绑定给自己的手机与虫洞模块。");
        Power power = resolvePower(player, phone);
        if (power == null) return fail("手机没有绑定可用的自有虫洞供能设备。");
        if (ElectricItem.manager == null) return fail("IC2R 电量服务不可用。");

        RobotEntity robot = null;
        Landing landing;
        int phoneCost, robotCost = 0, deviceCost;
        ItemStack robotStack = ItemStack.EMPTY;
        if (action == TO_BEACON) {
            var destinations = WormholePhoneData.beacons(phone);
            if (beaconIndex < 0 || beaconIndex >= destinations.size()) return fail("尚未绑定此信标目的地。");
            var destination = destinations.get(beaconIndex);
            ServerLevel level = player.server.getLevel(destination.dimension());
            if (level == null || !chunkLoaded(level, destination.pos())) return fail("信标目的地区块未完成加载，本次未传送。");
            if (!level.getBlockState(destination.pos()).is(Blocks.BEACON)
                    || !(level.getBlockEntity(destination.pos()) instanceof BeaconBlockEntity)) return fail("绑定的信标已不存在。");
            landing = findLanding(level, player, destination.pos(), true);
            if (landing == null) return fail("信标附近没有安全落点。");
            boolean cross = level != player.serverLevel();
            phoneCost = cross ? 5_000 : 1_000;
            deviceCost = cross ? 200_000 : 50_000;
        } else {
            if (robotNullable == null) return fail("请选择已绑定的机器人。");
            robot = RobotContacts.resolve(player.server, robotNullable);
            if (robot == null || !robot.isOwnedBy(player) || !robot.isAlive() || robot.isRemoved()) return fail("机器人离线或不属于你。");
            if (!robot.getPersistentData().getBoolean("NewEraWormholeModule")) return fail("机器人尚未安装虫洞模块。");
            if (robot.isWorking() || robot.workTaskActive() || robot.getTarget() != null || robot.swinging
                    || robot.isPassenger() || robot.isVehicle() || !robot.getPassengers().isEmpty()
                    || robot.isLeashed() || robot.isSleeping()) return fail("机器人正在工作、战斗、搭乘或牵绳状态，不能传送。");
            robotStack = robot.inventory().getStackInSlot(0);
            ServerLevel targetLevel;
            BlockPos anchor;
            boolean above;
            Entity transported;
            if (action == PLAYER_TO_ROBOT) {
                targetLevel = (ServerLevel) robot.level();
                anchor = robot.blockPosition();
                above = false;
                transported = player;
            } else {
                targetLevel = player.serverLevel();
                anchor = player.blockPosition();
                above = false;
                transported = robot;
            }
            landing = findLanding(targetLevel, transported, anchor, above);
            if (landing == null) return fail("目标附近没有安全落点，或目标区块尚未加载。");
            boolean cross = transported.level() != landing.level;
            phoneCost = cross ? 5_000 : 1_000;
            robotCost = cross ? 16_000 : 4_000;
            deviceCost = cross ? 200_000 : 50_000;
        }
        if (ElectricItem.manager.discharge(phone, phoneCost, 1, true, false, true) < phoneCost)
            return fail("手机电量不足，需要 " + phoneCost + " EU。");
        if (robot != null && (ElectricItem.manager.discharge(robotStack, robotCost,
                3, true, false, true) < robotCost))
            return fail("机器人电池电量不足，需要 " + robotCost + " EU。");
        if (power.device.storedEU() < deviceCost) return fail("虫洞设备储能不足，需要 " + deviceCost + " EU。");

        ItemStack originalPhone = phone.copy();
        ItemStack originalRobotBattery = robot == null ? ItemStack.EMPTY : robotStack.copy();
        int originalDeviceEnergy = power.device.storedEU();
        try {
            if (ElectricItem.manager.discharge(phone, phoneCost, 1, true, false, false) != phoneCost) {
                restorePhone(player, phone, originalPhone);
                return fail("手机电量预留失败，未传送。");
            }
            if (robot != null && ElectricItem.manager.discharge(robotStack, robotCost,
                    3, true, false, false) != robotCost) {
                restorePhone(player, phone, originalPhone);
                robot.inventory().setStackInSlot(0, originalRobotBattery);
                return fail("机器人电池预留失败，未传送。");
            }
            if (!power.device.spend(deviceCost)) {
                restorePhone(player, phone, originalPhone);
                if (robot != null) robot.inventory().setStackInSlot(0, originalRobotBattery);
                power.device.setStored(originalDeviceEnergy);
                return fail("供能设备预留失败，未传送。");
            }
        } catch (RuntimeException reserveFailure) {
            restorePhone(player, phone, originalPhone);
            if (robot != null) robot.inventory().setStackInSlot(0, originalRobotBattery);
            power.device.setStored(originalDeviceEnergy);
            LOGGER.error("Wormhole energy reservation failed before travel for player {}", player.getUUID(), reserveFailure);
            return fail("能量预留发生异常，已回滚；未传送。");
        }
        Entity transported = action == ROBOT_TO_PLAYER ? robot : player;
        Entity moved = null;
        try { moved = teleport(transported, landing); }
        catch (RuntimeException externalTravelFailure) {
            LOGGER.error("Wormhole travel callback failed for action {} and player {}", action, player.getUUID(), externalTravelFailure);
        }
        if (moved == null && action == ROBOT_TO_PLAYER) {
            RobotEntity relocated = RobotContacts.resolve(player.getServer(), robotNullable);
            if (relocated != null && relocated.level() == landing.level
                    && relocated.distanceToSqr(landing.position) < 1.0) moved = relocated;
        }
        boolean actuallyMoved = moved != null || (transported != null && !transported.isRemoved()
                && transported.level() == landing.level && transported.distanceToSqr(landing.position) < 1.0);
        if (!actuallyMoved) {
            restorePhone(player, phone, originalPhone);
            if (robot != null) {
                RobotEntity current = RobotContacts.resolve(player.getServer(), robotNullable);
                if (current != null) current.inventory().setStackInSlot(0, originalRobotBattery);
            }
            WormholeDeviceBlockEntity currentDevice = resolveDevice(player, WormholePhoneData.source(phone));
            if (currentDevice != null) currentDevice.setStored(originalDeviceEnergy);
            return fail("维度切换被取消或传送未完成；已回滚预留电量。");
        }
        Entity landed = moved != null ? moved : transported;
        try { settleMotion(landed); }
        catch (RuntimeException externalMovementFailure) {
            LOGGER.warn("Wormhole entity arrived but post-travel motion reset failed for {}", landed.getUUID(), externalMovementFailure);
        }
        player.getPersistentData().putLong(COOLDOWN_KEY, now + COOLDOWN_TICKS);
        player.getInventory().setChanged();
        return new Result(true, action == TO_BEACON ? "已传送至信标。"
                : action == PLAYER_TO_ROBOT ? "已传送至机器人身边。" : "机器人已传送到你身边。");
    }

    private static void restorePhone(ServerPlayer player, ItemStack reference, ItemStack original) {
        if (player.getMainHandItem() == reference) player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, original);
        else if (player.getOffhandItem() == reference) player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original);
    }
    private static WormholeDeviceBlockEntity resolveDevice(ServerPlayer player, WormholePhoneData.Destination source) {
        if (source == null) return null;
        ServerLevel level = player.getServer().getLevel(source.dimension());
        return level != null && chunkLoaded(level, source.pos())
                && level.getBlockEntity(source.pos()) instanceof WormholeDeviceBlockEntity be ? be : null;
    }
    private static void settleMotion(Entity entity) {
        if (entity == null) return;
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();
        if (entity instanceof RobotEntity robot) robot.finishMovement();
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.setTarget(null);
        }
    }
    @SubscribeEvent public static void copyCooldown(PlayerEvent.Clone event) {
        CompoundTag oldData = event.getOriginal().getPersistentData();
        if (oldData.contains(COOLDOWN_KEY)) event.getEntity().getPersistentData()
                .putLong(COOLDOWN_KEY, oldData.getLong(COOLDOWN_KEY));
        if (oldData.contains(REQUEST_KEY)) event.getEntity().getPersistentData()
                .putLong(REQUEST_KEY, oldData.getLong(REQUEST_KEY));
        if (oldData.contains("NewEraWormholePacketLastRequest")) event.getEntity().getPersistentData()
                .putLong("NewEraWormholePacketLastRequest", oldData.getLong("NewEraWormholePacketLastRequest"));
        if (event.getOriginal() instanceof ServerPlayer oldPlayer && event.getEntity() instanceof ServerPlayer newPlayer)
            WormholePackets.copySeenTickets(oldPlayer, newPlayer);
    }
    @SubscribeEvent public static void clearSession(PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PendingRequest pending = PENDING.get(player.getUUID());
            if (pending != null) finishPending(pending, fail("玩家已离线；虫洞请求取消并释放临时票据。"));
            else IN_FLIGHT.remove(player.getUUID());
            WormholePackets.clearSession(player);
        }
    }

    @SubscribeEvent public static void serverStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        for (PendingRequest pending : List.copyOf(PENDING.values())) {
            if (pending.player.getServer() != event.getServer()) continue;
            pending.ended.set(true);
            releaseTickets(pending.ticketToken, pending.tickets);
            PENDING.remove(pending.player.getUUID(), pending);
            IN_FLIGHT.remove(pending.player.getUUID());
            WormholePackets.clearSession(pending.player);
        }
    }

    private static Entity teleport(Entity entity, Landing landing) {
        if (entity == null || entity.isRemoved() || !landing.level.getWorldBorder().isWithinBounds(entity.getBoundingBox().move(
                landing.position.x - entity.getX(), landing.position.y - entity.getY(), landing.position.z - entity.getZ()))) return null;
        if (entity.level() == landing.level) {
            if (entity instanceof ServerPlayer player) {
                player.teleportTo(landing.position.x, landing.position.y, landing.position.z);
            } else {
                entity.teleportTo(landing.position.x, landing.position.y, landing.position.z);
            }
            return entity.level() == landing.level && entity.distanceToSqr(landing.position) < 1.0 ? entity : null;
        }
        ServerLevel origin = (ServerLevel) entity.level();
        float yaw = entity.getYRot();
        ITeleporter teleporter = new ITeleporter() {
            @Override public Entity placeEntity(Entity original, ServerLevel current, ServerLevel destination,
                                                float rotation, Function<Boolean, Entity> reposition) {
                Entity moved = reposition.apply(false);
                if (moved != null) moved.moveTo(landing.position.x, landing.position.y, landing.position.z, rotation, moved.getXRot());
                return moved;
            }
            @Override public PortalInfo getPortalInfo(Entity moving, ServerLevel destination,
                                                       Function<ServerLevel, PortalInfo> defaultInfo) {
                return new PortalInfo(landing.position, Vec3.ZERO, yaw, moving.getXRot());
            }
            @Override public boolean isVanilla() { return false; }
        };
        Entity moved = entity.changeDimension(landing.level, teleporter);
        return moved != null && moved.getUUID().equals(entity.getUUID()) && moved.level() == landing.level
                && moved.distanceToSqr(landing.position) < 1.0 ? moved : null;
    }

    private static Landing findLanding(ServerLevel level, Entity entity, BlockPos anchor, boolean aboveAnchor) {
        int yBase = anchor.getY() + (aboveAnchor ? 1 : 0);
        for (int radius = 0; radius <= 1; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                for (int dy = 0; dy >= -2; dy--) {
                    BlockPos feet = new BlockPos(anchor.getX() + dx, yBase + dy, anchor.getZ() + dz);
                    BlockPos floor = feet.below();
                    BlockPos head = feet.above();
                    if (!chunkLoaded(level, floor) || !chunkLoaded(level, feet) || !chunkLoaded(level, head)
                            || !level.getWorldBorder().isWithinBounds(feet)) continue;
                    var floorState = level.getBlockState(floor);
                    if (!floorState.isFaceSturdy(level, floor, Direction.UP) || dangerous(floorState)
                            || dangerous(level.getBlockState(feet)) || dangerous(level.getBlockState(head))) continue;
                    if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty()) continue;
                    double x = feet.getX() + 0.5, y = feet.getY(), z = feet.getZ() + 0.5;
                    AABB movedBox = entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ());
                    if (level.noCollision(entity, movedBox)) return new Landing(level, new Vec3(x, y, z));
                }
            }
        }
        return null;
    }
    private static boolean dangerous(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }
    private static boolean chunkLoaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }
    private static Power resolvePower(ServerPlayer player, ItemStack phone) {
        if (!player.getUUID().equals(WormholePhoneData.owner(phone))) return null;
        var source = WormholePhoneData.source(phone);
        if (source == null) return null;
        ServerLevel level = player.server.getLevel(source.dimension());
        if (level == null || !chunkLoaded(level, source.pos())) return null;
        if (!(level.getBlockEntity(source.pos()) instanceof WormholeDeviceBlockEntity device)
                || !player.getUUID().equals(device.owner())) return null;
        return new Power(phone, device);
    }
    private static ItemStack findPhone(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof IndustrialPhoneItem) return validPhone(main, player) ? main : ItemStack.EMPTY;
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof IndustrialPhoneItem && validPhone(off, player) ? off : ItemStack.EMPTY;
    }
    private static boolean validPhone(ItemStack stack, Player player) {
        return stack.getItem() instanceof IndustrialPhoneItem && WormholePhoneData.installed(stack)
                && player.getUUID().equals(WormholePhoneData.owner(stack));
    }
    private static Result fail(String text) { return new Result(false, text); }
}
