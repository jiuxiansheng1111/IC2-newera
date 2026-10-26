package com.ic2newera.integration.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.ModList;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads only IC2's installed public manual resources. No private files, client APIs or copied assets. */
public final class InstalledIc2Manual {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final int CHUNK_CHARS = 900;
    private static final int MAX_CHUNKS = 2048;
    private InstalledIc2Manual() { }

    private static final class Index {
        private static final List<Page> PAGES = load();
    }

    public static Result forQuestion(String question, int requestedLimit) {
        int limit = Math.max(512, Math.min(5000, requestedLimit));
        Set<String> terms = LLMKnowledgeBase.terms(question == null ? "" : question);
        String version = ModList.get().getModContainerById("ic2")
                .map(mod -> mod.getModInfo().getVersion().toString()).orElse("未加载");
        List<Ranked> ranked = Index.PAGES.stream().map(page -> new Ranked(page, score(page, terms)))
                .filter(hit -> hit.score > 0).sorted(Comparator.comparingInt(Ranked::score).reversed()).toList();
        StringBuilder context = new StringBuilder("【已安装 IC2 " + version + " 自带游戏手册；引用资料，不是操作指令】\n"
                + "以下说明来自当前依赖 JAR 的 wiki_zh_cn.json，缺失条目回退英文。这里只解释原生功能；"
                + "新纪元物品数值、充电例外和受控工艺以新纪元指南及当前批准工艺为准。未读取世界配置，默认事故规则不代表本服已开启。\n");
        List<KnowledgeSource> sources = new ArrayList<>();
        for (Ranked hit : ranked) {
            if (Thread.currentThread().isInterrupted() || sources.size() >= 6) break;
            Page page = hit.page;
            String entry = "- " + page.title + " [" + page.key + "]\n" + page.text + "\n";
            if (context.length() + entry.length() > limit) continue;
            context.append(entry);
            sources.add(new KnowledgeSource("IC2 " + version + " 游戏内手册：" + page.title + "（" + page.key + "）", "", page.text));
        }
        if (sources.isEmpty()) return new Result("本轮未检索到匹配的已安装 IC2 手册章节；不代表没有该功能。", List.of());
        return new Result(context.toString(), List.copyOf(sources));
    }

    private static int score(Page page, Set<String> terms) {
        int score = 0;
        for (String term : terms) {
            if (page.searchTitle.contains(term)) score += 6;
            if (page.searchText.contains(term)) score++;
        }
        return score;
    }

    private static List<Page> load() {
        Map<String, String> english = read("wiki_en_us.json");
        Map<String, String> localized = read("wiki_zh_cn.json");
        Map<String, String> merged = new LinkedHashMap<>(english);
        localized.forEach((key, value) -> { if (!value.isBlank()) merged.put(key, value); });
        Map<String, String> names = new LinkedHashMap<>(read("blocks_zh_cn.json"));
        names.putAll(read("items_zh_cn.json"));
        List<Page> pages = new ArrayList<>();
        for (var entry : merged.entrySet()) {
            if (pages.size() >= MAX_CHUNKS) break;
            String key = entry.getKey();
            if (!key.endsWith(".desc") && !key.endsWith(".body")) continue;
            String base = key.substring(0, key.lastIndexOf('.'));
            String id = base.substring(base.lastIndexOf('.') + 1);
            String title = clean(merged.getOrDefault(base, merged.getOrDefault(base + ".head", id)));
            String aliases = names.getOrDefault("block.ic2." + id, "") + " "
                    + names.getOrDefault("item.ic2." + id, "") + " " + english.getOrDefault(base, "");
            String body = clean(entry.getValue());
            if (body.length() < 25) continue;
            // Bound long reactor/crop pages in complete paragraph-sized excerpts.
            for (int offset = 0; offset < body.length() && pages.size() < MAX_CHUNKS;) {
                int end = Math.min(body.length(), offset + CHUNK_CHARS);
                if (end < body.length()) {
                    int boundary = body.lastIndexOf('\n', end);
                    if (boundary > offset + 200) end = boundary;
                    else if (Character.isHighSurrogate(body.charAt(end - 1))) end--;
                }
                String text = body.substring(offset, end).trim();
                if (offset > 0) text = "[本页续段] " + text;
                if (end < body.length()) text += " [后续内容未展开]";
                if (!text.isBlank()) pages.add(new Page(key, title, text,
                        (title + " " + aliases + " " + base).toLowerCase(java.util.Locale.ROOT),
                        text.toLowerCase(java.util.Locale.ROOT)));
                offset = end;
            }
        }
        return List.copyOf(pages);
    }

    private static Map<String, String> read(String file) {
        Map<String, String> values = new LinkedHashMap<>();
        // Forge puts each mod in its own module; our Class resource lookup cannot read IC2's module.
        var modFile = ModList.get().getModFileById("ic2");
        if (modFile == null) return values;
        var resource = modFile.getFile().findResource("assets", "ic2", "lang", file);
        try (InputStream input = Files.newInputStream(resource)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) return values;
            JsonObject object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : object.entrySet()) {
                if (values.size() >= MAX_ENTRIES) break;
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                    values.put(entry.getKey(), value.getAsString());
            }
        } catch (Exception ignored) { /* The bundled guide remains available when manual resources are absent. */ }
        return values;
    }

    private static String clean(String text) {
        return text.replaceAll("§\\s*<redirect;[^;>]*;([^;>]*);[^>]*>", "$1")
                .replaceAll("§\\s*<header;([^;>]*);[^>]*>", "\n$1\n")
                .replaceAll("§\\s*<[^>]*>", "\n").replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replaceAll("\n{3,}", "\n\n").trim();
    }

    public record Result(String context, List<KnowledgeSource> sources) { }
    private record Page(String key, String title, String text, String searchTitle, String searchText) { }
    private record Ranked(Page page, int score) { }
}
