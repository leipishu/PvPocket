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
}
