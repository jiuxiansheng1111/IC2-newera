package com.ic2newera.media;

import com.google.gson.*;
import net.minecraft.nbt.CompoundTag;

/** Catalog identities and attribution are persisted; never audio URLs used for fetching or credentials. */
public record MusicTrack(String id, String title, String artist, String source, String author, String publicUrl, String license) {
    public MusicTrack {
        if (id == null || !id.matches("(?:netease:[0-9]{1,20}|audius:[a-zA-Z0-9_-]{1,128}|query:[a-zA-Z0-9_-]{1,96})"))
            throw new IllegalArgumentException("歌曲编号无效。");
        title = bound(title, 96); artist = bound(artist, 64);
        source = bound(source, 48);
        author = bound(author, 64);
        publicUrl = safePublicUrl(publicUrl, id);
        license = bound(license, 160);
        if (title.isBlank()) throw new IllegalArgumentException("歌曲名称为空。");
    }
    public MusicTrack(String id, String title, String artist) {
        this(id, title, artist, defaultSource(id), artist, defaultUrl(id), "");
    }
    /** query identities are legacy user input, never verified catalog metadata or system capability messages. */
    public boolean needsCatalogVerification() { return id.startsWith("query:"); }
    public String displayTitle() { return needsCatalogVerification() ? "待核实导入内容" : title; }
    public static String bound(String value, int length) {
        if (value == null) return "";
        value = value.replaceAll("[\\p{Cntrl}§]", " ").trim();
        return value.substring(0, Math.min(length, value.length()));
    }
    public JsonObject json() {
        JsonObject j = new JsonObject(); j.addProperty("id", id); j.addProperty("title", title); j.addProperty("artist", artist);
        j.addProperty("source", source); j.addProperty("author", author); j.addProperty("url", publicUrl); j.addProperty("license", license); return j;
    }
    public static MusicTrack from(JsonObject j) {
        String id = j.get("id").getAsString();
        return new MusicTrack(id, j.get("title").getAsString(), j.has("artist") ? j.get("artist").getAsString() : "",
                j.has("source") ? j.get("source").getAsString() : defaultSource(id),
                j.has("author") ? j.get("author").getAsString() : (j.has("artist") ? j.get("artist").getAsString() : ""),
                j.has("url") ? j.get("url").getAsString() : defaultUrl(id), j.has("license") ? j.get("license").getAsString() : "");
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putString("id", id); tag.putString("title", title); tag.putString("artist", artist);
        tag.putString("source", source); tag.putString("author", author); tag.putString("url", publicUrl); tag.putString("license", license); return tag;
    }
    public static MusicTrack load(CompoundTag tag) {
        String id = tag.getString("id");
        return new MusicTrack(id, tag.getString("title"), tag.getString("artist"),
                tag.contains("source") ? tag.getString("source") : defaultSource(id),
                tag.contains("author") ? tag.getString("author") : tag.getString("artist"),
                tag.contains("url") ? tag.getString("url") : defaultUrl(id), tag.getString("license"));
    }
    private static String defaultSource(String id) { return id != null && id.startsWith("audius:") ? "Audius" : id != null && id.startsWith("netease:") ? "网易云音乐" : ""; }
    private static String defaultUrl(String id) {
        if (id == null) return "";
        if (id.startsWith("netease:") && id.substring(8).matches("[0-9]{1,20}")) return "https://music.163.com/#/song?id=" + id.substring(8);
        return "";
    }
    private static String safePublicUrl(String value, String id) {
        if (value == null || value.isBlank()) return defaultUrl(id);
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1) return "";
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("audius.co") || host.equalsIgnoreCase("www.audius.co")
                    || host.equalsIgnoreCase("music.163.com"))) return "";
            return value.substring(0, Math.min(value.length(), 256));
        } catch (RuntimeException invalid) { return ""; }
    }
}
