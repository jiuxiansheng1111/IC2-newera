package com.ic2newera.robot;

import net.minecraft.network.chat.Component;

/** Stable character IDs shared by dolls, entity state, packaged personas and optional voice profiles. */
public enum RobotCharacter {
    TOMORI("tomori", "高松灯", "light_gray"), ANON("anon", "千早爱音", "pink"),
    SOYO("soyo", "长崎爽世", "brown"), TAKI("taki", "椎名立希", "black"),
    RANA("rana", "要乐奈", "white"), SAKIKO("sakiko", "丰川祥子", "light_blue"),
    UIKA("uika", "三角初华", "yellow"), UMIRI("umiri", "八幡海铃", "cyan");

    public final String id, chineseName, dye;
    RobotCharacter(String id, String chineseName, String dye) {
        this.id = id; this.chineseName = chineseName; this.dye = dye;
    }
    public Component title() { return Component.translatable("robot.ic2newera." + id); }
    public static RobotCharacter byId(String id) {
        for (RobotCharacter character : values()) if (character.id.equals(id)) return character;
        return TOMORI;
    }
}
