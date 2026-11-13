package com.ic2newera.field;

import com.ic2newera.IC2NewEra;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Each player receives the FTB book. Old players and full inventories are handled on the same route. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class FieldGuideBooks {
    private static final ResourceLocation BOOK = new ResourceLocation("ftbquests", "book");
    private static final Set<ServerPlayer> waiting = Collections.newSetFromMap(new WeakHashMap<>());
    private FieldGuideBooks() {}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) deliver(p);
    }
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) deliver(p);
    }
    @SubscribeEvent public static void drops(LivingDropsEvent e) {
        if (e.getEntity() instanceof ServerPlayer)
            e.getDrops().removeIf(x -> x.getItem().hasTag() && x.getItem().getTag().getBoolean("NewEraFieldGuide")
                    && BOOK.equals(ForgeRegistries.ITEMS.getKey(x.getItem().getItem())));
    }
    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent e) {
        if (e.phase == TickEvent.Phase.END && e.player instanceof ServerPlayer p && p.tickCount % 20 == 0 && waiting.contains(p)) deliver(p);
    }
    private static void deliver(ServerPlayer p) {
        var book = ForgeRegistries.ITEMS.getValue(BOOK);
        if (book == null || book == Items.AIR) return;
        var inv = p.getInventory();
        for (int i=0; i<inv.getContainerSize(); i++) if (inv.getItem(i).is(book)) { waiting.remove(p); return; }
        if (inv.getFreeSlot() < 0) {
            if (waiting.add(p)) p.sendSystemMessage(Component.literal("请腾出一格背包，《新纪元工业手册》会自动补发。"));
            return;
        }
        var stack = new ItemStack(book);
        stack.setHoverName(Component.literal("新纪元工业手册")); stack.getOrCreateTag().putBoolean("NewEraFieldGuide", true);
        if (inv.add(stack)) { waiting.remove(p); p.inventoryMenu.broadcastChanges(); } else waiting.add(p);
    }
}
