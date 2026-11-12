package com.leipishu.pvpocket.preset;

/**
 * One saved configuration, as it sits on the player's own disk.
 *
 * <p>Deliberately just three facts plus a timestamp. The encoded string is opaque here — this record
 * never parses it, never validates it and never rewrites it. That is what lets a preset written by a
 * newer build survive being loaded, listed and shared by an older one instead of being "repaired"
 * into something the player did not save.</p>
 *
 * <p>Name is the identity inside a kind: saving under an existing name replaces it. An opaque id
 * would allow two entries with the same name and give the player no way to tell them apart, which is
 * a worse trade than losing the ability to keep two variants called "剑".</p>
 */
public record Preset(PresetKind kind, String name, String encoded, long savedAt) {

    /** Long enough for a readable name, short enough to fit a row next to two action words. */
    public static final int MAX_NAME = 24;

    /**
     * Makes a raw name safe to store and to draw.
     *
     * <p>Control characters are stripped rather than escaped: a name arrives from a text field the
     * player typed into, and the one thing a self-drawn row cannot survive is a newline. The result
     * is trimmed and clamped, so {@link #isValid} is a pure check on the cleaned form.</p>
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
