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
    public enum Work { MINING, LOGGING, COOKING, SMELTING, FARMING, CANCEL_COOKING, FOLLOW, STAY }
    public record Parsed(Intent intent, Work work, String clarification) {
        public Parsed(Intent intent, String clarification) { this(intent, null, clarification); }
        public static Parsed work(Work work) { return new Parsed(null, work, ""); }
    }
    private static final Pattern VERB = Pattern.compile("采集|收集|获取|挖掘|挖|开采|拾取|捡|砍|伐木|寻找|找|collect|gather|mine|chop|pick\s?up|採集|集め|掘|採掘|拾って|伐採");
    private static final Pattern REQUEST_START = Pattern.compile("^(?:(?:帮我|给我|请|麻烦|please|couldyou|ロボット|ロボットさん|お願い|私に)?(?:去|开始|please)?)(?:采集|收集|获取|挖掘|挖|开采|拾取|捡|砍|伐木|寻找|找|collect|gather|mine|chop|pick\s?up|採集|集め|掘|採掘|拾って|伐採)");
    private static final Pattern NOT_ORDER = Pattern.compile("不要|不需要|不用|不采|不挖|别|取消|停止|怎么|如何|如果|假如|要是|万一|例如|比如|假设|举例|解释|教程|能采到吗|能挖到吗|需要什么|有什么用|分别|以及|同时|然后|并且|再|还有|和|don't|do not|not |if |how |why |can i |can you |whether|for example|採集しない|しないで|もし|例えば|どうやって|何が必要");
    private static final Pattern ENGLISH_UNSAFE_CONTEXT = Pattern.compile("(?<![\\p{L}\\p{N}])(?:don't|do\\s+not|not|if|how|why|can\\s+(?:i|you)|whether|for\\s+example)(?![\\p{L}\\p{N}])");
    private static final Pattern QUOTED_OR_QUESTION = Pattern.compile("[\"'“”‘’「」『』?？]|吗|呢|是否|能否|能不能|可不可以|行不行|ですか|ますか|できる|かな|？|\\?");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[。！!；;，,、]+$");
    private static final String NUM = "[0-9零〇一二两三四五六七八九十百千万]+";
    private static final Pattern COOK_ORDER = Pattern.compile("^(?:机器人)?(?:帮我|给我|请|麻烦)?(?:烹饪|做|煮)(" + NUM + ")(?:份|个|碗|盘)?(.+?)(?:吧)?$");
    private static final Pattern COOK_ORDER_JA_TRAILING = Pattern.compile("^(.+?)を([0-9]+)(?:人前|個|皿|杯|つ)?(?:作って|作ってください|料理して|調理して)$");
    private static final Pattern COLLECTION_ORDER_JA_TRAILING = Pattern.compile("^.{1,128}を[0-9]+(?:個|つ|本|枚|箱|組|スタック)?(?:集めて|集めてください|採集して|採集してください|拾って|拾ってください)$");
    private static final Pattern QUANTITY = Pattern.compile("(" + NUM + ")\\s*(组|堆|个|块|颗|份|件|根|棵|只|把|粒|片|条|台|瓶|桶|items?|blocks?|pieces?|stacks?|units?|個|つ|本|枚|箱|点)");
    private RobotChatCommands() { }

    /** Parse a single direct food-and-quantity order; questions and quoted examples never execute. */
    public static Intent parseCooking(String text) {
        String normalized = normalize(text);
        if (normalized.length() > 160 || unsafeOrderContext(text, normalized) || normalized.contains("\"")
                || normalized.contains("「") || normalized.contains("『") || normalized.matches(".*[?？].*")) return null;
        Matcher match = COOK_ORDER.matcher(TRAILING_PUNCTUATION.matcher(normalized).replaceFirst(""));
        String item; int quantity;
        if (match.matches()) { quantity = number(match.group(1)); item = match.group(2); }
        else {
            Matcher ja = Pattern.compile("^(?:ロボット)?(?:料理して|作って|焼いて|煮て)([0-9]+)(?:人前|個|皿|杯)?(.+?)$").matcher(normalized);
            Matcher jaTrailing = COOK_ORDER_JA_TRAILING.matcher(TRAILING_PUNCTUATION.matcher(normalized).replaceFirst(""));
            Matcher en = Pattern.compile("^(?:(?:please|robot)?(?:cook|make|prepare))([0-9]+)(?:servings?|pieces?|bowls?|plates?)?(.+?)$").matcher(normalized);
            if (jaTrailing.matches()) { item = jaTrailing.group(1); quantity = number(jaTrailing.group(2)); }
            else if (ja.matches()) { quantity = number(ja.group(1)); item = ja.group(2); }
            else if (en.matches()) { quantity = number(en.group(1)); item = en.group(2); }
            else return null;
        }
        if (quantity < 1 || quantity > 256) return null;
        return new Intent(item, quantity, RobotCollectionTask.AUTO);
    }

    public static boolean mayRequest(String text) {
        String value = normalize(text);
        String withoutTrailingPunctuation = TRAILING_PUNCTUATION.matcher(value).replaceFirst("");
        boolean directJapaneseItemFirst = COLLECTION_ORDER_JA_TRAILING.matcher(withoutTrailingPunctuation).matches();
        if (value.length() > 1200 || !(REQUEST_START.matcher(value).find() || directJapaneseItemFirst) || unsafeOrderContext(text, value)
                || QUOTED_OR_QUESTION.matcher(value).find()) return false;
        Matcher verbs = VERB.matcher(value);
        return verbs.find() && !verbs.find();
    }
    /** Exact whitelist for short, direct work-mode commands; surrounding prose never executes. */
    public static Parsed parseDirectWork(String text) {
        String normalized = normalize(text);
        if (QUOTED_OR_QUESTION.matcher(normalized).find()) return null;
        String value = TRAILING_PUNCTUATION.matcher(normalized).replaceFirst("");
        // Exact whitelist matching below already rejects extra negation, hypotheticals, and multiple actions.
        value = value.replaceAll("^(?:机器人|ロボット|robot|please|请|麻烦|帮我|给我|おねがい|お願い)+", "")
                .replaceAll("(?:吧|して|ください|please)$", "");
        return switch (value) {
            case "挖矿", "采矿", "mine", "gomining", "採掘", "掘って" -> Parsed.work(Work.MINING);
            case "伐木", "chopwood", "gatherwood", "木を切って", "伐採" -> Parsed.work(Work.LOGGING);
            case "烹饪", "做饭", "cook", "startcooking", "料理", "料理して" -> Parsed.work(Work.COOKING);
            case "烧矿", "熔炼", "冶炼", "smelt", "smeltores", "精錬", "鉱石を精錬" -> Parsed.work(Work.SMELTING);
            case "种地", "种田", "耕种", "farm", "startfarming", "農業", "畑仕事" -> Parsed.work(Work.FARMING);
            case "取消烹饪", "重置烹饪", "cancelcooking", "stopcooking", "料理をキャンセル" -> Parsed.work(Work.CANCEL_COOKING);
            case "跟随", "跟着我", "跟着", "follow", "followme", "ついてきて" -> Parsed.work(Work.FOLLOW);
            case "待命", "进入待命", "停下", "停止工作", "wait", "stay", "stopwork", "待機", "待って" -> Parsed.work(Work.STAY);
            default -> null;
        };
    }
    public static Parsed parse(JsonElement proposed, String userText) {
        if (!mayRequest(userText)) return new Parsed(null, null, "本轮不是明确的采集指令，未派发任务。");
        if (proposed == null || proposed.isJsonNull()) return new Parsed(null, null, "请明确要采集的物品和数量，例如：帮我采集20个钨矿。");
        try {
            JsonObject value = proposed.getAsJsonObject();
            if (!value.has("item_query") || !value.get("item_query").isJsonPrimitive()
                    || !value.get("item_query").getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            String query = value.get("item_query").getAsString().trim();
            if (query.isBlank() || query.length() > 128 || !normalize(userText).contains(normalize(query)))
                return new Parsed(null, null, "物品名需要在你的本轮指令中明确出现，请补充准确物品名或注册ID。");
            if (!value.has("amount") || !value.get("amount").isJsonPrimitive()
                    || !value.get("amount").getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            int amount = value.get("amount").getAsBigDecimal().intValueExact();
            if (amount < 1 || amount > 256) return new Parsed(null, null, "一次采集数量为1～256，请给出这个范围内的整数。");
            int explicit = explicitAmount(userText, query);
            if (explicit < 1 || explicit != amount)
                return new Parsed(null, null, "数量还不明确，请一次只指定一个物品和数量，例如：帮我采集20个钨矿。");
            String mode = value.has("mode") ? value.get("mode").getAsString() : "auto";
            if (!mode.equals("auto") && !mode.equals("pickup")) throw new IllegalArgumentException();
            // A pickup request never authorizes breaking blocks, even if the model returns the wrong mode.
            String normalizedText = normalize(userText);
            boolean pickupRequest = Pattern.compile("^(?:(?:please|robot|帮我|请|ロボット)?)(?:拾取|拾って|拾う|捡|pickup)")
                    .matcher(normalizedText).find();
            boolean onlyPickup = pickupRequest || Pattern.compile("(?:只|仅|only|just).{0,8}(?:拾取|捡|pickup|拾って|拾う)").matcher(normalizedText).find()
                    || Pattern.compile("拾取|拾って|拾う|捡|pickup").matcher(normalizedText).find()
                        && !Pattern.compile("采集|开采|挖|砍|伐木|mine|chop|採掘|伐採").matcher(normalizedText).find();
            int kind = mode.equals("pickup") || onlyPickup
                    ? RobotCollectionTask.PICKUP_ONLY : RobotCollectionTask.AUTO;
            return new Parsed(new Intent(query, amount, kind), null, "");
        } catch (RuntimeException invalid) {
            return new Parsed(null, null, "没有识别到明确的物品和整数数量，未派发任务。可说：帮我采集20个钨矿。");
        }
    }
    public static RobotCollectionTask.Result execute(ServerPlayer player, RobotEntity robot, boolean remote, Intent intent) {
        RobotChatItems.Resolution item = RobotChatItems.resolve(intent.itemQuery());
        if (item.item() == null) return new RobotCollectionTask.Result(false, item.message());
        String id = ForgeRegistries.ITEMS.getKey(item.item()).toString();
        RobotCollectionTask.Result result = RobotCollectionOrders.assign(player, robot, remote, true, id, intent.amount(), intent.kind());
        if (!result.accepted()) return result;
        return new RobotCollectionTask.Result(true, "已接收采集任务：" + RobotChatItems.displayName(item.item()) + "×" + intent.amount()
                + (intent.kind() == RobotCollectionTask.PICKUP_ONLY ? "，仅拾取现成物品。" : "，按目标选择方块或对应生物；鸡蛋仅巡视鸡群拾取自然掉落。")
                + "收集足量后待命；缺工具、权限受阻或无目标会通知你。");
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
            if (units.group(2).equals("组") || units.group(2).equals("堆") || units.group(2).startsWith("stack")) number *= 64;
            if (number > 256) return -1;
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
    private static boolean unsafeOrderContext(String raw, String normalized) {
        String spaced = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return NOT_ORDER.matcher(normalized).find() || ENGLISH_UNSAFE_CONTEXT.matcher(spaced).find();
    }
}
