package com.leipishu.pvpocket.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Self-check for {@link DuelStats}. Run without Minecraft:
 *
 * <pre>
 *   java -cp build/classes/java/main:&lt;gson&gt; com.leipishu.pvpocket.stats.DuelStatsCheck
 * </pre>
 *
 * <p>It exists because the stats files are the one thing in this mod whose bugs are permanent: a
 * duplicated history row or a cap that drops the wrong end does not fail anything visibly, it just
 * leaves a player's record wrong forever. The alternative — checking it by playing two hundred
 * matches — is not a test.</p>
 *
 * <p>Same idea as {@code PvRaster}'s self-check: a {@code main} that asserts the invariants the class
 * promises, so a refactor of the file layout has something to break against.</p>
 */
public final class DuelStatsCheck {

    private static int failures;

    private DuelStatsCheck() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("pvpocket-stats-check");
        try {
            countsOneSeriesOnce(root);
            survivesReload(root.resolve("reload"));
            capsAndKeepsTheNewest(root.resolve("cap"));
            countsStreaks(root.resolve("streak"));
            ignoresUnreadableFiles(root.resolve("corrupt"));
        } finally {
            delete(root);
        }
        if (failures == 0) {
            System.out.println("DuelStatsCheck: OK");
        } else {
            System.out.println("DuelStatsCheck: " + failures + " failure(s)");
            System.exit(1);
        }
    }

    /** Recording the same series twice must not double the counters or the history row. */
    private static void countsOneSeriesOnce(Path root) {
        DuelStats stats = new DuelStats(root);
        MatchRecord record = series("s1", true, "1,,1000;1,,2000;0,,3000");
        stats.record("u1", record);
        stats.record("u1", record);

        PlayerStats player = stats.of("u1");
        check(player.matches == 1, "duplicate record counted twice: " + player.matches);
        check(player.wins == 1, "duplicate record counted two wins: " + player.wins);
        check(player.rounds == 3, "rounds counted " + player.rounds + ", expected 3");
        check(player.roundWins == 2, "round wins counted " + player.roundWins + ", expected 2");
        check(stats.history("u1", 10).size() == 1, "duplicate record wrote two history rows");
    }

    /** The counters are rewritten whole, so a reload has to read back exactly what was flushed. */
    private static void survivesReload(Path root) {
        DuelStats first = new DuelStats(root);
        first.record("u2", series("a", true, "1,,1"));
        first.addDamage("u2", 12.5F);
        first.addKill("u2");
        first.addDeath("u2");
        first.flush();

        DuelStats second = new DuelStats(root);
        second.load();
        PlayerStats player = second.of("u2");
        check(player.matches == 1, "reload lost matches");
        check(Math.abs(player.damage - 12.5D) < 0.001D, "reload lost damage: " + player.damage);
        check(player.kills == 1 && player.deaths == 1, "reload lost kills/deaths");
        check(second.history("u2", 10).size() == 1, "reload lost history");
    }

    /**
     * Past the compaction threshold the file has to settle at exactly the keep count, holding the
     * newest rows — dropping the wrong end would silently erase a player's recent form.
     */
    private static void capsAndKeepsTheNewest(Path root) {
        DuelStats stats = new DuelStats(root);
        int total = DuelStats.HISTORY_KEEP + 60;
        for (int i = 0; i < total; i++) {
            stats.record("u3", series("m" + i, i % 2 == 0, "1,,1"));
        }
        List<MatchRecord> history = stats.history("u3", total + 10);
        check(history.size() == DuelStats.HISTORY_KEEP,
                "history holds " + history.size() + " rows, expected " + DuelStats.HISTORY_KEEP);
        check(history.get(0).id.equals("m" + (total - 1)),
                "newest row is " + history.get(0).id + ", expected m" + (total - 1));
        check(history.get(history.size() - 1).id.equals("m" + (total - DuelStats.HISTORY_KEEP)),
                "oldest kept row is " + history.get(history.size() - 1).id);
        // The counters are lifetime totals and must not be trimmed with the history.
        check(stats.of("u3").matches == total,
                "lifetime matches trimmed to " + stats.of("u3").matches);
    }

    /** A loss resets the current streak; the best is a high-water mark that never comes back down. */
    private static void countsStreaks(Path root) {
        DuelStats stats = new DuelStats(root);
        stats.record("u4", series("w1", true, ""));
        stats.record("u4", series("w2", true, ""));
        stats.record("u4", series("l1", false, ""));
        stats.record("u4", series("w3", true, ""));
        PlayerStats player = stats.of("u4");
        check(player.bestStreak == 2, "best streak is " + player.bestStreak + ", expected 2");
        check(player.streak == 1, "current streak is " + player.streak + ", expected 1");
        check(player.wins == 3 && player.matches == 4, "wins/matches are " + player.wins + "/"
                + player.matches);
    }

    /** A truncated tail line is a crash, not a corruption: the rest of the file must still read. */
    private static void ignoresUnreadableFiles(Path root) throws IOException {
        DuelStats stats = new DuelStats(root);
        stats.record("u5", series("ok", true, ""));
        Path file = root.resolve("history").resolve("u5.jsonl");
        Files.writeString(file, "{\"id\":\"broken\"", java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        DuelStats reloaded = new DuelStats(root);
        List<MatchRecord> history = reloaded.history("u5", 10);
        check(history.size() == 1, "a truncated line cost the whole file: " + history.size() + " rows");
        check(history.get(0).id.equals("ok"), "the readable row is not the one returned");

        // And a new record still appends cleanly behind the broken line.
        reloaded.record("u5", series("after", false, ""));
        check(reloaded.history("u5", 10).size() == 2, "appending behind a broken line lost the row");
    }

    private static MatchRecord series(String id, boolean won, String rounds) {
        MatchRecord record = new MatchRecord();
        record.id = id;
        record.t = 1_700_000_000_000L;
        record.map = "arena_colosseum";
        record.mode = "modern";
        record.teams = 2;
        record.teamSize = 1;
        record.myScore = won ? 3 : 1;
        record.theirScore = won ? 1 : 3;
        record.won = won;
        record.reason = "";
        record.duration = 300_000L;
        record.rounds = rounds;
        record.opponents = "Dev2";
        return record;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            failures++;
            System.out.println("  FAIL " + message);
        }
    }

    private static void delete(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temp directory is not worth failing the check over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
