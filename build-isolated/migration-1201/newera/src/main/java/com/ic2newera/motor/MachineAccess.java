package com.ic2newera.motor;

import com.ic2newera.industrial.ServerControllerAccess;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.scores.Team;

import java.util.UUID;

/** Local machine controls belong to the placer and their current team. */
final class MachineAccess {
    private static final String OWNER_KEY = "NewEraMachineOwner";
    private UUID owner;

    void claim(BlockEntity machine, Player player) {
        if (owner != null || player == null || machine.getLevel() == null || machine.getLevel().isClientSide) return;
        owner = player.getUUID();
        machine.setChanged();
    }

    boolean canManage(BlockEntity machine, Player player) {
        if (player == null || machine.getLevel() == null || machine.getLevel().isClientSide) return false;
        if (player.hasPermissions(2) || ServerControllerAccess.canConfigure(player)
                || player.getUUID().equals(owner)) return true;
        MinecraftServer server = machine.getLevel().getServer();
        if (owner == null || server == null || player.getTeam() == null) return false;
        ServerPlayer onlineOwner = server.getPlayerList().getPlayer(owner);
        Team ownerTeam;
        if (onlineOwner != null) ownerTeam = onlineOwner.getTeam();
        else {
            String name = server.getProfileCache().get(owner).map(GameProfile::getName).orElse("");
            ownerTeam = name.isBlank() ? null : server.getScoreboard().getPlayersTeam(name);
        }
        return ownerTeam != null && player.getTeam().getName().equals(ownerTeam.getName());
    }

    void save(CompoundTag tag) {
        if (owner != null) tag.putUUID(OWNER_KEY, owner);
    }

    void load(CompoundTag tag) {
        owner = tag.hasUUID(OWNER_KEY) ? tag.getUUID(OWNER_KEY) : null;
    }
}
