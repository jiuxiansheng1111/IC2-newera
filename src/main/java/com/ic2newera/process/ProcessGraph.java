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

    public enum Stage { RAW, IMPURE_DUST, CLEAN_DUST, FINAL_METAL }
    public enum Classification { LEGACY_PROCESS, APPROVED_INDUSTRIAL_PROCESS, BLOCKED_BYPASS }
    public record Material(String id, boolean advanced, Map<Stage, ResourceLocation> items) { }
    public record Input(String item, String tag, int count) {
        public String key() { return item.isEmpty() ? "#" + tag : item; }
    }
    public record Route(ResourceLocation id, String material, Classification classification,
                        List<ResourceLocation> machines, List<Input> inputs, ResourceLocation output,
                        int outputCount, int durationTicks, int euPerTick, int priority, String format) { }
    public record Snapshot(Map<String, Material> materials, List<Route> routes) { }
    public static Snapshot current() { return current; }

    public static Material materialFor(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return current.materials.values().stream().filter(material -> material.items.containsValue(id)).findFirst().orElse(null);
    }
    public static Stage stageFor(ItemStack stack) {
        Material material = materialFor(stack);
        if (material == null) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return material.items.entrySet().stream().filter(entry -> entry.getValue().equals(id)).map(Map.Entry::getKey).findFirst().orElse(null);
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
            try {
                for (var file : files.entrySet()) {
                    if (!file.getKey().getNamespace().equals("ic2newera")) continue;
                    JsonObject root = file.getValue().getAsJsonObject();
                    for (JsonElement value : array(root, "materials")) {
                        JsonObject object = value.getAsJsonObject();
                        String name = string(object, "id");
                        Map<Stage, ResourceLocation> stages = new LinkedHashMap<>();
                        JsonObject stageObject = object.getAsJsonObject("stages");
                        for (Stage stage : Stage.values()) stages.put(stage, id(stageObject.get(stage.name()).getAsString()));
                        if (materials.putIfAbsent(name, new Material(name, object.get("advanced").getAsBoolean(), Map.copyOf(stages))) != null)
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
                                integer(object, "priority", 0, 1000), string(object, "format")));
                    }
                }
                if (routes.stream().map(Route::id).distinct().count() != routes.size()) throw new IllegalArgumentException("Duplicate route ID");
                if (materials.size()>16 || routes.size()>256) throw new IllegalArgumentException("ProcessGraph exceeds network bounds");
                for (var route : routes) {
                    if (route.machines.size()>8 || !java.util.Set.of("smelting","blasting","crafting","crushing","splashing","mixing","processor","macerator","blast_furnace","mixing_furnace").contains(route.format))
                        throw new IllegalArgumentException("Unsupported route format " + route.id);
                    requireItem(route.output);
                    for (var input : route.inputs) if (!input.item.isEmpty()) requireItem(id(input.item));
                    validateStages(route, materials);
                }
                for (var material : materials.values()) for (var item : material.items.values()) requireItem(item);
                current = new Snapshot(Map.copyOf(materials), List.copyOf(routes));
                LOGGER.info("ProcessGraph loaded {} controlled materials and {} routes", materials.size(), routes.size());
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Invalid IC2 New Era ProcessGraph; refusing inconsistent recipe policy", failure);
            }
        }
    }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) ? object.getAsJsonArray(key) : new JsonArray(); }
    private static String string(JsonObject object, String key) { return object.get(key).getAsString(); }
    private static ResourceLocation id(String value) { return new ResourceLocation(value); }
    private static void requireItem(ResourceLocation id) {
        if (!ForgeRegistries.ITEMS.containsKey(id) || id.equals(new ResourceLocation("minecraft:air")))
            throw new IllegalArgumentException("Unknown ProcessGraph item " + id);
    }
    private static void validateStages(Route route, Map<String, Material> materials) {
        for (var material : materials.values()) for (var input : route.inputs) {
            Stage stage=null;
            for (var entry : material.items.entrySet()) if (entry.getValue().toString().equals(input.item)) stage=entry.getKey();
            if (stage==null || stage==Stage.FINAL_METAL) continue;
            boolean valid=switch(stage) {
                case RAW -> route.classification==Classification.LEGACY_PROCESS
                        ? java.util.Set.of("smelting","blasting").contains(route.format)
                          && route.output.equals(material.items.get(Stage.FINAL_METAL)) && route.outputCount==1 && input.count==1
                        : java.util.Set.of("macerator","crushing").contains(route.format)
                          && route.output.equals(material.items.get(Stage.IMPURE_DUST)) && route.outputCount<=input.count*2;
                case IMPURE_DUST -> route.format.equals("splashing") && route.output.equals(material.items.get(Stage.CLEAN_DUST))
                        && route.outputCount<=input.count;
                case CLEAN_DUST -> route.output.equals(material.items.get(Stage.FINAL_METAL)) && route.outputCount<=input.count
                        && (material.advanced ? route.format.equals("blast_furnace")
                        : java.util.Set.of("smelting","blasting","blast_furnace").contains(route.format));
                default -> true;
            };
            if (!valid) throw new IllegalArgumentException("Prohibited controlled-stage transition " + route.id);
        }
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
