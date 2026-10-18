package com.ic2newera.robot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-safe item-name resolution for robot chat requests. Suggestions never authorize a task.
 * The first call caches up to 100,000 registered items and at most 16 MiB of Chinese language resources
 * across up to 512 namespaces (up to 2 MiB per file).
 * Missing, unreadable, oversized, or absent zh_cn.json resources fall back to the item registry ID.
 */
public final class RobotChatItems {
    private static final int MAX_LANG_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_LANG_BYTES = 16 * 1024 * 1024;
    private static final int MAX_LANG_NAMESPACES = 512;
    private static final int MAX_ITEMS = 100_000;
    private static final int MAX_HINTS = 24;
    private static volatile Index cachedIndex;

    private RobotChatItems() { }

    /** Resolves a complete localized name, explicit alias, or registry ID to one registered non-AIR item. */
    public static Resolution resolve(String query) {
        if (query == null || query.isBlank()) return new Resolution(null, "请提供明确的物品名称或注册ID。");
        Index index = index();
        String needle = normalize(query);

        Item byId = index.byId.get(needle);
        if (isRegistered(byId)) return success(byId, index);

        List<Item> exactNames = index.byName.get(needle);
        if (exactNames != null && !exactNames.isEmpty()) return choose(exactNames, index);

        List<Item> aliases = index.byAlias.get(needle);
        if (aliases != null && !aliases.isEmpty()) return choose(aliases, index);
        return new Resolution(null, "没有找到名称完全匹配的已注册物品；请提供更具体的中文名或注册ID。");
    }

    /** Returns a cached Chinese name, falling back to the actual registry ID when no translation is available. */
    public static String displayName(Item item) {
        if (item == null || item == Items.AIR) return "未知物品";
        Index index = index();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null || ForgeRegistries.ITEMS.getValue(id) != item) return "未知物品";
        return index.displayNames.getOrDefault(id.toString(), id.toString());
    }

    /** Emits only candidates whose full name, alias, or ID occurs literally in the user's text. */
    public static String promptHints(String userText) {
        if (userText == null || userText.isBlank()) return "";
        Index index = index();
        String text = normalize(userText);
        Map<String, Hint> hits = new LinkedHashMap<>();
        collectHints(text, index.byName, index, hits);
        collectHints(text, index.byAlias, index, hits);
        for (var entry : index.byId.entrySet()) {
            String id = entry.getKey();
            int at = text.indexOf(id);
            if (at >= 0) addHint(hits, entry.getValue(), at, id.length(), index);
        }
        if (hits.isEmpty()) return "";
        List<Hint> ordered = hits.values().stream()
                .sorted(Comparator.comparingInt(Hint::position).thenComparing(Comparator.comparingInt(Hint::matchLength).reversed())
                        .thenComparing(Hint::id))
                .limit(MAX_HINTS).toList();
        StringBuilder result = new StringBuilder("用户原文中出现的物品候选（仅供识别，不构成任务授权；必须由服务端重新解析并校验）：");
        for (Hint hit : ordered) result.append("\n- ").append(hit.name).append(" (`").append(hit.id).append('`');
        return result.toString();
    }

    private static void collectHints(String text, Map<String, List<Item>> names, Index index, Map<String, Hint> hits) {
        for (var entry : names.entrySet()) {
            int at = text.indexOf(entry.getKey());
            if (at < 0) continue;
            for (Item item : entry.getValue()) addHint(hits, item, at, entry.getKey().length(), index);
        }
    }

    private static void addHint(Map<String, Hint> hits, Item item, int at, int length, Index index) {
        ResourceLocation registryKey = ForgeRegistries.ITEMS.getKey(item);
        if (registryKey == null || item == Items.AIR || ForgeRegistries.ITEMS.getValue(registryKey) != item) return;
        String id = registryKey.toString();
        Hint current = hits.get(id);
        if (current == null || at < current.position || at == current.position && length > current.matchLength)
            hits.put(id, new Hint(id, index.displayNames.getOrDefault(id, id), at, length));
    }

    private static Resolution choose(List<Item> candidates, Index index) {
        List<Item> valid = candidates.stream().distinct().filter(item -> {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            return id != null && item != Items.AIR && ForgeRegistries.ITEMS.getValue(id) == item;
        }).sorted(Comparator.comparing(item -> String.valueOf(ForgeRegistries.ITEMS.getKey(item)))).toList();
        if (valid.size() == 1) return success(valid.get(0), index);
        if (valid.isEmpty()) return new Resolution(null, "该物品当前未注册或不可用，请提供其他名称或注册ID。");
        String ids = valid.stream().limit(6).map(item -> String.valueOf(ForgeRegistries.ITEMS.getKey(item))).reduce((a, b) -> a + "、" + b).orElse("");
        return new Resolution(null, "名称对应多个已注册物品，请提供具体注册ID：" + ids);
    }

    private static Resolution success(Item item, Index index) {
        String id = String.valueOf(ForgeRegistries.ITEMS.getKey(item));
        return new Resolution(item, "已解析为 " + index.displayNames.getOrDefault(id, id) + "（" + id + "）。服务端仍须独立校验采集规则、权限与任务状态。");
    }

    private static boolean isRegistered(Item item) {
        if (item == null || item == Items.AIR) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id != null && ForgeRegistries.ITEMS.getValue(id) == item;
    }

    private static Index index() {
        Index index = cachedIndex;
        if (index != null) return index;
        synchronized (RobotChatItems.class) {
            index = cachedIndex;
            if (index == null) cachedIndex = index = buildIndex();
        }
        return index;
    }

    private static Index buildIndex() {
        Map<String, Item> byId = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        Map<String, List<Item>> byName = new LinkedHashMap<>();
        Map<String, List<Item>> byAlias = new LinkedHashMap<>();
        Map<String, Map<String, String>> languageByNamespace = new LinkedHashMap<>();
        Map<String, String> fallback = fallbackAliases();
        Set<String> namespaces = new LinkedHashSet<>();
        try {
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null && item != Items.AIR) namespaces.add(id.getNamespace());
            }
        } catch (RuntimeException ignored) { /* Empty/partial registry degrades to no candidates. */ }
        int languageBytes = 0;
        for (String namespace : namespaces.stream().limit(MAX_LANG_NAMESPACES).toList()) {
            if (languageBytes >= MAX_TOTAL_LANG_BYTES) break;
            LanguageData language = readChineseLanguage(namespace, Math.min(MAX_LANG_BYTES, MAX_TOTAL_LANG_BYTES - languageBytes));
            languageByNamespace.put(namespace, language.values);
            languageBytes += language.bytesRead;
        }

        int count = 0;
        try {
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                if (count++ >= MAX_ITEMS) break;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id == null || item == Items.AIR || ForgeRegistries.ITEMS.getValue(id) != item) continue;
                String idText = id.toString();
                byId.put(idText, item);
                Map<String, String> language = languageByNamespace.get(id.getNamespace());
                String localized = language == null ? null : language.get(item.getDescriptionId());
                String extra = fallback.get(idText);
                String name = localized == null || localized.isBlank() ? extra == null ? idText : extra.split("\\|")[0] : localized.trim();
                displayNames.put(idText, name);
                if (!name.equals(idText)) add(byName, name, item);
                String normalizedName = normalize(name);
                if (normalizedName.endsWith("矿石") && normalizedName.length() > 2)
                    add(byAlias, normalizedName.substring(0, normalizedName.length() - 1), item);
                if (extra != null) for (String alias : extra.split("\\|")) add(byAlias, alias, item);
            }
        } catch (RuntimeException ignored) { /* Keep entries captured before a registry failure. */ }
        return new Index(Map.copyOf(byId), Map.copyOf(displayNames), freeze(byName), freeze(byAlias));
    }

    private static LanguageData readChineseLanguage(String namespace, int byteLimit) {
        if (namespace == null || namespace.isBlank() || byteLimit <= 0) return new LanguageData(Map.of(), 0);
        try {
            var modFile = ModList.get().getModFileById(namespace);
            if (modFile == null) return new LanguageData(Map.of(), 0);
            var resource = modFile.getFile().findResource("assets", namespace, "lang", "zh_cn.json");
            try (InputStream input = Files.newInputStream(resource)) {
                byte[] bytes = input.readNBytes(byteLimit + 1);
                if (bytes.length > byteLimit) return new LanguageData(Map.of(), byteLimit);
                JsonObject object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, String> values = new LinkedHashMap<>();
                for (var entry : object.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                        values.put(entry.getKey(), value.getAsString());
                }
                return new LanguageData(Map.copyOf(values), bytes.length);
            }
        } catch (Exception ignored) { return new LanguageData(Map.of(), 0); }
    }

    private static Map<String, String> fallbackAliases() {
        Map<String, String> values = new LinkedHashMap<>();
        alias(values, "ic2newera:tungsten_ore", "钨矿|钨矿石");
        alias(values, "minecraft:iron_ore", "铁矿石|铁矿");
        alias(values, "minecraft:deepslate_iron_ore", "深层铁矿石|深层铁矿");
        alias(values, "minecraft:raw_iron", "粗铁|铁粗矿");
        alias(values, "minecraft:gold_ore", "金矿石|金矿");
        alias(values, "minecraft:deepslate_gold_ore", "深层金矿石|深层金矿");
        alias(values, "minecraft:raw_gold", "粗金|金粗矿");
        alias(values, "minecraft:copper_ore", "铜矿石|铜矿");
        alias(values, "minecraft:deepslate_copper_ore", "深层铜矿石|深层铜矿");
        alias(values, "minecraft:raw_copper", "粗铜|铜粗矿");
        alias(values, "minecraft:coal_ore", "煤矿石|煤矿");
        alias(values, "minecraft:deepslate_coal_ore", "深层煤矿石|深层煤矿");
        alias(values, "minecraft:diamond_ore", "钻石矿石|钻石矿");
        alias(values, "minecraft:deepslate_diamond_ore", "深层钻石矿石|深层钻石矿");
        alias(values, "minecraft:lapis_ore", "青金石矿石|青金石矿");
        alias(values, "minecraft:deepslate_lapis_ore", "深层青金石矿石|深层青金石矿");
        alias(values, "minecraft:redstone_ore", "红石矿石|红石矿");
        alias(values, "minecraft:deepslate_redstone_ore", "深层红石矿石|深层红石矿");
        alias(values, "minecraft:emerald_ore", "绿宝石矿石|绿宝石矿");
        alias(values, "minecraft:deepslate_emerald_ore", "深层绿宝石矿石|深层绿宝石矿");
        alias(values, "minecraft:nether_quartz_ore", "下界石英矿石|下界石英矿");
        alias(values, "minecraft:ancient_debris", "远古残骸|远古碎片");
        alias(values, "minecraft:diamond", "钻石");
        alias(values, "minecraft:coal", "煤炭|煤");
        alias(values, "minecraft:charcoal", "木炭");
        alias(values, "minecraft:redstone", "红石粉|红石");
        alias(values, "minecraft:lapis_lazuli", "青金石");
        alias(values, "minecraft:emerald", "绿宝石");
        alias(values, "minecraft:quartz", "下界石英|石英");
        alias(values, "minecraft:iron_ingot", "铁锭");
        alias(values, "minecraft:gold_ingot", "金锭");
        alias(values, "minecraft:copper_ingot", "铜锭");
        alias(values, "minecraft:cobblestone", "圆石");
        alias(values, "minecraft:stone", "石头|石块");
        alias(values, "minecraft:dirt", "泥土");
        alias(values, "minecraft:stick", "木棍");
        alias(values, "minecraft:oak_log", "橡木原木");
        alias(values, "minecraft:oak_wood", "橡木");
        alias(values, "minecraft:spruce_log", "云杉原木");
        alias(values, "minecraft:spruce_wood", "云杉木");
        alias(values, "minecraft:birch_log", "白桦原木");
        alias(values, "minecraft:birch_wood", "白桦木");
        alias(values, "minecraft:jungle_log", "丛林原木");
        alias(values, "minecraft:jungle_wood", "丛林木");
        alias(values, "minecraft:acacia_log", "金合欢原木|相思木原木");
        alias(values, "minecraft:acacia_wood", "金合欢木|相思木");
        alias(values, "minecraft:dark_oak_log", "深色橡木原木");
        alias(values, "minecraft:dark_oak_wood", "深色橡木");
        alias(values, "minecraft:mangrove_log", "红树原木");
        alias(values, "minecraft:mangrove_wood", "红树木");
        alias(values, "minecraft:crimson_stem", "绯红菌柄|绯红木");
        alias(values, "minecraft:warped_stem", "诡异菌柄|诡异木");
        return Map.copyOf(values);
    }

    private static void alias(Map<String, String> aliases, String id, String values) { aliases.put(id, values); }

    private static void add(Map<String, List<Item>> values, String name, Item item) {
        String normalized = normalize(name);
        if (normalized.length() < 2) return;
        values.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(item);
    }

    private static Map<String, List<Item>> freeze(Map<String, List<Item>> source) {
        Map<String, List<Item>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value.stream().distinct()
                .sorted(Comparator.comparing(item -> String.valueOf(ForgeRegistries.ITEMS.getKey(item)))).toList())));
        return Map.copyOf(result);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public record Resolution(Item item, String message) { }
    private record Hint(String id, String name, int position, int matchLength) { }
    private record LanguageData(Map<String, String> values, int bytesRead) { }
    private record Index(Map<String, Item> byId, Map<String, String> displayNames,
                         Map<String, List<Item>> byName, Map<String, List<Item>> byAlias) { }
}
