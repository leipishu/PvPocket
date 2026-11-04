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
     * This player's loadout, encoded by {@code Loadout.encode()}.
     *
     * <p>Stored per participant rather than per session because the default rule is "每人独立". It is
     * also part of what the two sides agree to, so it is fingerprinted alongside the rule set.</p>
     */
    public String loadout;
    public String loadoutDigest;

    /** True once the loadout has been written into the inventory for the current round. */
    public boolean loadoutApplied;

    public Participant() {
    }

    public Participant(String uuid, String name, int spawnIndex) {
        this.uuid = uuid;
        this.name = name;
        this.spawnIndex = spawnIndex;
    }
}
