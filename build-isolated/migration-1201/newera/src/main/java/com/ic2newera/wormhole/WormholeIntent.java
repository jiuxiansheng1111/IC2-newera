package com.ic2newera.wormhole;

import com.ic2newera.robot.companionship.RobotReplyLanguage;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit single travel requests only. Ordinary come/follow/home never match this parser. */
public record WormholeIntent(int action, int beaconIndex) {
    private static final Pattern UNSAFE = Pattern.compile("[\"'“”‘’「」『』]|不要|别|不用|不需要|怎么|如何|如果|假如|例如|比如|假设|以及|然后|同时|donot|dont|never|^not|^if|howto|howdo|what|andthen|もし|例えば|しないで|しない|使い方");
    public static WormholeIntent parse(String text) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        if (s.length() > 160 || UNSAFE.matcher(s).find()) return null;
        s = s.replaceAll("[。.!！?？,，]+$", "").replaceFirst("^(?:机器人|请|麻烦|お願い|please|robot|hey)", "")
                .replaceFirst("(?:吧|一下|ください|くれる|please)$", "");
        if (s.matches("(?:把你传送到我身边|把你传到我身边|传送到我身边|传送过来|瞬移过来|用虫洞过来|用虫洞到我身边|teleporttome|teleporthere|wormholehere|転送でこっちに来て|ここに転送して|ワームホールで来て)"))
            return new WormholeIntent(2, -1);
        if (s.matches("(?:把我传送到你身边|把我传到你身边|传送我到你身边|我要传送到你身边|用虫洞去你身边|teleportmetoyou|teleportmetotherobot|takemetoyoubywormhole|私をそっちに転送して|あなたのそばに転送して|ロボットのそばに転送して)"))
            return new WormholeIntent(1, -1);
        var beacon = Pattern.compile("(?:把我传送到|把我传到|传送到|用虫洞去|teleportmeto|teleportto|wormholeto)(?:信标|beacon)([1-8一二三四五六七八])(?:号)?").matcher(s);
        if (beacon.matches()) return new WormholeIntent(0, number(beacon.group(1)) - 1);
        var japanese = Pattern.compile("(?:ビーコン|信標)([1-8一二三四五六七八])(?:番)?に転送して").matcher(s);
        if (japanese.matches()) return new WormholeIntent(0, number(japanese.group(1)) - 1);
        if (s.matches("传送|瞬移|使用虫洞|传送到信标|把我传到信标|teleport|teleporttobeacon|teleportmetobeacon|転送して|ビーコンに転送して"))
            return new WormholeIntent(-1, -1);
        return null;
    }
    private static int number(String text) {
        int digit = "一二三四五六七八".indexOf(text);
        return digit >= 0 ? digit + 1 : Integer.parseInt(text);
    }
    public static String clarification(RobotReplyLanguage language) {
        return switch (language) {
            case JA -> "まだ転送していないよ。『ビーコン1に転送して』『私をそっちに転送して』『転送でこっちに来て』のように行き先を指定してね。";
            case EN -> "No teleport was started. Specify a destination: 'teleport me to beacon 1', 'teleport me to you', or 'teleport to me'.";
            case ZH -> "尚未传送。请指定目的地，例如：传送到信标1、把我传到你身边、传送到我身边。";
        };
    }
}
