package com.ic2newera.client.robot;

import com.ic2newera.robot.RobotMemoryPackets;
import com.ic2newera.robot.RobotPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A single outstanding private read for the open screen, never a cache of another world's memories. */
public final class RobotMemoryClient {
    enum Topic { ALL, MEETING, PET, BUILD, COOK, COLLECT, UNRECORDED }
    private static final java.util.regex.Pattern PUNCTUATION = java.util.regex.Pattern.compile("[\\s,，。.!！?？、：:]+");
    private record Pending(ClientPacketListener connection, UUID owner, UUID robot, UUID ticket, boolean remote,
                           CompletableFuture<RobotMemoryPackets.Reply> result) { }
    private static Pending pending;
    private RobotMemoryClient() { }

    static CompletableFuture<RobotMemoryPackets.Reply> read(UUID robot, boolean remote) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return CompletableFuture.failedFuture(new IllegalStateException("已断开服务器。"));
        if (pending != null && !pending.result.isDone()) pending.result.cancel(false);
        var result = new CompletableFuture<RobotMemoryPackets.Reply>();
        var p = new Pending(mc.getConnection(), mc.player.getUUID(), robot, UUID.randomUUID(), remote, result);
        pending = p;
        result.orTimeout(8, TimeUnit.SECONDS).whenComplete((v, e) -> mc.execute(() -> { if (pending == p) pending = null; }));
        RobotPackets.CHANNEL.sendToServer(new RobotMemoryPackets.Request(p.ticket, robot, remote));
        return result;
    }
    public static void receive(RobotMemoryPackets.Reply reply) {
        Minecraft mc = Minecraft.getInstance(); Pending p = pending;
        if (p == null || mc.getConnection() != p.connection || mc.player == null || !p.owner.equals(mc.player.getUUID())
                || !p.robot.equals(reply.robot()) || !p.ticket.equals(reply.ticket()) || p.remote != reply.remote()) return;
        if (reply.code() == RobotMemoryPackets.OK) p.result.complete(reply);
        else p.result.completeExceptionally(new IllegalStateException(switch (reply.code()) {
            case RobotMemoryPackets.EMPTY_PHONE -> "读取回忆需要手机至少有 20 EU 电量。";
            case RobotMemoryPackets.TOO_FAST -> "刚读过回忆，请稍等片刻再试。";
            case RobotMemoryPackets.DIRECTORY_FULL -> "这台机器人未登记：本世界的机器人回忆名额已满，原有回忆仍保留。";
            default -> p.remote ? "无法连接这台机器人。请确认它已加载、有电，并手持与它配对的手机。"
                    : "请主人站到自己的机器人八格内，再查看回忆。";
        }));
    }
    // Only explicit questions about a shared event take the private route. Other chat and orders stay intact.
    static Topic topic(String text) {
        if (text == null || text.length() > 90) return null;
        String s = PUNCTUATION.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll("");
        if (s.equals("回忆") || s.equals("共同回忆") || s.equals("看看我们的回忆") || s.equals("你还记得什么")
                || s.equals("你记得什么") || s.equals("我们的回忆") || s.equals("whatareourmemories")
                || s.equals("whatdoyouremember") || s.equals("私たちの思い出")) return Topic.ALL;
        boolean recall = s.startsWith("你还记得") || s.startsWith("你记得") || s.startsWith("还记得")
                || s.startsWith("我们第一次") || s.startsWith("咱们第一次")
                || s.startsWith("doyouremember") || s.startsWith("whenwasourfirst") || s.startsWith("wherewasourfirst")
                || s.startsWith("初めて") && s.contains("覚えて");
        if (!recall) return null;
        if (s.contains("摸头") || s.contains("摸你的头") || s.contains("摸摸头") || s.contains("petting") || s.contains("petted") || s.contains("撫で")) return Topic.PET;
        if (s.contains("相遇") || s.contains("见面") || s.contains("认识") || s.contains("遇见") || s.contains("wemet") || s.contains("meeting") || s.contains("出会")) return Topic.MEETING;
        if (s.contains("建") || s.contains("盖") || s.contains("房子") || s.contains("build") || s.contains("built") || s.contains("建て")) return Topic.BUILD;
        if (s.contains("做饭") || s.contains("烹饪") || s.contains("做菜") || s.contains("做的菜") || s.contains("饭") || s.contains("cook") || s.contains("meal") || s.contains("料理")) return Topic.COOK;
        if (s.contains("采集") || s.contains("收集") || s.contains("collect") || s.contains("採集")) return Topic.COLLECT;
        if (s.contains("我说") || s.contains("聊过") || s.contains("聊天记录") || s.contains("我的生日")
                || s.contains("whatisaid") || s.contains("ourconversation") || s.contains("会話")) return Topic.UNRECORDED;
        return s.contains("回忆") || s.contains("经历") || s.contains("我们的事") || s.contains("一起做过什么") ? Topic.ALL : null;
    }
    static String heading(String kind, String language) {
        boolean en = "en".equals(language), ja = "ja".equals(language);
        return switch (kind) {
            case "FIRST_MEETING" -> en ? "When we met" : ja ? "初めて会った日" : "第一次见面";
            case "FIRST_PET" -> en ? "The first time you patted my head" : ja ? "初めて頭を撫でてくれた日" : "第一次摸头";
            case "FIRST_BUILD" -> en ? "Our first finished build" : ja ? "初めて完成した建物" : "第一次完成建造";
            case "FIRST_COOKING" -> en ? "The first meal I made for you" : ja ? "初めて作った料理" : "第一次为你做好一餐";
            case "FIRST_COLLECTION" -> en ? "My first collection order for you" : ja ? "初めて達成した採集依頼" : "第一次完成指定采集";
            default -> en ? "A shared memory" : ja ? "一緒の思い出" : "共同回忆";
        };
    }
    static String place(RobotMemoryPackets.Row r, String language) {
        boolean en = "en".equals(language), ja = "ja".equals(language);
        String dimension = switch (r.dimension().toString()) {
            case "minecraft:overworld" -> en ? "Overworld" : ja ? "オーバーワールド" : "主世界";
            case "minecraft:the_nether" -> en ? "Nether" : ja ? "ネザー" : "下界";
            case "minecraft:the_end" -> en ? "End" : ja ? "ジ・エンド" : "末地";
            default -> r.dimension().toString();
        };
        long day = Math.floorDiv(r.dayTime(), 24000L) + 1;
        return (en ? "Day " + day : ja ? day + " 日目" : "世界第 " + day + " 天") + " · " + dimension + " (" + r.position().getX() + ", " + r.position().getY() + ", " + r.position().getZ() + ")";
    }
    static String subject(RobotMemoryPackets.Row r) {
        if (r.subject().isBlank() || r.kind().equals("FIRST_PET") || r.kind().equals("FIRST_MEETING")) return "";
        ResourceLocation id = r.subject().contains(":") ? ResourceLocation.tryParse(r.subject()) : null;
        var item = id != null && ForgeRegistries.ITEMS.containsKey(id) ? ForgeRegistries.ITEMS.getValue(id) : null;
        return item == null ? r.subject() : item.getDescription().getString();
    }
    static String recall(RobotMemoryPackets.Reply reply, Topic topic) {
        String lang = reply.language(); boolean en = "en".equals(lang), ja = "ja".equals(lang);
        if (topic == Topic.UNRECORDED) return en ? "I keep memories of things we did together. I don’t keep a transcript of our chats."
                : ja ? "一緒にしたことは覚えているけど、会話の内容は保存していないよ。" : "我会记下我们一起做过的事，但不会保存聊天原文。";
        String kind = switch (topic) {
            case ALL -> ""; case MEETING -> "FIRST_MEETING"; case PET -> "FIRST_PET"; case COOK -> "FIRST_COOKING";
            case BUILD -> "FIRST_BUILD"; case COLLECT -> "FIRST_COLLECTION"; case UNRECORDED -> throw new AssertionError();
        };
        List<RobotMemoryPackets.Row> found = reply.rows().stream().filter(r -> kind.isEmpty() || r.kind().equals(kind)).toList();
        if (found.isEmpty()) return en ? "I don’t have a memory of that yet." : ja ? "その思い出はまだ記録にないよ。" : "我的回忆里还没有这件事。";
        StringBuilder result = new StringBuilder(en ? "I remember." : ja ? "覚えているよ。" : "我记得。");
        for (var r : found) {
            String what = subject(r);
            if (!en && !ja && topic != Topic.ALL) {
                String place = place(r, lang).replace(" · ", "，在");
                return switch (topic) {
                    case MEETING -> "记得。我们第一次见面是" + place + "。";
                    case PET -> "记得。你第一次摸我的头，是" + place + "。";
                    case BUILD -> "记得。那次完工是" + place + "。图纸记录：" + what + "。";
                    case COOK -> "记得。我第一次给你做好一单菜，是" + place + "。那次做的是：" + what + "。";
                    case COLLECT -> "记得。第一单采集是在" + place + "完成的，带回来的是" + what + "。";
                    default -> throw new AssertionError();
                };
            }
            result.append("\n").append(heading(r.kind(), lang)).append(" · ").append(place(r, lang));
            if (!what.isBlank()) result.append(" · ").append(what);
        }
        return result.toString();
    }
}
