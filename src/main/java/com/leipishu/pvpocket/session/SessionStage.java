package com.leipishu.pvpocket.session;

/**
 * Session lifecycle: DRAFT -> NEGOTIATING -> VOTING -> RESULT -> FROZEN -> ROUND_READY ->
 * ROUND_ACTIVE -> ROUND_SETTLED -> ROUND_READY (next round) or RESTORING -> ARCHIVED.
 *
 * <p>{@link #NEGOTIATING} is the branch window; {@link #VOTING} is entered only when at least one
 * branch exists, and a window that closes with no branches goes straight to {@link #FROZEN}.
 *
 * <p>{@link #RESULT} is the only pre-freeze stage that has touched the world: it leases and builds
 * the plot, writes every snapshot and moves the participants into the lobby. A session dropped
 * there must be rolled back, while DRAFT, NEGOTIATING and VOTING can simply be discarded.
 */
public enum SessionStage {
    DRAFT,
    NEGOTIATING,
    VOTING,
    RESULT,
    FROZEN,
    ROUND_READY,
    ROUND_ACTIVE,
    ROUND_SETTLED,
    RESTORING,
    ARCHIVED,
    VOIDED;

    public boolean isTerminal() {
        return this == ARCHIVED || this == VOIDED;
    }

    /**
     * True before the freeze: the match has not started, so it can still start and the rules and
     * loadouts can still be edited.
     *
     * <p>Not "nothing has happened yet". {@link #RESULT} has leased the plot, built it and moved
     * everybody into the lobby, so a session dropped there has to be rolled back; see
     * {@code SessionManager#releaseLobby}.
     */
    public boolean isPreFreeze() {
        return this == DRAFT || this == NEGOTIATING || this == VOTING || this == RESULT;
    }

    /**
     * True once a session may have snapshots on disk.
     *
     * <p>Not the inverse of {@link #isPreFreeze}: {@link #RESULT} writes one per player before it
     * moves them into the lobby.
     */
    public boolean hasSnapshots() {
        return this == RESULT || !isPreFreeze();
    }

    /**
     * Translation key naming the stage, {@code pvpocket.stage.<lowercase name>}.
     *
     * <p>Every constant gets a key, so a status line never falls back to a bare enum name in one
     * language and a word in another. A key missing from a language file renders as itself.
     */
    public String labelKey() {
        return "pvpocket.stage." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
