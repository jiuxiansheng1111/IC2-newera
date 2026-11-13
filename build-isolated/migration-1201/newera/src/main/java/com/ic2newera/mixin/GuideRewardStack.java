package com.ic2newera.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo @Mixin(targets="dev.ftb.mods.ftbquests.quest.reward.ItemReward", remap=false)
public interface GuideRewardStack {
    @Accessor("item") ItemStack newera$stack();
    @Accessor("count") int newera$count();
}
