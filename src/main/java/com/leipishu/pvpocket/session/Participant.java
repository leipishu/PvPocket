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
     * 0-based side, or {@code -1} when unset. A save without this field keeps the initialiser, and
     * {@link #teamIndex()} then falls back to the spawn index.
     *
     * <p>Assigned by the roster the proposer builds in the panel.
     */
    public int team = -1;

    /**
     * Index within the team, 0-based.
     *
     * <p>Spreads team-mates around their side's spawn pad. Derived from the roster order and not
     * stored on the wire.
     */
    public int member;

    /**
     * True once this player has declined the invite.
     *
     * <p>Terminal and distinct from "has not answered yet": a rejection removes the player without
     * voiding the match. A later revision bump must not put a rejected player back into the asking
     * queue, so this is a flag rather than a negative {@code acceptedRevision}.
     */
    public boolean rejected;

    /**
     * The rule revision this player has accepted, or {@code -1}.
     *
     * <p>Every participant records the revision they accepted, and the session starts when all of
     * them match the current revision. A re-proposal bumps the revision and invalidates earlier
     * acceptances.
     */
    public int acceptedRevision = -1;

    /**
     * True once this player is out of the current round: killed, out of bounds, or surrendered.
     *
     * <p>Per-round, cleared at every round start. The round ends when only one team still has
     * somebody standing.
     */
    public boolean eliminated;

    /**
     * This player's loadout, encoded by {@code Loadout.encode()}.
     *
     * <p>Every participant in a session holds the same value; written by
     * {@code SessionManager#adoptSharedLoadout} and read back by {@code sharedLoadout}. The consent
     * digest covers the participants, so the copy is kept per participant.
     */
    public String loadout;
    public String loadoutDigest;

    /** True once the loadout has been written into the inventory for the current round. */
    public boolean loadoutApplied;

    /**
     * A between-round loadout change waiting for the rest of the match to agree.
     *
     * <p>Kept apart from {@link #loadout} because the change is not in effect until everybody
     * accepts. {@link #loadout} stays intact, so a refusal needs no rollback.
     */
    public String pendingLoadout;
    public String pendingLoadoutDigest;

    /**
     * Which {@code DuelSession#loadoutRevision} this player has agreed to.
     *
     * <p>Same shape as {@link #acceptedRevision}: the change lands when every participant's number
     * matches the current revision.
     */
    public int acceptedLoadoutRevision;

    /**
     * The combined loadout fingerprint this player agreed to, or null when they have not agreed.
     *
     * <p>A digest rather than a boolean, so a later loadout change invalidates every earlier
     * agreement.
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

    /**
     * A participant placed on a specific side by the roster.
     *
     * <p>{@code spawnIndex} equals {@code team}: the arena has one spawn pad per side.
     */
    public Participant(String uuid, String name, int team, int member) {
        this.uuid = uuid;
        this.name = name;
        this.team = team;
        this.member = member;
        this.spawnIndex = team;
    }

    /** True once the player accepted the given revision or declined the invite. */
    public boolean responded(int revision) {
        return rejected || acceptedRevision == revision;
    }

    /** Side index for the scoreboard and for spawn placement. */
    public int teamIndex() {
        return team >= 0 ? team : spawnIndex;
    }
}
