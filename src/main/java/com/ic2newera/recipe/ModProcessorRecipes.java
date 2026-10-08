package com.ic2newera.recipe;

import com.ic2newera.IC2NewEra;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModProcessorRecipes {
    public static final DeferredRegister<RecipeType<?>> TYPES = DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, IC2NewEra.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, IC2NewEra.MOD_ID);

    public static final RegistryObject<RecipeType<ProcessorRecipe>> PROCESSOR = TYPES.register("processor", () -> new RecipeType<>() {
        @Override public String toString() { return IC2NewEra.MOD_ID + ":processor"; }
    });
    public static final RegistryObject<RecipeSerializer<ProcessorRecipe>> PROCESSOR_SERIALIZER =
            SERIALIZERS.register("processor", ProcessorRecipeSerializer::new);

    private ModProcessorRecipes() { }
}
