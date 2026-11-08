package com.leipishu.pvpocket.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.storage.PvPocketStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifetime counters and finished-match history, per player, per world.
 *
 * <p>Two files, because they have two different write patterns:</p>
 * <ul>
 *   <li>{@code stats/stats.json} — every counter for every player, rewritten whole. Counters change
 *       on every hit, so they are held in memory and flushed at series end, on disconnect and on
 *       shutdown; a write per hit would be a disk write per half second of combat.</li>
 *   <li>{@code history/<uuid>.jsonl} — one line per finished series, appended. A player's history is
 *       always read as a whole ("my last twenty"), so a file per player is one sequential read; a
 *       shared file would make every append a full rewrite of everybody's records.</li>
 * </ul>
 *
 * <p>Nothing here is authoritative for gameplay: a lost counter costs a number on a panel, never a
 * match. That is why a failed write logs and continues instead of throwing.</p>
 */
public final class DuelStats {

    /** Bumped if a counter's meaning changes; the loader refuses nothing, but the field is evidence. */
    private static final int VERSION = 1;

    /** How many finished series a player keeps. Beyond this the oldest are dropped. */
    public static final int HISTORY_KEEP = 200;
    /** Rewrite the file down to {@link #HISTORY_KEEP} once it passes this. */
    private static final int HISTORY_COMPACT_AT = 250;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Gson LINES = new Gson();

    /** On-disk shape of {@code stats.json}. */
    private static final class File_ {
        int version = VERSION;
        Map<String, PlayerStats> players = new HashMap<>();
    }

    private final Path root;
    private final Map<String, PlayerStats> players = new HashMap<>();
    private boolean loaded;
    private boolean dirty;

    public DuelStats(Path root) {
        this.root = root;
    }

    public synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = statsFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            File_ parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), File_.class);
            if (parsed != null && parsed.players != null) {
                players.putAll(parsed.players);
            }
        } catch (Exception e) {
            // A corrupt stats file costs numbers, not matches. Keep the world playable.
            PvPocket.LOGGER.warn("Discarding unreadable stats file {}", file, e);
        }
    }

    /** The counters for a player, created empty on first sight. Never null. */
    public synchronized PlayerStats of(String uuid) {
        load();
        return players.computeIfAbsent(uuid, key -> new PlayerStats());
    }

    public synchronized void name(String uuid, String name) {
        if (uuid == null || name == null || name.isEmpty()) {
            return;
        }
        PlayerStats stats = of(uuid);
        if (!name.equals(stats.name)) {
            stats.name = name;
            dirty = true;
        }
    }

    /**
     * Health points dealt to an opponent.
     *
     * <p>Not clamped to the victim's remaining health: the damage value arriving here is what the
     * attack produced after armour, and a 20-point hit on a player with 3 points left still counts
     * as 20. Clamping would make "total damage" depend on how the last hit landed rather than on how
     * hard it hit.</p>
     */
    public synchronized void addDamage(String uuid, float amount) {
        if (amount <= 0F) {
            return;
        }
        of(uuid).damage += amount;
        dirty = true;
    }

    public synchronized void addKill(String uuid) {
        of(uuid).kills++;
        dirty = true;
    }

    public synchronized void addDeath(String uuid) {
        of(uuid).deaths++;
        dirty = true;
    }

    /**
     * Files one finished series for one participant.
     *
     * <p>Idempotent on {@link MatchRecord#id}: {@code finishSeries} can be reached twice (a clean end
     * and then the reconciler), and a duplicated row would show the same match twice forever. The
     * check reads the tail of the player's own file rather than keeping an in-memory set, because the
     * duplicate can arrive after a restart.</p>
     */
    public synchronized void record(String uuid, MatchRecord record) {
        if (alreadyRecorded(uuid, record.id)) {
            PvPocket.LOGGER.info("Stats: match {} already recorded for {}", record.id, uuid);
            return;
        }
        append(uuid, record);
        PlayerStats stats = of(uuid);
        stats.matches++;
        stats.rounds += Math.max(0, record.rounds.isEmpty() ? 0 : record.rounds.split(";").length);
        stats.roundWins += roundsWonBy(record);
        stats.lastPlayed = record.t;
        if (record.won) {
            stats.wins++;
            stats.streak++;
            stats.bestStreak = Math.max(stats.bestStreak, stats.streak);
        } else {
            stats.streak = 0;
        }
        dirty = true;
    }

    /**
     * Round wins, counted from the per-round winners already stored relative to this record's side.
     */
    private static int roundsWonBy(MatchRecord record) {
        if (record.rounds.isEmpty()) {
            return 0;
        }
        int won = 0;
        for (String part : record.rounds.split(";")) {
            if (part.startsWith("1,")) {
                won++;
            }
        }
        return won;
    }

    /** Newest first. */
    public synchronized List<MatchRecord> history(String uuid, int limit) {
        List<MatchRecord> out = new ArrayList<>();
        Path file = historyFile(uuid);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    MatchRecord record = LINES.fromJson(line, MatchRecord.class);
                    if (record != null) {
                        out.add(record);
                    }
                } catch (Exception ignored) {
                    // One bad line is a truncated tail or a newer build; skip it, keep the rest.
                }
            }
        } catch (IOException e) {
            PvPocket.LOGGER.warn("Cannot read history for {}", uuid, e);
        }
        return out;
    }

    /** Writes {@code stats.json} if anything changed. Safe to call on every shutdown. */
    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        dirty = false;
        File_ out = new File_();
        out.players = players;
        try {
            Files.createDirectories(root.resolve("stats"));
            PvPocketStorage.writeAtomically(statsFile(),
                    GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            dirty = true;
            PvPocket.LOGGER.error("Cannot write stats to {}", statsFile(), e);
        }
    }

    // ------------------------------------------------------------------ files

    private Path statsFile() {
        return root.resolve("stats").resolve("stats.json");
    }

    private Path historyFile(String uuid) {
        return root.resolve("history").resolve(uuid + ".jsonl");
    }

    private boolean alreadyRecorded(String uuid, String id) {
        Path file = historyFile(uuid);
        if (!Files.isRegularFile(file) || id == null || id.isEmpty()) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // The tail is enough: a duplicate can only be the most recent write.
            int from = Math.max(0, lines.size() - 20);
            for (int i = from; i < lines.size(); i++) {
                if (lines.get(i).contains("\"id\":\"" + id + "\"")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // Unreadable file: fall through and append. A duplicate row is cheaper than a lost one.
        }
        return false;
    }

    private void append(String uuid, MatchRecord record) {
        Path file = historyFile(uuid);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, LINES.toJson(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            compactIfLong(file);
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot append history for {}", uuid, e);
        }
    }

    /**
     * Drops the oldest rows once the file grows past the compaction threshold.
     *
     * <p>Every fifty matches, not every match: the rewrite is a full read and write, and doing it on
     * each append would turn an O(1) append into an O(n) one for the sake of a bound nobody is close
     * to.</p>
     */
    private void compactIfLong(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() <= HISTORY_COMPACT_AT) {
                return;
            }
            List<String> kept = new ArrayList<>(lines.subList(lines.size() - HISTORY_KEEP, lines.size()));
            StringBuilder sb = new StringBuilder();
            for (String line : kept) {
                sb.append(line).append(System.lineSeparator());
            }
            PvPocketStorage.writeAtomically(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            PvPocket.LOGGER.warn("Cannot compact history {}", file, e);
        }
    }
}
