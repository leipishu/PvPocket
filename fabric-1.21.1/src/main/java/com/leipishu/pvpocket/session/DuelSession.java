package com.leipishu.pvpocket.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The persisted record of one duel series. Everything needed to resume, roll back or audit a
 * session lives here; nothing that matters is kept only in memory.
 */
public final class DuelSession {

    public String id;
    public SessionStage stage = SessionStage.DRAFT;
    public int revision;
    public long createdAt;
    public long updatedAt;
    public String voidReason;

    /** S1 is fixed to one round of modern combat; the fields exist so later stages can widen it. */
    public String mode = "modern";
    public int bestOf = 1;
    public int round;

    /**
     * Encoded rule set plus its fingerprint. Both are written to disk and to the journal, so a
     * disputed match can be checked against the exact rules that ran rather than the rules the
     * proposer remembers proposing.
     */
    public String rules;
    public String rulesDigest;
    /** Bumped on every accepted edit, so "you agreed to v1, this is v3" is visible to both sides. */
    public int rulesRevision;
    /** Uuid of the player who authored the current revision. */
    public String rulesAuthor;
    /** Uuid of the player whose answer the session is waiting on. */
    public String awaitingUuid;

    public List<Participant> participants = new ArrayList<>();
    /** Wins keyed by participant uuid (teams arrive in S5). */
    public Map<String, Integer> score = new LinkedHashMap<>();

    public String arenaId = "arena-0";

    /**
     * Set when a round outcome is decided but cannot be applied yet. Death is the main case: the
     * player is on the death screen and cannot be teleported, so the settle is deferred until
     * they respawn.
     */
    public String pendingWinner;
    public String pendingReason;

    public Participant participant(String uuid) {
        for (Participant p : participants) {
            if (p.uuid.equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    public Participant opponentOf(String uuid) {
        for (Participant p : participants) {
            if (!p.uuid.equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    public boolean isParticipant(String uuid) {
        return participant(uuid) != null;
    }
}
