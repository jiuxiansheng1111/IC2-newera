package com.ic2newera.integration.jei;

import com.ic2newera.block.ModProcessorBlocks;
import com.ic2newera.client.ui.ProcessorScreen;
import com.ic2newera.machine.ProcessorKind;
import com.ic2newera.recipe.ModProcessorRecipes;
import com.ic2newera.recipe.ProcessorRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.stream.Collectors;

@JeiPlugin
public final class IC2NewEraJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = new ResourceLocation("ic2newera", "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var guiHelper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
                new AdvancedFurnaceJeiCategory(guiHelper),
                new ProcessorJeiCategory(guiHelper, ProcessorKind.NON_METAL),
                new ProcessorJeiCategory(guiHelper, ProcessorKind.ELECTRONIC));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        Minecraft minecraft = Minecraft.getInstance();
        AdvancedFurnaceJeiSync.registerRecipes(registration);
        if (minecraft.level == null) return;
        if (net.minecraftforge.fml.ModList.get().isLoaded("farmersdelight")
                && net.minecraftforge.fml.ModList.get().isLoaded("kaleidoscope_cookery"))
            CookingBridgeRecipes.register(registration, minecraft.level);
        List<ProcessorRecipe> recipes = minecraft.level.getRecipeManager()
                .getAllRecipesFor(ModProcessorRecipes.PROCESSOR.get());
        registration.addRecipes(ProcessorJeiCategory.NON_METAL, recipes.stream()
                .filter(recipe -> recipe.kind() == ProcessorKind.NON_METAL).collect(Collectors.toList()));
        registration.addRecipes(ProcessorJeiCategory.ELECTRONIC, recipes.stream()
                .filter(recipe -> recipe.kind() == ProcessorKind.ELECTRONIC).collect(Collectors.toList()));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        AdvancedFurnaceJeiSync.registerCatalysts(registration);
        registration.addRecipeCatalyst(new ItemStack(ModProcessorBlocks.NON_METAL_PROCESSOR.get()),
                ProcessorJeiCategory.NON_METAL);
        registration.addRecipeCatalyst(new ItemStack(ModProcessorBlocks.ELECTRONIC_FABRICATOR.get()),
                ProcessorJeiCategory.ELECTRONIC);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addRecipeClickArea(ProcessorScreen.class, 10, 80, 156, 6,
                ProcessorJeiCategory.NON_METAL, ProcessorJeiCategory.ELECTRONIC, AdvancedFurnaceJeiCategory.TYPE);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        AdvancedFurnaceJeiSync.onRuntimeAvailable(runtime);
        runtime.getIngredientManager().removeIngredientsAtRuntime(mezz.jei.api.constants.VanillaTypes.ITEM_STACK,
                List.of(new ItemStack(com.ic2newera.industrial.IndustrialItems.TERMINAL.get())));
    }

    @Override
    public void onRuntimeUnavailable() {
        AdvancedFurnaceJeiSync.onRuntimeUnavailable();
    }

    /** Optional FD classes are resolved only after both cooking mods are present. */
    private static final class CookingBridgeRecipes {
        private static void register(IRecipeRegistration registration, net.minecraft.world.level.Level level) {
            registration.addRecipes(vectorwing.farmersdelight.integration.jei.FDRecipeTypes.COOKING,
                    com.ic2newera.robot.cooking.KaleidoToFarmerRecipeBridge.recipes(level));
        }
    }
}
