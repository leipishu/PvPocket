package com.leipishu.pvpocket.session;

/**
 * One player's participation in a session. Plain data so it round-trips through Gson unchanged.
 */
public final class Participant {

    public String uuid;
    public String name;
    /** File name only, relative to {@code snapshots/<sessionId>/}. Null until frozen. */
    public String snapshotFile;
    public String fingerprint;
    public boolean restored;
    public int spawnIndex;

    /**
     * 0-based side. {@code -1} means "not set" — sessions written before teams existed have no such
     * field, and Gson leaves the initialiser in place, so {@link #teamIndex()} falls back to the
     * spawn index. That keeps an old save readable instead of silently putting both players on the
     * same side of the scoreboard.
     */
    public int team = -1;

    /**
     * This player's loadout, encoded by {@code Loadout.encode()}.
     *
     * <p>Stored per participant rather than per session because the default rule is "每人独立". It is
     * also part of what the two sides agree to, so it is fingerprinted alongside the rule set.</p>
     */
    public String loadout;
    public String loadoutDigest;

    /** True once the loadout has been written into the inventory for the current round. */
    public boolean loadoutApplied;

    /**
     * The combined loadout fingerprint this player has agreed to, or null if they have not agreed.
     *
     * <p>Storing the fingerprint rather than a boolean is what makes "everyone agrees" checkable:
     * if anyone changes their loadout afterwards the combined value changes, and every agreement
     * recorded against the old value stops matching. A boolean would keep saying yes to a set of
     * loadouts nobody has seen.</p>
     */
    public String agreedLoadoutDigest;

    public Participant() {
    }

    public Participant(String uuid, String name, int spawnIndex) {
        this.uuid = uuid;
        this.name = name;
        this.spawnIndex = spawnIndex;
        this.team = spawnIndex;
    }

    /** Side index for the scoreboard and for spawn placement. */
    public int teamIndex() {
        return team >= 0 ? team : spawnIndex;
    }
}
