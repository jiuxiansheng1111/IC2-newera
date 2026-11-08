package com.ic2newera.client.robot;

import com.ic2newera.client.media.MediaClient;
import com.ic2newera.media.MediaPackets;
import com.ic2newera.robot.RobotPackets;
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
}
