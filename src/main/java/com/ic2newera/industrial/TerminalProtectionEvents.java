package com.ic2newera.industrial;

import com.ic2newera.IC2NewEra;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Prevent direct player destruction from bypassing terminal menu ownership. Logistics remain physical interfaces. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TerminalProtectionEvents {
    private TerminalProtectionEvents() { }
    @SubscribeEvent
    public static void breakTerminal(BlockEvent.BreakEvent event) {
        if (event.getLevel().getBlockEntity(event.getPos()) instanceof TerminalBlockEntity terminal
                && !terminal.canManage(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.literal("无终端机管理权限，不能拆除。请联系所有者或加入同一队伍。"), true);
        }
    }
}
