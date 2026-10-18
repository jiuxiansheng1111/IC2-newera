package com.ic2newera.media;

import com.google.gson.*;
import com.ic2newera.IC2NewEra;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.integration.llm.LLMTransport;
import com.ic2newera.integration.llm.VoiceText;
import com.ic2newera.media.music.NetEaseMusicProvider;
import com.ic2newera.media.music.PreparedAudio;
import com.ic2newera.robot.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Native catalog/model integration on the game host. Only optional TTS uses a loopback model worker. */
@Mod.EventBusSubscriber(modid = IC2NewEra.MOD_ID)
public final class MediaServer {
    private static final String PLAYLIST_KEY = "IC2NEMusicPlaylist";
    private static final int MAX_AUDIO = 16 * 1024 * 1024;
    private static final long DISK_LIMIT = 128L * 1024 * 1024;
    private static final AtomicLong RESERVED = new AtomicLong();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> { Thread t = new Thread(task, "IC2-NewEra-Media-Bridge"); t.setDaemon(true); return t; });
    private static final ThreadPoolExecutor VOICE_WORK = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> { Thread t = new Thread(task, "IC2-NewEra-Voice-Queue"); t.setDaemon(true); return t; });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();
    private MediaServer() { }
    private static final class Session {
        final ServerPlayer player;
        final Map<UUID, Job> jobs = new HashMap<>();
        final Map<UUID, Transfer> transfers = new HashMap<>();
        List<MusicTrack> candidates = List.of();
        final LinkedHashMap<UUID, SpokenReply> spokenReplies = new LinkedHashMap<>();
        final LinkedHashMap<UUID, ChatReceipt> chatReceipts = new LinkedHashMap<>();
        long replyRevision;
        long nextAction, nextSearch;
        Session(ServerPlayer player) { this.player = player; }
    }
    private record SpokenReply(UUID robot, String character, List<String> segments, long expires) { }
    private record ChatReceipt(MediaPackets.Request request, JsonObject reply, long expires) { }
    private static final class Job {
        final long started = System.nanoTime();
        final int operation;
        final long replyRevision;
        final LLMConfig model;
        final boolean dedicated;
        final UUID target;
        final String character;
        volatile boolean canceled;
        volatile InputStream input;
        volatile Future<?> future;
        volatile CompletableFuture<String> modelFuture;
        private Audio stagedAudio;
        Job(int operation, long replyRevision, ServerPlayer player, UUID target, String character) {
            this.operation = operation; this.replyRevision = replyRevision;
            this.target = target; this.character = character;
            dedicated = player.server.isDedicatedServer();
            model = MediaPackets.isChat(operation) || operation == MediaPackets.SMART_SEARCH ? NativeMediaConfig.modelFor(player.server) : null;
        }
        void cancel() {
            Audio staged;
            synchronized (this) { canceled = true; staged = stagedAudio; stagedAudio = null; }
            discard(staged); close(input); if (modelFuture != null) modelFuture.cancel(true); if (future != null) future.cancel(true);
        }
        synchronized boolean stage(Audio audio) { if (canceled) return false; stagedAudio = audio; return true; }
        synchronized Audio takeAudio() { Audio result = stagedAudio; stagedAudio = null; return result; }
    }
    private static final class Transfer {
        final Path path;
        final long size;
        final int operation;
        long lastAccess = System.nanoTime();
        int sequence;
        boolean reading;
        Transfer(Path path, long size, int operation) { this.path = path; this.size = size; this.operation = operation; }
        void close() { delete(path); RESERVED.addAndGet(-size); }
    }
    private record Audio(Path path, long size, String sha, String mime) { }
    private static void send(ServerPlayer player, MediaPackets.Reply reply) {
        if (!player.hasDisconnected()) MediaPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply);
    }
    private static void json(ServerPlayer player, UUID ticket, JsonObject value) {
        send(player, new MediaPackets.Reply(ticket, MediaPackets.JSON, value.toString(), 0, true, new byte[0]));
    }
    private static void error(ServerPlayer player, UUID ticket, String message) {
        JsonObject value = new JsonObject(); value.addProperty("message", MusicTrack.bound(message, 240));
        send(player, new MediaPackets.Reply(ticket, MediaPackets.ERROR, value.toString(), 0, true, new byte[0]));
    }
    public static void request(ServerPlayer player, MediaPackets.Request request) {
        if (player.hasDisconnected() || !player.isAlive()) return;
        int operation = request.operation();
        if (operation < 0 || operation > MediaPackets.PHONE_CHAT || operation == 6 || (!MediaPackets.isChat(operation) && !request.messages().isEmpty())) return;
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session(player));
        if (session.player != player) { clear(session); session = new Session(player); SESSIONS.put(player.getUUID(), session); }
        long now = System.nanoTime();
        session.chatReceipts.entrySet().removeIf(entry -> entry.getValue().expires < now);
        ChatReceipt receipt = session.chatReceipts.get(request.ticket());
        if (receipt != null) {
            if (receipt.request.equals(request)) json(player, request.ticket(), receipt.reply);
            return;
        }
        if (now < session.nextAction) { error(player, request.ticket(), "操作过快，请稍后重试。"); return; }
        session.nextAction = now + 250_000_000L;
        if (session.jobs.containsKey(request.ticket()) || session.transfers.containsKey(request.ticket())) return;
        if (session.jobs.size() >= 2) { error(player, request.ticket(), "还有请求正在处理，请先等待或取消。"); return; }
        if (session.jobs.values().stream().anyMatch(job -> job.operation == operation || (MediaPackets.isChat(job.operation) && MediaPackets.isChat(operation)))) {
            error(player, request.ticket(), "同类请求正在处理，请先等待或取消。"); return;
        }
        // All submissions run on the server thread. Refuse a full queue before charging either device.
        ThreadPoolExecutor executor = operation == MediaPackets.VOICE || operation == MediaPackets.ASSISTANT_VOICE ? VOICE_WORK : WORK;
        executor.purge();
        if (executor.isShutdown() || executor.getQueue().remainingCapacity() == 0) {
            error(player, request.ticket(), "服务器媒体任务繁忙，请稍后重试。"); return;
        }
        JsonObject body = new JsonObject(); body.addProperty("protocol", 1);
        RobotEntity robot = null;
        if (operation == MediaPackets.ASSISTANT_VOICE) {
            if (!player.isAlive() || !request.target().equals(MediaPackets.NONE) || request.value().length() > 2048) return;
            try {
                JsonObject value = JsonParser.parseString(request.value()).getAsJsonObject();
                String character = text(value, "character", 32), speech = text(value, "text", 241);
                if (speech.isBlank() || speech.length() > 240
                        || Arrays.stream(RobotCharacter.values()).noneMatch(c -> c.id.equals(character))) return;
                body.addProperty("character", character); body.addProperty("text", speech);
            } catch (RuntimeException invalid) { return; }
        } else if (operation == MediaPackets.VOICE) {
            // A speech token references only this player's previously authorized reply, not a new world action.
            // It remains usable while walking away or closing the dialog.
            try {
                String[] token = request.value().split(":", -1);
                if (token.length != 2) throw new IllegalArgumentException();
                SpokenReply speech = session.spokenReplies.get(UUID.fromString(token[0]));
                int segment = Integer.parseInt(token[1]);
                if (speech == null || !speech.robot.equals(request.target()) || speech.expires < now
                        || segment < 0 || segment >= speech.segments.size()) throw new IllegalArgumentException();
                body.addProperty("character", speech.character); body.addProperty("text", speech.segments.get(segment));
            } catch (RuntimeException invalid) {
                error(player, request.ticket(), "朗读回复已过期或不属于该机器人，请重新发送。完整文字已保留。"); return;
            }
        } else if (MediaPackets.isChat(operation)) {
            boolean remote = operation == MediaPackets.PHONE_CHAT;
            RobotEntity found = chatRobot(player, request.target(), operation);
            if (found == null || (remote ? !phoneChatAccess(player, found) : !found.canManage(player))) {
                error(player, request.ticket(), remote ? "请手持有电手机；机器人须已加载、在线且属于你，联系不限距离。" : "机器人已离线、离得太远或不属于你。"); return;
            }
            robot = found;
            body.addProperty("character", robot.character().id);
            {
                if (request.messages().isEmpty() || !request.messages().get(request.messages().size() - 1).role().equals("user")
                        || request.messages().stream().anyMatch(m -> m.content().isBlank())) return;
                if (remote && !IndustrialPhoneItem.hasHeldCharge(player, IndustrialPhoneItem.COMMAND_COST)) {
                    error(player, request.ticket(), IndustrialPhoneItem.EMPTY_MESSAGE); return;
                }
                if (!robot.allowChat(player)) { error(player, request.ticket(), "机器人电量不足 2000 EU，或聊天过快。"); return; }
                if (remote) { IndustrialPhoneItem.consumeHeld(player, IndustrialPhoneItem.COMMAND_COST); player.getInventory().setChanged(); }
                session.replyRevision++;
                body.addProperty("player_uuid", player.getUUID().toString()); body.addProperty("player_name", player.getGameProfile().getName());
                body.addProperty("robot_uuid", robot.getUUID().toString());
                JsonArray history = new JsonArray();
                for (var message : request.messages()) {
                    JsonObject m = new JsonObject(); m.addProperty("role", message.role()); m.addProperty("content", message.content()); history.add(m);
                }
                body.add("messages", history);
                JsonObject context = new JsonObject(); context.addProperty("dimension", robot.level.dimension().location().toString());
                context.addProperty("mode", robot.following() ? "FOLLOW" : "STAY"); context.addProperty("health", robot.getHealth());
                context.addProperty("cooking_available", false); body.add("context", context);
                context.addProperty("当前采集任务", robot.collectionSummary());
                String latest = request.messages().get(request.messages().size() - 1).content();
                if (RobotChatCommands.mayRequest(latest)) context.addProperty("本轮采集物品候选", RobotChatItems.promptHints(latest));
                while (body.toString().getBytes(StandardCharsets.UTF_8).length > 20 * 1024 && history.size() > 1) history.remove(0);
            }
        } else {
            if (!musicAccess(player, operation)) {
                error(player, request.ticket(), "请手持手机，并连接你有权限使用的交换机 Wi-Fi。"); return;
            }
            if ((operation == MediaPackets.SEARCH || operation == MediaPackets.SMART_SEARCH) && (request.value().isBlank() || request.value().length() > 240)) return;
            if ((operation == MediaPackets.SEARCH || operation == MediaPackets.SMART_SEARCH || operation == MediaPackets.IMPORT) && now < session.nextSearch) {
                error(player, request.ticket(), "检索或导入过快，请稍后重试。"); return;
            }
            int cost = operation == MediaPackets.PLAYLIST ? IndustrialPhoneItem.READ_COST : IndustrialPhoneItem.COMMAND_COST;
            if (!(operation == MediaPackets.PREPARE ? IndustrialPhoneItem.consumeCarried(player, cost) : IndustrialPhoneItem.consumeHeld(player, cost))) {
                error(player, request.ticket(), IndustrialPhoneItem.EMPTY_MESSAGE); return;
            }
            player.getInventory().setChanged();
            List<MusicTrack> playlist = playlist(player);
            if (operation == MediaPackets.PLAYLIST) { json(player, request.ticket(), playlistJson(playlist, "歌单仅对你可见，最多 100 首。")); return; }
            if (operation == MediaPackets.ADD || operation == MediaPackets.REMOVE) {
                List<MusicTrack> edited = new ArrayList<>(playlist);
                if (operation == MediaPackets.ADD) {
                    MusicTrack track = find(session, playlist, request.value());
                    if (track == null) { error(player, request.ticket(), "请先检索并选择有效歌曲。"); return; }
                    if (edited.size() >= 100) { error(player, request.ticket(), "歌单已满，最多 100 首。"); return; }
                    if (edited.stream().noneMatch(t -> t.id().equals(track.id()))) edited.add(track);
                } else edited.removeIf(t -> t.id().equals(request.value()));
                savePlaylist(player, edited); json(player, request.ticket(), playlistJson(edited, "歌单已保存。")); return;
            }
            if (operation == MediaPackets.SEARCH || operation == MediaPackets.SMART_SEARCH) {
                session.nextSearch = now + 3_000_000_000L;
                body.addProperty("query", request.value().trim()); body.add("playlist", tracks(playlist));
            } else if (operation == MediaPackets.IMPORT) {
                session.nextSearch = now + 3_000_000_000L;
                body.addProperty("value", request.value());
            } else {
                MusicTrack track = find(session, playlist, request.value());
                if (track == null) { error(player, request.ticket(), "歌曲已不在候选或歌单中，请重新选择。"); return; }
                body.add("track", track.json());
                // A new track invalidates a previous unfinished transfer, never another player's stream.
                var iterator = session.transfers.values().iterator();
                while (iterator.hasNext()) { var transfer = iterator.next(); if (transfer.operation == operation) { transfer.close(); iterator.remove(); } }
            }
        }
        final Session active = session;
        final String character = robot == null ? text(body, "character", 32) : robot.character().id;
        Job job = new Job(operation, session.replyRevision, player, request.target(), character); session.jobs.put(request.ticket(), job);
        try {
            job.future = executor.submit(() -> perform(active, request, body, character, job));
        } catch (RejectedExecutionException full) {
            session.jobs.remove(request.ticket()); error(player, request.ticket(), "服务器媒体任务繁忙，请稍后重试。");
        }
    }
    private static MusicTrack find(Session session, List<MusicTrack> playlist, String id) {
        return java.util.stream.Stream.concat(playlist.stream(), session.candidates.stream()).filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }
    private static boolean musicAccess(ServerPlayer player, int operation) {
        return operation == MediaPackets.PREPARE ? IndustrialMobileAccess.musicOnline(player) : IndustrialMobileAccess.phoneOnline(player);
    }
    private static boolean phoneChatAccess(ServerPlayer player, RobotEntity robot) {
        // The request prepays its phone cost once. Revalidation must not require a second energy reserve.
        return player.isAlive() && IndustrialMobileAccess.canReachRobot(player, robot);
    }
    private static RobotEntity chatRobot(ServerPlayer player, UUID id, int operation) {
        return operation == MediaPackets.PHONE_CHAT ? RobotContacts.resolve(player.server, id)
                : player.getLevel().getEntity(id) instanceof RobotEntity robot ? robot : null;
    }
    private static void perform(Session session, MediaPackets.Request request, JsonObject body, String character, Job job) {
        Audio audio = null;
        try {
            int op = request.operation();
            if (job.canceled || Thread.currentThread().isInterrupted()) return;
            if (op == MediaPackets.VOICE || op == MediaPackets.ASSISTANT_VOICE) {
                VoiceRuntimeAutostart.ensureStarted().get(20, TimeUnit.SECONDS);
                HttpResponse<InputStream> response = callVoice(body, job);
                requireOk(response, job);
                if (!response.headers().firstValue("Content-Type").orElse("").startsWith("audio/wav")
                        || !response.headers().firstValue("X-IC2NE-Character").orElse("").equals(character))
                    throw new IOException("语音工作进程格式或角色不匹配。");
                audio = download(response, job, 8 * 1024 * 1024, "audio/wav");
                Audio completed = audio; audio = null;
                completeAudio(session, request, job, completed); return;
            }
            NetEaseMusicProvider provider = new NetEaseMusicProvider(input -> {
                job.input = input;
                if (job.canceled) close(input);
            });
            if (op == MediaPackets.PREPARE) {
                audio = prepareMusic(provider, MusicTrack.from(body.getAsJsonObject("track")), job);
                Audio completed = audio; audio = null;
                completeAudio(session, request, job, completed); return;
            }
            NativeMediaLlm.Completion completion = (prompt, history) -> completeModel(job, prompt, history);
            final String reply;
            final NativeMediaLlm.ChatReply chat;
            final NativeMediaLlm.Search found;
            final NetEaseMusicProvider.PlaylistResult imported;
            if (MediaPackets.isChat(op)) {
                chat = NativeMediaLlm.chat(completion, character, request.messages(), body.getAsJsonObject("context")); reply = chat.text();
                found = null; imported = null;
            } else if (op == MediaPackets.SMART_SEARCH) {
                reply = ""; chat = null; imported = null;
                found = NativeMediaLlm.search(completion, provider, request.value(), readTracks(body, "playlist", 100));
            } else if (op == MediaPackets.SEARCH) {
                reply = ""; chat = null; imported = null;
                var matches = provider.searchCandidates(request.value(), readTracks(body, "playlist", 100));
                LinkedHashMap<String, MusicTrack> selected = new LinkedHashMap<>();
                java.util.stream.Stream.concat(matches.playlistMatches().stream(), matches.candidates().stream())
                        .limit(20).forEach(track -> { if (selected.size() < 10) selected.putIfAbsent(track.id(), track); });
                found = new NativeMediaLlm.Search(List.copyOf(selected.values()), "网易云搜索结果；选择歌曲后播放或收藏。", "");
            } else {
                reply = ""; chat = null; found = null; imported = provider.importPlaylist(request.value(), "auto");
            }
            session.player.server.execute(() -> {
                if (!valid(session, request, job)) return;
                session.jobs.remove(request.ticket());
                JsonObject clean;
                if (MediaPackets.isChat(op)) {
                    String spoken = reply;
                    if (chat.collection() != null) {
                        var parsed = chat.collection();
                        if (parsed.intent() == null) spoken = "未派发采集任务。" + parsed.clarification();
                        else {
                            RobotEntity target = chatRobot(session.player, request.target(), op);
                            var outcome = RobotChatCommands.execute(session.player, target, op == MediaPackets.PHONE_CHAT, parsed.intent());
                            spoken = outcome.accepted() ? outcome.message() : "未派发新的采集任务。" + outcome.message();
                            if (outcome.accepted() && op == MediaPackets.CHAT) RobotPackets.open(session.player, target, -1);
                        }
                    }
                    UUID replyId = UUID.randomUUID();
                    List<String> segments = VoiceText.split(spoken);
                    session.spokenReplies.entrySet().removeIf(entry -> entry.getValue().expires < System.nanoTime());
                    while (session.spokenReplies.size() >= 16) session.spokenReplies.remove(session.spokenReplies.keySet().iterator().next());
                    session.spokenReplies.put(replyId, new SpokenReply(request.target(), character, segments, System.nanoTime() + 3_600_000_000_000L));
                    clean = new JsonObject(); clean.addProperty("reply", spoken); clean.addProperty("character", character);
                    clean.addProperty("robot_uuid", request.target().toString());
                    clean.addProperty("voice_reply_id", replyId.toString()); clean.addProperty("voice_segments", segments.size());
                    while (session.chatReceipts.size() >= 16) session.chatReceipts.remove(session.chatReceipts.keySet().iterator().next());
                    session.chatReceipts.put(request.ticket(), new ChatReceipt(request, clean, System.nanoTime() + 600_000_000_000L));
                } else if (op == MediaPackets.SEARCH || op == MediaPackets.SMART_SEARCH) {
                    session.candidates = found.candidates(); clean = new JsonObject(); clean.add("candidates", tracks(found.candidates()));
                    clean.addProperty("interpretation", found.interpretation()); clean.addProperty("suggested_id", found.suggestedId());
                } else {
                    savePlaylist(session.player, imported.tracks());
                    clean = playlistJson(imported.tracks(), "已导入 " + imported.tracks().size() + " 首。" + imported.message()
                            + (imported.truncated() ? " 歌单已按上限截断。" : ""));
                }
                json(session.player, request.ticket(), clean);
            });
        } catch (Exception failure) {
            discard(audio);
            session.player.server.execute(() -> {
                if (session.jobs.remove(request.ticket(), job) && !job.canceled)
                    error(session.player, request.ticket(), failure instanceof ConnectException ? "独立语音工作进程未启动，请在服务器主机启动语音运行时。"
                            : failure instanceof IOException || failure instanceof IllegalStateException ? MusicTrack.bound(failure.getMessage(), 240)
                            : "媒体请求未完成，请检查模型设置和网络连接。");
            });
        } finally { close(job.input); job.input = null; }
    }
    private static String completeModel(Job job, String prompt, List<LLMTransport.Message> history) throws Exception {
        if (job.canceled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (job.model == null || !job.model.enabled()) throw new IOException(job.dedicated
                ? "服主尚未启用模型，请在 ic2newera-media-common.toml 的 server_ai 中配置。"
                : "请先在助手的模型设置中启用并保存 AI 连接。");
        var future = LLMTransport.complete(job.model, history, prompt, "", partial -> { });
        job.modelFuture = future;
        try {
            if (job.canceled) { future.cancel(true); throw new InterruptedException(); }
            return future.get(Math.min(180, job.model.timeoutSeconds()), TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            throw new IOException(cause instanceof IllegalStateException ? cause.getMessage() : "模型调用失败，请检查模型地址、权限和网络。", cause);
        } catch (TimeoutException timeout) {
            throw new IOException("模型请求超时，请重试。", timeout);
        } finally { if (!future.isDone()) future.cancel(true); job.modelFuture = null; }
    }
    private static Audio prepareMusic(NetEaseMusicProvider provider, MusicTrack track, Job job) throws Exception {
        long reservation = MAX_AUDIO;
        if (RESERVED.addAndGet(reservation) > DISK_LIMIT) { RESERVED.addAndGet(-reservation); throw new IOException("服务器音频缓存已满，请稍后重试。"); }
        PreparedAudio prepared = null;
        try {
            prepared = provider.prepare(track, () -> job.canceled || Thread.currentThread().isInterrupted());
            RESERVED.addAndGet(prepared.sizeBytes() - reservation);
            return new Audio(prepared.path(), prepared.sizeBytes(), prepared.sha256(), prepared.mime());
        } catch (Exception failure) {
            if (prepared != null) delete(prepared.path());
            RESERVED.addAndGet(-reservation); throw failure;
        }
    }
    private static boolean valid(Session session, MediaPackets.Request request, Job job) {
        if (job.canceled || SESSIONS.get(session.player.getUUID()) != session || session.player.hasDisconnected()
                || session.jobs.get(request.ticket()) != job) return false;
        if (MediaPackets.isChat(job.operation) && job.replyRevision != session.replyRevision) {
            session.jobs.remove(request.ticket()); job.cancel(); error(session.player, request.ticket(), "已开始新对话，旧回复已取消。"); return false;
        }
        RobotEntity currentRobot = MediaPackets.isChat(request.operation()) ? chatRobot(session.player, request.target(), request.operation()) : null;
        boolean authorized = request.operation() == MediaPackets.ASSISTANT_VOICE || request.operation() == MediaPackets.VOICE ? session.player.isAlive()
                : MediaPackets.isChat(request.operation())
                ? session.player.isAlive() && currentRobot != null && currentRobot.character().id.equals(job.character)
                    && (request.operation() == MediaPackets.PHONE_CHAT ? phoneChatAccess(session.player, currentRobot) : currentRobot.canManage(session.player))
                : session.player.isAlive() && musicAccess(session.player, request.operation());
        if (!authorized) { session.jobs.remove(request.ticket()); job.cancel(); error(session.player, request.ticket(), "连接或权限已经改变，请重新操作。"); }
        return authorized;
    }
    private static HttpResponse<InputStream> callVoice(JsonObject payload, Job job) throws IOException, InterruptedException {
        if (job.canceled) throw new InterruptedException();
        var builder = HttpRequest.newBuilder(NativeMediaConfig.voiceEndpoint()).timeout(Duration.ofSeconds(620));
        if (payload != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        job.input = response.body();
        if (job.canceled) { close(job.input); throw new InterruptedException(); }
        return response;
    }
    private static void requireOk(HttpResponse<InputStream> response, Job job) throws IOException {
        if (response.statusCode() == 200) return;
        String message = switch (response.statusCode()) { case 404 -> "独立语音工作进程缺少接口，请更新语音运行时。";
            case 503, 429 -> "语音工作进程繁忙或资源不足，请稍后重试。"; case 504 -> "语音请求超时，请稍后重试。"; default -> "语音工作进程未能完成请求。"; };
        try (InputStream in = response.body()) {
            byte[] data = in.readNBytes(4097);
            if (data.length <= 4096) { var j = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject(); if (j.has("message")) message = text(j, "message", 240); }
        } catch (RuntimeException ignored) { }
        throw new IOException(message);
    }
    private static Audio download(HttpResponse<InputStream> response, Job job, int limit, String mime) throws Exception {
        long reserved = limit;
        if (RESERVED.addAndGet(reserved) > DISK_LIMIT) { RESERVED.addAndGet(-reserved); throw new IOException("服务器音频缓存已满，请稍后重试。"); }
        Path path = null; long count = 0;
        try {
            path = Files.createTempFile("ic2newera-media-", ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(path)) {
                byte[] buffer = new byte[32768]; int read;
                while ((read = in.read(buffer)) != -1) {
                    if (job.canceled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    count += read; if (count > limit) throw new IOException("音频文件超过大小限制。");
                    digest.update(buffer, 0, read); out.write(buffer, 0, read);
                }
            }
            if (count < 44) throw new IOException("音频文件不完整。");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] magic = in.readNBytes(12);
                if (mime.equals("audio/ogg") ? !new String(magic, 0, 4, StandardCharsets.US_ASCII).equals("OggS")
                        : !new String(magic, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") || !new String(magic, 8, 4, StandardCharsets.US_ASCII).equals("WAVE"))
                    throw new IOException("音频内容与格式不匹配。");
            }
            RESERVED.addAndGet(count - reserved);
            return new Audio(path, count, HexFormat.of().formatHex(digest.digest()), mime);
        } catch (Exception error) { delete(path); RESERVED.addAndGet(-reserved); throw error; }
    }
    private static void completeAudio(Session session, MediaPackets.Request request, Job job, Audio audio) {
        // The job owns the file until the server thread adopts it. Shutdown/cancel can free it
        // even if a queued server callback never gets to execute.
        if (!job.stage(audio)) { discard(audio); return; }
        try { session.player.server.execute(() -> {
            Audio staged = job.takeAudio();
            if (staged == null) return;
            if (!valid(session, request, job)) { discard(staged); return; }
            session.jobs.remove(request.ticket());
            session.transfers.put(request.ticket(), new Transfer(staged.path, staged.size, request.operation()));
            JsonObject info = new JsonObject(); info.addProperty("size", staged.size); info.addProperty("sha256", staged.sha); info.addProperty("mime", staged.mime);
            send(session.player, new MediaPackets.Reply(request.ticket(), MediaPackets.AUDIO, info.toString(), 0, false, new byte[0]));
        }); } catch (RuntimeException rejected) { discard(job.takeAudio()); throw rejected; }
    }
    public static void pull(ServerPlayer player, MediaPackets.Pull packet) {
        Session session = SESSIONS.get(player.getUUID()); if (session == null || session.player != player) return;
        Transfer transfer = session.transfers.get(packet.ticket());
        if (transfer == null || transfer.reading || packet.sequence() != transfer.sequence) return;
        transfer.reading = true; transfer.lastAccess = System.nanoTime();
        try {
            WORK.execute(() -> {
                byte[] bytes = new byte[(int)Math.min(MediaPackets.CHUNK_SIZE, transfer.size - (long)transfer.sequence * MediaPackets.CHUNK_SIZE)];
                String problem = null;
                try (FileChannel file = FileChannel.open(transfer.path, StandardOpenOption.READ)) {
                    file.position((long)packet.sequence() * MediaPackets.CHUNK_SIZE);
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) if (file.read(buffer) < 0) throw new EOFException();
                } catch (IOException error) { problem = "音频传输中断，请重试。"; }
                final String failure = problem;
                player.server.execute(() -> {
                    if (session.transfers.get(packet.ticket()) != transfer) return;
                    if (failure != null) { session.transfers.remove(packet.ticket()); transfer.close(); error(player, packet.ticket(), failure); return; }
                    boolean last = (long)(transfer.sequence + 1) * MediaPackets.CHUNK_SIZE >= transfer.size;
                    send(player, new MediaPackets.Reply(packet.ticket(), MediaPackets.CHUNK, "", transfer.sequence++, last, bytes));
                    transfer.reading = false;
                    if (last) { session.transfers.remove(packet.ticket()); transfer.close(); }
                });
            });
        } catch (RejectedExecutionException busy) {
            transfer.reading = false; session.transfers.remove(packet.ticket()); transfer.close(); error(player, packet.ticket(), "媒体传输繁忙，请重试。");
        }
    }
    public static void cancel(ServerPlayer player, UUID ticket) {
        Session session = SESSIONS.get(player.getUUID()); if (session == null || session.player != player) return;
        Job job = session.jobs.remove(ticket); if (job != null) job.cancel();
        Transfer transfer = session.transfers.remove(ticket); if (transfer != null) transfer.close();
    }
    private static List<MusicTrack> readTracks(JsonObject result, String key, int limit) {
        if (!result.has(key) || !result.get(key).isJsonArray()) throw new IllegalArgumentException("缺少歌曲列表");
        List<MusicTrack> list = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (JsonElement item : result.getAsJsonArray(key)) {
            if (list.size() >= limit) break;
            MusicTrack track = MusicTrack.from(item.getAsJsonObject()); if (seen.add(track.id())) list.add(track);
        }
        return List.copyOf(list);
    }
    private static JsonArray tracks(List<MusicTrack> list) { JsonArray array = new JsonArray(); list.forEach(t -> array.add(t.json())); return array; }
    private static JsonObject playlistJson(List<MusicTrack> list, String message) {
        JsonObject json = new JsonObject(); json.add("playlist", tracks(list)); json.addProperty("message", message); return json;
    }
    private static List<MusicTrack> playlist(ServerPlayer player) {
        ListTag list = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getList(PLAYLIST_KEY, Tag.TAG_COMPOUND);
        List<MusicTrack> result = new ArrayList<>();
        for (int i = 0; i < Math.min(100, list.size()); i++) try { result.add(MusicTrack.load(list.getCompound(i))); } catch (RuntimeException ignored) { }
        return result;
    }
    private static void savePlaylist(ServerPlayer player, List<MusicTrack> playlist) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        ListTag list = new ListTag(); playlist.stream().limit(100).forEach(t -> list.add(t.save()));
        persisted.put(PLAYLIST_KEY, list); player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }
    private static String text(JsonObject j, String key, int limit) { return j.has(key) && j.get(key).isJsonPrimitive() ? MusicTrack.bound(j.get(key).getAsString(), limit) : ""; }
    private static void close(InputStream input) { if (input != null) try { input.close(); } catch (IOException ignored) { } }
    private static void discard(Audio audio) { if (audio != null) { delete(audio.path); RESERVED.addAndGet(-audio.size); } }
    private static void delete(Path path) { if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
    private static void clear(Session session) {
        session.jobs.values().forEach(Job::cancel); session.jobs.clear(); session.transfers.values().forEach(Transfer::close); session.transfers.clear();
        session.spokenReplies.clear(); session.chatReceipts.clear();
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { Session session = SESSIONS.remove(event.getEntity().getUUID()); if (session != null) clear(session); }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { SESSIONS.values().forEach(MediaServer::clear); SESSIONS.clear(); WORK.purge(); VOICE_WORK.purge(); }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        for (Session session : SESSIONS.values()) {
            var jobs = session.jobs.entrySet().iterator();
            while (jobs.hasNext()) { var item = jobs.next();
                Job job = item.getValue();
                RobotEntity currentRobot = job.operation == MediaPackets.PHONE_CHAT ? RobotContacts.resolve(session.player.server, job.target) : null;
                if (job.operation == MediaPackets.PHONE_CHAT && session.player.level.getGameTime() % 20 == 0
                        && (currentRobot == null || !currentRobot.character().id.equals(job.character) || !phoneChatAccess(session.player, currentRobot))) {
                    jobs.remove(); job.cancel(); error(session.player, item.getKey(), "手机、目标或权限已改变，本次远程聊天已取消。"); continue;
                }
                long timeout = job.operation == MediaPackets.VOICE || job.operation == MediaPackets.ASSISTANT_VOICE ? 650_000_000_000L : 200_000_000_000L;
                if (now - item.getValue().started > timeout) { item.getValue().cancel(); jobs.remove(); error(session.player, item.getKey(), "媒体请求超时，已取消。"); } }
            var transfers = session.transfers.entrySet().iterator();
            while (transfers.hasNext()) { var item = transfers.next(); if (now - item.getValue().lastAccess > 60_000_000_000L) { item.getValue().close(); transfers.remove(); error(session.player, item.getKey(), "音频传输空闲超时。"); } }
        }
    }
}
