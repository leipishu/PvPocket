package com.leipishu.pvpocket.stats;

/**
 * One finished series, as one player saw it.
 *
 * <p>Stored per player because that is how it is read: a player's last twenty matches. The same
 * series produces one record per participant, each with its own score and opponents.
 *
 * <p>Every field is flat and Gson-friendly. {@link #rounds} is a compact string rather than a list
 * of objects, because it is read only to draw a row of squares.
 */
public final class MatchRecord {

    /** Session id. Doubles as the idempotency key when a series is finished twice. */
    public String id = "";
    public long t;
    /** Map id, resolved through {@code pvpocket.map.<id>} on the client. */
    public String map = "";
    /** Either {@code modern} or {@code legacy}. */
    public String mode = "modern";
    /** Number of sides that actually played. */
    public int teams;
    /** Players on the reader's side, including the reader. */
    public int teamSize;
    public int myScore;
    public int theirScore;
    public boolean won;
    /** Empty for a normal finish, else {@code SURRENDER} / {@code CUT} / {@code DRAW}. */
    public String reason = "";
    /** Wall-clock length of the series. */
    public long duration;
    /**
     * Per-round outcomes: {@code won,reason,milliseconds} separated by {@code ;}, where {@code won}
     * is {@code 1} or {@code 0} from this record's own point of view.
     *
     * <p>Relative rather than an absolute side index, because the record is written once per
     * participant and every reader asks whether its own side won that round.
     */
    public String rounds = "";
    /** Names, comma separated. Empty when the reader played alone on their side. */
    public String teamMates = "";
    public String opponents = "";
}
