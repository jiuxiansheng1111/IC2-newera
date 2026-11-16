package com.ic2newera.integration.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/** OpenAI Responses request and response codec shared by HTTP and local subscription providers. */
public final class ResponsesCodec {
    private static final int MAX_BODY_CHARS = 512_000;
    private static final int MAX_REPLY_CHARS = 6000;

    private ResponsesCodec() { }

    /** Build a Responses request body. The caller supplies the model ID without local name mapping. */
    public static JsonObject request(String model, int maxOutputTokens, boolean stream,
                                     String instructions, List<LLMTransport.Message> messages,
                                     String imageDataUrl) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", instructions == null ? "" : instructions);
        body.addProperty("max_output_tokens", maxOutputTokens);
        body.addProperty("stream", stream);
        body.addProperty("store", false);
        JsonArray input = new JsonArray();
        for (int index = 0; index < messages.size(); index++) {
            LLMTransport.Message message = messages.get(index);
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            if (imageDataUrl != null && index == messages.size() - 1 && "user".equals(message.role())) {
                JsonArray content = new JsonArray();
                JsonObject text = new JsonObject(); text.addProperty("type", "input_text"); text.addProperty("text", message.content());
                content.add(text);
                JsonObject image = new JsonObject(); image.addProperty("type", "input_image"); image.addProperty("image_url", imageDataUrl);
                content.add(image);
                item.add("content", content);
                imageDataUrl = null;
            } else item.addProperty("content", message.content());
            input.add(item);
        }
        if (imageDataUrl != null) {
            JsonObject item = new JsonObject();
            item.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject image = new JsonObject();
            image.addProperty("type", "input_image");
            image.addProperty("image_url", imageDataUrl);
            content.add(image);
            item.add("content", content);
            input.add(item);
        }
        body.add("input", input);
        return body;
    }

    public static String parseJson(InputStream input) throws IOException {
        return parseResponse(parseObject(input));
    }

    /** Read and validate the JSON envelope, preserving it for callers that need full provider metadata. */
    public static JsonObject parseObject(InputStream input) throws IOException {
        byte[] data = input.readNBytes(MAX_BODY_CHARS + 1);
        if (data.length > MAX_BODY_CHARS) throw new IllegalStateException("AI返回内容超出大小限制");
        try {
            JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            // Validate structured terminal states without discarding metadata.
            if ("failed".equals(string(root.get("status"))) || "incomplete".equals(string(root.get("status")))
                    || (root.has("error") && !root.get("error").isJsonNull())) parseResponse(root);
            return root;
        } catch (RuntimeException error) {
            if (error instanceof IllegalStateException && error.getMessage() != null
                    && (error.getMessage().startsWith("AI返回") || error.getMessage().startsWith("Responses"))) throw error;
            throw new IllegalStateException("Responses响应格式无法识别");
        }
    }

    /** Parse a complete Responses JSON object, including local subscription-provider responses. */
    public static String parseResponse(JsonObject root) {
        return parseResponse(root, MAX_REPLY_CHARS);
    }

    /** Parse with a caller-specific character ceiling, used for structured blueprint output. */
    public static String parseResponse(JsonObject root, int maxReplyChars) {
        JsonElement error = root.get("error");
        if (error != null && !error.isJsonNull()) throw new IllegalStateException("Responses请求失败：" + safeError());
        String status = string(root.get("status"));
        if ("failed".equals(status)) throw new IllegalStateException("Responses请求失败：" + safeError());
        if ("incomplete".equals(status)) throw incomplete(root);
        if (!status.isEmpty() && !"completed".equals(status)) throw new IllegalStateException("Responses回复尚未完成，请重试");
        String answer = outputText(root);
        if (answer.isBlank()) throw new IllegalStateException("Responses返回了空内容；可尝试调整模型或输出长度");
        int limit = Math.max(1, maxReplyChars);
        if (answer.length() > limit) throw new IllegalStateException("Responses回复超过长度限制，内容可能被截断；请提高输出长度或简化请求");
        return answer;
    }

    public static String readStream(InputStream input, Consumer<String> onPartial) throws IOException {
        StringBuilder event = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        int received = 0;
        boolean completed = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                received += line.length() + 1;
                if (received > MAX_BODY_CHARS) throw new IllegalStateException("AI返回内容超出大小限制");
                if (line.isEmpty()) {
                    if (!completed) completed = consume(event.toString(), answer, onPartial);
                    event.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    event.append(line.substring(5).stripLeading());
                }
            }
            if (!completed && !event.isEmpty()) completed = consume(event.toString(), answer, onPartial);
        }
        if (!completed) throw new IllegalStateException("Responses流在完成事件前中断，回复可能被截断；请重试");
        if (answer.isEmpty()) throw new IllegalStateException("Responses返回了空内容；可尝试调整模型或输出长度");
        return answer.toString();
    }

    private static boolean consume(String data, StringBuilder answer, Consumer<String> onPartial) {
        if (data.isBlank()) return false;
        if ("[DONE]".equals(data.trim())) return false;
        JsonObject event;
        try { event = JsonParser.parseString(data).getAsJsonObject(); }
        catch (RuntimeException malformed) { throw new IllegalStateException("Responses流式事件格式无法识别"); }
        String type = string(event.get("type"));
        if (type.endsWith(".failed") || "error".equals(type)) {
            JsonObject response = event.has("response") && event.get("response").isJsonObject() ? event.getAsJsonObject("response") : event;
            throw new IllegalStateException("Responses请求失败：" + safeError());
        }
        if (type.endsWith(".incomplete")) {
            JsonObject response = event.has("response") && event.get("response").isJsonObject() ? event.getAsJsonObject("response") : event;
            throw incomplete(response);
        }
        if (type.endsWith(".output_text.delta")) {
            append(answer, string(event.get("delta")), onPartial);
            return false;
        }
        if ("response.completed".equals(type)) {
            JsonObject response = event.has("response") && event.get("response").isJsonObject() ? event.getAsJsonObject("response") : event;
            if ("incomplete".equals(string(response.get("status")))) throw incomplete(response);
            if (response.has("error") && !response.get("error").isJsonNull()
                    || response.has("status") && !"completed".equals(string(response.get("status"))))
                throw new IllegalStateException("Responses请求失败：" + safeError());
            String fullText = outputText(response);
            if (!fullText.isBlank() && !fullText.contentEquals(answer)) {
                answer.setLength(0);
                append(answer, fullText, onPartial);
            }
            return true;
        }
        return false;
    }

    private static void append(StringBuilder answer, String fragment, Consumer<String> onPartial) {
        if (fragment.isEmpty()) return;
        int remaining = MAX_REPLY_CHARS - answer.length();
        if (fragment.length() > remaining) throw new IllegalStateException("Responses流式回复超过长度限制，内容可能被截断；请提高输出长度或简化请求");
        answer.append(fragment);
        onPartial.accept(answer.toString());
    }

    private static String outputText(JsonObject root) {
        if (root.has("output_text") && !string(root.get("output_text")).isBlank()) return string(root.get("output_text"));
        StringBuilder text = new StringBuilder();
        JsonArray output = root.has("output") && root.get("output").isJsonArray() ? root.getAsJsonArray("output") : new JsonArray();
        for (JsonElement item : output) {
            if (!item.isJsonObject()) continue;
            JsonElement contentValue = item.getAsJsonObject().get("content");
            if (contentValue == null || !contentValue.isJsonArray()) continue;
            for (JsonElement part : contentValue.getAsJsonArray()) {
                if (part.isJsonObject() && "output_text".equals(string(part.getAsJsonObject().get("type"))))
                    text.append(string(part.getAsJsonObject().get("text")));
            }
        }
        return text.toString();
    }

    private static IllegalStateException incomplete(JsonObject root) {
        String reason = root != null && root.has("incomplete_details") && root.get("incomplete_details").isJsonObject()
                ? string(root.getAsJsonObject("incomplete_details").get("reason")) : "";
        if ("max_output_tokens".equals(reason)) return new IllegalStateException("Responses回复达到输出长度上限，内容可能被截断；请提高输出长度或简化问题");
        if ("content_filter".equals(reason)) return new IllegalStateException("Responses回复因内容策略未完成");
        return new IllegalStateException("Responses回复未完整生成，请检查请求后重试");
    }

    private static String safeError() {
        return "服务端未能完成请求；请检查模型权限、请求内容与输出长度";
    }

    private static String string(JsonElement value) { return value == null || value.isJsonNull() ? "" : value.getAsString(); }
}
