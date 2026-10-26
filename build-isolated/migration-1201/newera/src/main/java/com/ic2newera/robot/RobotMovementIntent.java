package com.ic2newera.robot;

import java.text.Normalizer;
import java.util.Locale;

/** Exact whitelist for affirmative movement requests; questions only execute when they explicitly ask to come. */
public enum RobotMovementIntent {
    NONE, COME, FOLLOW, HOME, SET_HOME, WAIT, STOP, RESUME, STATUS;
    private RobotMovementIntent() { }

    public static RobotMovementIntent parse(String text) {
        String spaced = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[。！!？?,，、]+", " ").trim().replaceAll("\\s+", " ");
        if (spaced.isBlank() || spaced.length() > 120 || spaced.matches(".*[\\\"'“”‘’「」『』].*")) return NONE;
        String s = spaced.replace(" ", "").replaceFirst("^(?:机器人|ロボット|robot)", "");
        // These two short negatives are explicit stop/wait commands and must precede general negation checks.
        if (matches(s, "别动", "不要动", "待在原地", "等着", "等一下", "待命", "原地等", "原地待命", "待在这里", "留在原地",
                "ここで待って", "待機して", "そこで待って", "ここにいて", "wait", "waitthere", "stay", "stayhere", "holdposition")) return WAIT;
        if (matches(s, "停下", "停止工作", "暂停工作", "暂停任务", "先停一下", "停止跟随", "别跟着", "别跟着我", "不要跟着", "不要跟着我", "跟随停止", "停止移动", "停止",
                "止まって", "作業を止めて", "作業を一時停止して", "ついてこないで", "追従停止", "移動停止", "stop", "stopwork", "pausework", "pausetask", "stopfollowing", "stopmoving", "standdown")) return STOP;

        if (matches(s, "继续工作", "请继续工作", "恢复工作", "请恢复工作", "继续任务", "恢复任务", "继续之前的工作", "继续之前的任务",
                "作業を再開して", "作業を再開してください", "仕事を続けて", "タスクを再開して", "タスクを再開してください", "作業を続けて", "前の作業を続けて",
                "resumework", "pleaseresumework", "resumetask", "pleasecontinuetask", "continuework", "continuetask", "continueworking", "resumeoldtask")) return RESUME;
        if (matches(s, "状态", "当前状态", "工作状态", "查看状态", "回家状态", "家状态", "现在怎么样", "你在干什么", "在做什么", "现在在干什么",
                "ステータス", "状態", "現在の状態", "作業状況", "ホームの状態", "家の状態",
                "status", "workstatus", "checkstatus", "showstatus", "homestatus", "checkhomestatus", "whereishome", "whereismyhome", "howareyoudoing")) return STATUS;

        // A specific affirmative invitation overrides the generic “can you” question guard.
        if (matches(s, "能过来吗", "能过来一下吗", "可以过来吗", "可以过来一下吗", "你能过来一下吗", "你可以过来一下吗", "请过来", "麻烦过来", "麻烦过来一下", "来这里", "到这里来", "请到这里来", "到我这里", "到我这里来", "请到我这里来", "来我这里", "来我身边", "到我身边来", "请到我身边来", "请来我身边", "到我旁边来", "来我旁边", "过来", "过来一下",
                "こっちに来て", "こちらに来て", "ここに来て", "ここへ来て", "私のそばに来て", "私のところに来て", "こっちに来てくれる", "こちらに来てくれる", "来てもらえる", "来てください", "こちらに来てください", "ここに来てください", "私のそばに来てください", "私のところに来てください",
                "come", "comeover", "comehere", "cometome", "cometomyside", "pleasecome", "pleasecomeover", "pleasecomehere", "pleasecometome", "pleasecometomyside", "canyoucome", "canyoucomeover", "canyoucomehere", "canyoupleasecomehere", "canyoupleasecomeover", "comehereplease", "comeoverplease",
                "couldyoucome", "couldyoucomeover", "couldyoucomehere", "wouldyoucome", "wouldyoucomeover", "wouldyoucomehere")) return COME;

        if (matches(s, "回家", "回到家", "回到家里", "回到家去", "请回家", "回家吧", "回家待命", "回家休息", "おうちに帰って", "家に帰って", "家へ帰って", "家に戻って", "家に帰ってください", "家に戻ってください", "ホームに戻って", "帰宅して", "帰宅してください", "gohome", "pleasegohome", "returnhome", "pleasereturnhome", "headhome", "gobackhome", "pleasegobackhome")) return HOME;
        if (matches(s, "把家设在这里", "把这里设为家", "设为家", "设置家", "这里作为家", "ここを家にして", "ここをホームにして", "ここを家に設定", "sethomehere", "makethishome", "setthisashome")) return SET_HOME;
        if (matches(s, "跟随", "跟随我", "跟着我", "请跟着我", "请跟随我", "跟我来", "请跟我来", "跟过来", "跟在我身边", "跟在我后面", "跟在我身后", "跟着走", "ついてきて", "ついてきてください", "私についてきて", "私についてきてください", "一緒に来て", "一緒についてきて", "後をついてきて", "後についてきて", "follow", "followme", "pleasefollowme", "followmeplease", "comewithme", "pleasecomewithme", "comealong", "pleasecomealong", "staywithme")) return FOLLOW;

        // Ability, hypothetical, cited, and negative language is never converted into an action.
        if (spaced.matches(".*(?:\\b(?:can|could|would|will|should|if|how|why|whether)\\b|\\bdo not\\b|\\bdon't\\b|teleport|transfer|能否|能不能|可以吗|传送|瞬移|怎么|如何|如果|假如|例如|比如|假设|引用|不要|不|别|できる|しないで|来ないで|もし|例えば).*")) return NONE;
        return NONE;
    }

    private static boolean matches(String actual, String... whitelist) {
        for (String item : whitelist) if (actual.equals(item)) return true;
        return false;
    }
}
