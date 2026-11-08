package com.leipishu.pvpocket.stats;

/**
 * One finished series, as one player saw it.
 *
 * <p>Stored per player rather than per session because that is how it is read: "my last twenty
 * matches". The same series therefore produces one record per participant, each with its own side's
 * score and its own list of opponents — a shared record would need a reader that knows which side
 * the reader was on.</p>
 *
 * <p>Everything is a flat, Gson-friendly field. {@link #rounds} is a compact string rather than a
 * list of objects: it is written once and read only to draw a row of six-pixel squares, and a nested
 * type would double the file size of the one thing this feature stores a lot of.</p>
 */
public final class MatchRecord {

    /** Session id. Doubles as the idempotency key when a series is finished twice. */
    public String id = "";
    public long t;
    /** Map id, resolved through {@code pvpocket.map.<id>} on the client. */
    public String map = "";
    /** {@code modern} or {@code legacy}. */
    public String mode = "modern";
    /** Number of sides that actually played. */
    public int teams;
    /** Players on the reader's side, including the reader. */
    public int teamSize;
    public int myScore;
    public int theirScore;
    public boolean won;
    /** {@code ""} for a normal finish, else {@code SURRENDER} / {@code CUT} / {@code DRAW}. */
    public String reason = "";
    /** Wall-clock length of the series. */
    public long duration;
    /**
     * Per-round outcomes: {@code won,reason,seconds} separated by {@code ;}, where {@code won} is
     * {@code 1} or {@code 0} from this record's own point of view.
     *
     * <p>Relative rather than an absolute side index because the record is written once per
     * participant: "did I win round 2" is the question every reader asks, and storing the side would
     * mean every reader also has to be told which side it is.</p>
     */
    public String rounds = "";
    /** Names, comma separated. Empty when the reader played alone on their side. */
    public String teamMates = "";
    public String opponents = "";
}
