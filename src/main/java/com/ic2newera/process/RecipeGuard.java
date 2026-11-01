package com.ic2newera.process;

import com.ic2newera.recipe.ProcessorRecipe;
import com.mojang.logging.LogUtils;
import ic2.api.recipes.RecipeRegistry;
import ic2.api.recipes.ingridients.generators.ItemWithNBTGenerator;
import ic2.api.recipes.ingridients.inputs.ItemInput;
import ic2.api.recipes.ingridients.recipes.IRecipeOutput;
import ic2.api.recipes.ingridients.recipes.SimpleRecipeOutput;
import ic2.api.recipes.registries.IMachineRecipeList;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
    private static final Set<IMachineRecipeList> GUARDING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final java.util.Map<IMachineRecipeList, Set<ResourceLocation>> INSTALLED_BLAST =
            Collections.synchronizedMap(new IdentityHashMap<>());
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

    /** The IC2 cooking-list packet methods are empty, so mirror graph routes into its client-side list after recipe sync. */
    @Mod.EventBusSubscriber(modid = com.ic2newera.IC2NewEra.MOD_ID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() { }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onRecipesUpdated(RecipesUpdatedEvent event) {
            syncClientBlastRoutes(ProcessGraphClientState.snapshot());
        }
    }

    public static void syncClientBlastRoutes(ProcessGraph.Snapshot snapshot) {
        restoreApprovedBlastFurnaceRoutes(RecipeRegistry.BLAST_FURNACE.get(false), snapshot);
    }

    private static void hook(IMachineRecipeList list, String profile) {
        if (list == null) return;
        if (HOOKED.add(list)) list.registerListener(reloaded -> guardIC2(reloaded, profile));
        guardIC2(list, profile);
    }
    private static void guardIC2(IMachineRecipeList list, String profile) {
        if (ProcessGraph.current().materials().isEmpty() || !GUARDING.add(list)) return;
        try {
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
            if (profile.equals("blast_furnace")) restoreApprovedBlastFurnaceRoutes(list, ProcessGraph.current());
            if (profile.equals("mixing_furnace")) prioritizeApprovedMixingRoutes(list);
        } finally {
            GUARDING.remove(list);
        }
    }

    /**
     * IC2 2.1.3.4's BLAST_FURNACE is a CookingMachineRecipeList. Its JSON reader is empty and it
     * imports ordinary Minecraft blasting recipes lazily from RecipeManager instead. Register
     * graph-approved advanced-furnace routes directly in this native IC2 list after its reload
     * and after filtering, without publishing the controlled dust recipe to vanilla blasting.
     */
    private static void restoreApprovedBlastFurnaceRoutes(IMachineRecipeList list, ProcessGraph.Snapshot snapshot) {
        if (list == null) return;
        Set<ResourceLocation> installed = new java.util.HashSet<>();
        for (ProcessGraph.Route route : snapshot.routes()) {
            if (route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                    || !route.format().equals("blast_furnace")
                    || route.inputs().size() != 1
                    || route.machines().stream().noneMatch(machine -> machine.toString().equals("ic2:electric_blast_furnace")
                    || machine.toString().equals("ic2:blast_induction_furnace"))) continue;

            ProcessGraph.Input input = route.inputs().get(0);
            if (input.item().isEmpty()) continue;
            ResourceLocation inputId = ResourceLocation.tryParse(input.item());
            ItemStack result = ForgeRegistries.ITEMS.getValue(route.output()) == null
                    ? ItemStack.EMPTY : new ItemStack(ForgeRegistries.ITEMS.getValue(route.output()), route.outputCount());
            if (inputId == null || input.count() < 1 || result.isEmpty()) continue;
            var inputItem = ForgeRegistries.ITEMS.getValue(inputId);
            if (inputItem == null) continue;

            IMachineRecipeList.RecipeEntry existing = list.getRecipe(route.id());
            if (isExactApprovedBlastEntry(route, existing)) { installed.add(route.id()); continue; }
            if (existing != null) list.removeRecipe(route.id());

            IRecipeOutput output = new SimpleRecipeOutput(
                    List.of(new ItemWithNBTGenerator(result)), 0.0F);
            list.addRecipe(route.id(), output, new ItemInput(inputItem, input.count()));
            IMachineRecipeList.RecipeEntry registered = list.getRecipe(route.id());
            if (!isExactApprovedBlastEntry(route, registered)) {
                list.removeRecipe(route.id());
                LOGGER.error("RecipeGuard could not register exact approved IC2 blast-furnace route {}", route.id());
            } else {
                installed.add(route.id());
                LOGGER.info("RecipeGuard registered approved IC2 blast-furnace route {}", route.id());
            }
        }
        // Remove our old entry even when the ID survives but its machine/input is no longer supported.
        for (ResourceLocation previous : INSTALLED_BLAST.getOrDefault(list, Set.of()))
            if (!installed.contains(previous)) list.removeRecipe(previous);
        INSTALLED_BLAST.put(list, Set.copyOf(installed));
    }

    private static boolean isExactApprovedBlastEntry(ProcessGraph.Route route,
                                                      IMachineRecipeList.RecipeEntry entry) {
        if (route == null || entry == null || route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                || !route.format().equals("blast_furnace") || !route.id().equals(entry.getLocation())
                || entry.getInputs().length != route.inputs().size()) return false;
        List<Ingredient> ingredients = Arrays.stream(entry.getInputs()).map(input -> input.asIngredient()).toList();
        for (int i = 0; i < entry.getInputs().length; i++)
            if (entry.getInputs()[i].getInputSize() != route.inputs().get(i).count()) return false;
        return exact(route, ingredients, entry.getOutput().getAllOutputs(), null);
    }

    /**
     * IC2 selects the first matching alloy recipe. Reorder only approved routes with the same
     * ingredient identities and comparable quantities, placing the larger requirement first.
     * All other registry entries remain in the same relative positions.
     */
    private static void prioritizeApprovedMixingRoutes(IMachineRecipeList list) {
        if (!list.getClass().getName().equals("ic2.core.block.machines.recipes.MultiMachineRecipeList")) return;
        List<IMachineRecipeList.RecipeEntry> original = new ArrayList<>(list.getAllEntries());
        List<IMachineRecipeList.RecipeEntry> reordered = new ArrayList<>(original);
        java.util.Map<List<String>, List<ApprovedMixingEntry>> groups = new java.util.LinkedHashMap<>();

        for (int position = 0; position < original.size(); position++) {
            IMachineRecipeList.RecipeEntry entry = original.get(position);
            ProcessGraph.Route route = ProcessGraph.route(entry.getLocation());
            if (!isExactApprovedMixingEntry(route, entry)) continue;
            List<String> key = route.inputs().stream().map(ProcessGraph.Input::key).sorted().toList();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new ApprovedMixingEntry(position, entry, route));
        }

        boolean changed = false;
        for (List<ApprovedMixingEntry> group : groups.values()) {
            if (group.size() < 2 || !quantitiesAreComparable(group)) continue;
            List<ApprovedMixingEntry> ordered = group.stream()
                    .sorted(java.util.Comparator.comparingInt((ApprovedMixingEntry value) -> totalInputCount(value.route()))
                            .reversed())
                    .toList();
            for (int i = 0; i < group.size(); i++) {
                ApprovedMixingEntry current = group.get(i);
                ApprovedMixingEntry desired = ordered.get(i);
                if (!current.entry().getLocation().equals(desired.entry().getLocation())) {
                    reordered.set(current.position(), desired.entry());
                    changed = true;
                }
            }
        }

        if (!changed) return;
        // This concrete IC2 registry iterates a linked map. Rebuild it in the computed order;
        // entries unrelated to approved collision groups retain their original order and data.
        for (IMachineRecipeList.RecipeEntry entry : original) list.removeRecipe(entry.getLocation());
        for (IMachineRecipeList.RecipeEntry entry : reordered) list.addRecipe(entry);
        LOGGER.info("RecipeGuard reordered approved mixing-furnace routes by required input quantity");
    }

    private static boolean isExactApprovedMixingEntry(ProcessGraph.Route route,
                                                       IMachineRecipeList.RecipeEntry entry) {
        if (route == null || route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                || !route.format().equals("mixing_furnace") || !route.id().equals(entry.getLocation())
                || entry.getInputs().length != route.inputs().size()) return false;
        List<Ingredient> ingredients = Arrays.stream(entry.getInputs()).map(input -> input.asIngredient()).toList();
        for (int i = 0; i < entry.getInputs().length; i++)
            if (entry.getInputs()[i].getInputSize() != route.inputs().get(i).count()) return false;
        return exact(route, ingredients, entry.getOutput().getAllOutputs(), null);
    }

    private static boolean quantitiesAreComparable(List<ApprovedMixingEntry> group) {
        for (int i = 0; i < group.size(); i++) for (int j = i + 1; j < group.size(); j++) {
            java.util.Map<String, Integer> left = inputCountsByKey(group.get(i).route());
            java.util.Map<String, Integer> right = inputCountsByKey(group.get(j).route());
            boolean leftAtLeast = left.entrySet().stream().allMatch(entry -> entry.getValue() >= right.getOrDefault(entry.getKey(), 0));
            boolean rightAtLeast = right.entrySet().stream().allMatch(entry -> entry.getValue() >= left.getOrDefault(entry.getKey(), 0));
            if (!leftAtLeast && !rightAtLeast) return false;
        }
        return true;
    }

    private static int totalInputCount(ProcessGraph.Route route) {
        return route.inputs().stream().mapToInt(ProcessGraph.Input::count).sum();
    }

    private static java.util.Map<String, Integer> inputCountsByKey(ProcessGraph.Route route) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (ProcessGraph.Input input : route.inputs()) counts.merge(input.key(), input.count(), Integer::sum);
        return counts;
    }

    private record ApprovedMixingEntry(int position, IMachineRecipeList.RecipeEntry entry, ProcessGraph.Route route) { }

    private static String blockedReason(ResourceLocation id, List<ItemStack> inputs, List<ItemStack> outputs,
                                        boolean cooking, List<Ingredient> ingredients, Recipe<?> actual) {
        if (outputs.stream().anyMatch(stack -> new ResourceLocation("ic2newera", "terminal")
                .equals(ForgeRegistries.ITEMS.getKey(stack.getItem())))) return "终端机已停用，不再提供生产配方";
        ProcessGraph.Route declared=ProcessGraph.route(id);
        if(declared!=null&&declared.classification()==ProcessGraph.Classification.BLOCKED_BYPASS)
            return "该精确配方ID已在统一工艺图标记为阶段旁路";
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
        boolean protectedHardware = outputs.stream().anyMatch(stack -> ProcessGraph.current().routes().stream()
                .anyMatch(route -> Set.of("campaign_hardware","arsenal_components","arsenal_armor").contains(route.material())
                        && route.output().equals(ForgeRegistries.ITEMS.getKey(stack.getItem())))
                || Set.of("ic2newera:industrial_control_server",
                        "ic2newera:industrial_ai_core", "ic2newera:eu_motor", "ic2newera:wifi_amplifier")
                .contains(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem()))));
        ProcessGraph.Route approved = ProcessGraph.route(id);
        if (protectedAlloy || protectedSteelIngot) return approved != null
                && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "合金配料须走批准的IC2合金炉路线；仅允许批准的成品熔炼与同级回收";
        if (protectedHardware) return approved != null && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "工业设备、武器与装甲须使用工艺图批准的电子中间体及实际数量";
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
                case "crafting_shaped" -> "minecraft:crafting_shaped";
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
        if (route.format().equals("crafting") || route.format().equals("crafting_shaped")) {
            List<Ingredient> remaining = new ArrayList<>(actual.stream().filter(i -> !i.isEmpty()).toList());
            if (expected.size()!=remaining.size()) return false;
            // Vanilla ingredients are unordered for shapeless recipes; shaped recipes contain empty cells.
            expected.sort(java.util.Comparator.comparing(i -> i.item().isEmpty()));
            for (var wanted : expected) {
                int match=-1;
                for (int n=0;n<remaining.size();n++) if (matches(wanted,remaining.get(n))) { match=n; break; }
                if (match<0) return false;
                remaining.remove(match);
            }
            return true;
        }
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

    private static boolean matches(ProcessGraph.Input wanted, Ingredient actual) {
        ItemStack[] candidates=actual.getItems();
        if (candidates.length==0) return false;
        for (ItemStack candidate:candidates) {
            if (!wanted.item().isEmpty()) {
                if (!wanted.item().equals(String.valueOf(ForgeRegistries.ITEMS.getKey(candidate.getItem())))) return false;
            } else if (!candidate.is(TagKey.create(Registry.ITEM_REGISTRY,new ResourceLocation(wanted.tag())))) return false;
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
