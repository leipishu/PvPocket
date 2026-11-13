package com.leipishu.pvpocket.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.storage.PvPocketStorage;

import java.io.IOException;
import java.io.RandomAccessFile;
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
 * <p>{@code stats/stats.json} holds every counter for every player and is rewritten whole.
 * Counters are held in memory and flushed at series end, on disconnect and on shutdown.
 *
 * <p>{@code history/<uuid>.jsonl} holds one line per finished series, appended. A player's history
 * is always read whole, so a file per player is one sequential read.
 *
 * <p>Nothing here is authoritative for gameplay: a lost counter costs a number on a panel, so a
 * failed write logs and continues.
 */
public final class DuelStats {

    /** Bumped when a counter's meaning changes; the loader reads it as evidence only. */
    private static final int VERSION = 1;

    /**
     * How many finished series a player keeps, enforced on read. The file holds up to
     * {@link #HISTORY_COMPACT_AT} rows between compactions.
     */
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
     * <p>Not clamped to the victim's remaining health: a 20-point hit on a player with 3 points
     * left counts as 20. Clamping would make the total depend on how the last hit landed.
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
     * <p>Idempotent on {@link MatchRecord#id}, because {@code finishSeries} can be reached twice.
     * The check reads the tail of the player's own file rather than an in-memory set, since a
     * duplicate can arrive after a restart.
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

    /**
     * Newest first, at most {@link #HISTORY_KEEP} rows.
     *
     * <p>The clamp is here rather than only in the compactor, because the file may hold up to
     * {@link #HISTORY_COMPACT_AT} rows.
     */
    public synchronized List<MatchRecord> history(String uuid, int limit) {
        List<MatchRecord> out = new ArrayList<>();
        int cap = Math.min(limit, HISTORY_KEEP);
        Path file = historyFile(uuid);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && out.size() < cap; i--) {
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
            // A crash mid-write leaves a partial row with no terminator, so appending onto it would
            // glue the record to the broken one and lose both.
            if (!endsWithNewline(file)) {
                Files.writeString(file, System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(file, LINES.toJson(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            compactIfLong(file);
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot append history for {}", uuid, e);
        }
    }

    /** True when the file is absent, empty, or already terminated. */
    private static boolean endsWithNewline(Path file) {
        if (!Files.isRegularFile(file)) {
            return true;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            if (length == 0L) {
                return true;
            }
            raf.seek(length - 1L);
            int last = raf.read();
            return last == '\n' || last == '\r';
        } catch (IOException e) {
            // Unreadable: assume the worst and write the separator. A blank line is harmless.
            return false;
        }
    }

    /**
     * Drops the oldest rows once the file grows past the compaction threshold.
     *
     * <p>Runs only past {@link #HISTORY_COMPACT_AT}, because the rewrite is a full read and write.
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
