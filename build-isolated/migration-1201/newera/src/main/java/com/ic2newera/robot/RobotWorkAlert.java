package com.ic2newera.robot;

/** A current, server-side explanation of a blocked robot task. It is intentionally transient. */
public record RobotWorkAlert(String type, String reason, String suggestion, long firstSeenGameTime) {
    public static RobotWorkAlert create(String reason, long gameTime) {
        String safeReason = reason == null || reason.isBlank() ? "工作条件暂不可用" : reason;
        String type = classify(safeReason);
        String suggestion = switch (type) {
            case "缺料" -> "按原因补入所需原料、燃料、工具或配方容器，再让机器人继续工作。";
            case "输出满" -> "整理成品箱、周转箱、目的箱或机器人货物格，给下一份成品留出空间。";
            case "供电" -> "更换或充电机器人电池；若提示机器缺电，检查该设备的 EU 电源和电缆。";
            case "水洗" -> "检查 Create 水洗配方、风扇/水源和置物台中的物料，再等待真实加工。";
            case "堵路" -> "清除目标与机器人之间的障碍，并确认目标旁存在可达站位。";
            default -> "按原因检查目标、设备或任务现场；已投入物料和任务记录会保留。";
        };
        return new RobotWorkAlert(type, safeReason.length() > 160 ? safeReason.substring(0, 160) : safeReason,
                suggestion, gameTime);
    }

    public long elapsedSeconds(long gameTime) {
        return Math.max(0, gameTime - firstSeenGameTime) / 20;
    }

    public String summary(long gameTime) {
        return "类型：" + type + "；原因：" + reason + "；建议：" + suggestion
                + "；持续：" + elapsedSeconds(gameTime) + " 秒";
    }

    private static String classify(String reason) {
        if (containsAny(reason, "放不下", "无法容纳", "没有容纳", "货物槽已满", "没有空间", "输出箱", "成品箱",
                "周转箱满", "周转箱空间不足", "目的箱空间不足", "未领取成品", "输出满", "生产需留", "空货物格",
                "输出槽不可完整提取", "暂存空间不足")) return "输出满";
        // Missing supplies and fuel stay in the materials category even when their purpose is power.
        if (containsAny(reason, "缺少", "没有适用燃料", "没有可处理", "没有能", "没有可组成", "没有目标",
                "无原料", "无燃料", "需要餐具", "没有同时满足", "货物槽没有", "没有可拾取", "没有露出的",
                "原料不足", "进料箱缺少")) return "缺料";
        if (containsAny(reason, "电量不足", "无电", "供电", "机器到电", "EU", "电源")) return "供电";
        if (containsAny(reason, "水洗", "清洗", "Splashing", "washing", "净粉")) return "水洗";
        if (containsAny(reason, "通路", "堵", "可达", "不可到达", "站位", "遮挡", "被封闭", "寻路", "路径")) return "堵路";
        return "其他";
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
