package com.ic2newera.client.robot;

import com.ic2newera.client.media.MediaClient;
import com.ic2newera.media.MediaPackets;
import com.ic2newera.robot.RobotPackets;
import com.ic2newera.robot.RobotTaskBook;
import com.ic2newera.robot.RobotTaskIntent;
import com.ic2newera.robot.RobotTaskPackets;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Native server model service; identity, ownership and energy are checked on the server. */
final class RobotChatBridge {
    record Message(String role, String content, boolean privateMemory) {
        Message(String role, String content) { this(role, content, false); }
    }
    record Reply(String text, java.util.UUID voiceReplyId, int segments) { }
    static CompletableFuture<Reply> send(RobotPackets.View view, List<Message> messages) {
        return send(view.robot(), view.character(), MediaPackets.CHAT, messages);
    }
    static CompletableFuture<Reply> sendPhone(java.util.UUID robot, String character, List<Message> messages) {
        return send(robot, character, MediaPackets.PHONE_CHAT, messages);
    }
    private static CompletableFuture<Reply> send(java.util.UUID robot, String character, int operation, List<Message> messages) {
        var last = messages.get(messages.size() - 1);
        if (last.privateMemory()) {
            var field = FieldJournalScreen.query(last.content());
            if (field != null) return FieldJournalScreen.chat(robot, operation == MediaPackets.PHONE_CHAT, field)
                    .thenApply(text -> new Reply(text, null, 0));
            RobotTaskIntent task = RobotTaskIntent.parse(last.content());
            if (task != null) {
                if (!task.error().isEmpty()) return CompletableFuture.completedFuture(new Reply(task.error(), null, 0));
                var call = RobotTaskClient.request(robot, operation == MediaPackets.PHONE_CHAT, task.action(), task.source(), task.name(), "");
                return call.thenApply(reply -> new Reply(taskReply(task, reply), null, 0));
            }
            var topic = RobotMemoryClient.topic(last.content());
            if (topic == null) return CompletableFuture.failedFuture(new IllegalStateException("请选择要回顾的经历。"));
            var call = RobotMemoryClient.read(robot, operation == MediaPackets.PHONE_CHAT);
            var answer = call.thenApply(snapshot -> new Reply(RobotMemoryClient.recall(snapshot, topic), null, 0));
            answer.whenComplete((value, problem) -> { if (answer.isCancelled()) call.cancel(false); });
            return answer;
        }
        // Private recall never enters the model history, even when the next turn is an ordinary question.
        var publicMessages = messages.stream().filter(m -> !m.privateMemory()).toList();
        var history = publicMessages.subList(Math.max(0, publicMessages.size() - 12), publicMessages.size()).stream()
                .map(m -> new MediaPackets.Message(m.role(), m.content().substring(0, Math.min(1200, m.content().length())))).toList();
        var transfer = MediaClient.request(operation, robot, "", history);
        var result = transfer.thenApply(reply -> {
            var json = reply.json();
            if (!json.get("character").getAsString().equals(character)
                    || !java.util.UUID.fromString(json.get("robot_uuid").getAsString()).equals(robot))
                throw new IllegalStateException("机器人连接已改变，请刷新设备后重试。");
            return new Reply(json.get("reply").getAsString(), java.util.UUID.fromString(json.get("voice_reply_id").getAsString()), json.get("voice_segments").getAsInt());
        });
        result.whenComplete((reply, error) -> { if (result.isCancelled()) transfer.cancel(true); });
        return result;
    }

    private static String taskReply(RobotTaskIntent intent, RobotTaskPackets.Reply reply) {
        if (intent.action() == RobotTaskBook.Action.LIST && reply.accepted()) {
            if (reply.rows().isEmpty()) return "还没有已完成的采集、烹饪或制作记录。完成一单或一批后，可在“常用任务”里保存昵称。旧制作记录若缺少配方、工具或材料，将说明原因且不会复做。";
            StringBuilder text = new StringBuilder("常用任务：");
            for (var row : reply.rows()) {
                String label = switch (row.key()) {
                    case RobotTaskBook.LAST -> "最近任务";
                    case RobotTaskBook.COLLECT -> "最近采集";
                    case RobotTaskBook.COOK -> "最近烹饪";
                    case RobotTaskBook.CRAFT -> "最近制作";
                    default -> "已保存：" + row.key();
                };
                var item = ForgeRegistries.ITEMS.getValue(row.item());
                String itemName = item == null ? row.item().toString() : item.getDescription().getString();
                if (row.type().equals("craft")) {
                    text.append("\n").append(label).append(" · 制作 ").append(itemName)
                            .append("，按一批；配方 ").append(row.recipe() == null ? "缺失" : row.recipe())
                            .append("；工具 ").append(itemName(row.tool()))
                            .append("；材料 ").append(itemName(row.material()));
                } else {
                    text.append("\n").append(label).append(" · ").append(row.type().equals("cook") ? "烹饪" : "采集")
                            .append(" ").append(itemName).append(" × ").append(row.amount());
                    if (row.type().equals("collect")) text.append(row.mode() == com.ic2newera.robot.RobotCollectionTask.AUTO ? " · 按掉落物自动采集" : " · 仅拾取");
                }
            }
            return text.toString();
        }
        if (reply.message() != null && !reply.message().isBlank()) return reply.message();
        return reply.accepted() ? "常用任务操作已完成。" : "常用任务操作未完成。";
    }

    private static String itemName(net.minecraft.resources.ResourceLocation id) {
        if (id == null) return "缺失（旧记录无法安全复做）";
        var item = ForgeRegistries.ITEMS.getValue(id);
        return item == null ? id.toString() : item.getDescription().getString();
    }
}
