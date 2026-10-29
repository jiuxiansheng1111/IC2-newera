package com.ic2newera.process;

import com.ic2newera.recipe.ProcessorRecipe;
import com.mojang.logging.LogUtils;
import ic2.api.recipe.IBasicMachineRecipeManager;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.Recipes;
import ic2.core.recipe.BasicMachineRecipeManager;
import ic2.core.recipe.v2.RecipeHolder;
import ic2.core.ref.Ic2RecipeTypes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Only controlled-stage transitions are guarded. Unrelated ores, devices and transport remain usable. */
public final class RecipeGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    private RecipeGuard() { }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void started(ServerStartedEvent event) {
        var server = event.getServer();
        apply(server.getRecipeManager(), server.registryAccess(), server.overworld());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void sync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            var server = event.getPlayerList().getServer();
            apply(server.getRecipeManager(), server.registryAccess(), server.overworld());
        }
    }

    public static void apply(RecipeManager manager, RegistryAccess registries, net.minecraft.server.level.ServerLevel level) {
        if (ProcessGraph.current().materials().isEmpty())
            throw new IllegalStateException("RecipeGuard cannot publish recipes before the controlled ProcessGraph has loaded");
        List<Recipe<?>> kept = new ArrayList<>();
        Set<ResourceLocation> worldRecipeIds = manager.getRecipes().stream().map(Recipe::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (Recipe<?> recipe : manager.getRecipes()) {
            String nativeMachine = nativeMachine(recipe);
            if (nativeMachine != null) {
                MachineRecipe<IRecipeInput, Collection<ItemStack>> nativeRecipe = nativeRecipe(recipe);
                if (nativeRecipe == null || nativeRecipe.getInput() == null || nativeRecipe.getOutput() == null)
                    throw new IllegalStateException("RecipeGuard cannot inspect IC2R world recipe " + recipe.getId());
                List<ItemStack> nativeInputs = nativeRecipe.getInput().getInputs().stream().map(ItemStack::copy).toList();
                List<ItemStack> nativeOutputs = nativeRecipe.getOutput().stream().map(ItemStack::copy).toList();
                ResourceLocation approvedRoute = matchingApprovedNativeRoute(nativeMachine,
                        nativeFormat(nativeMachine), nativeRecipe);
                ProcessGraph.Route declaredNativeRoute = ProcessGraph.route(recipe.getId());
                String nativeReason = approvedRoute != null && declaredNativeRoute != null
                        && !declaredNativeRoute.id().equals(approvedRoute)
                        ? "IC2R RecipeHolder的ID对应另一条工艺图路线，机器类型或内容不符"
                        : approvedRoute == null
                        ? (declaredNativeRoute != null
                            ? "IC2R RecipeHolder内容与同ID批准路线的输入、完整产物集合或minHeat不完全一致"
                            : blockedReason(recipe.getId(), nativeInputs, nativeOutputs, false,
                                List.of(nativeRecipe.getInput().getIngredient()), null)
                          )
                        : null;
                if (nativeReason != null) {
                    log(recipe.getId(), nativeInputs, nativeOutputs,
                            nativeReason + "；IC2R世界RecipeManager包装配方与机器缓存均需同步裁决");
                } else if (approvedRoute != null && !recipe.getId().equals(approvedRoute)
                        && worldRecipeIds.contains(approvedRoute)) {
                    log(recipe.getId(), nativeInputs, nativeOutputs,
                            "内容与已存在的规范批准配方完全相同，移除重复IC2R RecipeHolder显示");
                } else kept.add(recipe);
                continue;
            }
            List<ItemStack> inputs = recipe.getIngredients().stream().flatMap(i -> Arrays.stream(i.getItems())).toList();
            List<ItemStack> outputs = outputs(recipe, registries);
            String reason = blockedReason(recipe.getId(), inputs, outputs, recipe instanceof AbstractCookingRecipe,
                    recipe.getIngredients(), recipe);
            if (reason == null) kept.add(recipe);
            else log(recipe.getId(), inputs, outputs, reason);
        }
        if (kept.size() != manager.getRecipes().size()) manager.replaceRecipes(kept);
        // IC2R caches native managers per RecipeManager. Its concrete manager mutates the live
        // manager backing, so restoring graph routes here survives later reads in the same world.
        guardNative(level, Recipes.macerator.get(level), "ic2:macerator", "macerator");
        guardNative(level, Recipes.centrifuge.get(level), "ic2:centrifuge", "centrifuge");
        guardNative(level, Recipes.blast_furnace.get(level), "ic2:blast_furnace", "blast_furnace");
    }

    /** Compatibility furnaces use synchronized ProcessorRecipes, never the IC2R fluid blast list. */
    public static void syncClientBlastRoutes(ProcessGraph.Snapshot snapshot) {
        // Vanilla recipe synchronization supplies the graph-derived compatibility recipes.
    }

    /** Server execution also checks approval: datapacks cannot smuggle recipes into a processor. */
    public static boolean isApprovedProcessor(ProcessorRecipe recipe) {
        ProcessGraph.Route route = ProcessGraph.route(recipe.getId());
        return route != null && route.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(route, recipe.getIngredients(), List.of(recipe.getResultItem()), recipe);
    }

    private static boolean processorMachineMatches(ProcessGraph.Route route, ProcessorRecipe recipe) {
        String expectedMachine = switch (recipe.kind()) {
            case NON_METAL -> "ic2newera:non_metal_processor";
            case ELECTRONIC -> "ic2newera:electronic_fabricator";
            case ALLOY -> "ic2newera:alloy_smelter";
            case ADVANCED -> "ic2newera:advanced_electric_furnace";
        };
        String expectedFormat = switch (recipe.kind()) {
            case ALLOY -> "mixing_furnace";
            case ADVANCED -> "blast_furnace";
            default -> "processor";
        };
        return route.format().equals(expectedFormat)
                && route.machines().equals(List.of(new ResourceLocation(expectedMachine)));
    }

    private static void guardNative(net.minecraft.server.level.ServerLevel level, IBasicMachineRecipeManager manager,
                                    String machine, String format) {
        if (!(manager instanceof BasicMachineRecipeManager mutable)) {
            throw new IllegalStateException("RecipeGuard cannot safely inspect IC2R recipes for " + machine
                    + "; expected BasicMachineRecipeManager but got "
                    + (manager == null ? "null" : manager.getClass().getName()));
        }
        if (!mutable.isIterable())
            throw new IllegalStateException("RecipeGuard cannot enumerate IC2R recipes for " + machine);
        if (machine.equals("ic2:macerator") || machine.equals("ic2:centrifuge"))
            restoreApprovedNativeRoutes(mutable, machine, format);
        var recipes = mutable.getRecipes().iterator();
        while (recipes.hasNext()) {
            MachineRecipe<IRecipeInput, Collection<ItemStack>> recipe = recipes.next();
            IRecipeInput input = recipe.getInput();
            Collection<ItemStack> output = recipe.getOutput();
            if (input == null || output == null)
                throw new IllegalStateException("RecipeGuard cannot inspect IC2R " + machine + " recipe with missing input/output");
            List<ItemStack> inputs = input.getInputs().stream().map(ItemStack::copy).toList();
            List<ItemStack> outputs = output.stream().map(ItemStack::copy).toList();
            ResourceLocation approvedId = matchingApprovedNativeRoute(machine, format, recipe);
            String reason = approvedId == null
                    ? blockedReason(ResourceLocation.parse("ic2newera:native_recipe"), inputs, outputs, false,
                        List.of(input.getIngredient()), null)
                    : null;
            if (reason != null) {
                // IC2R's iterator removes this exact recipe and invalidates its input cache.
                // Re-resolving each tag alternative could remove another overlapping recipe.
                recipes.remove();
                log(ResourceLocation.parse("ic2newera:native_recipe"), inputs, outputs,
                        reason + "；IC2R manager 不公开原配方ID，按精确输入/输出识别");
            }
        }
    }

    private static void restoreApprovedNativeRoutes(BasicMachineRecipeManager manager, String machine, String format) {
        if (Recipes.inputFactory == null)
            throw new IllegalStateException("RecipeGuard cannot restore approved " + machine
                    + " routes: IC2R input factory is unavailable");
        for (ProcessGraph.Route route : ProcessGraph.current().routes()) {
            if (route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                    || !route.format().equals(format)
                    || route.machines().stream().noneMatch(id -> id.toString().equals(machine))) continue;
            if (route.inputs().size() != 1)
                throw new IllegalStateException("Approved IC2R " + machine + " route must have one input: " + route.id());
            if (machine.equals("ic2:centrifuge") && (route.minHeat() < 0 || route.minHeat() > 5000))
                throw new IllegalStateException("Approved IC2R centrifuge route has invalid minHeat: " + route.id());
            if (machine.equals("ic2:macerator") && route.minHeat() != -1)
                throw new IllegalStateException("Approved IC2R macerator route cannot declare minHeat: " + route.id());
            ProcessGraph.Input source = route.inputs().get(0);
            IRecipeInput input;
            if (!source.item().isEmpty()) {
                var itemId = ResourceLocation.parse(source.item());
                var item = BuiltInRegistries.ITEM.get(itemId);
                if (item == net.minecraft.world.item.Items.AIR)
                    throw new IllegalStateException("Approved " + machine + " route has unavailable input " + source.item());
                input = Recipes.inputFactory.forStack(new ItemStack(item, 1), source.count());
            } else {
                input = Recipes.inputFactory.forTag(source.tag(), source.count());
            }
            var outputId = route.output();
            var outputItem = BuiltInRegistries.ITEM.get(outputId);
            if (outputItem == net.minecraft.world.item.Items.AIR)
                throw new IllegalStateException("Approved " + machine + " route has unavailable output " + outputId);
            ItemStack result = new ItemStack(outputItem, route.outputCount());
            List<ItemStack> accepted = input.getInputs();
            if (accepted.isEmpty())
                throw new IllegalStateException("Approved " + machine + " route has no resolved input " + route.id());
            CompoundTag metadata = new CompoundTag();
            if (machine.equals("ic2:centrifuge")) metadata.putInt("minHeat", route.minHeat());
            int replaced = 0;
            var existing = manager.getRecipes().iterator();
            while (existing.hasNext()) {
                var old = existing.next();
                if (old.getInput() != null && nativeInputMatches(source, old.getInput())) {
                    existing.remove();
                    replaced++;
                }
            }
            // Never enter the native replacement loop; unexpected conflicts fail fast.
            if (!manager.addRecipe(input, metadata, false, result))
                throw new IllegalStateException("IC2R refused approved ProcessGraph " + machine + " route " + route.id());
            LOGGER.info("RecipeGuard restored native route={} machine={} replaced_native={} input={} output={} metadata={}",
                    route.id(), machine, replaced, source.key(), result, metadata);
        }
    }

    private static ResourceLocation matchingApprovedNativeRoute(String machine, String format,
            MachineRecipe<IRecipeInput, Collection<ItemStack>> recipe) {
        if (!machine.equals("ic2:macerator") && !machine.equals("ic2:centrifuge")) return null;
        IRecipeInput actualInput = recipe.getInput();
        Collection<ItemStack> actualOutputs = recipe.getOutput();
        if (actualInput == null || actualOutputs == null) return null;
        for (ProcessGraph.Route route : ProcessGraph.current().routes()) {
            if (route.classification() != ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                    || !route.format().equals(format) || route.machines().stream().noneMatch(id -> id.toString().equals(machine))
                    || route.inputs().size() != 1 || !nativeInputMatches(route.inputs().get(0), actualInput)
                    || !nativeOutputMatches(route, actualOutputs) || !nativeHeatMatches(route, recipe.getMetaData())) continue;
            return route.id();
        }
        return null;
    }

    private static String nativeMachine(Recipe<?> recipe) {
        if (!(recipe instanceof RecipeHolder<?, ?> holder)) return null;
        if (holder.getType() == Ic2RecipeTypes.MACERATOR) return "ic2:macerator";
        if (holder.getType() == Ic2RecipeTypes.CENTRIFUGE) return "ic2:centrifuge";
        if (holder.getType() == Ic2RecipeTypes.BLAST_FURNACE) return "ic2:blast_furnace";
        return null;
    }

    private static String nativeFormat(String machine) {
        return switch (machine) {
            case "ic2:centrifuge" -> "centrifuge";
            case "ic2:blast_furnace" -> "blast_furnace";
            default -> "macerator";
        };
    }

    @SuppressWarnings("unchecked")
    private static MachineRecipe<IRecipeInput, Collection<ItemStack>> nativeRecipe(Recipe<?> recipe) {
        return recipe instanceof RecipeHolder<?, ?> holder
                ? (MachineRecipe<IRecipeInput, Collection<ItemStack>>) holder.recipe() : null;
    }

    private static boolean nativeInputMatches(ProcessGraph.Input expected, IRecipeInput actual) {
        if (actual.getAmount() != expected.count()) return false;
        List<ItemStack> actualItems = actual.getInputs().stream().filter(stack -> !stack.isEmpty()).toList();
        if (actualItems.isEmpty()) return false;
        Set<ResourceLocation> actualIds = new java.util.LinkedHashSet<>();
        for (ItemStack stack : actualItems) {
            if (stack.hasTag()) return false;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null || !actualIds.add(id)) return false;
        }
        Set<ResourceLocation> expectedIds = new java.util.LinkedHashSet<>();
        if (!expected.item().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(expected.item());
            if (id == null) return false;
            expectedIds.add(id);
        } else {
            ResourceLocation tagId = ResourceLocation.tryParse(expected.tag());
            if (tagId == null) return false;
            for (ItemStack stack : Ingredient.of(TagKey.create(Registries.ITEM, tagId)).getItems()) {
                if (!stack.isEmpty()) expectedIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        }
        return !expectedIds.isEmpty() && actualIds.equals(expectedIds);
    }

    /** ProcessGraph has one deterministic output per approved native IC2 route. */
    private static boolean nativeOutputMatches(ProcessGraph.Route route, Collection<ItemStack> actual) {
        if (actual.size() != 1) return false;
        ItemStack output = actual.iterator().next();
        return !output.isEmpty() && !output.hasTag()
                && route.output().equals(BuiltInRegistries.ITEM.getKey(output.getItem()))
                && output.getCount() == route.outputCount();
    }

    private static boolean nativeHeatMatches(ProcessGraph.Route route, CompoundTag metadata) {
        CompoundTag data = metadata == null ? new CompoundTag() : metadata;
        if (route.minHeat() < 0) return !data.contains("minHeat");
        return data.contains("minHeat", Tag.TAG_INT) && data.getInt("minHeat") == route.minHeat();
    }
    private static String blockedReason(ResourceLocation id, List<ItemStack> inputs, List<ItemStack> outputs,
                                        boolean cooking, List<Ingredient> ingredients, Recipe<?> actual) {
        if (outputs.stream().anyMatch(stack -> ResourceLocation.parse("ic2newera:terminal")
                .equals(BuiltInRegistries.ITEM.getKey(stack.getItem())))) return "终端机已停用，不再提供生产配方";
        ProcessGraph.Route declared=ProcessGraph.route(id);
        if (actual instanceof ProcessorRecipe processor && !isApprovedProcessor(processor))
            return "加工机配方未通过工艺图的设备、槽位、配比、产物、时间与EU校验";
        if(declared!=null&&declared.classification()==ProcessGraph.Classification.BLOCKED_BYPASS)
            return "该精确配方ID已在统一工艺图标记为阶段旁路";
        // Coal, quartz and gems retain native solid-item uses. Only their conversion
        // to this material's powder is subject to the washing policy.
        boolean solidToPowder = ProcessGraph.current().materials().values().stream().anyMatch(material ->
                inputs.stream().anyMatch(stack -> material.grindingInputs().contains(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                        && outputs.stream().anyMatch(stack -> {
                            Controlled target=controlled(stack);
                            return target!=null && target.material.equals(material)
                                    && Set.of(ProcessGraph.Stage.IMPURE_DUST,ProcessGraph.Stage.WASHED_DUST,
                                            ProcessGraph.Stage.CLEAN_DUST).contains(target.stage);
                        }));
        boolean touches = solidToPowder || inputs.stream().anyMatch(stack -> controlled(stack) != null);
        // The graph's alloy-smelter outputs define the guarded alloys, including native bronze/brass.
        boolean protectedAlloy = outputs.stream().anyMatch(stack -> ProcessGraph.current().routes().stream()
                .anyMatch(route -> route.format().equals("mixing_furnace")
                        && route.output().equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))));
        boolean protectedSteelIngot = outputs.stream().anyMatch(stack -> Set.of("ic2newera:tempered_steel_ingot",
                        "ic2newera:tempered_high_carbon_steel_ingot")
                .contains(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()))));
        boolean protectedCircuit = outputs.stream().anyMatch(stack -> Set.of("ic2:advanced_circuit","ic2newera:complex_circuit")
                .contains(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()))));
        boolean protectedHardware = outputs.stream().anyMatch(stack -> ProcessGraph.current().routes().stream()
                .anyMatch(route -> Set.of("campaign_hardware","arsenal_components","arsenal_armor").contains(route.material())
                        && route.output().equals(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                || Set.of("ic2newera:industrial_control_server",
                        "ic2newera:industrial_ai_core", "ic2newera:eu_motor", "ic2newera:advanced_eu_motor",
                        "ic2newera:kinetic_generator", "ic2newera:advanced_kinetic_generator", "ic2newera:universal_transformer", "ic2newera:wifi_amplifier",
                        "ic2newera:alloy_smelter", "ic2newera:advanced_electric_furnace",
                        "ic2newera:plate_advanced_alloy", "ic2newera:glowtronic_crystal", "ic2newera:uesc")
                .contains(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()))));
        ProcessGraph.Route approved = ProcessGraph.route(id);
        if (protectedAlloy || protectedSteelIngot) return approved != null
                && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "合金配料须走批准的合金兼容炉路线；仅允许批准的成品熔炼与同级回收";
        if (protectedHardware) return approved != null && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "工业设备、武器与装甲须使用工艺图批准的电子中间体及实际数量";
        if (protectedCircuit) return approved != null && approved.classification()==ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                && exact(approved, ingredients, outputs, actual) ? null : "高级电路须使用PCB与集成电路";
        if (hasForbiddenMineralDowngrade(inputs, outputs))
            return "洗后中间物、最终净粉或金属不得退回含杂质粉/水洗中间物阶段";
        if (isOldImpureAliasConversion(inputs, outputs, ingredients, actual)) return null;
        if (!touches) return null;
        if (cooking && isLegacyRawCooking(inputs) && ingredients.size()==1 && outputs.size()==1 && outputs.get(0).getCount()==1
                && ProcessGraph.current().routes().stream().anyMatch(route ->
                    route.classification()==ProcessGraph.Classification.LEGACY_PROCESS
                            && (actual!=null || route.format().equals("smelting"))
                            && exact(route, ingredients, outputs, actual))) return null;
        boolean guarded = solidToPowder;
        for (ItemStack input : inputs) {
            Controlled c = controlled(input);
            if (c == null) continue;
            if (c.stage == ProcessGraph.Stage.IMPURE_DUST || c.stage == ProcessGraph.Stage.WASHED_DUST) {
                guarded = true;
                continue;
            }
            if (c.stage == ProcessGraph.Stage.FINAL_METAL) {
                for (ItemStack output : outputs) {
                    Controlled target = controlled(output);
                    if (target != null && target.material.equals(c.material)
                            && target.stage.ordinal() < c.stage.ordinal()) guarded = true;
                }
                continue;
            }
            for (ItemStack output : outputs) {
                Controlled target = controlled(output);
                if (c.stage==ProcessGraph.Stage.CLEAN_DUST) {
                    if (target != null || isGraphProduct(output))
                        guarded=true;
                } else {
                    // Guard only a controlled stage transition. Keep unrelated native
                    // byproducts and uses of a raw mineral available.
                    if (target != null && (!target.material.equals(c.material)
                            || target.stage != ProcessGraph.Stage.RAW)) guarded = true;
                    else if (target == null && isGraphProduct(output)) guarded = true;
                    else if (target != null && target.stage == ProcessGraph.Stage.RAW) {
                        String serializer = actual == null ? "" : String.valueOf(
                                ForgeRegistries.RECIPE_SERIALIZERS.getKey(actual.getSerializer()));
                        if (!target.material.equals(c.material) || !serializer.startsWith("minecraft:crafting"))
                            guarded = true;
                    }
                }
            }
        }
        if (!guarded) return null;
        if (approved == null || approved.classification()!=ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS)
            return "受控材料绕过批准的粉碎/清洗/熔炼阶段";
        return exact(approved, ingredients, outputs, actual) && allowedMineralTransition(approved, inputs, outputs, actual)
                ? null : "配方ID已批准，但输入/输出阶段、数量、设备或类型不符合允许转换";
    }

    private static boolean isLegacyRawCooking(List<ItemStack> inputs) {
        boolean foundRaw = false;
        for (ItemStack input : inputs) {
            Controlled stage = controlled(input);
            if (stage == null) continue;
            if (stage.stage != ProcessGraph.Stage.RAW) return false;
            foundRaw = true;
        }
        return foundRaw;
    }

    private static boolean allowedMineralTransition(ProcessGraph.Route route, List<ItemStack> inputs,
                                                     List<ItemStack> outputs, Recipe<?> actual) {
        for (ItemStack input : inputs) {
            Controlled source = controlled(input);
            if (source == null) continue;
            boolean requiredEdgeFound = source.stage != ProcessGraph.Stage.IMPURE_DUST
                    && source.stage != ProcessGraph.Stage.WASHED_DUST;
            for (ItemStack output : outputs) {
                Controlled target = controlled(output);
                if (source.stage == ProcessGraph.Stage.RAW && target != null
                        && !target.material.equals(source.material)) return false;
                if (source.stage == ProcessGraph.Stage.RAW && target == null && isGraphProduct(output)) return false;
                if (target == null || !target.material.equals(source.material)) continue;
                switch (source.stage) {
                    case RAW -> {
                        if (target.stage == ProcessGraph.Stage.RAW) {
                            String serializer = actual == null ? "" : String.valueOf(
                                    ForgeRegistries.RECIPE_SERIALIZERS.getKey(actual.getSerializer()));
                            if (!serializer.startsWith("minecraft:crafting")) return false;
                        } else if (target.stage == ProcessGraph.Stage.IMPURE_DUST) {
                            if (!Set.of("macerator", "crushing").contains(route.format())) return false;
                        } else return false;
                    }
                    case IMPURE_DUST -> {
                        if (target.stage == ProcessGraph.Stage.WASHED_DUST) {
                            if (!route.format().equals("splashing")) return false;
                            requiredEdgeFound = true;
                        } else if (target.stage == ProcessGraph.Stage.CLEAN_DUST) {
                            if (!route.format().equals("centrifuge") || !hasMachine(route, "ic2:centrifuge")) return false;
                            requiredEdgeFound = true;
                        } else return false;
                    }
                    case WASHED_DUST -> {
                        if (target.stage != ProcessGraph.Stage.CLEAN_DUST || !route.format().equals("centrifuge")
                                || !hasMachine(route, "ic2:centrifuge")) return false;
                        requiredEdgeFound = true;
                    }
                    case CLEAN_DUST -> {
                        if (target.stage == ProcessGraph.Stage.IMPURE_DUST || target.stage == ProcessGraph.Stage.WASHED_DUST)
                            return false;
                    }
                    case FINAL_METAL -> {
                        if (target.stage == ProcessGraph.Stage.IMPURE_DUST || target.stage == ProcessGraph.Stage.WASHED_DUST)
                            return false;
                    }
                }
            }
            if (!requiredEdgeFound) return false;
        }
        return true;
    }

    private static boolean hasMachine(ProcessGraph.Route route, String machine) {
        return route.machines().stream().anyMatch(id -> id.toString().equals(machine));
    }

    private static boolean isGraphProduct(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return ProcessGraph.current().routes().stream().anyMatch(route -> route.output().equals(id));
    }

    private static boolean exact(ProcessGraph.Route route, List<Ingredient> actual, List<ItemStack> outputs, Recipe<?> recipe) {
        if (recipe != null) {
            String expected = switch(route.format()) {
                case "smelting","blasting" -> "minecraft:"+route.format();
                case "crafting" -> "minecraft:crafting_shapeless";
                case "crafting_shaped" -> "minecraft:crafting_shaped";
                case "processor", "mixing_furnace", "blast_furnace" -> "ic2newera:processor";
                default -> "create:"+route.format();
            };
            if (!expected.equals(String.valueOf(ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.getSerializer())))) return false;
            if (recipe instanceof AbstractCookingRecipe cooking && cooking.getCookingTime()!=route.durationTicks()) return false;
            if (recipe instanceof ProcessorRecipe processor && (!processorMachineMatches(route, processor)
                    || processor.durationTicks()!=route.durationTicks() || processor.euPerTick()!=route.euPerTick())) return false;
            try {
                var rolled=recipe.getClass().getMethod("getRollableResults").invoke(recipe);
                if (rolled instanceof List<?> values) for (Object value : values)
                    if (((Number)value.getClass().getMethod("getChance").invoke(value)).floatValue()!=1.0F) return false;
            } catch (NoSuchMethodException ignored) { }
            catch (ReflectiveOperationException failure) { return false; }
        }
        if (outputs.size()!=1 || outputs.get(0).getCount()!=route.outputCount()
                || !route.output().equals(BuiltInRegistries.ITEM.getKey(outputs.get(0).getItem()))) return false;
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
                    if (!wanted.item().equals(String.valueOf(BuiltInRegistries.ITEM.getKey(candidate.getItem())))) return false;
                } else if (!candidate.is(TagKey.create(Registries.ITEM,new ResourceLocation(wanted.tag())))) return false;
            }
        }
        return true;
    }

    private static boolean matches(ProcessGraph.Input wanted, Ingredient actual) {
        ItemStack[] candidates=actual.getItems();
        if (candidates.length==0) return false;
        for (ItemStack candidate:candidates) {
            if (!wanted.item().isEmpty()) {
                if (!wanted.item().equals(String.valueOf(BuiltInRegistries.ITEM.getKey(candidate.getItem())))) return false;
            } else if (!candidate.is(TagKey.create(Registries.ITEM,new ResourceLocation(wanted.tag())))) return false;
        }
        return true;
    }

    private static List<ItemStack> outputs(Recipe<?> recipe, RegistryAccess registries) {
        try {
            // Verified Create API exposes every possible output, including chance outputs.
            var method = recipe.getClass().getMethod("getRollableResultsAsItemStacks");
            @SuppressWarnings("unchecked") List<ItemStack> outputs=(List<ItemStack>)method.invoke(recipe);
            return outputs;
        } catch (NoSuchMethodException ignored) { return List.of(recipe.getResultItem(registries)); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect recipe " + recipe.getId(),failure); }
    }

    private static Controlled controlled(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation id=BuiltInRegistries.ITEM.getKey(stack.getItem());
        for (var material : ProcessGraph.current().materials().values()) {
            ProcessGraph.Stage stage=material.stage(id);
            if (stage!=null) return new Controlled(material,stage);
            if (material.impureVariants().contains(id)) return new Controlled(material,ProcessGraph.Stage.IMPURE_DUST);
            for (var path : List.of("ingots/","nuggets/","storage_blocks/"))
                if (stack.is(TagKey.create(Registries.ITEM,ResourceLocation.parse("forge:"+path+material.id()))))
                    return new Controlled(material,ProcessGraph.Stage.FINAL_METAL);
            if (stack.is(TagKey.create(Registries.ITEM,ResourceLocation.parse("forge:dusts/"+material.id()))))
                return new Controlled(material,ProcessGraph.Stage.CLEAN_DUST);
            if (stack.is(TagKey.create(Registries.ITEM,ResourceLocation.parse("forge:ores/"+material.id()))))
                return new Controlled(material,ProcessGraph.Stage.RAW);
            if (stack.is(TagKey.create(Registries.ITEM,ResourceLocation.parse("forge:raw_materials/"+material.id())))
                    || stack.is(TagKey.create(Registries.ITEM,ResourceLocation.parse("forge:storage_blocks/raw_"+material.id()))))
                return new Controlled(material,ProcessGraph.Stage.RAW);
        }
        return null;
    }

    /** Old IC2 crushed ores are accepted only as a one-item compatibility alias for graph IMPURE_DUST. */
    private static boolean isOldImpureAliasConversion(List<ItemStack> inputs, List<ItemStack> outputs,
            List<Ingredient> ingredients, Recipe<?> actual) {
        if (actual == null || ingredients.size() != 1 || inputs.size() != 1 || outputs.size() != 1
                || outputs.get(0).isEmpty() || outputs.get(0).getCount() != 1) return false;
        String serializer = String.valueOf(ForgeRegistries.RECIPE_SERIALIZERS.getKey(actual.getSerializer()));
        if (!serializer.equals("minecraft:crafting_shaped") && !serializer.equals("minecraft:crafting_shapeless")) return false;
        ResourceLocation inputId = BuiltInRegistries.ITEM.getKey(inputs.get(0).getItem());
        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(outputs.get(0).getItem());
        if (inputId == null || outputId == null || inputs.get(0).getCount() != 1
                || inputs.get(0).hasTag() || outputs.get(0).hasTag()) return false;
        for (ProcessGraph.Material material : ProcessGraph.current().materials().values()) {
            ResourceLocation impure = material.items().get(ProcessGraph.Stage.IMPURE_DUST);
            if (impure == null || !material.impureVariants().contains(inputId) || !impure.equals(outputId)) continue;
            ProcessGraph.Route approved = ProcessGraph.route(actual.getId());
            return approved != null
                    && approved.classification() == ProcessGraph.Classification.APPROVED_INDUSTRIAL_PROCESS
                    && approved.material().equals(material.id())
                    && exact(approved, ingredients, outputs, actual);
        }
        return false;
    }

    private static boolean hasForbiddenMineralDowngrade(List<ItemStack> inputs, List<ItemStack> outputs) {
        for (ItemStack input : inputs) {
            Controlled source = controlled(input);
            if (source == null || source.stage.ordinal() < ProcessGraph.Stage.WASHED_DUST.ordinal()) continue;
            for (ItemStack output : outputs) {
                Controlled target = controlled(output);
                if (target != null && target.material.equals(source.material)
                        && target.stage.ordinal() < source.stage.ordinal()
                        && target.stage.ordinal() <= ProcessGraph.Stage.WASHED_DUST.ordinal()) return true;
            }
        }
        return false;
    }
    private static void log(ResourceLocation id,List<ItemStack> inputs,List<ItemStack> outputs,String reason) {
        LOGGER.warn("RecipeGuard BLOCKED_BYPASS action=remove id={} inputs={} outputs={} reason={}",id,inputs,outputs,reason);
    }
    private record Controlled(ProcessGraph.Material material,ProcessGraph.Stage stage) { }
}
