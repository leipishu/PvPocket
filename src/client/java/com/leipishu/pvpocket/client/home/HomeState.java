package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.match.MatchListScreen;
import com.leipishu.pvpocket.network.HomeStatePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * The last home-screen snapshot, parsed and held on the client.
 *
 * <p>The state is static because the screen is recreated on every resize and every page rebuild.
 * The client is single-threaded and only one home screen can be open, so one slot is enough.
 *
 * <p>{@link #version()} is bumped on every update. The screen watches it instead of rebuilding from
 * the network callback, which can arrive mid-render.
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
     * <p>{@code rounds} is parsed on demand by {@link #roundList()}, so the field stays a single
     * string until the detail dialog opens.
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
                    // A malformed round is dropped and the match row still draws.
                }
            }
            return out;
        }
    }

    /** One settled round: who took it (from this player's side), why, and how long it ran. */
    public record Round(boolean won, String reason, long ms) {
    }

    /**
     * One row of the operator page: a map, how many plots it owns, and how many are in use.
     *
     * <p>{@code copies} counts the map's own address, so "4 plots" means four concurrent duels of
     * this map.
     */
    public record MapRow(String id, int copies, int leased) {

        /** The translated map name, falling back to the id when the key is missing. */
        public String label() {
            String key = "pvpocket.map." + id;
            String text = com.leipishu.pvpocket.i18n.PvLang.str(key);
            return text.equals(key) ? id : text;
        }

        /** True when every plot this map owns is leased, i.e. the next duel on it is refused. */
        public boolean full() {
            return leased >= copies;
        }
    }

    /**
     * The arena section of the snapshot, as the operator page reads it.
     *
     * <p>{@code guard} is this reader's own stray-player flag, not a server-wide setting: it means
     * the player is not moved out of the arena.
     */
    public record Arena(boolean guard, int stamped, int slots, int leased, boolean building,
                        List<MapRow> maps) {

        public static final Arena EMPTY = new Arena(true, 0, 0, 0, false, List.of());

        /** Cells the layout has handed out, i.e. the sum of every map's copies. */
        public int assigned() {
            int total = 0;
            for (MapRow row : maps) {
                total += row.copies();
            }
            return total;
        }

        /** True when there is no spare cell left, so "copy" would be refused. */
        public boolean gridFull() {
            return slots > 0 && assigned() >= slots;
        }
    }

    private static Stats stats = Stats.EMPTY;
    private static List<History> history = List.of();
    private static List<MatchListScreen.Entry> matches = List.of();
    private static Arena arena = Arena.EMPTY;
    private static int flags;
    private static int version;
    private static boolean loaded;

    private HomeState() {
    }

    public static void update(HomeStatePayload payload) {
        stats = parseStats(payload.stats());
        history = parseHistory(payload.history());
        matches = MatchListScreen.Entry.parse(payload.matches());
        arena = parseArena(payload.arena());
        flags = payload.flags();
        loaded = true;
        version++;
    }

    /** Drops everything on disconnect: another server's career is not this server's. */
    public static void clear() {
        stats = Stats.EMPTY;
        history = List.of();
        matches = List.of();
        arena = Arena.EMPTY;
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

    public static Arena arena() {
        return arena;
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

    /**
     * Returns the arena block, or {@link Arena#EMPTY} when the field is absent.
     *
     * <p>The server leaves the block empty below operator level, and an older server does not send
     * it at all. Both read as empty.
     */
    private static Arena parseArena(String block) {
        if (block == null || block.isEmpty()) {
            return Arena.EMPTY;
        }
        String[] lines = block.split("\n");
        String[] head = lines[0].split(",", 5);
        if (head.length < 5) {
            return Arena.EMPTY;
        }
        List<MapRow> maps = new ArrayList<>(lines.length - 1);
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split(",", 3);
            if (f.length < 3) {
                continue;
            }
            try {
                maps.add(new MapRow(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2])));
            } catch (NumberFormatException ignored) {
                // A row this client cannot read is a row it does not draw; the rest still work.
            }
        }
        try {
            return new Arena(!"0".equals(head[0]), Integer.parseInt(head[1]),
                    Integer.parseInt(head[2]), Integer.parseInt(head[3]), "1".equals(head[4]), maps);
        } catch (NumberFormatException e) {
            return Arena.EMPTY;
        }
    }
}
