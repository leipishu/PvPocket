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
     *
     * <p>Assigned by the roster the proposer builds in the panel, not dealt by the server. The
     * round-robin deal that used to live in {@code SessionManager.assignTeams} is gone with it: a
     * proposer who wants Dev1 and Dev3 on one side can now say so, and the price is that the shape
     * of the match is a property of the roster rather than of a rule.</p>
     */
    public int team = -1;

    /**
     * Index within the team, 0-based.
     *
     * <p>Used for one thing: spreading team-mates around their side's spawn pad instead of stacking
     * them on the same block. Derived from the order the proposer filled the card in, not stored on
     * the wire.</p>
     */
    public int member;

    /**
     * True once this player has declined the invite.
     *
     * <p>Terminal, and separate from "has not answered yet": a rejection removes the player from
     * the match without voiding it, so a four-way invite with one refusal can still start as a 2v1.
     * A later revision bump must not put a rejected player back into the asking queue, which is why
     * this is a flag rather than a negative {@code acceptedRevision}.</p>
     */
    public boolean rejected;

    /**
     * The rule revision this player has accepted, or {@code -1}.
     *
     * <p>Multi-party consent needs a number, not a turn: with three or more sides there is no single
     * "the other party" to hand the pen to, so every participant records the revision they said yes
     * to and the session starts when all of them match the current one. A re-proposal bumps the
     * revision, which invalidates every earlier yes by construction.</p>
     */
    public int acceptedRevision = -1;

    /**
     * True once this player is out of the current round — killed, out of bounds, or surrendered.
     *
     * <p>Per-round, cleared at every round start. The round ends when only one team still has
     * somebody standing, which is the same rule for a 1v1 (one death ends it) and a 3v3 (the last
     * member of a side dies).</p>
     */
    public boolean eliminated;

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
     * A between-round loadout change waiting for the rest of the match to agree.
     *
     * <p>Separate from {@link #loadout} rather than overwriting it, because the change is not in
     * effect until everybody says yes. Overwriting first and rolling back on a refusal would mean
     * the old loadout has to be reconstructed from somewhere — and there is nowhere to reconstruct
     * it from, since it was never written down twice.</p>
     */
    public String pendingLoadout;
    public String pendingLoadoutDigest;

    /**
     * Which {@code DuelSession#loadoutRevision} this player has agreed to.
     *
     * <p>Same shape as {@link #acceptedRevision} and for the same reason: with three or four sides
     * there is no single "the other party" to ask, so the change lands when every participant's
     * number matches the current one. Bumping the revision invalidates every earlier yes by
     * construction.</p>
     */
    public int acceptedLoadoutRevision;

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

    /**
     * A participant placed on a specific side by the roster.
     *
     * <p>{@code spawnIndex} is the side as well: the arena has one spawn pad per side, and a player
     * who is on side 2 stands on pad 2. Keeping them equal here means a session written by this
     * constructor is readable by every path that predates explicit teams.</p>
     */
    public Participant(String uuid, String name, int team, int member) {
        this.uuid = uuid;
        this.name = name;
        this.team = team;
        this.member = member;
        this.spawnIndex = team;
    }

    /** Answered the current revision one way or the other — accepted it, or declined the invite. */
    public boolean responded(int revision) {
        return rejected || acceptedRevision == revision;
    }

    /** Side index for the scoreboard and for spawn placement. */
    public int teamIndex() {
        return team >= 0 ? team : spawnIndex;
    }
}
