package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.rules.DuelRules;
import com.leipishu.pvpocket.rules.Rulebook;

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

    /** Match mode, {@code "modern"} or {@code "legacy"}, derived from c.mode. */
    public String mode = "modern";
    /**
     * How many rounds the whole series may run, 1..15. Derived from {@code a.maxRounds}.
     *
     * <p>A cap only: the series ends early when somebody reaches {@link #winsNeeded}, and ends on
     * the cap when nobody does.
     */
    public int maxRounds = 1;
    /**
     * Round wins needed to take the series, 1..{@link #maxRounds}. Derived from
     * {@code a.winsNeeded}.
     */
    public int winsNeeded = 1;
    /** Index of the round currently being played or just played, 1-based. */
    public int round;

    /**
     * Encoded rule set plus its fingerprint. Both are written to disk and to the journal, so a
     * disputed match can be checked against the rules that ran.
     */
    public String rules;
    public String rulesDigest;

    /**
     * Decoded form of {@link #rules}, rebuilt whenever the encoded string changes.
     *
     * <p>Not persisted. The cache is keyed on the string itself, so assigning {@code rules}
     * directly cannot leave a stale decode behind. The rule questions run on hot paths.
     */
    private transient String rulebookSource;
    private transient Rulebook rulebook;

    /** The rules of this session, read by name. Never null, even before a draft is submitted. */
    public Rulebook rulebook() {
        if (rulebook == null || !java.util.Objects.equals(rulebookSource, rules)) {
            rulebookSource = rules;
            rulebook = Rulebook.of(DuelRules.decode(rules));
        }
        return rulebook;
    }
    /** Bumped on every accepted edit, so both sides can tell which revision they agreed to. */
    public int rulesRevision;
    /**
     * Bumped every time somebody changes their loadout between rounds; see
     * {@code Participant#acceptedLoadoutRevision}.
     *
     * <p>Separate from {@link #rulesRevision}: the rules are frozen once the series starts, and
     * {@code d.allowSwapBetweenRounds} covers the loadout alone.
     */
    public int loadoutRevision;
    /** Uuid of the player who authored the current revision. */
    public String rulesAuthor;
    /** Uuid of the player whose answer the session is waiting on. */
    public String awaitingUuid;

    public List<Participant> participants = new ArrayList<>();

    /**
     * How many sides this series has, derived from the roster when the match starts.
     *
     * <p>Stored rather than recomputed, so a roster edited mid-negotiation cannot change the shape
     * of a match that already ran.
     *
     * <p>This is the number of cards the proposer put on the table, including any left empty. A
     * side with nobody on it does not play; see {@link #playingTeams()}.
     */
    public int teamCount = 2;

    /**
     * Rounds won, keyed by team index as a string ("0".."3").
     *
     * <p>Team-scoped, so a 2v2 is won by a side rather than by each member. The key is a string
     * because that is what Gson round-trips without a custom adapter.
     */
    public Map<String, Integer> teamScore = new LinkedHashMap<>();

    public String arenaId = "arena-0";

    /**
     * One entry per settled round, appended by {@code settle}.
     *
     * <p>Persisted with the session because the match record is written at the end of the series.
     * Capped by {@link #maxRounds}.
     */
    public List<RoundLog> roundLog = new ArrayList<>();

    /** The outcome of one round. Plain fields, so it round-trips through Gson. */
    public static final class RoundLog {
        /** Side that took the round, or {@code -1} for a round nobody won. */
        public int winner;
        /** One of {@code DEATH}, {@code BOUNDARY}, {@code SURRENDER}, {@code TIMEOUT}. */
        public String reason;
        /** Length of the round in milliseconds. */
        public long ms;

        public RoundLog() {
        }

        public RoundLog(int winner, String reason, long ms) {
            this.winner = winner;
            this.reason = reason;
            this.ms = ms;
        }
    }

    /**
     * Set when a round outcome is decided but cannot be applied yet. Death is the main case: the
     * player is on the death screen and cannot be teleported, so the settle waits for the respawn.
     */
    public String pendingWinner;
    public String pendingReason;

    /**
     * The side that won the decided-but-not-applied round, or {@code -1}.
     *
     * <p>{@link #pendingWinner} holds the participant whose respawn the settle waits for, which is
     * a different question from which side won.
     */
    public int pendingWinnerTeam = -1;

    /**
     * Why the series ended, when that reason is not "somebody reached the win count".
     *
     * <p>{@code "SURRENDER"} and {@code "CUT"} can end a series with the winner behind on rounds.
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
     * <p>Declined invites are excluded. There is no single-opponent variant, since a match can have
     * three or four sides.
     */
    public List<Participant> opponentsOf(String uuid) {
        Participant me = participant(uuid);
        List<Participant> out = new ArrayList<>();
        for (Participant p : participants) {
            if (!p.uuid.equals(uuid) && !p.rejected
                    && (me == null || p.teamIndex() != me.teamIndex())) {
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

    /**
     * Members of a side who are actually in the match; declined invites excluded.
     *
     * <p>A rejection is not a removal: the declined player stays on the record, but every question
     * that means "who is playing" has to use this list.
     */
    public List<Participant> activeMembers(int team) {
        List<Participant> out = new ArrayList<>();
        for (Participant p : participants) {
            if (p.teamIndex() == team && !p.rejected) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Sides with at least one player left, in card order.
     *
     * <p>An empty card is not a side: the proposer may put four cards on the table and fill two.
     * Fewer than two entries means there is nobody to fight.
     */
    public List<Integer> playingTeams() {
        List<Integer> out = new ArrayList<>();
        for (int team = 0; team < Math.max(1, teamCount); team++) {
            if (!activeMembers(team).isEmpty()) {
                out.add(team);
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
     * <p>Read from {@link Participant#eliminated} rather than from online-ness: a disconnected
     * player may reconnect during the grace period.
     */
    public List<Integer> survivingTeams() {
        List<Integer> out = new ArrayList<>();
        for (int team : playingTeams()) {
            for (Participant p : activeMembers(team)) {
                if (!p.eliminated) {
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
