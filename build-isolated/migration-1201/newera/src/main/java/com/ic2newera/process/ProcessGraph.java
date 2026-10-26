package com.ic2newera.process;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared material stages and approved routes; recipe files are derived from the same JSON at build time. */
public final class ProcessGraph {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Snapshot current = new Snapshot(Map.of(), List.of());
    private ProcessGraph() { }

    public enum Stage { RAW, IMPURE_DUST, WASHED_DUST, CLEAN_DUST, FINAL_METAL }
    public enum Classification { LEGACY_PROCESS, APPROVED_INDUSTRIAL_PROCESS, BLOCKED_BYPASS }
    public record Material(String id, boolean advanced, Map<Stage, ResourceLocation> items,
                           List<ResourceLocation> rawVariants, List<ResourceLocation> grindingInputs,
                           List<ResourceLocation> impureVariants) {
        public Stage stage(ResourceLocation item) {
            for (var entry : items.entrySet()) if (entry.getValue().equals(item)) return entry.getKey();
            return rawVariants.contains(item) ? Stage.RAW : impureVariants.contains(item) ? Stage.IMPURE_DUST : null;
        }
        public ResourceLocation legacyOutput() {
            return items.containsKey(Stage.FINAL_METAL) ? items.get(Stage.FINAL_METAL) : grindingInputs.get(0);
        }
    }
    public record Input(String item, String tag, int count) {
        public String key() { return item.isEmpty() ? "#" + tag : item; }
    }
    public record Route(ResourceLocation id, String material, Classification classification,
                        List<ResourceLocation> machines, List<Input> inputs, ResourceLocation output,
                        int outputCount, int durationTicks, int euPerTick, int priority, String format, int minHeat) { }
    public record Snapshot(Map<String, Material> materials, List<Route> routes) { }
    public static Snapshot current() { return current; }

    /** Shared by resource loading and both directions of the read-only graph codec. */
    public static void validateFormat(Route route) {
        // Ad Astra FE alloying is audit-only metadata, never an approved steel route.
        boolean blockedNativeAlloying = route.format.equals("alloying")
                && route.classification == Classification.BLOCKED_BYPASS
                && route.machines.equals(List.of(id("ad_astra:etrionic_blast_furnace")));
        if (!blockedNativeAlloying && !java.util.Set.of("smelting", "blasting", "crafting", "crafting_shaped",
                "crushing", "splashing", "mixing", "pressing", "processor", "macerator",
                "blast_furnace", "mixing_furnace", "centrifuge").contains(route.format))
            throw new IllegalArgumentException("Unsupported process format " + route.format + " in " + route.id);
        if (route.format.equals("centrifuge")) {
            if (!route.machines.equals(List.of(id("ic2:centrifuge"))) || route.inputs.size() != 1
                    || route.minHeat < 0 || route.minHeat > 5000 || route.durationTicks != 500 || route.euPerTick != 48)
                throw new IllegalArgumentException("Invalid native centrifuge metadata " + route.id);
        } else if (route.minHeat != -1) throw new IllegalArgumentException("Heat on non-centrifuge route " + route.id);
    }

    public static Material materialFor(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return current.materials.values().stream().filter(material -> material.stage(id) != null).findFirst().orElse(null);
    }
    public static Stage stageFor(ItemStack stack) {
        Material material = materialFor(stack);
        if (material == null) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return material.stage(id);
    }
    public static Route route(ResourceLocation id) {
        return current.routes.stream().filter(route -> route.id.equals(id)).findFirst().orElse(null);
    }
    public static List<Route> industrialRoutes(ResourceLocation output) {
        return current.routes.stream().filter(route -> route.output.equals(output)
                        && route.classification == Classification.APPROVED_INDUSTRIAL_PROCESS)
                .sorted(Comparator.comparingInt(Route::priority).thenComparing(route -> route.id.toString())).toList();
    }

    public static final class ReloadListener extends SimpleJsonResourceReloadListener {
        public ReloadListener() { super(new Gson(), "process_graph"); }
        @Override protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<String, Material> materials = new LinkedHashMap<>();
            List<Route> routes = new ArrayList<>();
            Map<ResourceLocation, JsonObject> exclusions = new LinkedHashMap<>();
            try {
                for (var file : files.entrySet()) {
                    if (!file.getKey().getNamespace().equals("ic2newera")) continue;
                    JsonObject root = file.getValue().getAsJsonObject();
                    // These unavailable native outputs are audit metadata, never routes.
                    // Same-ID false-condition resources exclude them before deserialization.
                    for (JsonElement value : array(root, "native_recipe_exclusions")) {
                        JsonObject excluded = value.getAsJsonObject();
                        ResourceLocation recipeId = id(string(excluded, "id"));
                        boolean ownerDeclared = false;
                        boolean loaded = true;
                        for (JsonElement required : array(excluded, "required_mods")) {
                            String mod = required.getAsString();
                            if (!mod.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("Invalid exclusion mod");
                            ownerDeclared |= mod.equals(recipeId.getNamespace());
                            loaded &= net.minecraftforge.fml.ModList.get().isLoaded(mod);
                        }
                        if (!ownerDeclared || string(excluded, "reason").isBlank()
                                || array(excluded, "inputs").isEmpty())
                            throw new IllegalArgumentException("Incomplete native recipe exclusion " + recipeId);
                        JsonObject original = excluded.getAsJsonObject("original_output");
                        id(string(original, "item"));
                        integer(original, "count", 1, 64);
                        if (loaded && exclusions.putIfAbsent(recipeId, excluded) != null)
                            throw new IllegalArgumentException("Duplicate native recipe exclusion " + recipeId);
                    }
                    for (JsonElement value : array(root, "materials")) {
                        JsonObject object = value.getAsJsonObject();
                        String name = string(object, "id");
                        Map<Stage, ResourceLocation> stages = new LinkedHashMap<>();
                        JsonObject stageObject = object.getAsJsonObject("stages");
                        for (Stage stage : Stage.values()) if (stageObject.has(stage.name()))
                            stages.put(stage, id(stageObject.get(stage.name()).getAsString()));
                        List<ResourceLocation> rawVariants = ids(object, "raw_variants");
                        List<ResourceLocation> grindingInputs = ids(object, "grinding_inputs");
                        List<ResourceLocation> impureVariants = ids(object, "impure_variants");
                        if (!stages.keySet().containsAll(java.util.Set.of(Stage.RAW, Stage.IMPURE_DUST, Stage.WASHED_DUST, Stage.CLEAN_DUST))
                                || rawVariants.size()>16 || grindingInputs.size()>8 || impureVariants.size()>8
                                || stages.values().stream().distinct().count()!=stages.size()
                                || (!stages.containsKey(Stage.FINAL_METAL) && grindingInputs.isEmpty()))
                            throw new IllegalArgumentException("Incomplete or oversized mineral stages " + name);
                        if (materials.putIfAbsent(name, new Material(name, object.get("advanced").getAsBoolean(),
                                Map.copyOf(stages), rawVariants, grindingInputs, impureVariants)) != null)
                            throw new IllegalArgumentException("Duplicate material " + name);
                    }
                    for (JsonElement value : array(root, "routes")) {
                        JsonObject object = value.getAsJsonObject();
                        boolean missingMod = false;
                        for (JsonElement required : array(object, "required_mods")) {
                            String mod = required.getAsString();
                            if (!mod.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("Invalid required mod");
                            if (!net.minecraftforge.fml.ModList.get().isLoaded(mod)) missingMod = true;
                        }
                        if (missingMod) continue;
                        List<ResourceLocation> machines = new ArrayList<>();
                        for (JsonElement machine : array(object, "machines")) machines.add(id(machine.getAsString()));
                        if (machines.isEmpty()) throw new IllegalArgumentException("A route needs a verified machine");
                        List<Input> inputs = new ArrayList<>();
                        for (JsonElement input : array(object, "inputs")) {
                            JsonObject ingredient = input.getAsJsonObject();
                            String item = ingredient.has("item") ? string(ingredient, "item") : "";
                            String tag = ingredient.has("tag") ? string(ingredient, "tag") : "";
                            if (item.isEmpty() == tag.isEmpty()) throw new IllegalArgumentException("Input requires exactly one item or tag");
                            id(item.isEmpty() ? tag : item);
                            inputs.add(new Input(item, tag, integer(ingredient, "count", 1, 64)));
                        }
                        if (inputs.isEmpty() || inputs.size() > 25) throw new IllegalArgumentException("Invalid input count");
                        JsonObject output = object.getAsJsonObject("output");
                        routes.add(new Route(id(string(object, "id")), string(object, "material"),
                                Classification.valueOf(string(object, "classification")), List.copyOf(machines), List.copyOf(inputs),
                                id(string(output, "item")), integer(output, "count", 1, 64),
                                integer(object, "duration_ticks", 0, 72000), integer(object, "eu_per_tick", -1, 32768),
                                integer(object, "priority", 0, 1000), string(object, "format"),
                                object.has("min_heat") ? integer(object, "min_heat", 0, 5000) : -1));
                    }
                }
                if (routes.stream().map(Route::id).distinct().count() != routes.size()) throw new IllegalArgumentException("Duplicate route ID");
                if (exclusions.size() > 64 || routes.stream().anyMatch(route -> exclusions.containsKey(route.id)))
                    throw new IllegalArgumentException("Invalid or conflicting native recipe exclusions");
                if (materials.size()>24 || routes.size()>512) throw new IllegalArgumentException("ProcessGraph exceeds network bounds");
                for (var route : routes) {
                    if (route.machines.size()>8) throw new IllegalArgumentException("Too many machines in route " + route.id);
                    validateFormat(route);
                    requireItem(route.output);
                    route.machines.forEach(ProcessGraph::requireItem);
                    for (var input : route.inputs) if (!input.item.isEmpty()) requireItem(id(input.item));
                    validateProcessorRoute(route);
                    validateStages(route, materials);
                }
                for (var material : materials.values()) {
                    for (var item : material.items.values()) requireItem(item);
                    material.rawVariants.forEach(ProcessGraph::requireItem);
                    material.grindingInputs.forEach(ProcessGraph::requireItem);
                    material.impureVariants.forEach(ProcessGraph::requireItem);
                }
                current = new Snapshot(Map.copyOf(materials), List.copyOf(routes));
                exclusions.forEach((recipeId, excluded) -> LOGGER.warn(
                        "RecipeGuard action=disable_before_parse id={} inputs={} original_output={} reason={}",
                        recipeId, excluded.get("inputs"), excluded.get("original_output"), string(excluded, "reason")));
                LOGGER.info("ProcessGraph loaded {} controlled materials and {} routes", materials.size(), routes.size());
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Invalid IC2 New Era ProcessGraph; refusing inconsistent recipe policy", failure);
            }
        }
    }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) ? object.getAsJsonArray(key) : new JsonArray(); }
    private static List<ResourceLocation> ids(JsonObject object, String key) {
        List<ResourceLocation> values = new ArrayList<>();
        for (JsonElement value : array(object, key)) values.add(id(value.getAsString()));
        if (values.stream().distinct().count()!=values.size()) throw new IllegalArgumentException("Duplicate " + key);
        return List.copyOf(values);
    }
    private static String string(JsonObject object, String key) { return object.get(key).getAsString(); }
    private static ResourceLocation id(String value) { return new ResourceLocation(value); }
    private static void requireItem(ResourceLocation id) {
        if (!ForgeRegistries.ITEMS.containsKey(id) || id.equals(new ResourceLocation("minecraft:air")))
            throw new IllegalArgumentException("Unknown ProcessGraph item " + id);
    }
    private static void validateStages(Route route, Map<String, Material> materials) {
        for (var material : materials.values()) for (var input : route.inputs) {
            if (input.item.isEmpty()) continue;
            ResourceLocation source=id(input.item);
            Stage stage=material.stage(source);
            if (material.grindingInputs.contains(source) && java.util.Set.of("macerator","crushing").contains(route.format)) {
                if (!route.output.equals(material.items.get(Stage.IMPURE_DUST)) || route.outputCount>input.count*2)
                    throw new IllegalArgumentException("Unwashed resource grinding " + route.id);
                continue;
            }
            if (stage==null || stage==Stage.FINAL_METAL) continue;
            boolean valid=switch(stage) {
                case RAW -> route.classification==Classification.LEGACY_PROCESS
                        ? java.util.Set.of("smelting","blasting").contains(route.format)
                          && route.output.equals(material.legacyOutput()) && route.outputCount==1 && input.count==1
                        : java.util.Set.of("macerator","crushing").contains(route.format)
                          && route.output.equals(material.items.get(Stage.IMPURE_DUST)) && route.outputCount<=input.count*2;
                case IMPURE_DUST -> route.classification == Classification.APPROVED_INDUSTRIAL_PROCESS && (
                        route.format.equals("splashing") && route.output.equals(material.items.get(Stage.WASHED_DUST))
                                && route.outputCount == input.count
                        || route.format.equals("centrifuge") && route.output.equals(material.items.get(Stage.CLEAN_DUST))
                                && route.outputCount == input.count
                        || route.format.equals("crafting") && material.impureVariants.contains(source)
                                && route.output.equals(material.items.get(Stage.IMPURE_DUST)) && route.outputCount == input.count);
                case WASHED_DUST -> route.classification == Classification.APPROVED_INDUSTRIAL_PROCESS
                        && route.format.equals("centrifuge") && route.output.equals(material.items.get(Stage.CLEAN_DUST))
                        && route.outputCount == input.count * 3;
                // Normal powder remains usable in native downstream recipes (steel, silicon, carbon, etc.).
                case CLEAN_DUST -> !java.util.Set.of(Stage.RAW, Stage.IMPURE_DUST, Stage.WASHED_DUST)
                        .contains(material.stage(route.output) == null ? Stage.CLEAN_DUST : material.stage(route.output))
                        && (!route.output.equals(material.items.get(Stage.FINAL_METAL))
                        || route.outputCount<=input.count && (material.advanced ? route.format.equals("blast_furnace")
                        : java.util.Set.of("smelting","blasting","blast_furnace").contains(route.format)));
                default -> true;
            };
            if (!valid) throw new IllegalArgumentException("Prohibited controlled-stage transition " + route.id);
        }
    }

    private static void validateProcessorRoute(Route route) {
        String machine;
        int slots;
        switch (route.format) {
            case "mixing_furnace" -> { machine = "ic2newera:alloy_smelter"; slots = 3; }
            case "blast_furnace" -> { machine = "ic2newera:advanced_electric_furnace"; slots = 1; }
            case "processor" -> {
                if (route.machines.equals(List.of(id("ic2newera:non_metal_processor")))) {
                    machine = "ic2newera:non_metal_processor"; slots = 1;
                } else { machine = "ic2newera:electronic_fabricator"; slots = 6; }
            }
            default -> { return; }
        }
        if (!route.machines.equals(List.of(id(machine))) || route.inputs.size() > slots
                || route.durationTicks < 1 || route.euPerTick < 1 || route.euPerTick > 512)
            throw new IllegalArgumentException("Invalid processor machine, layout, duration or EU cost " + route.id);
    }
    private static int integer(JsonObject object, String key, int min, int max) {
        JsonElement number = object.get(key);
        if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Missing integer " + key);
        int value = number.getAsInt();
        if (number.getAsDouble() != value || value < min || value > max) throw new IllegalArgumentException("Out of range " + key);
        return value;
    }
}
