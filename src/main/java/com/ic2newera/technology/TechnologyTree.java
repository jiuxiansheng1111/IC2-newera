package com.ic2newera.technology;

import com.ic2newera.IC2NewEra;
import com.google.gson.*;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validated, immutable task snapshot. Native IC2 and Create advancements are joined by TechnologyService. */
public final class TechnologyTree {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Curated New Era tasks shown alongside the complete native IC2 and Create advancement trees. */
    private static final Set<String> ACTIVE_TASKS = Set.of(
            "raw_materials", "mechanical_to_electric", "sphalerite_mining", "copper_tin", "refined_iron",
            "bronze", "bronze_tools", "bronze_hoe", "bronze_shield", "bronze_shovel", "tin_bronze_equipment",
            "aluminium_bronze", "brass_ingot_task", "zinc_dust", "zinc_ingot_task", "low_carbon_steel_dust", "high_carbon_steel_dust",
            "tempered_steel_ingot", "tempered_steel_equipment", "tempered_steel_axe", "tempered_steel_boots",
            "tempered_steel_chestplate", "tempered_steel_leggings", "tempered_high_carbon_steel_ingot",
            "tempered_high_carbon_steel_equipment", "aluminium_bronze_equipment", "aluminium_bronze_axe",
            "aluminium_bronze_boots", "aluminium_bronze_helmet", "aluminium_bronze_leggings"
    );
    private static volatile Map<ResourceLocation, TechnologyNode> nodes = Map.of();
    private static volatile int revision;
    private TechnologyTree() {}
    public static Collection<TechnologyNode> getNodes() { return nodes.values(); }
    public static int revision() { return revision; }

    public static final class ReloadListener extends SimpleJsonResourceReloadListener {
        public ReloadListener() { super(new Gson(), "technology"); }
        @Override protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, JsonElement> parsed = new TreeMap<>();
            manager.listResources("technology", file -> file.getPath().endsWith(".json")).forEach((file, resource) -> {
                String path = file.getPath().substring("technology/".length());
                ResourceLocation id = new ResourceLocation(file.getNamespace(), path.substring(0, path.length() - 5));
                if (!id.getNamespace().equals(IC2NewEra.MOD_ID) || !ACTIVE_TASKS.contains(id.getPath())) return;
                try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                    if (parsed.putIfAbsent(id, JsonParser.parseReader(reader)) != null)
                        throw new IllegalArgumentException("Duplicate task " + id);
                } catch (Exception error) {
                    throw new IllegalStateException("Invalid task JSON " + id + ": " + error.getMessage(), error);
                }
            });
            return parsed;
        }
        @Override protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, TechnologyNode> candidate = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            entries.forEach((id, json) -> {
                try { candidate.put(id, parse(id, json.getAsJsonObject())); }
                catch (RuntimeException error) { errors.add(id + ": " + error.getMessage()); }
            });
            candidate.values().forEach(node -> node.prerequisites().forEach(parent -> {
                if (!candidate.containsKey(parent)) errors.add(node.id() + ": missing prerequisite " + parent);
            }));
            if (errors.isEmpty()) {
                Map<ResourceLocation, Integer> visited = new HashMap<>();
                for (ResourceLocation id : candidate.keySet()) {
                    try { visit(id, candidate, visited); }
                    catch (RuntimeException error) { errors.add(error.getMessage()); break; }
                }
            }
            if (!errors.isEmpty()) {
                LOGGER.error("Rejected technology datapack: {}", String.join(" | ", errors));
                throw new IllegalStateException("Invalid technology tasks: " + String.join(" | ", errors));
            }
            nodes = Collections.unmodifiableMap(candidate);
            revision++;
            LOGGER.info("Loaded {} industrial technology tasks", candidate.size());
        }
        private static void visit(ResourceLocation id, Map<ResourceLocation, TechnologyNode> candidate,
                                  Map<ResourceLocation, Integer> states) {
            int state = states.getOrDefault(id, 0);
            if (state == 1) throw new IllegalArgumentException("Technology prerequisite cycle at " + id);
            if (state == 2) return;
            states.put(id, 1);
            for (ResourceLocation parent : candidate.get(id).prerequisites()) visit(parent, candidate, states);
            states.put(id, 2);
        }
        private static TechnologyNode parse(ResourceLocation id, JsonObject object) {
            Set<String> allowed = Set.of("title", "description", "branch", "icon", "prerequisites", "objectives", "steps", "x", "y");
            for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown field " + key);
            ResourceLocation icon = resource(string(object, "icon"));
            requireItem(icon);
            List<ResourceLocation> parents = new ArrayList<>();
            for (JsonElement element : array(object, "prerequisites")) {
                ResourceLocation parent = resource(element.getAsString());
                if (id.equals(parent) || parents.contains(parent)) throw new IllegalArgumentException("Invalid repeated/self prerequisite");
                parents.add(parent);
            }
            List<TechnologyNode.Objective> objectives = new ArrayList<>();
            for (JsonElement element : array(object, "objectives")) {
                JsonObject value = element.getAsJsonObject();
                String type = string(value, "type");
                if (!Set.of("item", "advancement").contains(type)) throw new IllegalArgumentException("Unknown objective " + type);
                Set<String> fields = type.equals("advancement") ? Set.of("type", "id") : Set.of("type", "item", "count");
                for (String field : value.keySet()) if (!fields.contains(field)) throw new IllegalArgumentException("Unknown objective field " + field);
                ResourceLocation target = resource(string(value, type.equals("advancement") ? "id" : "item"));
                int count = type.equals("item") ? integer(value, "count", 1, 4096) : 1;
                if (type.equals("item")) requireItem(target);
                objectives.add(new TechnologyNode.Objective(type, target, count));
            }
            if (objectives.isEmpty() || objectives.size() > 64) throw new IllegalArgumentException("Task needs 1..64 objectives");
            List<Component> steps = new ArrayList<>();
            for (JsonElement element : array(object, "steps")) {
                String text = element.getAsString();
                if (text.isBlank() || text.length() > 2048) throw new IllegalArgumentException("Invalid step text");
                steps.add(Component.literal(text));
            }
            if (steps.size() > 64) throw new IllegalArgumentException("Too many steps");
            return new TechnologyNode(id, Component.literal(string(object, "title")), Component.literal(string(object, "description")),
                    string(object, "branch"), icon, parents, objectives, steps,
                    integer(object, "x", 0, 256), integer(object, "y", 0, 4096));
        }
        private static String string(JsonObject object, String key) {
            JsonElement element = object.get(key);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Missing string " + key);
            String text = element.getAsString();
            if (text.isBlank() || text.length() > 2048) throw new IllegalArgumentException("Invalid text " + key);
            return text;
        }
        private static JsonArray array(JsonObject object, String key) {
            JsonElement element = object.get(key);
            if (element == null || !element.isJsonArray()) throw new IllegalArgumentException("Missing array " + key);
            return element.getAsJsonArray();
        }
        private static int integer(JsonObject object, String key, int minimum, int maximum) {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || value.getAsDouble() != value.getAsInt() || value.getAsInt() < minimum || value.getAsInt() > maximum)
                throw new IllegalArgumentException("Invalid integer " + key);
            return value.getAsInt();
        }
        private static ResourceLocation resource(String text) {
            ResourceLocation id = ResourceLocation.tryParse(text);
            if (id == null) throw new IllegalArgumentException("Invalid resource id " + text);
            return id;
        }
        private static void requireItem(ResourceLocation id) {
            if (!ForgeRegistries.ITEMS.containsKey(id) || id.equals(new ResourceLocation("minecraft", "air")))
                throw new IllegalArgumentException("Unknown item " + id);
        }
    }
}
