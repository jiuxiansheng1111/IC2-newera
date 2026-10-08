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
            String reason = blockedReason(entry.getLocation(), inputs, outputs, false, ingredients, null);
            ProcessGraph.Route approved = ProcessGraph.route(entry.getLocation());
            if (reason == null && approved != null) {
                if (!approved.format().equals(profile)) reason = "批准路线不属于这张机器配方表";
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
        boolean touches = inputs.stream().anyMatch(stack -> controlled(stack) != null);
        boolean protectedCircuit = outputs.stream().anyMatch(stack -> Set.of("ic2:advanced_circuit","ic2:complex_circuit")
                .contains(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()))));
        boolean protectedHardware = outputs.stream().anyMatch(stack -> Set.of("ic2newera:industrial_control_server",
                        "ic2newera:industrial_ai_core", "ic2newera:terminal")
                .contains(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()))));
        ProcessGraph.Route approved = ProcessGraph.route(id);
        if (protectedHardware) return approved != null && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "服务器/AI核心/终端须使用批准电子中间体；终端必须包含箱子与暗物质";
        if (protectedCircuit) return approved != null && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "高级电路须使用PCB与集成电路";
        if (!touches) return null;
        if (cooking && actual != null && ingredients.size()==1 && !inputs.isEmpty() && outputs.size()==1
                && outputs.get(0).getCount()==1 && Set.of("minecraft:smelting","minecraft:blasting").contains(
                    String.valueOf(ForgeRegistries.RECIPE_SERIALIZERS.getKey(actual.getSerializer())))) {
            Controlled target=controlled(outputs.get(0));
            if (target!=null && target.stage==ProcessGraph.Stage.FINAL_METAL
                    && target.material.items().get(ProcessGraph.Stage.FINAL_METAL).equals(ForgeRegistries.ITEMS.getKey(outputs.get(0).getItem()))
                    && inputs.stream().allMatch(input -> {
                        Controlled source=controlled(input);
                        return source!=null && source.stage==ProcessGraph.Stage.RAW && source.material.equals(target.material);
                    })) return null;
        }
        boolean guarded = false;
        for (ItemStack input : inputs) {
            Controlled c = controlled(input);
            if (c == null || c.stage == ProcessGraph.Stage.FINAL_METAL) continue;
            for (ItemStack output : outputs) {
                Controlled target = controlled(output);
                if (target == null || !target.material.equals(c.material)) {
                    // Raw and impure feedstock cannot be converted directly into alloy/electronics.
                    if (c.stage != ProcessGraph.Stage.FINAL_METAL) guarded = true;
                    continue;
                }
                if (target.stage.ordinal() <= c.stage.ordinal()) continue;
                guarded = true;
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
            for (var stage : material.items().entrySet()) if (stage.getValue().equals(id)) return new Controlled(material,stage.getKey());
            for (var path : List.of("ingots/","nuggets/","storage_blocks/"))
                if (stack.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation("forge",path+material.id()))))
                    return new Controlled(material,ProcessGraph.Stage.FINAL_METAL);
            if (stack.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation("forge","dusts/"+material.id()))))
                return new Controlled(material,ProcessGraph.Stage.CLEAN_DUST);
            if (stack.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation("forge","ores/"+material.id()))))
                return new Controlled(material,ProcessGraph.Stage.RAW);
        }
        return null;
    }
    private static void log(ResourceLocation id,List<ItemStack> inputs,List<ItemStack> outputs,String reason) {
        LOGGER.warn("RecipeGuard BLOCKED_BYPASS action=remove id={} inputs={} outputs={} reason={}",id,inputs,outputs,reason);
    }
    private record Controlled(ProcessGraph.Material material,ProcessGraph.Stage stage) { }
}
