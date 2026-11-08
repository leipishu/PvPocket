package com.leipishu.pvpocket.stats;

/**
 * One player's lifetime counters on this server.
 *
 * <p>Plain fields so Gson needs no adapters and so an old {@code stats.json} stays readable: a
 * missing field keeps its initialiser rather than failing the whole file, which is the same
 * tolerance {@code PresetStore} applies to a bad row.</p>
 *
 * <p><b>These numbers are not recomputable.</b> They are accumulated as the events happen, so a
 * change to what a counter means invalidates everything already stored. The definitions are written
 * down in {@code docs/zh/02-rules.md}; treat them as a format, not as an implementation detail.</p>
 */
public final class PlayerStats {

    /** Last name seen, so the panel can show a name for a uuid that is offline. */
    public String name = "";

    public int matches;
    public int wins;
    public int rounds;
    public int roundWins;
    /** Health points dealt to opponents. One heart is two points. */
    public double damage;
    public int kills;
    public int deaths;
    /** Current series win streak, and its high-water mark. */
    public int streak;
    public int bestStreak;
    public long lastPlayed;

    public void copyFrom(PlayerStats other) {
        name = other.name;
        matches = other.matches;
        wins = other.wins;
        rounds = other.rounds;
        roundWins = other.roundWins;
        damage = other.damage;
        kills = other.kills;
        deaths = other.deaths;
        streak = other.streak;
        bestStreak = other.bestStreak;
        lastPlayed = other.lastPlayed;
    }
}
