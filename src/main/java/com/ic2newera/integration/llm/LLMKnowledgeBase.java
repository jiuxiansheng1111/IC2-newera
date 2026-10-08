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
final class LLMKnowledgeBase {
    private static final String DOCUMENT = readDocument("ic2_create_guide_zh.md") + "\n\n"
            + readDocument("industrial_stage_guide_zh.md") + "\n\n"
            + readDocument("knowledge_zh.md");
    private static final int MAX_CONTEXT_CHARS = 6_000;

    private LLMKnowledgeBase() { }

    static String forQuestion(String question) {
        return forQuestion(question, MAX_CONTEXT_CHARS);
    }

    static String forQuestion(String question, int limit) {
        int contextLimit = Math.max(256, Math.min(limit, MAX_CONTEXT_CHARS));
        if (DOCUMENT.isBlank()) return "[随包工艺知识库不可用；请不要猜测配方或生产步骤。]";
        List<Section> sections = splitSections(DOCUMENT);
        if (sections.isEmpty()) return DOCUMENT.substring(0, Math.min(DOCUMENT.length(), contextLimit));

        Set<String> terms = terms(question == null ? "" : question);
        List<Section> ranked = new ArrayList<>(sections);
        ranked.forEach(section -> section.score = score(section, terms));
        ranked.sort(Comparator.comparingInt((Section section) -> section.score).reversed());
        if (ranked.get(0).score == 0) return "[随包指南未找到与本问题匹配的章节。]";

        StringBuilder context = new StringBuilder("【随包 Markdown 知识库相关章节】\n"
                + "本指南编写时的组合：IC2 Classic 2.1.3.4、Create 0.5.1.i、新纪元矿物/合金/装备；实际安装版本以本机资料为准。"
                + "EU 与转速互不转换，联动通过物品配方。精确配方查当前 JEI，结构查看 IC2 Wiki 与 Create Ponder。"
                + "化学实验和 IC2C Extras 已撤出。新增四矿、硅基电子与工业网络的数量和生产步骤以当前服务器批准工艺图为准；旧指南不能覆盖工艺裁决。以下是相关章节，未检索到的细节不能猜测。\n\n");
        for (Section section : ranked) {
            if (section.score == 0 && context.length() > 1_800) break;
            int remaining = contextLimit - context.length() - 1;
            if (remaining <= 0) break;
            if (section.text.length() > remaining) continue;
            context.append(section.text).append('\n');
        }
        return context.toString();
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

    private static Set<String> terms(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
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
        private int score;

        private Section(String text, String title) { this.text = text; this.title = title; }
    }
}
