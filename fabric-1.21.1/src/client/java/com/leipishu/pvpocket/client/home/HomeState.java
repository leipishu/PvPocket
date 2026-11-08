package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.match.MatchListScreen;
import com.leipishu.pvpocket.network.HomeStatePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * The last home-screen snapshot, parsed, held on the client.
 *
 * <p>Static rather than passed into the screen because the screen is recreated on every window
 * resize and on every page rebuild: a snapshot that lived on the screen instance would be thrown away
 * by an F11 press and the panel would flash "no data" for a round trip. The client is single-threaded
 * and only one home screen can be open, so one slot is the whole cache.</p>
 *
 * <p>{@link #version()} is bumped on every update. The screen watches it instead of being rebuilt
 * from the network callback: the callback can arrive while the screen is mid-render, and rebuilding a
 * widget list from inside a render pass is how a self-drawn GUI grows a concurrent modification.</p>
 */
public final class HomeState {

    /** Lifetime counters, already summed on the server. */
    public record Stats(int matches, int wins, int rounds, int roundWins, int damage,
                        int kills, int deaths, int streak, int bestStreak, long lastPlayed) {

        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L);

        /** Win rate in [0,1], or 0 when nothing has been played. */
        public double winRate() {
            return matches <= 0 ? 0D : (double) wins / matches;
        }

        public double roundRate() {
            return rounds <= 0 ? 0D : (double) roundWins / rounds;
        }

        /** Kills per death, or the kill count when there are no deaths yet. */
        public double kd() {
            return deaths <= 0 ? kills : (double) kills / deaths;
        }

        public boolean any() {
            return matches > 0;
        }
    }

    /**
     * One finished series, from this player's side.
     *
     * <p>{@code rounds} is parsed lazily into {@link Round} only when the detail dialog opens: a
     * thirty-row list would otherwise build thirty lists of round objects every time the screen is
     * opened, to draw six-pixel squares.</p>
     */
    public record History(String id, long t, String map, String mode, int teams, int teamSize,
                          int myScore, int theirScore, boolean won, String reason, long duration,
                          String rounds, String opponents, String teamMates) {

        public String mapLabel() {
            String key = "pvpocket.map." + map;
            String text = com.leipishu.pvpocket.i18n.PvLang.str(key);
            return text.equals(key) ? map : text;
        }

        public List<Round> roundList() {
            List<Round> out = new ArrayList<>();
            if (rounds == null || rounds.isEmpty()) {
                return out;
            }
            for (String part : rounds.split(";")) {
                String[] f = part.split(",", 3);
                if (f.length < 3) {
                    continue;
                }
                try {
                    out.add(new Round("1".equals(f[0]), f[1],
                            Math.max(0L, Long.parseLong(f[2]))));
                } catch (NumberFormatException ignored) {
                    // A malformed round is dropped; the match row still draws.
                }
            }
            return out;
        }
    }

    /** One settled round: who took it (from this player's side), why, and how long it ran. */
    public record Round(boolean won, String reason, long ms) {
    }

    private static Stats stats = Stats.EMPTY;
    private static List<History> history = List.of();
    private static List<MatchListScreen.Entry> matches = List.of();
    private static int flags;
    private static int version;
    private static boolean loaded;

    private HomeState() {
    }

    public static void update(HomeStatePayload payload) {
        stats = parseStats(payload.stats());
        history = parseHistory(payload.history());
        matches = MatchListScreen.Entry.parse(payload.matches());
        flags = payload.flags();
        loaded = true;
        version++;
    }

    /** Drops everything on disconnect: another server's career is not this server's. */
    public static void clear() {
        stats = Stats.EMPTY;
        history = List.of();
        matches = List.of();
        flags = 0;
        loaded = false;
        version++;
    }

    public static Stats stats() {
        return stats;
    }

    public static List<History> history() {
        return history;
    }

    public static List<MatchListScreen.Entry> matches() {
        return matches;
    }

    public static boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public static boolean loaded() {
        return loaded;
    }

    public static int version() {
        return version;
    }

    // ------------------------------------------------------------------ parsing

    private static Stats parseStats(String line) {
        if (line == null || line.isEmpty()) {
            return Stats.EMPTY;
        }
        String[] f = line.split(",", 10);
        if (f.length < 10) {
            return Stats.EMPTY;
        }
        try {
            return new Stats(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                    Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]), Integer.parseInt(f[5]),
                    Integer.parseInt(f[6]), Integer.parseInt(f[7]),
                    Integer.parseInt(f[8]), Long.parseLong(f[9]));
        } catch (NumberFormatException e) {
            // A short or malformed line is a version skew; an empty career is the safe reading.
            return Stats.EMPTY;
        }
    }

    private static List<History> parseHistory(String block) {
        List<History> out = new ArrayList<>();
        if (block == null || block.isEmpty()) {
            return out;
        }
        for (String line : block.split("\n")) {
            String[] f = line.split("\\|", 14);
            if (f.length < 14) {
                continue;
            }
            try {
                out.add(new History(f[0], Long.parseLong(f[1]), f[2], f[3],
                        Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]),
                        Integer.parseInt(f[7]), "1".equals(f[8]), f[9], Long.parseLong(f[10]),
                        f[11], f[12], f[13]));
            } catch (NumberFormatException ignored) {
                // One bad row must not cost the other nineteen.
            }
        }
        return out;
    }
}
