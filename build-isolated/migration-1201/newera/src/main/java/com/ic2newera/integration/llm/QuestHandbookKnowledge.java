package com.ic2newera.integration.llm;

import com.google.gson.Gson;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Retrieves from the handbook shipped with this particular instance. */
final class QuestHandbookKnowledge {
    private static final Pattern INTENT = Pattern.compile("任务|委托|工单|任务书|手册|章节|奖励|领奖|领什么|领啥|扣掉|扣除|会扣|上交|提交|交付|交货|前置|下一步|下一单|解锁|验收|ftb", Pattern.CASE_INSENSITIVE);
    private static final String OVERVIEW = "打开 FTB 任务书，选章节和节点查看目标、前置与奖励。物品图标区分背包检测和消耗提交；奖励的团队设置逐项生效。公告栏是另一套半小时刷新的个人委托。";
    private static List<Entry> entries = List.of();
    private static volatile long nextCheck;
    private static long stamp = -2;

    private record Book(int format, int count, List<Entry> quests) {}
    private record Entry(String id, String chapter, String title, boolean own, List<String> tasks,
                         List<String> rewards, List<String> previous, List<String> next, int minimum, String requirement, String hint) {}
    private record Match(Entry entry, int score) {}

    private QuestHandbookKnowledge() {}

    private static synchronized List<Entry> catalogue() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return entries;
        nextCheck = now + 5000;
        try {
            Path path = configDirectory().resolve("ic2newera/quest_knowledge.json");
            long modified = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
            if (modified == stamp) return entries;
            if (modified < 0 || Files.size(path) > 12L * 1024 * 1024) {
                entries = List.of();
            } else {
                try (Reader input = Files.newBufferedReader(path)) {
                    Book book = new Gson().fromJson(input, Book.class);
                    if (book == null || book.format != 1 || book.quests == null || book.count != book.quests.size()
                            || book.count > 24000) throw new IOException("Invalid handbook index");
                    entries = book.quests.stream().filter(e -> e != null && e.id != null && e.title != null
                            && e.chapter != null && e.tasks != null && e.rewards != null
                            && e.previous != null && e.next != null && e.hint != null).toList();
                }
            }
            stamp = modified;
        } catch (IOException | RuntimeException ignored) {
            entries = List.of();
        }
        return entries;
    }

    private static Path configDirectory() {
        try {
            Path configured = FMLPaths.CONFIGDIR.get();
            if (configured != null) return configured;
        } catch (LinkageError | IllegalStateException ignored) {
            // The standalone media assistant also runs outside the Forge bootstrap.
        }
        return Path.of("config");
    }

    static String forQuestion(String question, int budget) {
        if (question == null || budget < 150) return "";
        String query = question.toLowerCase(Locale.ROOT).replace("匠心", "匠魂");
        boolean intent = INTENT.matcher(query).find();
        // Random board contracts must be answered from their own rotation rules.
        if ((query.contains("公告栏") || query.contains("每日委托")) && !query.contains("任务书") && !query.contains("ftb")) return "";
        List<Entry> all = catalogue();
        if (all.isEmpty()) return "";
        Set<String> terms = LLMKnowledgeBase.terms(query);
        List<Match> matches = new ArrayList<>();
        for (Entry entry : all) {
            String title = entry.title.toLowerCase(Locale.ROOT);
            boolean named = query.contains(entry.id.toLowerCase(Locale.ROOT)) || title.length() >= 3 && query.contains(title);
            if (!intent && !named) continue;
            String targets = String.join(" ", entry.tasks).toLowerCase(Locale.ROOT);
            int score = named ? 150 : 0;
            for (String term : terms) {
                if (term.length() < 2 || INTENT.matcher(term).matches()) continue;
                if (title.contains(term)) score += 9;
                if (entry.chapter.toLowerCase(Locale.ROOT).contains(term)) score += 4;
                if (targets.contains(term)) score += term.contains(":") ? 45 : 3;
            }
            if (score >= 6) matches.add(new Match(entry, score + (entry.own ? 2 : 0)));
        }
        if (!intent && matches.isEmpty()) return "";
        matches.sort(Comparator.comparingInt(Match::score).reversed().thenComparing(m -> m.entry.id));
        StringBuilder out = new StringBuilder("【当前整合包的 FTB 任务书，共 " + all.size() + " 个节点】\n");
        if (matches.isEmpty()) return out + OVERVIEW + " 未确定具体节点，请问玩家任务名称或所需物品。\n";
        for (Match match : matches.subList(0, Math.min(4, matches.size()))) {
            Entry e = match.entry;
            String body = e.chapter + " / " + e.title + "（" + e.id + "）\n条件：" + String.join("；", e.tasks)
                    + "\n奖励：" + (e.rewards.isEmpty() ? "该节点没有独立奖励" : String.join("；", e.rewards))
                    + "\n前置：" + prerequisites(e)
                    + (e.minimum > 0 ? "（至少 " + e.minimum + " 项）" : "")
                    + "\n后续：" + (e.next.isEmpty() ? "本节点无直接后继" : String.join("、", e.next))
                    + (e.hint.isBlank() ? "" : "\n操作：" + e.hint) + "\n";
            int left = budget - out.length();
            if (left < 100) break;
            if (body.length() <= left) out.append(body);
            else { out.append(body, 0, left - 2).append("…\n"); break; }
        }
        return out.toString();
    }

    private static String prerequisites(Entry entry) {
        if (entry.previous.isEmpty()) return "无";
        String rule = switch (entry.requirement == null ? "" : entry.requirement) {
            case "one_started" -> "开始任一项";
            case "all_started" -> "全部开始";
            case "one_completed" -> "完成任一项";
            default -> entry.minimum > 0 ? "达到指定数量" : "全部完成";
        };
        return rule + " · " + String.join("、", entry.previous);
    }
}
