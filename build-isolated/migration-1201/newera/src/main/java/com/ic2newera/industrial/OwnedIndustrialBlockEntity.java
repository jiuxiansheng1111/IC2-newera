package com.ic2newera.industrial;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.Team;

import java.util.UUID;

/** Owner-aware base class for controls; viewers are read-only unless authorized. */
public abstract class OwnedIndustrialBlockEntity extends BlockEntity implements MenuProvider {
    private static final String OWNER = "IndustrialOwner";
    private static final String OWNER_TEAM = "IndustrialOwnerTeam";
    private UUID owner;
    private String ownerTeamName = "";

    protected OwnedIndustrialBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                         BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public final void claim(Player player) {
        if (level == null || level.isClientSide || owner != null) return;
        owner = player.getUUID();
        Team team = player.getTeam();
        ownerTeamName = team == null ? "" : team.getName();
        markSynced();
    }

    public boolean canManage(Player player) {
        if (player == null) return false;
        if (player.hasPermissions(2) || player.getUUID().equals(owner)) return true;
        if (owner == null || level == null || level.getServer() == null || player.getTeam() == null) return false;
        Team ownerTeam = resolveOwnerTeam(level.getServer());
        return ownerTeam != null && player.getTeam().getName().equals(ownerTeam.getName());
    }

    private Team resolveOwnerTeam(MinecraftServer server) {
        ServerPlayer onlineOwner = server.getPlayerList().getPlayer(owner);
        if (onlineOwner != null) return onlineOwner.getTeam();
        String ownerName = server.getProfileCache().get(owner).map(GameProfile::getName).orElse("");
        // An offline owner may have left the team after placement. Only current scoreboard
        // membership grants access; a saved former team name cannot grant permissions.
        return ownerName.isBlank() ? null : server.getScoreboard().getPlayersTeam(ownerName);
    }

    public final boolean sharesNetworkWith(OwnedIndustrialBlockEntity other) {
        if (other == null) return false;
        if (this == other) return true;
        if (owner == null || other.owner == null || level == null || level.getServer() == null
                || other.level != level) return false;
        if (owner.equals(other.owner)) return true;
        Team first = resolveOwnerTeam(level.getServer());
        Team second = other.resolveOwnerTeam(level.getServer());
        return first != null && second != null && first.getName().equals(second.getName());
    }

    public final UUID getOwner() { return owner; }
    protected abstract int menuKind();

    protected final void saveOwner(CompoundTag tag) {
        if (owner != null) tag.putUUID(OWNER, owner);
        if (!ownerTeamName.isBlank()) tag.putString(OWNER_TEAM, ownerTeamName);
    }

    protected final void loadOwner(CompoundTag tag) {
        owner = tag.hasUUID(OWNER) ? tag.getUUID(OWNER) : null;
        ownerTeamName = tag.getString(OWNER_TEAM);
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new IndustrialMenu(id, inventory, worldPosition, menuKind(), canManage(player));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        saveOwner(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        loadOwner(tag);
    }

    protected final void markSynced() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag() { return saveWithoutMetadata(); }

    @Override
    public void handleUpdateTag(CompoundTag tag) { load(tag); }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) load(tag);
    }
}
