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

    /** "modern" or "legacy", derived from c.mode. */
    public String mode = "modern";
    /** Rounds in the series. Odd, 1..15; 15 means "first to 8". Derived from a.series. */
    public int bestOf = 1;
    /** 1-based index of the round currently being played or just played. */
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

    /**
     * How many sides this series has, derived from {@code b.teamCount} when the match starts.
     *
     * <p>Stored rather than recomputed because the rule set is frozen at start but the session record
     * outlives it: the journal, the restore path and the result announcement all need to know how
     * many sides there were, and a rule that was edited mid-negotiation must not be able to
     * retroactively change the shape of a match that already ran.</p>
     */
    public int teamCount = 2;

    /**
     * Rounds won, keyed by team index as a string ("0".."3").
     *
     * <p>Team-scoped rather than player-scoped: a 2v2 is won by a side, and giving each member a
     * personal win count would let the scoreboard show 3:2 in a three-round series. The map is keyed
     * by string because that is what Gson round-trips without a custom adapter.</p>
     */
    public Map<String, Integer> teamScore = new LinkedHashMap<>();

    public String arenaId = "arena-0";

    /**
     * Set when a round outcome is decided but cannot be applied yet. Death is the main case: the
     * player is on the death screen and cannot be teleported, so the settle is deferred until
     * they respawn.
     */
    public String pendingWinner;
    public String pendingReason;

    /**
     * The side that won the decided-but-not-applied round, or {@code -1}.
     *
     * <p>Separate from {@link #pendingWinner} because the winner is a side now: a 2v2 is won by two
     * players, and storing one of their uuids would make the result depend on which of them happened
     * to land the last hit. {@code pendingWinner} is kept as "the participant whose respawn this
     * settle is waiting for", which is a different question.</p>
     */
    public int pendingWinnerTeam = -1;

    /**
     * Why the series ended, when that reason is not "somebody reached the win count".
     *
     * <p>{@code "SURRENDER"} and {@code "CUT"} are the two that need saying out loud: both can end a
     * series with the winner <em>behind</em> on rounds, and a bare "Dev1 获胜（0:1）" reads like a bug
     * unless the sentence also says the opponent quit.</p>
     */
    public String seriesEndReason;

    public Participant participant(String uuid) {
        for (Participant p : participants) {
            if (p.uuid.equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Everybody on a different side than {@code uuid}, in join order.
     *
     * <p>There is no {@code opponentOf} returning one player. That method existed while a match had
     * exactly two sides; with three or four there is no such person, and every caller that wanted
     * "the other side" wants the whole of it — a chat line, a panel subtitle, a HUD column.</p>
     */
    public List<Participant> opponentsOf(String uuid) {
        Participant me = participant(uuid);
        List<Participant> out = new ArrayList<>();
        for (Participant p : participants) {
            if (!p.uuid.equals(uuid) && (me == null || p.teamIndex() != me.teamIndex())) {
                out.add(p);
            }
        }
        return out;
    }

    public List<Participant> teamMembers(int team) {
        List<Participant> out = new ArrayList<>();
        for (Participant p : participants) {
            if (p.teamIndex() == team) {
                out.add(p);
            }
        }
        return out;
    }

    public int wins(int team) {
        return teamScore.getOrDefault(Integer.toString(team), 0);
    }

    public void addWin(int team) {
        teamScore.merge(Integer.toString(team), 1, Integer::sum);
    }

    /**
     * Teams that still have somebody standing this round.
     *
     * <p>Read from {@link Participant#eliminated} rather than from online-ness: a disconnected player
     * is not necessarily out (they may reconnect during the grace period), whereas an eliminated one
     * is out for the round by decision.</p>
     */
    public List<Integer> survivingTeams() {
        List<Integer> out = new ArrayList<>();
        for (int team = 0; team < Math.max(1, teamCount); team++) {
            for (Participant p : participants) {
                if (p.teamIndex() == team && !p.eliminated) {
                    out.add(team);
                    break;
                }
            }
        }
        return out;
    }

    public boolean isParticipant(String uuid) {
        return participant(uuid) != null;
    }
}
