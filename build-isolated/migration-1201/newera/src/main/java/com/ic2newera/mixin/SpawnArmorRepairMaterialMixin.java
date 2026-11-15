package com.ic2newera.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies the missing repair ingredient for Spawn's blue-footed boots armor material. */
@Pseudo
@Mixin(targets = "com.ninni.spawn.server.item.SpawnArmorMaterial", remap = false)
public abstract class SpawnArmorRepairMaterialMixin {
    @Inject(method = {"m_6230_()Lnet/minecraft/world/item/crafting/Ingredient;",
            "getRepairIngredient()Lnet/minecraft/world/item/crafting/Ingredient;"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ic2newera$useBoobyEggShardRepairMaterial(
            CallbackInfoReturnable<Ingredient> cir) {
        if (!"spawn:booby".equals(((ArmorMaterial) (Object) this).getName())) return;
        Item shard = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("spawn", "booby_egg_shard"));
        if (shard != null && shard != Items.AIR) {
            cir.setReturnValue(Ingredient.of(shard));
        }
    }
}
