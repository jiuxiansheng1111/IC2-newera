package com.ic2newera.robot;

import com.google.gson.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** A model may propose a bounded collection intent; only current user text and server rules authorize it. */
public final class RobotChatCommands {
    public record Intent(String itemQuery, int amount, int kind) { }
    public record Parsed(Intent intent, String clarification) { }
    private static final Pattern VERB = Pattern.compile("采集|收集|挖掘|挖|开采|拾取|捡|砍|伐木|寻找|找");
    private static final Pattern REQUEST = Pattern.compile("(?:帮我|给我|请|麻烦|去|开始).{0,16}(?:采集|收集|挖掘|挖|开采|拾取|捡|砍|伐木|寻找|找)|^(?:采集|收集|挖掘|挖|开采|拾取|捡|砍|伐木)");
    private static final Pattern NOT_ORDER = Pattern.compile("不要|不需要|不用|不采|不挖|别|取消|停止|怎么|如何|如果|假如|例如|比如|假设|举例|解释|教程|能采到吗|能挖到吗|需要什么|有什么用|分别|以及|同时");
    private static final String NUM = "[0-9零〇一二两三四五六七八九十百千万]+";
    private static final Pattern QUANTITY = Pattern.compile("(" + NUM + ")\\s*(组|堆|个|块|颗|份|件|根|棵|只|把|粒|片|条|台|瓶|桶)");
    private RobotChatCommands() { }

    public static boolean mayRequest(String text) {
        String value = normalize(text);
        return value.length() <= 1200 && VERB.matcher(value).find() && REQUEST.matcher(value).find()
                && !NOT_ORDER.matcher(value).find();
    }
    public static Parsed parse(JsonElement proposed, String userText) {
        if (!mayRequest(userText)) return new Parsed(null, "本轮不是明确的采集指令，未派发任务。");
        if (proposed == null || proposed.isJsonNull()) return new Parsed(null, "请明确要采集的物品和数量，例如：帮我采集20个钨矿。");
        try {
            JsonObject value = proposed.getAsJsonObject();
            if (!value.has("item_query") || !value.get("item_query").isJsonPrimitive()
                    || !value.get("item_query").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            String query = value.get("item_query").getAsString().trim();
            if (query.isBlank() || query.length() > 128 || !normalize(userText).contains(normalize(query)))
                return new Parsed(null, "物品名需要在你的本轮指令中明确出现，请补充准确物品名或注册ID。");
            if (!value.has("amount") || !value.get("amount").isJsonPrimitive()
                    || !value.get("amount").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            int amount = value.get("amount").getAsBigDecimal().intValueExact();
            if (amount < 1 || amount > 256) return new Parsed(null, "一次采集数量为1～256，请给出这个范围内的整数。");
            int explicit = explicitAmount(userText, query);
            if (explicit < 1 || explicit != amount)
                return new Parsed(null, "数量还不明确，请一次只指定一个物品和数量，例如：帮我采集20个钨矿。");
            String mode = value.has("mode") ? value.get("mode").getAsString() : "auto";
            if (!mode.equals("auto") && !mode.equals("pickup")) throw new IllegalArgumentException();
            // A pickup request never authorizes breaking blocks, even if the model returns the wrong mode.
            boolean onlyPickup = Pattern.compile("(?:只|仅).{0,4}(?:拾取|捡)").matcher(userText).find()
                    || Pattern.compile("拾取|捡").matcher(userText).find()
                        && !Pattern.compile("采集|开采|挖|砍|伐木").matcher(userText).find();
            int kind = mode.equals("pickup") || onlyPickup
                    ? RobotCollectionTask.PICKUP_ONLY : RobotCollectionTask.AUTO;
            return new Parsed(new Intent(query, amount, kind), "");
        } catch (RuntimeException invalid) {
            return new Parsed(null, "没有识别到明确的物品和整数数量，未派发任务。可说：帮我采集20个钨矿。");
        }
    }
    public static RobotCollectionTask.Result execute(ServerPlayer player, RobotEntity robot, boolean remote, Intent intent) {
        RobotChatItems.Resolution item = RobotChatItems.resolve(intent.itemQuery());
        if (item.item() == null) return new RobotCollectionTask.Result(false, item.message());
        String id = ForgeRegistries.ITEMS.getKey(item.item()).toString();
        RobotCollectionTask.Result result = RobotCollectionOrders.assign(player, robot, remote, true, id, intent.amount(), intent.kind());
        if (!result.accepted()) return result;
        return new RobotCollectionTask.Result(true, "已接收采集任务：" + RobotChatItems.displayName(item.item()) + "×" + intent.amount()
                + (intent.kind() == RobotCollectionTask.PICKUP_ONLY ? "，仅拾取现成物品。" : "，自动采矿/伐木并拾取。")
                + "收集足量后待命。");
    }
    private static int explicitAmount(String raw, String query) {
        String value = normalize(raw).replaceAll("[a-z0-9_.-]+:[a-z0-9_./-]+", "物品");
        if (Pattern.compile("[-−负]\\s*" + NUM + "|[0-9]+[.,][0-9]+|" + NUM + "点" + NUM
                + "|" + NUM + "\\s*(?:到|至|[-~～+*/])\\s*" + NUM).matcher(value).find()) return -1;
        List<Integer> quantities = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        Matcher units = QUANTITY.matcher(value);
        while (units.find()) {
            int number = number(units.group(1));
            if (number < 1 || number > 256) return -1;
            if (units.group(2).equals("组") || units.group(2).equals("堆")) number *= 64;
            quantities.add(number); spans.add(new int[]{units.start(), units.end()});
        }
        String target = normalize(query).contains(":") ? "物品" : normalize(query);
        Matcher bare = Pattern.compile("(" + NUM + ")\\s*" + Pattern.quote(target)).matcher(value);
        while (bare.find()) {
            int start = bare.start(), end = bare.end(1);
            if (spans.stream().noneMatch(span -> start < span[1] && end > span[0])) quantities.add(number(bare.group(1)));
        }
        return quantities.size() == 1 ? quantities.get(0) : -1;
    }
    private static int number(String raw) {
        if (raw.matches("[0-9]+")) { try { return Integer.parseInt(raw); } catch (NumberFormatException invalid) { return -1; } }
        int result = 0, digit = 0;
        for (char ch : raw.toCharArray()) {
            int d = "零一二三四五六七八九".indexOf(ch);
            if (ch == '两') d = 2;
            if (ch == '〇') d = 0;
            if (d >= 0) { digit = d; continue; }
            int unit = ch == '十' ? 10 : ch == '百' ? 100 : -1;
            if (unit < 0) return -1;
            result += (digit == 0 ? 1 : digit) * unit; digit = 0;
        }
        return result + digit;
    }
    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
