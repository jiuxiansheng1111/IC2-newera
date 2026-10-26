package com.ic2newera.integration.jei;

import com.ic2newera.process.ProcessGraph;
import com.ic2newera.block.ModProcessorBlocks;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Client bridge from the authoritative server-synced ProcessGraph snapshot into JEI 11. */
public final class AdvancedFurnaceJeiSync {
    private static volatile ProcessGraph.Snapshot snapshot = new ProcessGraph.Snapshot(Map.of(), List.of());
    private static final Map<RecipeKey, AdvancedFurnaceJeiRecipe> recipeCatalog = new HashMap<>();
    private static final Map<RecipeKey, AdvancedFurnaceJeiRecipe> registered = new LinkedHashMap<>();
    private static final Set<RecipeKey> hidden = new HashSet<>();
    private static IJeiRuntime runtime;

    private AdvancedFurnaceJeiSync() { }

    /** Call from the client snapshot install/clear path; safe before or after JEI runtime startup. */
    public static void install(ProcessGraph.Snapshot incoming) {
        snapshot = incoming == null ? new ProcessGraph.Snapshot(Map.of(), List.of()) : incoming;
        publishLateRoutes();
    }

    public static void registerRecipes(IRecipeRegistration registration) {
        Map<RecipeKey, AdvancedFurnaceJeiRecipe> desired = recipes(snapshot);
        registered.clear();
        registered.putAll(desired);
        hidden.clear();
        List<AdvancedFurnaceJeiRecipe> recipes = List.copyOf(desired.values());
        if (!recipes.isEmpty()) registration.addRecipes(AdvancedFurnaceJeiCategory.TYPE, recipes);
    }

    public static void registerCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new net.minecraft.world.item.ItemStack(
                ModProcessorBlocks.ADVANCED_ELECTRIC_FURNACE.get()), AdvancedFurnaceJeiCategory.TYPE);
        registration.addRecipeCatalyst(new net.minecraft.world.item.ItemStack(
                ModProcessorBlocks.ALLOY_SMELTER.get()), AdvancedFurnaceJeiCategory.TYPE);
    }

    public static void onRuntimeAvailable(IJeiRuntime available) {
        runtime = available;
        publishLateRoutes();
    }

    public static void onRuntimeUnavailable() {
        runtime = null;
    }

    static ProcessGraph.Route currentRoute(AdvancedFurnaceJeiRecipe recipe) {
        ProcessGraph.Route current = snapshot.routes().stream()
                .filter(route -> route.id().equals(recipe.routeId()))
                .findFirst().orElse(null);
        return current != null && isSupported(current) && signature(current).equals(recipe.signature())
                ? current : null;
    }

    private static void publishLateRoutes() {
        IJeiRuntime currentRuntime = runtime;
        if (currentRuntime == null) return;
        Map<RecipeKey, AdvancedFurnaceJeiRecipe> desired = recipes(snapshot);
        List<AdvancedFurnaceJeiRecipe> toHide = new ArrayList<>();
        List<AdvancedFurnaceJeiRecipe> toUnhide = new ArrayList<>();
        List<AdvancedFurnaceJeiRecipe> toAdd = new ArrayList<>();

        for (Map.Entry<RecipeKey, AdvancedFurnaceJeiRecipe> entry : registered.entrySet()) {
            if (!desired.containsKey(entry.getKey()) && hidden.add(entry.getKey())) toHide.add(entry.getValue());
        }
        for (Map.Entry<RecipeKey, AdvancedFurnaceJeiRecipe> entry : desired.entrySet()) {
            RecipeKey key = entry.getKey();
            AdvancedFurnaceJeiRecipe recipe = entry.getValue();
            if (hidden.remove(key)) toUnhide.add(recipe);
            if (!registered.containsKey(key)) {
                registered.put(key, recipe);
                toAdd.add(recipe);
            }
        }

        if (!toUnhide.isEmpty()) currentRuntime.getRecipeManager()
                .unhideRecipes(AdvancedFurnaceJeiCategory.TYPE, toUnhide);
        if (!toHide.isEmpty()) currentRuntime.getRecipeManager()
                .hideRecipes(AdvancedFurnaceJeiCategory.TYPE, toHide);
        if (!toAdd.isEmpty()) currentRuntime.getRecipeManager()
                .addRecipes(AdvancedFurnaceJeiCategory.TYPE, toAdd);
    }

    private static Map<RecipeKey, AdvancedFurnaceJeiRecipe> recipes(ProcessGraph.Snapshot source) {
        Map<RecipeKey, AdvancedFurnaceJeiRecipe> recipes = new LinkedHashMap<>();
        for (ProcessGraph.Route route : source.routes()) {
            if (!isSupported(route)) continue;
            RecipeKey key = new RecipeKey(route.id(), signature(route));
            recipes.computeIfAbsent(key, ignored -> recipeCatalog.computeIfAbsent(key,
                    value -> new AdvancedFurnaceJeiRecipe(value.routeId(), value.signature())));
        }
        return recipes;
    }

    private static boolean isSupported(ProcessGraph.Route route) {
        return route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && ((route.format().equals("blast_furnace")
                    && route.machines().stream().anyMatch(id -> id.toString().equals("ic2newera:advanced_electric_furnace")))
                || (route.format().equals("mixing_furnace")
                    && route.machines().stream().anyMatch(id -> id.toString().equals("ic2newera:alloy_smelter"))))
                && !route.inputs().isEmpty() && route.inputs().size() <= 3
                && route.durationTicks() > 0 && route.euPerTick() > 0;
    }

    private static String signature(ProcessGraph.Route route) {
        StringBuilder signature = new StringBuilder(route.material()).append('|').append(route.classification())
                .append('|').append(route.machines()).append('|');
        for (ProcessGraph.Input input : route.inputs()) signature.append(input.item()).append('|')
                .append(input.tag()).append('|').append(input.count()).append(';');
        return signature.append('|').append(route.output()).append('|').append(route.outputCount()).append('|')
                .append(route.durationTicks()).append('|').append(route.euPerTick()).toString();
    }

    private record RecipeKey(ResourceLocation routeId, String signature) { }
}
