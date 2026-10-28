package com.ic2newera.integration.llm;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the bundled Chinese Markdown and selects the most relevant process notes locally. */
public final class LLMKnowledgeBase {
    // The isolated 1.20.1 runtime must not retrieve Classic 1.19.2 machine instructions.
    private static final String DOCUMENT = readDocument("robot_capabilities_zh.md") + "\n\n"
            + readDocument("migration_runtime_zh.md") + "\n\n" + readDocument("runtime_enhancements_zh.md")
            + "\n\n" + readDocument("wormhole_and_orders_zh.md")
            + "\n\n" + readDocument("mechanical_pets_zh.md") + "\n\n" + readDocument("blueprint_building_zh.md");
    private static final List<Section> SECTIONS = List.copyOf(splitSections(DOCUMENT));
    private static final int MAX_CONTEXT_CHARS = 6_000;

    private LLMKnowledgeBase() { }

    public static String forQuestion(String question) {
        return forQuestion(question, MAX_CONTEXT_CHARS);
    }

    public static String forQuestion(String question, int limit) {
        int contextLimit = Math.max(256, Math.min(limit, MAX_CONTEXT_CHARS));
        if (DOCUMENT.isBlank()) return "[随包工艺知识库不可用；请不要猜测配方或生产步骤。]";
        List<Section> sections = SECTIONS;
        if (sections.isEmpty()) return DOCUMENT.substring(0, Math.min(DOCUMENT.length(), contextLimit));

        Set<String> terms = terms(question == null ? "" : question);
        List<Section> ranked = sections.stream().filter(section -> score(section, terms) > 0)
                .sorted(Comparator.comparingInt((Section section) -> score(section, terms)).reversed()).toList();
        if (ranked.isEmpty()) return "[随包指南未找到与本问题匹配的章节。]";

        StringBuilder context = new StringBuilder("【随包 Markdown 知识库相关章节】\n"
                + "适用 Minecraft 1.20.1、IC2 Refactored 2.10.33-ex120、Create 6.0.8 与新纪元隔离迁移版。"
                + "EU 与转速各自独立，只有新纪元 EU 电动机提供单向 EU→转轴动力。精确配方查当前 JEI，结构查看目标版本实际界面、已安装资料与 Create Ponder。"
                + "化学实验和 IC2C Extras 已撤出。受控矿物、硅基电子与工业网络的数量和生产步骤以当前服务器批准工艺图为准；旧指南不能覆盖工艺裁决。普通矿物阶段为含杂质粉、水洗中间物、最终净粉与锭；Create水洗后用IC2离心机取得较高净粉产量，含杂质粉也可直离取得较低产量，具体数量和机器由服务器同步工艺图决定。铅纳入普通路线；铀保留IC2原生核燃料及副产物路线。1.20.1的合金与高级熔炼使用新纪元兼容炉，材料与设备以当前注册和批准图为准，不能混用Classic旧ID。以下是相关章节，未检索到的细节不能猜测。\n\n");
        for (Section section : ranked) {
            int remaining = contextLimit - context.length() - 1;
            if (remaining <= 0) break;
            if (section.text.length() > remaining) continue;
            context.append(section.text).append('\n');
        }
        return context.toString();
    }

    /** Follow-up questions retain one recent player topic, never model-generated claims. */
    public static String queryWithContext(List<String> userQuestions) {
        if (userQuestions.isEmpty()) return "";
        String latest = userQuestions.get(userQuestions.size() - 1);
        if (latest == null) latest = "";
        latest = latest.substring(0, Math.min(2000, latest.length()));
        if (userQuestions.size() > 1 && (latest.length() < 16 || latest.matches("(?s).*(这个|那个|这些|它|还有|那么|那能).*"))) {
            String previous = userQuestions.get(userQuestions.size() - 2);
            if (previous != null) latest += "\n相关上一问：" + previous.substring(0, Math.min(500, previous.length()));
        }
        return latest;
    }

    private static String readDocument(String fileName) {
        try (InputStream input = LLMKnowledgeBase.class.getResourceAsStream(
                "/data/ic2newera/ai/" + fileName)) {
            if (input == null) return "";
            byte[] data = input.readNBytes(512 * 1024);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<Section> splitSections(String document) {
        Matcher headings = Pattern.compile("(?m)^#{2,3} .+$").matcher(document);
        List<Section> sections = new ArrayList<>();
        int start = -1;
        String parent = "";
        String heading = "";
        String contextHeading = "";
        while (headings.find()) {
            if (start >= 0) sections.add(new Section(contextHeading
                    + document.substring(start, headings.start()).trim(), heading));
            start = headings.start();
            heading = headings.group();
            if (heading.startsWith("## ")) {
                parent = heading;
                contextHeading = "";
            } else contextHeading = parent.isEmpty() ? "" : parent + "\n";
        }
        if (start >= 0) sections.add(new Section(contextHeading + document.substring(start).trim(), heading));
        else sections.add(new Section(document.trim(), ""));
        return sections;
    }

    static Set<String> terms(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replace("msfu", "mfsu")
                .replace("充能", "充电").replace("充电站", "充电台 电池站")
                .replace("炸机", "过压 爆炸").replace("炸了", "过压 爆炸")
                .replace("烧线", "电缆 熔断").replace("兰波顿", "兰波顿 lapotronic")
                .replace("电钻", "电钻 drill").replace("电锯", "电锯 chainsaw")
                .replace("ワームホール", "虫洞 wormhole").replace("ビーコン", "信标 beacon")
                .replace("ついて", "跟随 follow").replace("帰って", "回家 home").replace("音声", "语音 speech");
        Set<String> terms = new LinkedHashSet<>();
        Matcher latin = Pattern.compile("[a-z0-9_:.+-]{2,}").matcher(normalized);
        while (latin.find()) terms.add(latin.group());
        for (int index = 0; index + 1 < normalized.length(); index++) {
            char first = normalized.charAt(index);
            char second = normalized.charAt(index + 1);
            if (Character.isIdeographic(first) && Character.isIdeographic(second)) {
                terms.add(normalized.substring(index, index + 2));
            }
        }
        return terms;
    }

    private static int score(Section section, Set<String> terms) {
        String normalized = section.text.toLowerCase(Locale.ROOT);
        String title = section.title.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (normalized.contains(term)) score++;
            if (title.contains(term)) score += 5;
        }
        return score;
    }

    private static final class Section {
        private final String text;
        private final String title;
        private Section(String text, String title) { this.text = text; this.title = title; }
    }
}
