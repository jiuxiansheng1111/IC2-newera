package com.ic2newera.robot;

import java.util.Locale;

/** Server-owned transient facial expressions shared with the client renderer. */
public enum RobotExpression {
    NEUTRAL(0, 0),
    SOFT_SMILE(1, 80),
    HAPPY(2, 100),
    CURIOUS(3, 80),
    SERIOUS(4, 120),
    WORRIED(5, 120);

    private final int id;
    private final int durationTicks;

    RobotExpression(int id, int durationTicks) {
        this.id = id;
        this.durationTicks = durationTicks;
    }

    public int id() { return id; }
    public int duration() { return durationTicks; }
    public int durationTicks() { return durationTicks; }

    public static RobotExpression byId(int id) {
        for (RobotExpression expression : values()) if (expression.id == id) return expression;
        return NEUTRAL;
    }

    /** Lightweight wording cues only; ambiguous or ordinary speech keeps a gentle smile. */
    public static RobotExpression fromSpeech(String speech) {
        String text = speech == null ? "" : speech.toLowerCase(Locale.ROOT);
        if (containsAny(text, "担心", "害怕", "不安", "对不起", "抱歉", "受伤", "危险", "心配", "怖い", "不安", "ごめん", "申し訳", "けが", "危ない", "worried", "afraid", "sorry", "hurt", "not happy", "danger"))
            return WORRIED;
        if (containsAny(text, "必须", "务必", "警告", "紧急", "停止", "马上", "注意", "禁止", "危険", "止めて", "逃げて", "気をつけて", "must", "warning", "urgent", "careful", "stop "))
            return SERIOUS;
        if (containsAny(text, "开心", "高兴", "太好了", "成功", "完成了", "谢谢", "感谢", "喜欢", "嬉しい", "うれしい", "やった", "よかった", "ありがとう", "大好き", "happy", "glad", "thank", "great", "well done"))
            return HAPPY;
        if (containsAny(text, "为什么", "怎么回事", "如何", "吗", "呢？", "どうして", "なぜ", "でしょうか", "かな", "？", "?"))
            return CURIOUS;
        return SOFT_SMILE;
    }

    private static boolean containsAny(String text, String... cues) {
        for (String cue : cues) if (text.contains(cue)) return true;
        return false;
    }
}
