package com.ic2newera.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the generated handbook uses this ID prefix. FTB still handles quest completion and its native click-to-claim screen. */
@Pseudo @Mixin(targets="dev.ftb.mods.ftbquests.quest.TeamData",remap=false)
abstract class GuidePersonalClaimsMixin {
    @Unique private static final String NEWERA_CLAIM="claimReward(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbquests/quest/reward/Reward;ZJ)V";
    @Unique private static String newera$key(Object reward) {
        if (!(reward instanceof GuideQuestId id) || !(reward instanceof GuideRewardStack)) return "";
        return (id.newera$questId() >>> 48)==0x694A ? Long.toHexString(id.newera$questId()) : "";
    }
    @Unique private static CompoundTag newera$ledger(ServerPlayer p) {
        var data=p.getPersistentData();
        if(!data.contains(Player.PERSISTED_NBT_TAG,Tag.TAG_COMPOUND)) data.put(Player.PERSISTED_NBT_TAG,new CompoundTag());
        var persisted=data.getCompound(Player.PERSISTED_NBT_TAG);
        if(!persisted.contains("NewEraGuideAwards",Tag.TAG_COMPOUND)) persisted.put("NewEraGuideAwards",new CompoundTag());
        return persisted.getCompound("NewEraGuideAwards");
    }
    @Inject(method=NEWERA_CLAIM,at=@At("HEAD"),cancellable=true)
    private void newera$checkSpace(ServerPlayer p, @Coerce Object reward, boolean notify,long when, CallbackInfo ci) {
        String key=newera$key(reward);
        if(key.isEmpty()) return;
        if(newera$ledger(p).getBoolean(key))return;
        var view=(GuideRewardStack)reward; ItemStack gift=view.newera$stack(); int needed=view.newera$count();
        for(ItemStack stack:p.getInventory().items) {
            if(stack.isEmpty()) needed-=gift.getMaxStackSize();
            else if(ItemStack.isSameItemSameTags(stack,gift)) needed-=Math.max(0,gift.getMaxStackSize()-stack.getCount());
            if(needed<=0)return;
        }
        p.displayClientMessage(Component.literal("背包空间不足，腾出位置后可以回来领取。"),true);ci.cancel();
    }
    // FTB has already synchronized this team's claimed marker at this point. A
    // player changing teams can mark the same reward as read without getting a second item.
    @Inject(method=NEWERA_CLAIM,at=@At(value="INVOKE",target="Ldev/ftb/mods/ftbquests/quest/reward/Reward;claim(Lnet/minecraft/server/level/ServerPlayer;Z)V"),cancellable=true)
    private void newera$alreadyReceived(ServerPlayer p,@Coerce Object reward,boolean notify,long when,CallbackInfo ci) {
        String key=newera$key(reward);
        if(!key.isEmpty() && newera$ledger(p).getBoolean(key))ci.cancel();
    }
    @Inject(method=NEWERA_CLAIM,at=@At(value="INVOKE",target="Ldev/ftb/mods/ftbquests/quest/reward/Reward;claim(Lnet/minecraft/server/level/ServerPlayer;Z)V",shift=At.Shift.AFTER))
    private void newera$rememberClaim(ServerPlayer p,@Coerce Object reward,boolean notify,long when,CallbackInfo ci) {
        String key=newera$key(reward);
        if(!key.isEmpty())newera$ledger(p).putBoolean(key,true);
    }
}
