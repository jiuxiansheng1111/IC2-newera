package com.ic2newera.process;

import com.ic2newera.recipe.ProcessorRecipe;
import com.mojang.logging.LogUtils;
import ic2.api.recipes.RecipeRegistry;
import ic2.api.recipes.registries.IMachineRecipeList;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Only controlled-stage transitions are guarded. Unrelated ores, devices and transport remain usable. */
public final class RecipeGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<IMachineRecipeList> HOOKED = Collections.newSetFromMap(new IdentityHashMap<>());
    private RecipeGuard() { }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void started(ServerStartedEvent event) { apply(event.getServer().getRecipeManager()); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void sync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) apply(event.getPlayerList().getServer().getRecipeManager());
    }

    public static void apply(RecipeManager manager) {
        if (ProcessGraph.current().materials().isEmpty()) return;
        List<Recipe<?>> kept = new ArrayList<>();
        for (Recipe<?> recipe : manager.getRecipes()) {
            List<ItemStack> inputs = recipe.getIngredients().stream().flatMap(i -> Arrays.stream(i.getItems())).toList();
            List<ItemStack> outputs = outputs(recipe);
            String reason = blockedReason(recipe.getId(), inputs, outputs, recipe instanceof AbstractCookingRecipe,
                    recipe.getIngredients(), recipe);
            if (reason == null) kept.add(recipe);
            else log(recipe.getId(), inputs, outputs, reason);
        }
        if (kept.size() != manager.getRecipes().size()) manager.replaceRecipes(kept);
        hook(RecipeRegistry.MACERATOR.get(true), "macerator");
        hook(RecipeRegistry.FURNACE.get(true), "furnace");
        hook(RecipeRegistry.BLAST_FURNACE.get(true), "blast_furnace");
        hook(RecipeRegistry.MIXING_FURNACE.get(true), "mixing_furnace");
    }

    private static void hook(IMachineRecipeList list, String profile) {
        if (list == null) return;
        if (HOOKED.add(list)) list.registerListener(reloaded -> guardIC2(reloaded, profile));
        guardIC2(list, profile);
    }
    private static void guardIC2(IMachineRecipeList list, String profile) {
        if (ProcessGraph.current().materials().isEmpty()) return;
        for (var entry : List.copyOf(list.getAllEntries())) {
            List<ItemStack> inputs = Arrays.stream(entry.getInputs()).flatMap(i -> i.getComponents().stream()).toList();
            List<ItemStack> outputs = entry.getOutput().getAllOutputs();
            List<Ingredient> ingredients = Arrays.stream(entry.getInputs()).map(i -> i.asIngredient()).toList();
            String reason = blockedReason(entry.getLocation(), inputs, outputs, profile.equals("furnace"), ingredients, null);
            ProcessGraph.Route approved = ProcessGraph.route(entry.getLocation());
            if (reason == null && approved != null) {
                if (!approved.format().equals(profile.equals("furnace") ? "smelting" : profile)) reason = "批准路线不属于这张机器配方表";
                if (entry.getInputs().length != approved.inputs().size()) reason = "批准配方的输入槽数量不一致";
                else for (int i=0;i<entry.getInputs().length;i++)
                    if (entry.getInputs()[i].getInputSize() != approved.inputs().get(i).count()) reason = "批准配方的输入数量不一致";
            }
            if (reason != null) {
                list.removeRecipe(entry.getLocation());
                log(entry.getLocation(), inputs, outputs, reason);
            }
        }
    }

    private static String blockedReason(ResourceLocation id, List<ItemStack> inputs, List<ItemStack> outputs,
                                        boolean cooking, List<Ingredient> ingredients, Recipe<?> actual) {
        // Coal, quartz and gems retain native solid-item uses. Only their conversion
        // to this material's powder is subject to the washing policy.
        boolean solidToPowder = ProcessGraph.current().materials().values().stream().anyMatch(material ->
                inputs.stream().anyMatch(stack -> material.grindingInputs().contains(ForgeRegistries.ITEMS.getKey(stack.getItem())))
                        && outputs.stream().anyMatch(stack -> {
                            Controlled target=controlled(stack);
                            return target!=null && target.material.equals(material)
                                    && Set.of(ProcessGraph.Stage.IMPURE_DUST,ProcessGraph.Stage.CLEAN_DUST).contains(target.stage);
                        }));
        boolean touches = solidToPowder || inputs.stream().anyMatch(stack -> controlled(stack) != null);
        // The graph's alloy-smelter outputs define the guarded alloys, including native bronze/brass.
        boolean protectedAlloy = outputs.stream().anyMatch(stack -> ProcessGraph.current().routes().stream()
                .anyMatch(route -> route.format().equals("mixing_furnace")
                        && route.output().equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))));
        boolean protectedSteelIngot = outputs.stream().anyMatch(stack -> Set.of("ic2newera:tempered_steel_ingot",
                        "ic2newera:tempered_high_carbon_steel_ingot")
                .contains(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()))));
        boolean protectedCircuit = outputs.stream().anyMatch(stack -> Set.of("ic2:advanced_circuit","ic2:complex_circuit")
                .contains(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()))));
        boolean protectedHardware = outputs.stream().anyMatch(stack -> Set.of("ic2newera:industrial_control_server",
                        "ic2newera:industrial_ai_core", "ic2newera:terminal", "ic2newera:eu_motor", "ic2newera:wifi_amplifier")
                .contains(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()))));
        ProcessGraph.Route approved = ProcessGraph.route(id);
        if (protectedAlloy || protectedSteelIngot) return approved != null
                && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "合金配料须走批准的IC2合金炉路线；仅允许批准的成品熔炼与同级回收";
        if (protectedHardware) return approved != null && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "服务器/AI核心/终端须使用批准电子中间体；终端必须包含箱子与暗物质";
        if (protectedCircuit) return approved != null && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "高级电路须使用PCB与集成电路";
        if (!touches) return null;
        if (cooking && ingredients.size()==1 && outputs.size()==1 && outputs.get(0).getCount()==1
                && ProcessGraph.current().routes().stream().anyMatch(route ->
                    route.classification()==ProcessGraph.Classification.LEGACY_PROCESS
                            && (actual!=null || route.format().equals("smelting"))
                            && exact(route, ingredients, outputs, actual))) return null;
        boolean guarded = solidToPowder;
        for (ItemStack input : inputs) {
            Controlled c = controlled(input);
            if (c == null || c.stage == ProcessGraph.Stage.FINAL_METAL) continue;
            if (c.stage==ProcessGraph.Stage.IMPURE_DUST) { guarded=true; continue; }
            for (ItemStack output : outputs) {
                Controlled target = controlled(output);
                if (c.stage==ProcessGraph.Stage.CLEAN_DUST) {
                    // Clean iron powder may become steel and clean quartz may become
                    // silicon. Only this material's final smelting step is restricted.
                    if (target!=null && target.material.equals(c.material) && target.stage==ProcessGraph.Stage.FINAL_METAL)
                        guarded=true;
                } else {
                    // Preserve raw storage packing/unpacking; ore grinding itself must
                    // enter the approved impure stage, including Create's old crushed ore.
                    if (target==null || !target.material.equals(c.material) || target.stage!=ProcessGraph.Stage.RAW
                            || actual==null || !String.valueOf(ForgeRegistries.RECIPE_SERIALIZERS.getKey(actual.getSerializer()))
                                .startsWith("minecraft:crafting")) guarded=true;
                }
            }
        }
        if (!guarded) return null;
        if (approved == null || approved.classification()!=ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
            return "受控材料绕过批准的粉碎/清洗/熔炼阶段";
        return exact(approved, ingredients, outputs, actual) ? null : "配方ID已批准，但输入/输出数量或类型不符";
    }

    private static boolean exact(ProcessGraph.Route route, List<Ingredient> actual, List<ItemStack> outputs, Recipe<?> recipe) {
        if (recipe != null) {
            String expected = switch(route.format()) {
                case "smelting","blasting" -> "minecraft:"+route.format();
                case "crafting" -> "minecraft:crafting_shapeless";
                case "processor" -> "ic2newera:processor";
                default -> "create:"+route.format();
            };
            if (!expected.equals(String.valueOf(ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.getSerializer())))) return false;
            if (recipe instanceof AbstractCookingRecipe cooking && cooking.getCookingTime()!=route.durationTicks()) return false;
            if (recipe instanceof ProcessorRecipe processor && (processor.durationTicks()!=route.durationTicks() || processor.euPerTick()!=route.euPerTick())) return false;
            try {
                var rolled=recipe.getClass().getMethod("getRollableResults").invoke(recipe);
                if (rolled instanceof List<?> values) for (Object value : values)
                    if (((Number)value.getClass().getMethod("getChance").invoke(value)).floatValue()!=1.0F) return false;
            } catch (NoSuchMethodException ignored) { }
            catch (ReflectiveOperationException failure) { return false; }
        }
        if (outputs.size()!=1 || outputs.get(0).getCount()!=route.outputCount()
                || !route.output().equals(ForgeRegistries.ITEMS.getKey(outputs.get(0).getItem()))) return false;
        List<ProcessGraph.Input> expected = new ArrayList<>();
        if (recipe instanceof ProcessorRecipe processor) {
            if (processor.countedIngredients().size()!=route.inputs().size()) return false;
            for (int i=0;i<route.inputs().size();i++) if (processor.countedIngredients().get(i).count()!=route.inputs().get(i).count()) return false;
            expected.addAll(route.inputs());
        } else if (recipe == null) expected.addAll(route.inputs());
        else for (var input : route.inputs()) for (int i=0;i<input.count();i++) expected.add(input);
        if (expected.size()!=actual.size()) return false;
        for (int i=0;i<expected.size();i++) {
            var wanted=expected.get(i);
            ItemStack[] candidates=actual.get(i).getItems();
            if (candidates.length==0) return false;
            for (var candidate : candidates) {
                if (!wanted.item().isEmpty()) {
                    if (!wanted.item().equals(String.valueOf(ForgeRegistries.ITEMS.getKey(candidate.getItem())))) return false;
                } else if (!candidate.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation(wanted.tag())))) return false;
            }
        }
        return true;
    }

    private static List<ItemStack> outputs(Recipe<?> recipe) {
        try {
            // Verified Create API exposes every possible output, including chance outputs.
            var method = recipe.getClass().getMethod("getRollableResultsAsItemStacks");
            @SuppressWarnings("unchecked") List<ItemStack> outputs=(List<ItemStack>)method.invoke(recipe);
            return outputs;
        } catch (NoSuchMethodException ignored) { return List.of(recipe.getResultItem()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect recipe " + recipe.getId(),failure); }
    }

    private static Controlled controlled(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation id=ForgeRegistries.ITEMS.getKey(stack.getItem());
        for (var material : ProcessGraph.current().materials().values()) {
            ProcessGraph.Stage stage=material.stage(id);
            if (stage!=null) return new Controlled(material,stage);
            for (var path : List.of("ingots/","nuggets/","storage_blocks/"))
                if (stack.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation("forge",path+material.id()))))
                    return new Controlled(material,ProcessGraph.Stage.FINAL_METAL);
            if (stack.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation("forge","dusts/"+material.id()))))
                return new Controlled(material,ProcessGraph.Stage.CLEAN_DUST);
            if (stack.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation("forge","ores/"+material.id()))))
                return new Controlled(material,ProcessGraph.Stage.RAW);
            if (stack.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation("forge","raw_materials/"+material.id())))
                    || stack.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation("forge","storage_blocks/raw_"+material.id()))))
                return new Controlled(material,ProcessGraph.Stage.RAW);
        }
        return null;
    }
    private static void log(ResourceLocation id,List<ItemStack> inputs,List<ItemStack> outputs,String reason) {
        LOGGER.warn("RecipeGuard BLOCKED_BYPASS action=remove id={} inputs={} outputs={} reason={}",id,inputs,outputs,reason);
    }
    private record Controlled(ProcessGraph.Material material,ProcessGraph.Stage stage) { }
}
