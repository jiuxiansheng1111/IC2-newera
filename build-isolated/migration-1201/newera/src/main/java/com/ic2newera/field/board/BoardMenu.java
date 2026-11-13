package com.ic2newera.field.board;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class BoardMenu extends AbstractContainerMenu {
    private final BlockPos pos;

    public BoardMenu(int id, Inventory inventory, FriendlyByteBuf data) {
        this(id, inventory, data.readBlockPos());
    }

    public BoardMenu(int id, Inventory inventory, BlockPos pos) {
        super(FieldBoardContent.BOARD_MENU.get(), id);
        this.pos = pos.immutable();
    }

    @Override public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(player.level() instanceof ServerLevel serverLevel)) {
            if (player.level().isClientSide) {
                return pos.closerToCenterThan(player.position(), 8.0)
                        && player.level().hasChunkAt(pos)
                        && player.level().getBlockEntity(pos) instanceof BoardBlockEntity;
            }
            return false;
        }
        return BoardBlockEntity.validateInteraction(serverLevel,
                net.minecraft.core.GlobalPos.of(serverLevel.dimension(), pos), serverPlayer, true);
    }

    public BlockPos boardPos() { return pos; }

    public void request(int page, String action, String contractId) {
        BoardNetwork.request(this, page, action, contractId);
    }

    @Override public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
