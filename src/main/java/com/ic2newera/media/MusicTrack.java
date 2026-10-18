package com.ic2newera.media;

import com.google.gson.*;
import net.minecraft.nbt.CompoundTag;

/** Only catalog identities and display text are persisted; never audio URLs or credentials. */
public record MusicTrack(String id, String title, String artist) {
    public MusicTrack {
        if (id == null || !id.matches("(?:netease:[0-9]{1,20}|query:[a-zA-Z0-9_-]{1,96})"))
            throw new IllegalArgumentException("歌曲编号无效。");
        title = bound(title, 96); artist = bound(artist, 64);
        if (title.isBlank()) throw new IllegalArgumentException("歌曲名称为空。");
    }
    public static String bound(String value, int length) {
        if (value == null) return "";
        value = value.replaceAll("[\\p{Cntrl}§]", " ").trim();
        return value.substring(0, Math.min(length, value.length()));
    }
    public JsonObject json() {
        JsonObject j = new JsonObject(); j.addProperty("id", id); j.addProperty("title", title); j.addProperty("artist", artist); return j;
    }
    public static MusicTrack from(JsonObject j) {
        return new MusicTrack(j.get("id").getAsString(), j.get("title").getAsString(), j.has("artist") ? j.get("artist").getAsString() : "");
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putString("id", id); tag.putString("title", title); tag.putString("artist", artist); return tag;
    }
    public static MusicTrack load(CompoundTag tag) { return new MusicTrack(tag.getString("id"), tag.getString("title"), tag.getString("artist")); }
}
