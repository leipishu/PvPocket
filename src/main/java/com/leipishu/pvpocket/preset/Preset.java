package com.leipishu.pvpocket.preset;

/**
 * One saved configuration, as it sits on the player's own disk.
 *
 * <p>Three facts plus a timestamp. The encoded string is opaque here: this record never parses,
 * validates or rewrites it, so a preset written by a newer build survives being loaded by an older
 * one.
 *
 * <p>Name is the identity inside a kind: saving under an existing name replaces it.
 */
public record Preset(PresetKind kind, String name, String encoded, long savedAt) {

    /** Long enough for a readable name, short enough to fit a row next to two action words. */
    public static final int MAX_NAME = 24;

    /**
     * Makes a raw name safe to store and to draw.
     *
     * <p>Control characters are stripped rather than escaped, because a self-drawn row cannot
     * survive a newline. The result is trimmed and clamped to {@link #MAX_NAME}.
     */
    public static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    public static boolean isValidName(String cleaned) {
        return cleaned != null && !cleaned.isEmpty() && cleaned.length() <= MAX_NAME;
    }
}
