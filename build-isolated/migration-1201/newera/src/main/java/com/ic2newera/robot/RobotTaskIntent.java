package com.ic2newera.robot;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit owner commands for the task book. A question or a compound instruction cannot dispatch an order. */
public record RobotTaskIntent(RobotTaskBook.Action action, String source, String name, String error) {
    private static final Pattern SAVE = Pattern.compile(
            "^(?:把|将)?(上次(?:的)?(?:采集任务|烹饪任务|做饭任务|采集|烹饪|做饭|做菜|任务|订单)?|刚才的任务|上一单|上一次采集|上一次烹饪)"
            + "(?:记为|记作|记成|存为|存成|保存为|命名为)\\s*[:：]?\\s*(.*)$");
    private static final Pattern SAVE_ALIAS = Pattern.compile("^保存常用任务\\s*[:：]\\s*(.*)$");
    private static final Pattern RUN_NAMED = Pattern.compile("^(?:执行|运行|重做|再做一次)常用任务\\s*[:：]?\\s*(.*)$");
    private static final Pattern FORGET = Pattern.compile("^(?:忘记|删除|移除)常用任务\\s*[:：]?\\s*(.*)$");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} _-]*");
    private static final String NAMING_HELP = "请填 1～16 个字的任务名，例如“把上次采集记为备矿”。";

    public static String normalizedName(String value) {
        if (value == null || value.length() > 64) return "";
        String n = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (n.codePointCount(0, n.length()) > 16 || !NAME.matcher(n).matches()) return "";
        return n;
    }

    private static RobotTaskIntent command(RobotTaskBook.Action action, String source, String name) {
        return new RobotTaskIntent(action, source, name, "");
    }

    private static RobotTaskIntent usage(String message) {
        return new RobotTaskIntent(RobotTaskBook.Action.LIST, "", "", message);
    }

    private static String sourceOf(String text) {
        if (text.contains("采集")) return RobotTaskBook.COLLECT;
        if (text.contains("烹饪") || text.contains("做饭") || text.contains("做菜")) return RobotTaskBook.COOK;
        return RobotTaskBook.LAST;
    }

    private static final Pattern QUOTED_NAME = Pattern.compile("^(?:“([^”]*)”|「([^」]*)」|\"([^\"]*)\")$");

    private static String nickname(String raw) {
        String value = raw.strip();
        Matcher quote = QUOTED_NAME.matcher(value);
        if (quote.matches()) {
            for (int i = 1; i <= quote.groupCount(); i++) {
                if (quote.group(i) != null) return normalizedName(quote.group(i));
            }
        }
        return normalizedName(value);
    }

    public static RobotTaskIntent parse(String text) {
        if (text == null || text.length() > 180) return null;
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).strip();
        if (s.isEmpty()) return null;
        String key = s.toLowerCase(Locale.ROOT);
        if (key.matches("(?:常用任务(?:列表)?|(?:查看|列出|看看|打开)(?:我的)?常用任务|上次(?:采集|烹饪|做饭)(?:了什么|的是什么)|上次(?:的)?任务是什么|都记了哪些任务|saved tasks|task list|タスク一覧)[?？。.!！]?")) {
            return command(RobotTaskBook.Action.LIST, "", "");
        }
        // Questions about a saved command must not execute it, even with question marks removed.
        boolean question = s.endsWith("?") || s.endsWith("吗") || s.endsWith("吗。") || s.endsWith("么") || s.endsWith("么。");
        s = s.replaceFirst("[。.!！]$", "").stripTrailing();
        if (s.startsWith("请帮我")) s = s.substring(3).stripLeading();
        else if (s.startsWith("请") || s.startsWith("帮我")) s = s.substring(s.startsWith("请") ? 1 : 2).stripLeading();
        String compact = s.replace(" ", "").toLowerCase(Locale.ROOT);
        String replay = switch (compact) {
            case "照上次再采一单", "照上次再采一次", "按上次再采一单", "照上次采", "再采一单", "重复上次采集", "重做上次采集", "再做一次上次的采集", "repeatlastcollection" -> RobotTaskBook.COLLECT;
            case "照上次再做一餐", "照上次再做一次饭", "照上次再做一份菜", "再做一次上次的饭", "重复上次烹饪", "重做上次烹饪", "再做一次上次的菜", "repeatlastcooking" -> RobotTaskBook.COOK;
            case "照上次再来一单", "照上次再做一单", "和上次一样再来一单", "再来一单", "重复上次任务", "重做上次任务", "执行上次任务", "repeatlasttask" -> RobotTaskBook.LAST;
            default -> "";
        };
        if (!replay.isEmpty()) return command(RobotTaskBook.Action.RUN, replay, "");
        Matcher save = SAVE.matcher(s);
        Matcher alias = SAVE_ALIAS.matcher(s);
        if (save.matches() || alias.matches()) {
            boolean byType = save.matches();
            if (question) return usage("保存任务时请直接说“把上次采集记为备矿”。");
            String name = nickname(byType ? save.group(2) : alias.group(1));
            if (name.isEmpty()) return usage(NAMING_HELP);
            return command(RobotTaskBook.Action.SAVE, byType ? sourceOf(save.group(1)) : RobotTaskBook.LAST, name);
        }
        Matcher run = RUN_NAMED.matcher(s);
        Matcher forget = FORGET.matcher(s);
        if (!run.matches() && !forget.matches()) return null;
        boolean dispatch = run.matches();
        String example = dispatch ? "执行常用任务：备矿" : "忘记常用任务：备矿";
        if (question) return usage("要操作已保存的任务，请直接说“" + example + "”。");
        String name = nickname(dispatch ? run.group(1) : forget.group(1));
        if (name.isEmpty()) return usage("请带上一个完整的任务名，例如“" + example + "”。");
        return command(dispatch ? RobotTaskBook.Action.RUN : RobotTaskBook.Action.FORGET, name, "");
    }
}
