package com.leipishu.pvpocket.preset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Short-lived codes that let one player hand a preset to another.
 *
 * <p>Deliberately in memory and deliberately temporary. A share code is a <em>transfer</em>, not a
 * publication: the receiving client writes the preset to its own disk and never needs the code
 * again. Persisting them would create a second, worse preset library on the server, with no owner,
 * no expiry and no way to remove one — which is the opposite of what "saved locally" asks for.</p>
 *
 * <p>Everything here runs on the server thread. Packet handlers already hop onto it and the sweep is
 * driven from the server tick, so there is no locking and no concurrent collection.</p>
 */
public final class PresetShareRegistry {

    /** How long a code stays valid. Five minutes is long enough to paste it somewhere and short
     *  enough that a leaked code is worthless by the time anyone finds it. */
    public static final long TTL_MS = 5L * 60L * 1000L;

    /** 30^6 ≈ 7.3e8, so a collision is rare and the retry loop below is nearly free. */
    private static final int CODE_LEN = 6;
    /**
     * No 0/O/1/I/L: the code is read off one screen and typed into another, and those five
     * characters are the entire reason people mis-type codes.
     */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    /** Global ceiling. Reached only by a script; a person will never have this many live codes. */
    private static final int MAX_ENTRIES = 256;
    /** Per-player ceiling: past this, the player's own oldest code is dropped to make room. */
    private static final int MAX_PER_PLAYER = 8;

    /** One shared preset. {@code owner} exists only to enforce the per-player ceiling. */
    public record Shared(PresetKind kind, String name, String encoded, long expiresAt, UUID owner) {

        public int remainingSeconds() {
            long left = expiresAt - System.currentTimeMillis();
            return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0D);
        }
    }

    private static final Map<String, Shared> BY_CODE = new HashMap<>();
    private static int sweepCountdown;

    private PresetShareRegistry() {
    }

    /**
     * Stores a preset and returns the code to hand out, or null when the store is full.
     *
     * <p>The caller has already validated the kind, the name and the size — this method is storage,
     * not policy, and duplicating the checks here would give two places to keep in step.</p>
     */
    public static String publish(UUID owner, PresetKind kind, String name, String encoded) {
        sweep();
        dropOldestOf(owner);
        if (BY_CODE.size() >= MAX_ENTRIES) {
            return null;
        }
        String code = nextCode();
        BY_CODE.put(code, new Shared(kind, name, encoded, System.currentTimeMillis() + TTL_MS, owner));
        return code;
    }

    /**
     * Looks a code up, dropping it if it has expired.
     *
     * <p>Expiry is checked on read as well as by the sweep: the sweep runs on a timer, and "the code
     * still works for up to a tick after it expired" is a race nobody should have to reason about.</p>
     */
    public static Shared fetch(String rawCode) {
        String code = normalise(rawCode);
        if (code.isEmpty()) {
            return null;
        }
        Shared shared = BY_CODE.get(code);
        if (shared == null) {
            return null;
        }
        if (shared.expiresAt() <= System.currentTimeMillis()) {
            BY_CODE.remove(code);
            return null;
        }
        return shared;
    }

    /** Called from the server tick; internally throttled so it does not walk the map 20 times a second. */
    public static void tick() {
        if (--sweepCountdown > 0) {
            return;
        }
        // Every 5 seconds: the TTL is 300 seconds, so an entry lives at most 305 seconds and the
        // "cleaned after five minutes" promise is off by at most one sweep.
        sweepCountdown = 100;
        sweep();
    }

    /** Drops every expired entry. Public because the server-stop path calls it directly. */
    public static void sweep() {
        long now = System.currentTimeMillis();
        BY_CODE.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    public static void clear() {
        BY_CODE.clear();
        sweepCountdown = 0;
    }

    /** Number of live codes, for the diagnostic line. */
    public static int size() {
        sweep();
        return BY_CODE.size();
    }

    /**
     * Canonical form of a typed code.
     *
     * <p>Upper-cased and stripped of spaces and dashes, because the code is copied by eye: "ab3-k9"
     * and "AB3K9" are the same code and refusing one of them would be a puzzle, not security.</p>
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    private static String nextCode() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 64; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LEN);
            for (int i = 0; i < CODE_LEN; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            if (!BY_CODE.containsKey(code)) {
                return code;
            }
        }
        // 64 collisions in a row means the map is effectively full; the caller reports "full" rather
        // than handing out a code that points at somebody else's preset.
        return null;
    }

    /** Keeps one player from filling the shared space with their own codes. */
    private static void dropOldestOf(UUID owner) {
        List<Map.Entry<String, Shared>> mine = new ArrayList<>();
        for (Map.Entry<String, Shared> entry : BY_CODE.entrySet()) {
            if (entry.getValue().owner().equals(owner)) {
                mine.add(entry);
            }
        }
        if (mine.size() < MAX_PER_PLAYER) {
            return;
        }
        mine.sort(Comparator.comparingLong(e -> e.getValue().expiresAt()));
        Iterator<Map.Entry<String, Shared>> it = mine.iterator();
        int toDrop = mine.size() - MAX_PER_PLAYER + 1;
        for (int i = 0; i < toDrop && it.hasNext(); i++) {
            BY_CODE.remove(it.next().getKey());
        }
    }
}
