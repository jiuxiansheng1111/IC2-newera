package com.ic2newera.client.robot.voice;

import com.google.gson.JsonObject;
import com.ic2newera.client.media.MediaAudioPlayer;
import com.ic2newera.client.media.MediaClient;
import com.ic2newera.media.MediaPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One process-wide, bounded voice queue shared by robot and assistant screens. */
public final class RobotVoiceClient {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Minecraft MC = Minecraft.getInstance();
    private static final RobotVoiceClient GLOBAL = new RobotVoiceClient();
    private static final int MAX_JOBS = 16, MAX_CACHE_FILES = 32;
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private final MediaAudioPlayer player = new MediaAudioPlayer(true);
    private final Deque<Job> queue = new ArrayDeque<>();
    private final Deque<CachedReply> cache = new ArrayDeque<>();
    private final Set<Path> ownedFiles = new HashSet<>();
    private final Set<Path> cachedFiles = new HashSet<>();
    private Job active;
    private long cacheBytes;
    private int cacheFiles;
    private volatile String status = "语音空闲。";
    private boolean replaying;
    private boolean pausedWanted;
    private long replayRevision;

    private static final class CachedReply {
        final UUID id; final String character;
        final List<CachedSegment> segments = new ArrayList<>();
        boolean truncated;
        CachedReply(UUID id, String character) { this.id=id; this.character=character; }
    }
    private record CachedSegment(Path path, String mime, int index, long bytes) { }
    private static final class Job {
        final UUID robot, reply; final String character; final List<String> localSegments;
        final int total; final boolean assistant; final CompletableFuture<String> result = new CompletableFuture<>();
        volatile CompletableFuture<MediaClient.Result> request;
        CachedReply cached;
        int index;
        Job(UUID robot, UUID reply, String character, int segments) {
            this.robot=robot; this.reply=reply; this.character=character; total=segments;
            localSegments=List.of(); assistant=false;
        }
        Job(String character, List<String> segments) {
            robot=MediaPackets.NONE; reply=UUID.randomUUID(); this.character=character; total=segments.size();
            localSegments=segments; assistant=true;
        }
    }

    private RobotVoiceClient() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            player.stop();
            synchronized (this) {
                cancelJobs();
                deleteOwnedFiles(); cachedFiles.clear();
                cache.clear(); cacheBytes=0; cacheFiles=0;
            }
        }, "ic2newera-voice-cleanup"));
    }

    public static CompletableFuture<String> enqueue(UUID robot, String character, UUID replyId, int segments) {
        return GLOBAL.submit(new Job(robot, replyId, character, segments));
    }
    public static CompletableFuture<String> speakAssistant(String text) {
        if (!RobotVoiceSettings.enabled()) return CompletableFuture.completedFuture("语音已关闭。");
        final List<String> segments;
        try { segments = com.ic2newera.integration.llm.VoiceText.split(text); }
        catch (RuntimeException error) { return CompletableFuture.failedFuture(new IllegalStateException("语音文本分段失败，文字回复已保留。", error)); }
        return GLOBAL.submit(new Job("tomori", segments));
    }
    private CompletableFuture<String> submit(Job job) {
        onClient(() -> {
            if (!RobotVoiceSettings.enabled()) { job.result.complete("语音已关闭。"); return; }
            if (job.total < 1 || job.total > 64 || (job.assistant && job.localSegments.isEmpty())) {
                job.result.completeExceptionally(new IllegalStateException("语音分段信息无效，文字已保留。")); return;
            }
            if (queue.size() + (active == null ? 0 : 1) >= MAX_JOBS) {
                job.result.completeExceptionally(new IllegalStateException("语音队列已满，请停止或等待当前队列。")); return;
            }
            queue.addLast(job); status="语音队列：" + (queue.size() + (active == null ? 0 : 1)) + " 条回复等待播放。";
            runNext();
        });
        return job.result;
    }
    private void runNext() {
        if (active != null || replaying || queue.isEmpty()) return;
        active=queue.removeFirst(); Job job=active;
        job.cached=new CachedReply(job.reply,job.character); cache.addLast(job.cached);
        fetchSegment(job);
    }
    /** Called only on the Minecraft client thread. */
    private void fetchSegment(Job job) {
        if (active != job || job.result.isCancelled()) return;
        if (job.index >= job.total) {
            job.result.complete(job.cached.truncated ? "语音播放完成；重播缓存因容量限制只保留最近部分。" : "语音播放完成。");
            finish(job); return;
        }
        final int index=job.index;
        String value; int operation;
        if (job.assistant) {
            JsonObject request=new JsonObject(); request.addProperty("character",job.character); request.addProperty("text",job.localSegments.get(index));
            value=request.toString(); operation=MediaPackets.ASSISTANT_VOICE;
        } else { value=job.reply + ":" + index; operation=MediaPackets.VOICE; }
        LOG.info("IC2 New Era voice request character={} segment={}/{}",job.character,index+1,job.total);
        CompletableFuture<MediaClient.Result> transfer=MediaClient.request(operation,job.robot,value,List.of()); job.request=transfer;
        transfer.thenComposeAsync(audio -> {
            job.request=null;
            if (active != job || job.result.isCancelled()) { MediaClient.delete(audio.audio()); return CompletableFuture.completedFuture(null); }
            if (audio.audio()==null || !"audio/wav".equals(audio.mime())) {
                MediaClient.delete(audio.audio()); return CompletableFuture.failedFuture(new IllegalStateException("语音格式不受支持，文字已保留。"));
            }
            try {
                Path playback=ownTemp("ic2newera-voice-play-", ".wav");
                Files.copy(audio.audio(),playback,StandardCopyOption.REPLACE_EXISTING);
                cacheFile(audio.audio(),job,index);
                float gain=MC.options.getSoundSourceVolume(SoundSource.MASTER)*MC.options.getSoundSourceVolume(SoundSource.VOICE);
                LOG.info("IC2 New Era voice playback character={} segment={} gain={}",job.character,index+1,gain);
                status="正在播放语音：" + job.character + "（" + (index+1) + "/" + job.total + "）";
                player.setVolume(gain);
                return player.play(playback,"audio/wav",()->!job.result.isCancelled(),pausedWanted)
                        .thenComposeAsync(done -> {
                            releaseOwned(playback);
                            LOG.info("IC2 New Era voice playback completed character={} segment={}",job.character,index+1);
                            job.index++; fetchSegment(job); return CompletableFuture.completedFuture(null);
                        }, MC::execute);
            } catch (IOException error) {
                MediaClient.delete(audio.audio());
                return CompletableFuture.failedFuture(new IllegalStateException("无法准备语音播放文件，文字已保留。",error));
            }
        }, MC::execute).whenCompleteAsync((ignored,error) -> {
            if (error != null && !job.result.isDone()) {
                Throwable cause=error; while(cause.getCause()!=null) cause=cause.getCause();
                LOG.warn("IC2 New Era voice failed character={} segment={} type={}",job.character,index+1,cause.getClass().getSimpleName());
                job.result.completeExceptionally(cause); finish(job);
            }
        }, MC::execute);
    }
    private void cacheFile(Path source, Job job, int index) {
        try {
            long size=Files.size(source); Path copy=ownTemp("ic2newera-voice-cache-",".wav");
            Files.copy(source,copy,StandardCopyOption.REPLACE_EXISTING); MediaClient.delete(source);
            CachedSegment item=new CachedSegment(copy,"audio/wav",index,size);
            job.cached.segments.add(item); cacheBytes+=size; cacheFiles++; cachedFiles.add(copy);
            while(cacheFiles>MAX_CACHE_FILES || cacheBytes>MAX_CACHE_BYTES) {
                CachedReply oldest=cache.peekFirst(); if(oldest==null) break;
                if(oldest.segments.isEmpty()) { cache.removeFirst(); continue; }
                CachedSegment removed=oldest.segments.remove(0); oldest.truncated=true;
                cacheBytes-=removed.bytes(); cacheFiles--; releaseOwned(removed.path());
                if(oldest.segments.isEmpty() && oldest != job.cached) cache.removeFirst();
            }
        } catch(IOException error) { MediaClient.delete(source); job.cached.truncated=true; }
    }
    private Path ownTemp(String prefix,String suffix) throws IOException {
        Path file=Files.createTempFile(prefix,suffix); ownedFiles.add(file); return file;
    }
    private void releaseOwned(Path path) { ownedFiles.remove(path); cachedFiles.remove(path); deleteNow(path); }
    private void finish(Job job) {
        if(job.cached!=null && job.cached.segments.isEmpty()) cache.remove(job.cached);
        if(active==job) { active=null; status="语音空闲。"; runNext(); }
    }
    public static void pauseOrResume() { onClient(() -> { GLOBAL.pausedWanted=!GLOBAL.pausedWanted; GLOBAL.player.setPaused(GLOBAL.pausedWanted); GLOBAL.status=GLOBAL.pausedWanted?"语音已暂停。":"语音继续播放。"; }); }
    public static boolean paused() { return GLOBAL.pausedWanted; }
    public static void replayLast() { onClient(GLOBAL::replay); }
    private void replay() {
        if(active!=null || replaying) return;
        CachedReply reply=cache.peekLast(); if(reply==null || reply.segments.isEmpty()) { status="没有可重播的语音缓存。"; return; }
        replaying=true; status=reply.truncated?"正在重播最近缓存的部分语音。":"正在重播上一条语音。";
        replaySegment(reply,0,++replayRevision);
    }
    private void replaySegment(CachedReply reply,int position,long revision) {
        if(revision!=replayRevision) return;
        if(position>=reply.segments.size()) { replaying=false; status=reply.truncated?"重播完成；缓存因容量限制只含最近部分。":"重播完成。"; runNext(); return; }
        CachedSegment segment=reply.segments.get(position);
        try {
            Path copy=ownTemp("ic2newera-voice-replay-",".wav"); Files.copy(segment.path(),copy,StandardCopyOption.REPLACE_EXISTING);
            player.play(copy,segment.mime(),()->revision==replayRevision,pausedWanted).thenComposeAsync(done -> {
                releaseOwned(copy); replaySegment(reply,position+1,revision); return CompletableFuture.completedFuture(null);
            },MC::execute).whenCompleteAsync((v,e)-> { if(e!=null) { releaseOwned(copy); if(revision==replayRevision) { replaying=false; status="重播失败，缓存的文字回复仍在记录中。"; runNext(); } } },MC::execute);
        } catch(IOException error) { replaying=false; status="重播缓存读取失败。"; runNext(); }
    }
    public static void cancelAll() { onClient(() -> GLOBAL.clear(false)); }
    private void clear(boolean deleteCache) {
        ++replayRevision; pausedWanted=false; player.stop(); cancelJobs(); replaying=false;
        if(deleteCache) { cache.clear(); cacheBytes=0; cacheFiles=0; cachedFiles.clear(); deleteOwnedFiles(); }
        else for(Path path:List.copyOf(ownedFiles)) if(!cachedFiles.contains(path)) { ownedFiles.remove(path); deleteNow(path); }
        status="语音队列已停止。";
    }
    private void cancelJobs() {
        for(Job job:queue) { job.result.cancel(true); if(job.request!=null) job.request.cancel(true); }
        queue.clear();
        if(active!=null) { Job job=active; active=null; job.result.cancel(true); if(job.request!=null) job.request.cancel(true); }
    }
    private void deleteOwnedFiles() { for(Path path:List.copyOf(ownedFiles)) { ownedFiles.remove(path); deleteNow(path); } }
    private static void deleteNow(Path path) {
        if(path==null) return;
        for(int attempt=0;attempt<5;attempt++) {
            try { Files.deleteIfExists(path); return; }
            catch(IOException error) { if(attempt<4) try { Thread.sleep(20L); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return; } }
        }
    }
    public static void detach(UUID robot) { }
    public static void tickGlobal() { if(MC.player!=null) GLOBAL.player.setVolume(MC.options.getSoundSourceVolume(SoundSource.MASTER)*MC.options.getSoundSourceVolume(SoundSource.VOICE)); }
    public static String statusGlobal() { return GLOBAL.status; }
    public static void resetGlobal() { onClient(() -> GLOBAL.clear(true)); }
    public static void stopVoice() { cancelAll(); }
    private static void onClient(Runnable task) { if(MC.isSameThread()) task.run(); else MC.execute(task); }
}
