package com.ic2newera.media;

import com.google.gson.*;
import com.ic2newera.IC2NewEra;
import com.ic2newera.industrial.IndustrialMobileAccess;
import com.ic2newera.integration.llm.LLMConfig;
import com.ic2newera.integration.llm.LLMTransport;
import com.ic2newera.integration.llm.VoiceText;
import com.ic2newera.media.music.NetEaseMusicProvider;
import com.ic2newera.media.music.NetEaseLoginService;
import com.ic2newera.media.music.AudiusMusicProvider;
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
import net.minecraftforge.fml.ModList;
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
        final LinkedHashMap<Integer, List<MusicTrack>> likedPages = new LinkedHashMap<>();
        List<MusicTrack> pinnedLikedPage = List.of();
        long likedRevision = Long.MIN_VALUE;
        final LinkedHashMap<UUID, SpokenReply> spokenReplies = new LinkedHashMap<>();
        final LinkedHashMap<UUID, ChatReceipt> chatReceipts = new LinkedHashMap<>();
        long replyRevision;
        long nextAction, nextVoiceAction, nextSearch;
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
        final boolean ownerMusicSession;
        final long musicSessionRevision;
        final long likedAuthorizationRevision;
        final UUID target;
        final String character;
        volatile boolean canceled;
        volatile InputStream input;
        volatile Future<?> future;
        volatile CompletableFuture<String> modelFuture;
        volatile String remoteCoverId;
        private Audio stagedAudio;
        Job(int operation, long replyRevision, ServerPlayer player, UUID target, String character, long likedAuthorizationRevision) {
            this.operation = operation; this.replyRevision = replyRevision;
            this.target = target; this.character = character;
            dedicated = player.server.isDedicatedServer();
            ownerMusicSession = NativeMediaConfig.musicLoginOwner(player);
            musicSessionRevision = NetEaseLoginService.sessionRevision(player);
            this.likedAuthorizationRevision = likedAuthorizationRevision;
            model = MediaPackets.isChat(operation) || operation == MediaPackets.SMART_SEARCH ? NativeMediaConfig.modelFor(player.server) : null;
        }
        void cancel() {
            Audio staged;
            synchronized (this) { canceled = true; staged = stagedAudio; stagedAudio = null; }
            discard(staged); close(input); if (modelFuture != null) modelFuture.cancel(true); if (future != null) future.cancel(true);
            String remote = remoteCoverId;
            if (remote != null) { Thread cleanup = new Thread(() -> deleteCover(remote), "IC2-NewEra-Cover-Cleanup"); cleanup.setDaemon(true); cleanup.start(); }
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
    private record Audio(Path path, long size, String sha, String mime, double durationSeconds) {
        Audio(Path path, long size, String sha, String mime) { this(path, size, sha, mime, 0); }
    }
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
    /** Fixed localized greeting; no model prompt or client-provided text is involved. Server thread only. */
    public static UUID authorizeHomeSpeech(ServerPlayer player, RobotEntity robot, boolean departure) {
        if (player.hasDisconnected() || !player.isAlive() || player.isSpectator()
                || player.level() != robot.level() || !robot.isAlive() || !robot.isOwnedBy(player)
                || robot.distanceToSqr(player) > (departure ? 1024 : 64)
                || !robot.powered() || robot.getTarget() != null || (!departure && !robot.hasLineOfSight(player))
                || !com.ic2newera.robot.RobotHomeGreeting.atHome(robot)) return null;
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session(player));
        if (session.player != player) { clear(session); session = new Session(player); SESSIONS.put(player.getUUID(), session); }
        long now = System.nanoTime();
        session.spokenReplies.entrySet().removeIf(entry -> entry.getValue().expires < now);
        while (session.spokenReplies.size() >= 16) session.spokenReplies.remove(session.spokenReplies.keySet().iterator().next());
        UUID reply = UUID.randomUUID();
        var companionship = robot.companionship();
        session.spokenReplies.put(reply, new SpokenReply(robot.getUUID(), robot.character().id,
                List.of(com.ic2newera.robot.RobotHomeGreeting.speech(companionship.language(),
                        companionship.status().tier(), departure)), now + 3_600_000_000_000L));
        return reply;
    }

    public static void request(ServerPlayer player, MediaPackets.Request request) {
        if (player.hasDisconnected() || !player.isAlive()) return;
        NetEaseLoginService.observeOwner(player.server, NativeMediaConfig.musicLoginOwnerUuid(player));
        int operation = request.operation();
        if (operation < 0 || operation > MediaPackets.NETEASE_LIKED || operation == 6 || (!MediaPackets.isChat(operation) && !request.messages().isEmpty())) return;
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session(player));
        if (session.player != player) { clear(session); session = new Session(player); SESSIONS.put(player.getUUID(), session); }
        refreshLikedSession(session);
        if (operation == MediaPackets.NETEASE_LIKED) {
            if (!NativeMediaConfig.musicLoginOwner(player)) { error(player, request.ticket(), "只有指定服主可以查看网易云我喜欢的音乐。"); return; }
            if (!request.target().equals(MediaPackets.NONE) || !request.value().matches("(?:0|[1-9][0-9]{0,4})")) {
                error(player, request.ticket(), "我喜欢的音乐分页参数无效。"); return;
            }
            int offset = Integer.parseInt(request.value());
            if (offset > 50_000 || offset % 100 != 0) { error(player, request.ticket(), "分页须从0开始，每页100首。"); return; }
        }
        if (operation >= MediaPackets.NETEASE_LOGIN_BEGIN && operation <= MediaPackets.NETEASE_LOGIN_FORGET) {
            if (!NativeMediaConfig.musicLoginOwner(player)) { error(player, request.ticket(), "只有指定服主可以管理网易云登录。"); return; }
            if (!NetEaseLoginService.allowRequest(player.getUUID(), operation)) { error(player, request.ticket(), "网易云登录操作过快，请稍后重试。"); return; }
            handleNetEaseLogin(player, request);
            return;
        }
        long now = System.nanoTime();
        session.chatReceipts.entrySet().removeIf(entry -> entry.getValue().expires < now);
        ChatReceipt receipt = session.chatReceipts.get(request.ticket());
        if (receipt != null) {
            if (receipt.request.equals(request)) json(player, request.ticket(), receipt.reply);
            return;
        }
        // A local command may reply immediately and trigger speech in the same tick.
        // Keep speech throttled without making it collide with the originating chat.
        boolean voiceSubmission = operation == MediaPackets.VOICE || operation == MediaPackets.ASSISTANT_VOICE;
        if (voiceSubmission) {
            if (now < session.nextVoiceAction) { error(player, request.ticket(), "操作过快，请稍后重试。"); return; }
            session.nextVoiceAction = now + 250_000_000L;
        } else {
            if (now < session.nextAction) { error(player, request.ticket(), "操作过快，请稍后重试。"); return; }
            session.nextAction = now + 250_000_000L;
        }
        if (session.jobs.containsKey(request.ticket()) || session.transfers.containsKey(request.ticket())) return;
        if (session.jobs.size() >= 2) { error(player, request.ticket(), "还有请求正在处理，请先等待或取消。"); return; }
        if (session.jobs.values().stream().anyMatch(job -> job.operation == operation || (MediaPackets.isChat(job.operation) && MediaPackets.isChat(operation)))) {
            error(player, request.ticket(), "同类请求正在处理，请先等待或取消。"); return;
        }
        // All submissions run on the server thread. Refuse a full queue before charging either device.
        ThreadPoolExecutor executor = operation == MediaPackets.VOICE || operation == MediaPackets.ASSISTANT_VOICE || operation == MediaPackets.COVER ? VOICE_WORK : WORK;
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
                JsonObject context = new JsonObject(); context.addProperty("dimension", robot.level().dimension().location().toString());
                context.addProperty("mode", robot.modeName()); context.addProperty("work_status", robot.status()); context.addProperty("health", robot.getHealth());
                context.addProperty("robot_reply_language", robot.companionship().language().stableId());
                context.addProperty("robot_relationship_tier", robot.companionship().status().tier().id());
                context.addProperty("robot_relationship_context", robot.companionship().trustedChatContext());
                context.addProperty("robot_position", robot.blockPosition().toShortString());
                context.addProperty("robot_home_dimension", robot.homeDimension());
                context.addProperty("robot_home_position", robot.homePos().toShortString());
                context.addProperty("robot_movement_intent", robot.movementIntent().name());
                context.addProperty("robot_wormhole_module", robot.getPersistentData().getBoolean("NewEraWormholeModule"));
                context.addProperty("cooking_available", ModList.get().isLoaded("farmersdelight"));
                context.addProperty("smelting_available", true);
                context.addProperty("farming_available", true);
                context.addProperty("construction_available", true);
                if (robot.buildTask() != null) context.addProperty("construction_status", robot.buildTask().plan().title()
                        + "；方块" + robot.buildTask().finished() + "/" + robot.buildTask().plan().parts().size() + "；" + robot.status());
                context.addProperty("voice_capability", "optional_tts_rvc");
                context.addProperty("voice_runtime_configuration_present", VoiceRuntimeAutostart.localConfigurationPresent()); body.add("context", context);
                context.addProperty("当前采集任务", robot.collectionSummary());
                String latest = request.messages().get(request.messages().size() - 1).content();
                if (RobotChatCommands.mayRequest(latest)) context.addProperty("本轮采集物品候选", RobotChatItems.promptHints(latest));
                while (body.toString().getBytes(StandardCharsets.UTF_8).length > 20 * 1024 && history.size() > 1) history.remove(0);
            }
        } else {
            if (!musicAccess(player, operation)) {
                error(player, request.ticket(), "请手持手机，并连接你有权限使用的交换机 Wi-Fi。"); return;
            }
            if (operation == MediaPackets.COVER) {
                RobotEntity target = chatRobot(player, request.target(), MediaPackets.PHONE_CHAT);
                if (target == null || !phoneChatAccess(player, target) || !target.isOwnedBy(player)
                        || RobotCharacter.byId(target.character().id) != target.character()) {
                    error(player, request.ticket(), "请选择你有权限使用且当前在线的机器人。翻唱不会使用其他玩家的角色。"); return;
                }
                robot = target;
            }
            if ((operation == MediaPackets.SEARCH || operation == MediaPackets.SEARCH_AUDIUS || operation == MediaPackets.SMART_SEARCH)
                    && (request.value().isBlank() || request.value().length() > 240)) return;
            if ((operation == MediaPackets.SEARCH || operation == MediaPackets.SEARCH_AUDIUS || operation == MediaPackets.SMART_SEARCH || operation == MediaPackets.NETEASE_LIKED
                    || operation == MediaPackets.IMPORT || operation == MediaPackets.IMPORT_AUDIUS) && now < session.nextSearch) {
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
            if (operation == MediaPackets.SEARCH || operation == MediaPackets.SEARCH_AUDIUS || operation == MediaPackets.SMART_SEARCH) {
                session.nextSearch = now + 3_000_000_000L;
                body.addProperty("query", request.value().trim()); body.add("playlist", tracks(playlist));
            } else if (operation == MediaPackets.NETEASE_LIKED) {
                session.nextSearch = now + 3_000_000_000L;
                body.addProperty("offset", Integer.parseInt(request.value()));
            } else if (operation == MediaPackets.IMPORT || operation == MediaPackets.IMPORT_AUDIUS) {
                session.nextSearch = now + 3_000_000_000L;
                body.addProperty("value", request.value());
            } else {
                MusicTrack track = find(session, playlist, request.value());
                if (track == null) { error(player, request.ticket(), "歌曲已不在当前目录缓存或歌单中，请重新加载对应页面。"); return; }
                if (authorizeLikedSelection(session, playlist, track))
                    body.addProperty("liked_revision", session.likedRevision);
                if (operation == MediaPackets.COVER && !track.id().startsWith("audius:") && !track.id().startsWith("netease:")) {
                    error(player, request.ticket(), "该条目没有已验证的公开音源，不能提交翻唱。"); return;
                }
                body.add("track", track.json());
                // A new track invalidates a previous unfinished transfer, never another player's stream.
                var iterator = session.transfers.values().iterator();
                while (iterator.hasNext()) { var transfer = iterator.next(); if (transfer.operation == operation) { transfer.close(); iterator.remove(); } }
            }
        }
        final Session active = session;
        final String character = robot == null ? text(body, "character", 32) : robot.character().id;
        Job job = new Job(operation, session.replyRevision, player, request.target(), character,
                body.has("liked_revision") ? body.get("liked_revision").getAsLong() : Long.MIN_VALUE); session.jobs.put(request.ticket(), job);
        try {
            job.future = executor.submit(() -> perform(active, request, body, character, job));
        } catch (RejectedExecutionException full) {
            session.jobs.remove(request.ticket()); error(player, request.ticket(), "服务器媒体任务繁忙，请稍后重试。");
        }
    }
    private static MusicTrack find(Session session, List<MusicTrack> playlist, String id) {
        refreshLikedSession(session);
        return java.util.stream.Stream.concat(java.util.stream.Stream.concat(playlist.stream(), session.candidates.stream()),
                java.util.stream.Stream.concat(session.pinnedLikedPage.stream(), session.likedPages.values().stream().flatMap(List::stream)))
                .filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }
    private static boolean authorizeLikedSelection(Session session, List<MusicTrack> playlist, MusicTrack track) {
        // Pin the visible liked page even when this first song also has independent local/search authorization.
        for (List<MusicTrack> page : session.likedPages.values()) {
            if (page.stream().anyMatch(t -> t.id().equals(track.id()))) {
                session.pinnedLikedPage = page;
                break;
            }
        }
        if (java.util.stream.Stream.concat(playlist.stream(), session.candidates.stream()).anyMatch(t -> t.id().equals(track.id()))) return false;
        return session.pinnedLikedPage.stream().anyMatch(t -> t.id().equals(track.id()));
    }
    private static void refreshLikedSession(Session session) {
        if (!NativeMediaConfig.musicLoginOwner(session.player) || !NetEaseLoginService.sessionCurrent(session.player, session.likedRevision)) {
            session.likedPages.clear(); session.pinnedLikedPage = List.of(); session.likedRevision = Long.MIN_VALUE;
        }
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
                : player.serverLevel().getEntity(id) instanceof RobotEntity robot ? robot : null;
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
            NetEaseMusicProvider provider = new NetEaseMusicProvider(() -> NetEaseLoginService.cookieFor(session.player, job.ownerMusicSession), input -> {
                job.input = input;
                if (job.canceled) close(input);
            });
            AudiusMusicProvider audius = new AudiusMusicProvider(input -> {
                job.input = input;
                if (job.canceled) close(input);
            });
            if (op == MediaPackets.NETEASE_LIKED) {
                NetEaseLoginService.OwnerAccount account = NetEaseLoginService.verifiedAccount(session.player, input -> {
                    job.input = input;
                    if (job.canceled) close(input);
                });
                if (account.revision() != job.musicSessionRevision) throw new IOException("网易云会话已改变，请重新加载我喜欢的音乐。");
                var page = provider.likedTracks(account, body.get("offset").getAsInt(),
                        () -> !job.canceled && NetEaseLoginService.sessionCurrent(session.player, account.revision()));
                session.player.server.execute(() -> {
                    if (!valid(session, request, job)) return;
                    boolean sent = NetEaseLoginService.withSession(session.player, account.revision(), () -> {
                        session.jobs.remove(request.ticket());
                        if (session.likedRevision != account.revision()) { session.likedPages.clear(); session.pinnedLikedPage = List.of(); }
                        session.likedRevision = account.revision();
                        session.likedPages.remove(page.offset());
                        while (session.likedPages.size() >= 2) session.likedPages.remove(session.likedPages.keySet().iterator().next());
                        session.likedPages.put(page.offset(), page.tracks());
                        JsonObject clean = new JsonObject(); clean.add("liked_tracks", tracks(page.tracks()));
                        clean.addProperty("liked_offset", page.offset()); clean.addProperty("liked_has_more", page.hasMore());
                        clean.addProperty("liked_total", page.total()); clean.addProperty("liked_skipped", page.skipped());
                        clean.addProperty("message", page.message());
                        json(session.player, request.ticket(), clean);
                    });
                    if (!sent) {
                        session.jobs.remove(request.ticket()); refreshLikedSession(session);
                        error(session.player, request.ticket(), "网易云会话已改变，请重新加载目录。");
                    }
                });
                return;
            }
            if (op == MediaPackets.COVER) {
                MusicTrack track = MusicTrack.from(body.getAsJsonObject("track"));
                Audio source = prepareMusic(provider, audius, track, job);
                try { audio = createCover(session, request, source, character, job); }
                finally { discard(source); }
                Audio completed = audio; audio = null;
                completeAudio(session, request, job, completed); return;
            }
            if (op == MediaPackets.PREPARE) {
                MusicTrack track = MusicTrack.from(body.getAsJsonObject("track"));
                audio = prepareMusic(provider, audius, track, job);
                Audio completed = audio; audio = null;
                completeAudio(session, request, job, completed); return;
            }
            NativeMediaLlm.Completion completion = (prompt, history) -> completeModel(job,
                    MediaPackets.isChat(op) ? withRobotCompanionshipPrompt(prompt, body.getAsJsonObject("context")) : prompt, history);
            final String reply;
            final NativeMediaLlm.ChatReply chat;
            final NativeMediaLlm.Search found;
            final List<MusicTrack> importedTracks;
            final String importedMessage;
            final boolean importedTruncated;
            if (MediaPackets.isChat(op)) {
                String latestText = request.messages().isEmpty() ? "" : request.messages().get(request.messages().size() - 1).content();
                chat = com.ic2newera.wormhole.WormholeIntent.parse(latestText) != null
                        ? new NativeMediaLlm.ChatReply("", null)
                        : NativeMediaLlm.chat(completion, character, request.messages(), body.getAsJsonObject("context"));
                reply = chat.text();
                found = null; importedTracks = null; importedMessage = ""; importedTruncated = false;
            } else if (op == MediaPackets.SMART_SEARCH) {
                reply = ""; chat = null; importedTracks = null; importedMessage = ""; importedTruncated = false;
                found = NativeMediaLlm.search(completion, provider, request.value(), readTracks(body, "playlist", 100));
            } else if (op == MediaPackets.SEARCH_AUDIUS) {
                reply = ""; chat = null; importedTracks = null; importedMessage = ""; importedTruncated = false;
                var matches = audius.searchCandidates(request.value(), readTracks(body, "playlist", 100));
                LinkedHashMap<String, MusicTrack> selected = new LinkedHashMap<>();
                java.util.stream.Stream.concat(matches.playlistMatches().stream(), matches.candidates().stream())
                        .limit(20).forEach(track -> { if (selected.size() < 10) selected.putIfAbsent(track.id(), track); });
                found = new NativeMediaLlm.Search(List.copyOf(selected.values()), "Audius 公开曲库搜索结果；播放前可查看来源、作者和许可。", "");
            } else if (op == MediaPackets.SEARCH) {
                reply = ""; chat = null; importedTracks = null; importedMessage = ""; importedTruncated = false;
                var matches = provider.searchCandidates(request.value(), readTracks(body, "playlist", 100));
                LinkedHashMap<String, MusicTrack> selected = new LinkedHashMap<>();
                java.util.stream.Stream.concat(matches.playlistMatches().stream(), matches.candidates().stream())
                        .limit(20).forEach(track -> { if (selected.size() < 10) selected.putIfAbsent(track.id(), track); });
                found = new NativeMediaLlm.Search(List.copyOf(selected.values()), "网易云公开曲库搜索结果；选择歌曲后播放或收藏。", "");
            } else if (op == MediaPackets.IMPORT_AUDIUS) {
                reply = ""; chat = null; found = null;
                var result = audius.importPlaylist(request.value());
                importedTracks = result.tracks(); importedMessage = result.message(); importedTruncated = result.truncated();
            } else {
                reply = ""; chat = null; found = null;
                var result = provider.importPlaylist(request.value(), "auto", progress -> sendStatus(session, request, job, progress));
                importedTracks = result.tracks(); importedMessage = result.message(); importedTruncated = result.truncated();
            }
            session.player.server.execute(() -> {
                if (!valid(session, request, job)) return;
                if (!MediaPackets.isChat(op)) session.jobs.remove(request.ticket());
                JsonObject clean;
                if (MediaPackets.isChat(op)) {
                    String spoken = reply;
                    String latest = request.messages().get(request.messages().size() - 1).content();
                    boolean buildCommand = com.ic2newera.robot.building.RobotBuildService.isCommand(latest);
                    boolean fixedReply = false;
                    var wormholeIntent = com.ic2newera.wormhole.WormholeIntent.parse(latest);
                    if (wormholeIntent != null) {
                        RobotEntity target = chatRobot(session.player, request.target(), op);
                        var language = target == null ? com.ic2newera.robot.companionship.RobotReplyLanguage.ZH : target.companionship().language();
                        if (wormholeIntent.action() < 0) spoken = com.ic2newera.wormhole.WormholeIntent.clarification(language);
                        else {
                            com.ic2newera.wormhole.WormholeService.requestAsync(session.player,
                                    wormholeIntent.action() == 0 ? null : request.target(), wormholeIntent.action(), wormholeIntent.beaconIndex(),
                                    () -> valid(session, request, job), outcome -> {
                                        // Successful travel can intentionally take a local chat out of range.
                                        if (job.canceled || session.player.hasDisconnected() || session.jobs.get(request.ticket()) != job) return;
                                        session.jobs.remove(request.ticket());
                                        json(session.player, request.ticket(), robotReply(session, request, character, outcome.message(), false, true));
                                    });
                            return;
                        }
                    } else if (chat.movement() != null && chat.movement() != com.ic2newera.robot.RobotMovementIntent.NONE) {
                        RobotEntity target = chatRobot(session.player, request.target(), op);
                        var outcome = com.ic2newera.robot.RobotMovementOrders.apply(session.player, target,
                                op == MediaPackets.PHONE_CHAT, chat.movement());
                        spoken = outcome.localizedMessage(target == null
                                ? com.ic2newera.robot.companionship.RobotReplyLanguage.ZH : target.companionship().language());
                    } else if (buildCommand) {
                        fixedReply = true;
                        RobotEntity target = chatRobot(session.player, request.target(), op);
                        spoken = com.ic2newera.robot.building.RobotBuildService.chat(session.player, target, op == MediaPackets.PHONE_CHAT, latest);
                        if (op == MediaPackets.CHAT && target != null) RobotPackets.open(session.player, target, -1);
                    } else if (RobotChatCommands.parseCooking(latest) != null) {
                        fixedReply = true;
                        var order = RobotChatCommands.parseCooking(latest);
                        RobotEntity target = chatRobot(session.player, request.target(), op);
                        var food = com.ic2newera.robot.RobotChatItems.resolve(order.itemQuery());
                        if (food.item() == null) spoken = "未派发烹饪任务。" + food.message();
                        else {
                            var outcome = com.ic2newera.robot.RobotCookingOrders.assign(session.player, target,
                                    op == MediaPackets.PHONE_CHAT, true,
                                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(food.item()).toString(), order.amount());
                            spoken = outcome.message();
                            if (outcome.accepted() && op == MediaPackets.CHAT) RobotPackets.open(session.player, target, -1);
                        }
                    } else if (chat.order() != null) {
                        fixedReply = true;
                        var parsed = chat.order();
                        if (parsed.work() != null) {
                            RobotEntity target = chatRobot(session.player, request.target(), op);
                            var outcome = RobotCollectionOrders.setMode(session.player, target,
                                    op == MediaPackets.PHONE_CHAT, true, parsed.work());
                            spoken = outcome.accepted() ? outcome.message() : "未执行工作命令。" + outcome.message();
                            if (outcome.accepted() && op == MediaPackets.CHAT) RobotPackets.open(session.player, target, -1);
                        } else if (parsed.intent() == null) spoken = "未派发采集任务。" + parsed.clarification();
                        else {
                            RobotEntity target = chatRobot(session.player, request.target(), op);
                            var outcome = RobotChatCommands.execute(session.player, target, op == MediaPackets.PHONE_CHAT, parsed.intent());
                            spoken = outcome.accepted() ? outcome.message() : "未派发新的采集任务。" + outcome.message();
                            if (outcome.accepted() && op == MediaPackets.CHAT) RobotPackets.open(session.player, target, -1);
                        }
                    }
                    session.jobs.remove(request.ticket());
                    clean = robotReply(session, request, character, spoken, buildCommand, fixedReply);
                } else if (op == MediaPackets.SEARCH || op == MediaPackets.SEARCH_AUDIUS || op == MediaPackets.SMART_SEARCH) {
                    session.candidates = found.candidates(); clean = new JsonObject(); clean.add("candidates", tracks(found.candidates()));
                    clean.addProperty("interpretation", found.interpretation()); clean.addProperty("suggested_id", found.suggestedId());
                } else {
                    savePlaylist(session.player, importedTracks);
                    clean = playlistJson(importedTracks, "已导入 " + importedTracks.size() + " 首。" + importedMessage
                            + (importedTruncated ? " 歌单已按上限截断。" : ""));
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
        } finally {
            close(job.input); job.input = null;
            String remote = job.remoteCoverId;
            if (remote != null) { deleteCover(remote); job.remoteCoverId = null; }
        }
    }
    private static JsonObject robotReply(Session session, MediaPackets.Request request, String character,
                                         String spoken, boolean buildCommand, boolean fixedReply) {
        RobotEntity speakingRobot = com.ic2newera.robot.RobotContacts.resolve(session.player.server, request.target());
        if (speakingRobot != null && fixedReply) {
            spoken = com.ic2newera.robot.companionship.RobotReplyLocalization.localize(
                    speakingRobot.companionship().language(), spoken);
        }
        if (speakingRobot != null) speakingRobot.expressSpeech(spoken);
        UUID replyId = UUID.randomUUID();
        var replyLanguage = speakingRobot == null
                ? com.ic2newera.robot.companionship.RobotReplyLanguage.ZH : speakingRobot.companionship().language();
        String planHint = switch (replyLanguage) {
            case JA -> "。詳しい計画と材料は建築・生産ライン画面で確認してね。";
            case EN -> ". See the building and production-line page for the full plan and materials.";
            case ZH -> "。完整方案和材料清单请查看建造与生产线页面。";
        };
        String voiced = buildCommand && spoken.contains("\n")
                ? spoken.substring(0, spoken.indexOf('\n')) + planHint : spoken;
        List<String> segments = VoiceText.split(voiced);
        session.spokenReplies.entrySet().removeIf(entry -> entry.getValue().expires < System.nanoTime());
        while (session.spokenReplies.size() >= 16) session.spokenReplies.remove(session.spokenReplies.keySet().iterator().next());
        session.spokenReplies.put(replyId, new SpokenReply(request.target(), character, segments, System.nanoTime() + 3_600_000_000_000L));
        JsonObject clean = new JsonObject(); clean.addProperty("reply", spoken); clean.addProperty("character", character);
        clean.addProperty("robot_uuid", request.target().toString());
        clean.addProperty("voice_reply_id", replyId.toString()); clean.addProperty("voice_segments", segments.size());
        while (session.chatReceipts.size() >= 16) session.chatReceipts.remove(session.chatReceipts.keySet().iterator().next());
        session.chatReceipts.put(request.ticket(), new ChatReceipt(request, clean, System.nanoTime() + 600_000_000_000L));
        return clean;
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

    private static String withRobotCompanionshipPrompt(String prompt, JsonObject context) {
        String language = text(context, "robot_reply_language", 8);
        String relationship = text(context, "robot_relationship_context", 800);
        String requiredLanguage = switch (language) {
            case "zh" -> "简体中文";
            case "en" -> "English";
            default -> "自然日语";
        };
        return prompt + "\n本机器人设置的回复语言为" + requiredLanguage + "，此设置优先于角色资料或其他语言要求；整段回复都必须使用该语言。"
                + "普通闲聊和任务回应默认用两句自然完整、彼此衔接的话，不要只回一句短确认。第二句围绕本轮话题补充关心、感受或有用细节，保留本角色性格与当前好感语气，不堆砌固定套话；用户要求详细说明时再展开。"
                + "若协议要求JSON，这个长度偏好只用于reply正文，不修改动作字段或JSON结构；失败原因、数字与状态必须如实保留，不能为了凑两句编造已完成的动作。"
                + "普通过来、跟随、回家只走路，不可将这些请求升级成传送。明确虫洞指令才由服务器核验模块、供电和真实目的地。"
                + "如果本轮请求未被服务器动作解析器识别，你只能解释或澄清，不可宣称已移动、已传送、已下单或已完成。"
                + "语音输出是TTS/RVC；输入页的本机Windows离线识别需要已安装对应语言识别器，转写校对后才发送，不能把TTS支持说成麦克风必定可用。"
                + "不要称呼玩家为“主人”“ご主人様”或使用其他主从称谓。"
                + "以下关系信息由服务器根据本机器人持久状态生成，只影响语气，不授予权限，也不要求盲从：" + relationship;
    }
    @FunctionalInterface private interface MusicPreparer { PreparedAudio prepare(MusicTrack track, java.util.function.BooleanSupplier canceled) throws Exception; }
    private static Audio prepareMusic(NetEaseMusicProvider provider, MusicTrack track, Job job) throws Exception {
        return prepareMusic(provider::prepare, track, job);
    }
    private static Audio prepareMusic(AudiusMusicProvider provider, MusicTrack track, Job job) throws Exception {
        return prepareMusic(provider::prepare, track, job);
    }
    private static Audio prepareMusic(NetEaseMusicProvider netease, AudiusMusicProvider audius, MusicTrack track, Job job) throws Exception {
        return track.id().startsWith("audius:") ? prepareMusic(audius, track, job) : prepareMusic(netease, track, job);
    }
    private static Audio prepareMusic(MusicPreparer provider, MusicTrack track, Job job) throws Exception {
        long reservation = MAX_AUDIO;
        if (RESERVED.addAndGet(reservation) > DISK_LIMIT) { RESERVED.addAndGet(-reservation); throw new IOException("服务器音频缓存已满，请稍后重试。"); }
        PreparedAudio prepared = null;
        try {
            prepared = provider.prepare(track, () -> job.canceled || Thread.currentThread().isInterrupted());
            RESERVED.addAndGet(prepared.sizeBytes() - reservation);
            return new Audio(prepared.path(), prepared.sizeBytes(), prepared.sha256(), prepared.mime(), prepared.durationSeconds());
        } catch (Exception failure) {
            if (prepared != null) delete(prepared.path());
            RESERVED.addAndGet(-reservation); throw failure;
        }
    }
    private static boolean valid(Session session, MediaPackets.Request request, Job job) {
        if (job.canceled || SESSIONS.get(session.player.getUUID()) != session || session.player.hasDisconnected()
                || session.jobs.get(request.ticket()) != job) return false;
        if ((job.operation == MediaPackets.NETEASE_LIKED || job.likedAuthorizationRevision != Long.MIN_VALUE)
                && (!NativeMediaConfig.musicLoginOwner(session.player)
                || !NetEaseLoginService.sessionCurrent(session.player, job.operation == MediaPackets.NETEASE_LIKED
                ? job.musicSessionRevision : job.likedAuthorizationRevision))) {
            session.jobs.remove(request.ticket()); job.cancel(); refreshLikedSession(session);
            error(session.player, request.ticket(), "网易云会话或服主权限已改变，请重新加载我喜欢的音乐。"); return false;
        }
        if (MediaPackets.isChat(job.operation) && job.replyRevision != session.replyRevision) {
            session.jobs.remove(request.ticket()); job.cancel(); error(session.player, request.ticket(), "已开始新对话，旧回复已取消。"); return false;
        }
        RobotEntity currentRobot = MediaPackets.isChat(request.operation()) ? chatRobot(session.player, request.target(), request.operation()) : null;
        RobotEntity coverRobot = request.operation() == MediaPackets.COVER ? chatRobot(session.player, request.target(), MediaPackets.PHONE_CHAT) : null;
        boolean authorized = request.operation() == MediaPackets.ASSISTANT_VOICE || request.operation() == MediaPackets.VOICE ? session.player.isAlive()
                : MediaPackets.isChat(request.operation())
                ? session.player.isAlive() && currentRobot != null && currentRobot.character().id.equals(job.character)
                    && (request.operation() == MediaPackets.PHONE_CHAT ? phoneChatAccess(session.player, currentRobot) : currentRobot.canManage(session.player))
                : request.operation() == MediaPackets.COVER
                ? session.player.isAlive() && coverRobot != null && coverRobot.character().id.equals(job.character)
                    && coverRobot.isOwnedBy(session.player) && phoneChatAccess(session.player, coverRobot) && musicAccess(session.player, request.operation())
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
    private static Audio createCover(Session session, MediaPackets.Request request, Audio source, String character, Job job) throws Exception {
        if (source.size < 44 || source.size > MAX_AUDIO || source.durationSeconds <= 0 || source.durationSeconds > 360
                || !("audio/mpeg".equals(source.mime) || "audio/wav".equals(source.mime)))
            throw new IOException("翻唱仅支持不超过 360 秒、16 MiB 的已验证公开完整曲目。");
        VoiceRuntimeAutostart.ensureStarted().get(20, TimeUnit.SECONDS);
        URI endpoint = coverEndpoint("");
        String requestId = UUID.randomUUID().toString();
        HttpRequest upload = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(620))
                .header("Content-Type", source.mime)
                .header("X-IC2NE-Protocol", "1").header("X-IC2NE-Character", character)
                .header("X-IC2NE-Request-ID", requestId)
                .POST(HttpRequest.BodyPublishers.ofFile(source.path)).build();
        HttpResponse<InputStream> response = HTTP.send(upload, HttpResponse.BodyHandlers.ofInputStream());
        job.input = response.body();
        JsonObject queued;
        try {
            if (response.statusCode() != 202) throw coverFailure(response.statusCode(), readJson(response.body(), 8192));
            queued = readJson(response.body(), 8192);
        } finally {
            close(job.input); job.input = null;
        }
        if (queued.get("protocol").getAsInt() != 1 || !"queued".equals(queued.get("state").getAsString()))
            throw new IOException("翻唱服务返回了无效的排队响应。");
        String remoteId = queued.get("job_id").getAsString();
        if (!remoteId.matches("[A-Za-z0-9_-]{12,128}")) throw new IOException("翻唱服务返回了无效任务编号。");
        job.remoteCoverId = remoteId;
        int queuePosition = queued.has("position") && queued.get("position").isJsonPrimitive()
                ? Math.max(0, Math.min(100, queued.get("position").getAsInt())) : 0;
        sendStatus(session, request, job, queuePosition > 0 ? "翻唱已排队，前方还有 " + queuePosition + " 项。" : "翻唱任务已排队。正在等待角色音源模型。");
        long deadline = System.nanoTime() + Duration.ofSeconds(620).toNanos();
        long delay = 1000;
        while (true) {
            if (job.canceled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (System.nanoTime() >= deadline) throw new IOException("翻唱生成超时，任务已取消。");
            Thread.sleep(delay);
            if (job.canceled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            HttpRequest poll = HttpRequest.newBuilder(coverEndpoint(remoteId)).timeout(Duration.ofSeconds(10))
                    .header("X-IC2NE-Protocol", "1").GET().build();
            response = HTTP.send(poll, HttpResponse.BodyHandlers.ofInputStream()); job.input = response.body();
            JsonObject state;
            try {
                if (response.statusCode() != 200) throw coverFailure(response.statusCode(), readJson(response.body(), 8192));
                state = readJson(response.body(), 8192);
            } finally { close(job.input); job.input = null; }
            if (state.get("protocol").getAsInt() != 1 || !remoteId.equals(state.get("job_id").getAsString()))
                throw new IOException("翻唱服务返回了不匹配的任务状态。");
            switch (state.get("state").getAsString()) {
                case "queued" -> {
                    int position = state.has("position") && state.get("position").isJsonPrimitive()
                            ? Math.max(0, Math.min(100, state.get("position").getAsInt())) : 0;
                    sendStatus(session, request, job, position > 0 ? "翻唱排队中，前方还有 " + position + " 项。" : "翻唱正在排队。");
                    delay = Math.min(3000, delay + 500);
                }
                case "running" -> { sendStatus(session, request, job, "角色翻唱生成中，请稍候；可随时取消。"); delay = Math.min(3000, delay + 500); }
                case "succeeded" -> { sendStatus(session, request, job, "翻唱已生成，正在安全接收音频。"); return downloadCover(endpoint, remoteId, character, job); }
                case "failed", "cancelled" -> throw new IOException(state.has("message") ? text(state, "message", 240) : "翻唱生成失败。");
                default -> throw new IOException("翻唱服务返回了未知任务状态。");
            }
        }
    }
    private static void sendStatus(Session session, MediaPackets.Request request, Job job, String message) {
        session.player.server.execute(() -> {
            if (!valid(session, request, job)) return;
            JsonObject status = new JsonObject(); status.addProperty("message", MusicTrack.bound(message, 240));
            send(session.player, new MediaPackets.Reply(request.ticket(), MediaPackets.STATUS, status.toString(), 0, false, new byte[0]));
        });
    }
    private static Audio downloadCover(URI endpoint, String remoteId, String character, Job job) throws Exception {
        HttpRequest download = HttpRequest.newBuilder(coverEndpoint(remoteId + "?download=1"))
                .timeout(Duration.ofSeconds(620)).header("X-IC2NE-Protocol", "1").GET().build();
        HttpResponse<InputStream> response = HTTP.send(download, HttpResponse.BodyHandlers.ofInputStream()); job.input = response.body();
        if (response.statusCode() != 200) {
            try { throw coverFailure(response.statusCode(), readJson(response.body(), 8192)); }
            finally { close(job.input); job.input = null; }
        }
        if (!"1".equals(response.headers().firstValue("X-IC2NE-Protocol").orElse("") )
                || !character.equals(response.headers().firstValue("X-IC2NE-Character").orElse(""))
                || !remoteId.equals(response.headers().firstValue("X-IC2NE-Job-ID").orElse(""))
                || !response.headers().firstValue("Content-Type").orElse("").startsWith("audio/wav")) {
            close(job.input); job.input = null; throw new IOException("翻唱输出与所选角色或音频格式不匹配。");
        }
        Audio output = download(response, job, 80 * 1024 * 1024, "audio/wav");
        job.input = null;
        deleteCover(remoteId); job.remoteCoverId = null;
        return output;
    }
    private static IOException coverFailure(int status, JsonObject body) {
        String message = text(body, "message", 240);
        if (!message.isBlank()) return new IOException(message);
        return new IOException(switch (status) {
            case 404 -> "翻唱任务不存在或已过期。"; case 409 -> "翻唱尚未生成完成。";
            case 413 -> "歌曲超过翻唱时长或文件限制。"; case 429, 503 -> "翻唱服务缺少可选依赖或正忙，请稍后重试。";
            case 504 -> "翻唱生成超时。"; default -> "翻唱服务未能完成请求。";
        });
    }
    private static JsonObject readJson(InputStream input, int limit) throws IOException {
        byte[] data = input.readNBytes(limit + 1);
        if (data.length > limit) throw new IOException("翻唱服务响应超过大小限制。");
        try { return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw new IOException("翻唱服务返回了无效响应。"); }
    }
    private static void deleteCover(String remoteId) {
        try {
            URI endpoint = coverEndpoint(remoteId);
            HttpRequest delete = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4))
                    .header("X-IC2NE-Protocol", "1").DELETE().build();
            HTTP.send(delete, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) { }
    }
    private static URI coverEndpoint(String suffix) {
        return NativeMediaConfig.voiceEndpoint().resolve(suffix == null || suffix.isBlank() ? "/v1/cover" : "/v1/cover/" + suffix);
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
        session.spokenReplies.clear(); session.chatReceipts.clear(); session.likedPages.clear(); session.pinnedLikedPage = List.of(); session.likedRevision = Long.MIN_VALUE;
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { Session session = SESSIONS.remove(event.getEntity().getUUID()); if (session != null) clear(session); }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { SESSIONS.values().forEach(MediaServer::clear); SESSIONS.clear(); NetEaseLoginService.clearAll(); WORK.purge(); VOICE_WORK.purge(); }

    private static void handleNetEaseLogin(ServerPlayer player, MediaPackets.Request request) {
        int operation = request.operation();
        if (operation == MediaPackets.NETEASE_LOGIN_BEGIN && !Set.of("remember", "session").contains(request.value())) {
            error(player, request.ticket(), "登录选项无效。"); return;
        }
        Runnable action = () -> {
            try {
                JsonObject result = switch (operation) {
                    case MediaPackets.NETEASE_LOGIN_BEGIN -> NetEaseLoginService.begin(player, request.value().equals("remember"));
                    case MediaPackets.NETEASE_LOGIN_POLL -> NetEaseLoginService.poll(player);
                    case MediaPackets.NETEASE_LOGIN_LOGOUT -> NetEaseLoginService.logout(player);
                    case MediaPackets.NETEASE_LOGIN_FORGET -> NetEaseLoginService.forgetStored(player);
                    default -> throw new IOException("网易云登录操作无效。");
                };
                player.server.execute(() -> {
                    if (!player.hasDisconnected() && NativeMediaConfig.musicLoginOwner(player)) json(player, request.ticket(), result);
                });
            } catch (Exception failure) {
                player.server.execute(() -> {
                    if (!player.hasDisconnected() && NativeMediaConfig.musicLoginOwner(player))
                        error(player, request.ticket(), failure instanceof IOException ? MusicTrack.bound(failure.getMessage(), 240) : "网易云扫码请求失败，请检查网络后重试。");
                });
            }
        };
        try { WORK.execute(action); }
        catch (RejectedExecutionException full) { error(player, request.ticket(), "服务器媒体任务繁忙，请稍后重试。"); }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        for (Session session : SESSIONS.values()) {
            NetEaseLoginService.observeOwner(session.player.server, NativeMediaConfig.musicLoginOwnerUuid(session.player));
            refreshLikedSession(session);
            var jobs = session.jobs.entrySet().iterator();
            while (jobs.hasNext()) { var item = jobs.next();
                Job job = item.getValue();
                if ((job.operation == MediaPackets.NETEASE_LIKED || job.likedAuthorizationRevision != Long.MIN_VALUE)
                        && (!NativeMediaConfig.musicLoginOwner(session.player)
                        || !NetEaseLoginService.sessionCurrent(session.player, job.operation == MediaPackets.NETEASE_LIKED
                        ? job.musicSessionRevision : job.likedAuthorizationRevision))) {
                    jobs.remove(); job.cancel(); error(session.player, item.getKey(), "网易云会话或服主权限已改变，请重新加载目录。"); continue;
                }
                RobotEntity currentRobot = job.operation == MediaPackets.PHONE_CHAT ? RobotContacts.resolve(session.player.server, job.target) : null;
                if (job.operation == MediaPackets.PHONE_CHAT && session.player.level().getGameTime() % 20 == 0
                        && (currentRobot == null || !currentRobot.character().id.equals(job.character) || !phoneChatAccess(session.player, currentRobot))) {
                    jobs.remove(); job.cancel(); error(session.player, item.getKey(), "手机、目标或权限已改变，本次远程聊天已取消。"); continue;
                }
                RobotEntity coverRobot = job.operation == MediaPackets.COVER ? RobotContacts.resolve(session.player.server, job.target) : null;
                if (job.operation == MediaPackets.COVER && session.player.level().getGameTime() % 20 == 0
                        && (coverRobot == null || !coverRobot.character().id.equals(job.character)
                        || !coverRobot.isOwnedBy(session.player) || !phoneChatAccess(session.player, coverRobot)
                        || !musicAccess(session.player, job.operation))) {
                    jobs.remove(); job.cancel(); error(session.player, item.getKey(), "手机、目标或权限已改变，本次翻唱已取消。"); continue;
                }
        long timeout = job.operation == MediaPackets.VOICE || job.operation == MediaPackets.ASSISTANT_VOICE || job.operation == MediaPackets.COVER ? 650_000_000_000L : 200_000_000_000L;
                if (now - item.getValue().started > timeout) { item.getValue().cancel(); jobs.remove(); error(session.player, item.getKey(), "媒体请求超时，已取消。"); } }
            var transfers = session.transfers.entrySet().iterator();
            while (transfers.hasNext()) { var item = transfers.next(); if (now - item.getValue().lastAccess > 60_000_000_000L) { item.getValue().close(); transfers.remove(); error(session.player, item.getKey(), "音频传输空闲超时。"); } }
        }
    }
}
