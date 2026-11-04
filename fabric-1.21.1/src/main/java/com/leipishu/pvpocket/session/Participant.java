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

    public Participant() {
    }

    public Participant(String uuid, String name, int spawnIndex) {
        this.uuid = uuid;
        this.name = name;
        this.spawnIndex = spawnIndex;
    }
}
