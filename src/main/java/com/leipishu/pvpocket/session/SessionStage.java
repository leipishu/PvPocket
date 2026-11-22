package com.leipishu.pvpocket.session;

/**
 * Session lifecycle: DRAFT -> NEGOTIATING -> VOTING -> RESULT -> FROZEN -> ROUND_READY ->
 * ROUND_ACTIVE -> ROUND_SETTLED -> ROUND_READY (next round) or RESTORING -> ARCHIVED.
 *
 * <p>{@link #NEGOTIATING} is the branch window: the invite is out, everybody may submit one
 * alternative rule set, and nothing has been merged yet. {@link #VOTING} is entered only when at
 * least one branch exists; a window that closes with no branches skips it and goes straight to
 * {@link #FROZEN}.
 *
 * <p>At or after {@link #FROZEN} snapshots exist on disk, so the session must be rolled back
 * rather than discarded. Earlier stages have no side effects and can be dropped.
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

    /** True before any player state has been touched. */
    public boolean isPreFreeze() {
        return this == DRAFT || this == NEGOTIATING || this == VOTING || this == RESULT;
    }

    public boolean hasSnapshots() {
        return !isPreFreeze();
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
