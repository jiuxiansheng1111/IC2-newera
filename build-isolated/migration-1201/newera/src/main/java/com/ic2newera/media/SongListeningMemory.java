package com.ic2newera.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ic2newera.robot.RobotEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** One world data file per owner and stable robot identity, including when the robot is packed into its doll. */
final class SongListeningMemory extends SavedData {
    private static final int TRACKS = 32, TURNS = 4;
    private final LinkedHashMap<String, List<Note>> tracks = new LinkedHashMap<>();

    record Note(String song, String title, String artist, UUID play, int visits, long time, String evidence,
                String question, String reply) {}

    static SongListeningMemory forRobot(ServerPlayer owner, RobotEntity robot) {
        String name = "ic2newera_listening_" + owner.getUUID() + "_" + robot.memoryId();
        return owner.server.overworld().getDataStorage().computeIfAbsent(SongListeningMemory::load, SongListeningMemory::new, name);
    }

    List<Note> forSong(String id) { return List.copyOf(tracks.getOrDefault(id, List.of())); }
    List<Note> otherSongs(String id) {
        List<Note> recent = new ArrayList<>();
        tracks.forEach((song, rows) -> {
            if (!song.equals(id)) recent.add(rows.get(rows.size() - 1));
        });
        return List.copyOf(recent.subList(Math.max(0, recent.size() - 5), recent.size()));
    }

    void remember(MusicTrack song, SongConversation.Listening listening, String question, String reply) {
        UUID play = listening.playId();
        String id = song.id();
        List<Note> notes = new ArrayList<>(tracks.getOrDefault(id, List.of()));
        Note last = notes.isEmpty() ? null : notes.get(notes.size() - 1);
        int visits = last == null ? 1 : last.visits;
        if (last != null && !last.play.equals(play)) visits = Math.min(1_000_000, visits + 1);
        var note = new Note(id, SongConversation.safe(song.title(), 150), SongConversation.safe(song.artist(), 120),
                play, visits, System.currentTimeMillis(), listening.description(), SongConversation.safe(question, 280),
                SongConversation.safe(reply, 550));
        if (last != null && last.play.equals(play) && last.question.equals(note.question) && last.reply.equals(note.reply)) return;
        if (notes.size() == TURNS) notes.remove(0);
        notes.add(note);
        tracks.remove(id);
        tracks.put(id, notes);
        if (tracks.size() > TRACKS) tracks.remove(tracks.keySet().iterator().next());
        setDirty();
    }

    List<Note> recallNotes(String question) {
        String search = question.toLowerCase(Locale.ROOT);
        List<Note> matches = new ArrayList<>(), recent = new ArrayList<>();
        for (var rows : tracks.values()) {
            Note n = rows.get(rows.size() - 1);
            if (search.contains(n.song) || mentionsTitle(search, n.title)
                    || !n.artist.isBlank() && search.contains(n.artist.toLowerCase(Locale.ROOT))) matches.addAll(rows);
            recent.add(n);
        }
        if (matches.isEmpty() && search.indexOf('《') >= 0 && search.indexOf('》') > search.indexOf('《'))
            return List.of();
        List<Note> picked = matches.isEmpty() ? recent : matches;
        return picked.stream().sorted(java.util.Comparator.comparingLong(Note::time).reversed()).limit(TURNS).toList();
    }

    static boolean mentionsTitle(String search, String title) {
        String full = title.strip().toLowerCase(Locale.ROOT);
        if (full.isEmpty()) return false;
        if (search.contains(full)) return true;
        int divider = full.indexOf('(');
        int wide = full.indexOf('（');
        if (wide >= 0 && (divider < 0 || wide < divider)) divider = wide;
        if (divider <= 0) return false;
        String common = full.substring(0, divider).strip();
        return common.length() >= 2 && search.contains(common);
    }

    static JsonArray describe(List<Note> rows) {
        JsonArray result = new JsonArray();
        for (Note row : rows) {
            JsonObject j = new JsonObject();
            j.addProperty("歌曲", row.title);
            j.addProperty("歌手", row.artist);
            j.addProperty("交流时间", Instant.ofEpochMilli(row.time).toString());
            j.addProperty("一起播放并聊过的次数", row.visits);
            if (!row.evidence.isEmpty()) j.addProperty("当时的音频观察", row.evidence);
            j.addProperty("玩家当时问", SongConversation.safe(row.question, 120));
            j.addProperty("机器人当时回答", SongConversation.safe(row.reply, 320));
            result.add(j);
        }
        return result;
    }

    static SongListeningMemory load(CompoundTag root) {
        var memory = new SongListeningMemory();
        ListTag entries = root.getList("Turns", Tag.TAG_COMPOUND);
        for (int i = Math.max(0, entries.size() - TRACKS * TURNS); i < entries.size(); i++) {
            var t = entries.getCompound(i);
            String id = t.getString("Song");
            if (!id.matches("netease:[0-9]{1,20}") || !t.hasUUID("Play") || t.getString("Reply").isBlank()) continue;
            if (!memory.tracks.containsKey(id) && memory.tracks.size() >= TRACKS) continue;
            var rows = memory.tracks.computeIfAbsent(id, unused -> new ArrayList<>());
            if (rows.size() == TURNS) rows.remove(0);
            rows.add(new Note(id, SongConversation.safe(t.getString("Title"), 150), SongConversation.safe(t.getString("Artist"), 120),
                    t.getUUID("Play"), Math.max(1, Math.min(1_000_000, t.getInt("Visits"))), Math.max(0, t.getLong("Time")),
                    SongConversation.safe(t.getString("Evidence"), 180), SongConversation.safe(t.getString("Question"), 280),
                    SongConversation.safe(t.getString("Reply"), 550)));
        }
        return memory;
    }

    @Override public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (var rows : tracks.values()) for (Note n : rows) {
            var t = new CompoundTag();
            t.putString("Song", n.song); t.putString("Title", n.title); t.putString("Artist", n.artist);
            t.putUUID("Play", n.play); t.putInt("Visits", n.visits); t.putLong("Time", n.time);
            t.putString("Evidence", n.evidence); t.putString("Question", n.question); t.putString("Reply", n.reply);
            list.add(t);
        }
        root.put("Turns", list);
        return root;
    }
}
