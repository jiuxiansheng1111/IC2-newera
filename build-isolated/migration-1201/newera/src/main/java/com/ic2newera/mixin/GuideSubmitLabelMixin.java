package com.ic2newera.mixin;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** FTB Quests 2001.4.22 has translations for this action but the button uses a literal. */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.ValidItemsScreen", remap = false)
abstract class GuideSubmitLabelMixin {
    @ModifyConstant(method = "<init>", constant = @Constant(stringValue = "Submit"), require = 0)
    private String newera$translateSubmit(String fallback) {
        return Component.translatableWithFallback("ftbquests.task.click_to_submit", fallback).getString();
    }
}
