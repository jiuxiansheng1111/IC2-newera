package com.ic2newera.client.robot.camera;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Client-only presentation helpers for legacy entity IDs stored as waypoint labels. */
public final class PhoneEntityNames {
    private PhoneEntityNames() {}

    /**
     * Translates a leading registered entity ID while preserving any label suffix. Custom place names
     * that do not begin with a real entity ID pass through unchanged.
     */
    public static String translateLeadingEntity(String label) {
        if (label == null || label.isBlank()) return label == null ? "" : label;
        int split = 0;
        while (split < label.length() && !Character.isWhitespace(label.charAt(split))) split++;
        String candidate = label.substring(0, split);
        if (candidate.indexOf(':') <= 0) return label;
        ResourceLocation id = ResourceLocation.tryParse(candidate);
        if (id == null) return label;
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) return label;
        return type.getDescription().getString() + label.substring(split);
    }

    /** Uses curated ecology names first, then Minecraft's active client language for other entities. */
    public static String speciesName(String id) {
        if (id == null || id.isBlank()) return "风景 / 自拍";
        String curated = com.ic2newera.field.EcologyAlbum.speciesName(id);
        if (!curated.equals(id)) return curated;
        return translateLeadingEntity(id);
    }
}
