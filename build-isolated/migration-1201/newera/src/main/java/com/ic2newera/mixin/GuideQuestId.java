package com.ic2newera.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo @Mixin(targets="dev.ftb.mods.ftbquests.quest.QuestObjectBase", remap=false)
public interface GuideQuestId {
    @Accessor("id") long newera$questId();
}
