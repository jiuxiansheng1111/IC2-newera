package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Access policy and root-managed delegation for industrial-server controls. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerControllerAccess {
    public static final int MODE_OWNER_AND_DELEGATES = 0;
    public static final int MODE_ALL_PLAYERS = 1;
    public static final int MODE_OWNER_ONLY = 2;

    private static final String DATA_NAME = IC2NewEra.MOD_ID + "_controller_access";

    private ServerControllerAccess() { }

    /** Returns the integrated owner or dedicated-server root owner, if one exists. */
    public static boolean isControllerOwner(Player player) {
        if (player == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        if (!server.isDedicatedServer()) return server.isSingleplayerOwner(player.getGameProfile());

        UUID rootOwner = accessData(server).rootOwner;
        return rootOwner != null && rootOwner.equals(player.getUUID());
    }

    /** Device configuration policy; OP status alone never grants access. */
    public static boolean canConfigure(Player player) {
        if (isControllerOwner(player)) return true;
        if (player == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        return accessData(server).delegates.contains(player.getUUID());
    }

    /** Applies the per-server GUI access mode; unknown modes fail closed. */
    public static boolean canConfigure(Player player, int mode) {
        if (player == null || player.getServer() == null) return false;
        return switch (mode) {
            case MODE_OWNER_AND_DELEGATES -> canConfigure(player);
            case MODE_ALL_PLAYERS -> true;
            case MODE_OWNER_ONLY -> isControllerOwner(player);
            default -> false;
        };
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ic2newera")
                .then(Commands.literal("bind_controller")
                        .requires(ServerControllerAccess::isDedicatedConsole)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    if (!isDedicatedConsole(source)) return 0;
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    bindController(source.getServer(), target.getUUID());
                                    source.sendSuccess(Component.literal(
                                            "已将工业控制服务器服主绑定到玩家 "
                                                    + target.getGameProfile().getName()
                                                    + "（" + target.getUUID() + "）；旧委托授权已清除。"), false);
                                    return 1;
                                })))
                .then(Commands.literal("grant_controller")
                        .requires(ServerControllerAccess::isManagementSource)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    if (!isManagementSource(source)) return 0;
                                    MinecraftServer server = source.getServer();
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    UUID rootOwner = rootOwner(server);
                                    if (server.isDedicatedServer() && rootOwner == null) {
                                        source.sendFailure(Component.literal(
                                                "专用服尚未绑定服主；请先由控制台执行 /ic2newera bind_controller <player>。"));
                                        return 0;
                                    }
                                    ControllerAccessData data = accessData(server);
                                    if (target.getUUID().equals(rootOwner)) {
                                        data.revoke(target.getUUID());
                                        source.sendSuccess(Component.literal("该玩家是服主/房主，已拥有配置调试权限，无需委托。"), false);
                                        return 1;
                                    }
                                    boolean added = data.grant(target.getUUID());
                                    source.sendSuccess(Component.literal(added
                                            ? "已授权玩家 " + target.getGameProfile().getName() + " 配置和调试工业服务器。"
                                            : "玩家 " + target.getGameProfile().getName() + " 已拥有工业服务器配置调试授权。"), false);
                                    return 1;
                                })))
                .then(Commands.literal("revoke_controller")
                        .requires(ServerControllerAccess::isManagementSource)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    if (!isManagementSource(source)) return 0;
                                    MinecraftServer server = source.getServer();
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    if (target.getUUID().equals(rootOwner(server))) {
                                        source.sendFailure(Component.literal("不能撤销服主/房主本人的根权限。"));
                                        return 0;
                                    }
                                    boolean removed = accessData(server).revoke(target.getUUID());
                                    source.sendSuccess(Component.literal(removed
                                            ? "已撤销玩家 " + target.getGameProfile().getName() + " 的工业服务器配置调试授权。"
                                            : "玩家 " + target.getGameProfile().getName() + " 当前没有委托授权。"), false);
                                    return 1;
                                })))
                .then(Commands.literal("controller_status")
                        .requires(ServerControllerAccess::isManagementSource)
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            if (!isManagementSource(source)) return 0;
                            MinecraftServer server = source.getServer();
                            ControllerAccessData data = accessData(server);
                            UUID rootOwner = rootOwner(server);
                            String rootText = rootOwner == null
                                    ? "未绑定（所有玩家均为只读）"
                                    : identity(server, rootOwner);
                            String delegateText = data.delegates.isEmpty()
                                    ? "无"
                                    : data.delegates.stream()
                                            .map(uuid -> identity(server, uuid))
                                            .sorted()
                                            .reduce((left, right) -> left + "、" + right)
                                            .orElse("无");
                            source.sendSuccess(Component.literal("工业服务器配置调试授权状态：服主/房主："
                                    + rootText + "；委托玩家：" + delegateText + "。"), false);
                            return 1;
                        })));
    }

    private static boolean isDedicatedConsole(CommandSourceStack source) {
        return source.getServer().isDedicatedServer()
                && source.getEntity() == null
                && source.hasPermission(4);
    }

    private static boolean isManagementSource(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity == null) return source.hasPermission(4);
        return entity instanceof Player player && isControllerOwner(player);
    }

    private static UUID rootOwner(MinecraftServer server) {
        if (server.isDedicatedServer()) return accessData(server).rootOwner;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (server.isSingleplayerOwner(player.getGameProfile())) return player.getUUID();
        }
        return null;
    }

    private static String identity(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        return online == null ? uuid.toString() : online.getGameProfile().getName() + "（" + uuid + "）";
    }

    private static void bindController(MinecraftServer server, UUID owner) {
        accessData(server).bind(owner);
    }

    private static ControllerAccessData accessData(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ControllerAccessData::load,
                ControllerAccessData::new,
                DATA_NAME);
    }

    private static final class ControllerAccessData extends SavedData {
        private static final String ROOT_KEY = "RootOwner";
        private static final String DELEGATES_KEY = "Delegates";
        private static final String DELEGATE_UUID_KEY = "UUID";

        private UUID rootOwner;
        private final Set<UUID> delegates = new LinkedHashSet<>();

        private ControllerAccessData() { }

        private static ControllerAccessData load(CompoundTag tag) {
            ControllerAccessData data = new ControllerAccessData();
            if (tag.hasUUID(ROOT_KEY)) data.rootOwner = tag.getUUID(ROOT_KEY);
            ListTag storedDelegates = tag.getList(DELEGATES_KEY, Tag.TAG_COMPOUND);
            for (Tag entry : storedDelegates) {
                CompoundTag delegate = (CompoundTag) entry;
                if (delegate.hasUUID(DELEGATE_UUID_KEY)) data.delegates.add(delegate.getUUID(DELEGATE_UUID_KEY));
            }
            if (data.rootOwner != null) data.delegates.remove(data.rootOwner);
            return data;
        }

        private void bind(UUID owner) {
            rootOwner = owner;
            delegates.clear();
            setDirty();
        }

        private boolean grant(UUID delegate) {
            if (delegate.equals(rootOwner)) return false;
            boolean changed = delegates.add(delegate);
            if (changed) setDirty();
            return changed;
        }

        private boolean revoke(UUID delegate) {
            boolean changed = delegates.remove(delegate);
            if (changed) setDirty();
            return changed;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            if (rootOwner != null) tag.putUUID(ROOT_KEY, rootOwner);
            ListTag storedDelegates = new ListTag();
            for (UUID delegate : delegates) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID(DELEGATE_UUID_KEY, delegate);
                storedDelegates.add(entry);
            }
            tag.put(DELEGATES_KEY, storedDelegates);
            return tag;
        }
    }
}
