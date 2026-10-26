package com.ic2newera.integration.llm;

import java.util.ArrayList;
import java.util.List;

/** Shared, bounded segmentation; split on sentence boundaries without breaking UTF-16 pairs. */
public final class VoiceText {
    private VoiceText() { }
    public static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() > 6000) throw new IllegalArgumentException("回复超过6000字符朗读上限，请分次提问。");
        List<String> result = new ArrayList<>();
        String value = text.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "").trim();
        for (int start = 0; start < value.length();) {
            int end = Math.min(value.length(), start + 240);
            if (end < value.length()) {
                if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
                for (int index = end - 1; index > start + 80; index--) {
                    if ("。！？；\n.!?;".indexOf(value.charAt(index)) >= 0) { end = index + 1; break; }
                }
            }
            String segment = value.substring(start, end).trim();
            if (!segment.isBlank()) result.add(segment);
            start = end;
        }
        return List.copyOf(result);
    }
}
