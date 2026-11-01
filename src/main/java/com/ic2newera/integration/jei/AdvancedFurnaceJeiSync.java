package com.ic2newera.integration.jei;

import com.ic2newera.process.ProcessGraph;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

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
        registerCatalyst(registration, "ic2:electric_blast_furnace");
        registerCatalyst(registration, "ic2:blast_induction_furnace");
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
                && route.format().equals("blast_furnace")
                && route.inputs().size() == 1
                && route.machines().stream().anyMatch(machine -> machine.toString().equals("ic2:electric_blast_furnace")
                || machine.toString().equals("ic2:blast_induction_furnace"))
                && route.outputCount() > 0
                && ForgeRegistries.ITEMS.getValue(route.output()) != null
                && !AdvancedFurnaceJeiCategoryInput.empty(route.inputs().get(0));
    }

    private static String signature(ProcessGraph.Route route) {
        ProcessGraph.Input input = route.inputs().get(0);
        return route.material() + "|" + route.classification() + "|" + route.machines() + "|"
                + input.item() + "|" + input.tag() + "|" + input.count() + "|"
                + route.output() + "|" + route.outputCount() + "|" + route.durationTicks() + "|" + route.euPerTick();
    }

    private static void registerCatalyst(IRecipeCatalystRegistration registration, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item != null) registration.addRecipeCatalyst(new ItemStack(item), AdvancedFurnaceJeiCategory.TYPE);
    }

    private static final class AdvancedFurnaceJeiCategoryInput {
        private static boolean empty(ProcessGraph.Input input) {
            if (!input.item().isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(input.item());
                return id == null || ForgeRegistries.ITEMS.getValue(id) == null;
            }
            if (!input.tag().isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(input.tag());
                return id == null || net.minecraft.world.item.crafting.Ingredient.of(
                        net.minecraft.tags.TagKey.create(net.minecraft.core.Registry.ITEM_REGISTRY, id)).getItems().length == 0;
            }
            return true;
        }
    }

    private record RecipeKey(ResourceLocation routeId, String signature) { }
}
