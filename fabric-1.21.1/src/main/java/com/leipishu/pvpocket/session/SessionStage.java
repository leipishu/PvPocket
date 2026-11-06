package com.leipishu.pvpocket.session;

/**
 * Session lifecycle.
 *
 * <pre>
 *   DRAFT -> NEGOTIATING -> FROZEN -> ROUND_READY -> ROUND_ACTIVE
 *                                                          |
 *                                                    ROUND_SETTLED
 *                                                          |
 *                              (next round) ROUND_READY <-+-> RESTORING -> ARCHIVED
 * </pre>
 *
 * <p>Anything at or after {@link #FROZEN} means snapshots exist on disk, so the session must be
 * rolled back rather than discarded. Anything before it has no side effects and can be dropped.</p>
 */
public enum SessionStage {
    DRAFT,
    NEGOTIATING,
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
        return this == DRAFT || this == NEGOTIATING;
    }

    public boolean hasSnapshots() {
        return !isPreFreeze();
    }

    /**
     * Translation key naming the stage, {@code pvpocket.stage.<lowercase name>}.
     *
     * <p>Only the two stages a spectator can be looking at are worth translating, but every constant
     * gets a key so a status line never falls back to a bare {@code ROUND_SETTLED} in one language
     * and a word in another. A key that a language file does not carry renders as itself, which is
     * the same thing the enum name would have said.</p>
     */
    public String labelKey() {
        return "pvpocket.stage." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
