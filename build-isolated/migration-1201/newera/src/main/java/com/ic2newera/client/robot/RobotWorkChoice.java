package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotEntity;

/** The same compact work picker powers both local and phone controls. */
enum RobotWorkChoice {
    LOGGING("伐木", RobotEntity.LOGGING, 5, 5, "货物槽放斧头；在工作位置附近处理原木并收集掉落物。"),
    MINING("采矿", RobotEntity.MINING, 6, 6, "货物槽放镐或采矿钻头；指定矿物与数量请使用指定采集。"),
    COOKING("烹饪", RobotEntity.COOKING, 10, 13, "附近放加热的农夫乐事料理锅或森罗汤锅；备好食材、餐具，森罗另需汤底和锅盖。"),
    SMELTING("烧矿", RobotEntity.SMELTING, 12, 15, "附近放空的原版熔炉或原版高炉，货物槽放原料和煤/木炭等无容器燃料；自动投料、补燃料并收成品。"),
    FARMING("种地", RobotEntity.FARMING, 13, 16, "货物槽备2份以上小麦种子、胡萝卜、马铃薯或甜菜种子；种植、成熟收获和补种，带锄头可在水边开垦。");

    final String title, hint;
    final int mode, localAction, phoneAction;
    RobotWorkChoice(String title, int mode, int localAction, int phoneAction, String hint) {
        this.title = title; this.mode = mode; this.localAction = localAction; this.phoneAction = phoneAction; this.hint = hint;
    }
    RobotWorkChoice next() { return values()[(ordinal() + 1) % values().length]; }
    static RobotWorkChoice fromMode(int mode) {
        for (RobotWorkChoice choice : values()) if (choice.mode == mode) return choice;
        return LOGGING;
    }
    static RobotWorkChoice fromStatus(String status) {
        for (RobotWorkChoice choice : values()) if (status != null && status.startsWith(choice.title)) return choice;
        return LOGGING;
    }
}
