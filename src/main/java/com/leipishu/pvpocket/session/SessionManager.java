package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.compat.PvCompat;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.ArenaMaps;
import com.leipishu.pvpocket.arena.ArenaRegion;
import com.leipishu.pvpocket.arena.Lobby;
import com.leipishu.pvpocket.arena.Sandbox;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.DeathFx;
import com.leipishu.pvpocket.combat.UsePolicy;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutBook;
import com.leipishu.pvpocket.loadout.LoadoutBuilder;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.loadout.LoadoutLock;
import com.leipishu.pvpocket.mixin.PlayerManagerInvoker;
import com.leipishu.pvpocket.network.ArenaBoundsPayload;
import com.leipishu.pvpocket.network.HomeActionPayload;
import com.leipishu.pvpocket.network.HomeStatePayload;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.network.PrepareScreensPayload;
import com.leipishu.pvpocket.network.PrepareStatusPayload;
import com.leipishu.pvpocket.network.PvPocketNetworking;
import com.leipishu.pvpocket.network.ResultOpenPayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.RulesSubmitPayload;
import com.leipishu.pvpocket.network.SeriesResultPayload;
import com.leipishu.pvpocket.network.SpectateRequestPayload;
import com.leipishu.pvpocket.network.SurrenderStatePayload;
import com.leipishu.pvpocket.network.VoteOpenPayload;
import com.leipishu.pvpocket.network.VoteProgressPayload;
import com.leipishu.pvpocket.rules.DuelRules;
import com.leipishu.pvpocket.rules.Rulebook;
import com.leipishu.pvpocket.snapshot.PlayerSnapshot;
import com.leipishu.pvpocket.stats.DuelStats;
import com.leipishu.pvpocket.stats.MatchRecord;
import com.leipishu.pvpocket.stats.PlayerStats;
import com.leipishu.pvpocket.storage.HomePositions;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import com.leipishu.pvpocket.storage.SessionJournal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Orchestrates duel sessions: request, freeze, teleport, settle, restore, reconcile.
 *
 * <p>Invariants: the journal is written before the state change it describes, and player state is
 * only touched after the snapshot is on disk.
 */
public final class SessionManager {

    private static SessionManager instance;

    /** How often stray mobs are swept out of leased plots, in server ticks. */
    private static final int SWEEP_INTERVAL = 60;

    /**
     * How long the branch window stays open after the invite goes out.
     *
     * <p>Five minutes is the design's number: long enough to read thirty-five rules and one
     * loadout, short enough that an idle invite does not hold a plot's worth of players. A window
     * that everybody answers early closes early; only silence runs the clock out.
     */
    private static final long RULES_WINDOW_MS = 5L * 60L * 1000L;

    /**
     * How long voting stays open once the ballot is built.
     *
     * <p>Shorter than the branch window: every option is already written down, so the only work is
     * clicking. It exists so one player who walks away cannot hold the match forever; the result is
     * then decided on the votes already cast.
     */
    private static final long VOTE_WINDOW_MS = 3L * 60L * 1000L;

    /**
     * How long to wait before retrying a start the arena refused.
     *
     * <p>Long enough for a lease to be released or a rebuild to finish, short enough that the
     * players still see the countdown rather than a frozen panel.
     */
    private static final long START_RETRY_MS = 3_000L;

    /**
     * How long the result page stays up before the match starts.
     *
     * <p>Ten seconds: long enough to read the card through without having to come back for it with
     * `/pvpocket review`. The page is a countdown rather than a ready check, because everybody has
     * answered before it opens and a second gate would only add a way to stall the start.
     *
     * <p>Counted from the moment the plot is ready, not from the moment the page is published: the
     * build is synchronous and can block the server for seconds, and a deadline set before it would
     * be spent by the time the page reached the clients.
     */
    private static final long RESULT_WINDOW_MS = 10_000L;

    /**
     * How long the first round waits for a participant who accepted while dead, in server ticks.
     *
     * <p>Ten seconds covers a death screen and a click on a slow machine.
     */
    private static final int START_WAIT_TICKS = 200;

    /**
     * How long the first round tolerates a participant absent from the player list, in server
     * ticks.
     *
     * <p>Much shorter than {@link #START_WAIT_TICKS}: a login adds the entry a tick or two after
     * the accept packet, and anything longer is a disconnect.
     */
    private static final int ABSENT_GRACE_TICKS = 40;

    /**
     * How long a round boundary waits for a side with nobody present, in milliseconds.
     *
     * <p>Between rounds is the only moment a returning player can be taken back, so a dropped
     * connection gets one window here before the series is cut. Long enough for a client to
     * reconnect and answer the prompt; short enough that the other side is not left waiting.
     */
    private static final long RECONNECT_HOLD_MS = 60_000L;

    /**
     * How long a finished series can be replayed with one press, in milliseconds.
     *
     * <p>Long enough for the result card to be read and a decision to be made, short enough that
     * "rematch" still means the people who were just here rather than a stale roster.
     */
    private static final long REMATCH_WINDOW_MS = 10L * 60L * 1000L;

    /**
     * Upper bound on how long a participant can stay off the plot before the boundary rule
     * applies, in milliseconds.
     *
     * <p>A safety valve rather than a grace window; see {@link #awaitingFirstEntry}.
     */
    private static final long SETTLE_DEADLINE_MS = 30_000L;

    /**
     * Minimum gap between two boundary notices to the same player, in milliseconds.
     *
     * <p>A player leaning on the edge produces one pull-back per tick, so notices are throttled.
     */
    private static final long BOUNDARY_NOTICE_GAP_MS = 1_500L;

    /**
     * The minimum height the client draws the boundary curtain at, in blocks.
     *
     * <p>The client raises the curtain to clear its own camera, so this is a floor. Four blocks is
     * tall enough to see without looking up.
     */
    private static final double BOUNDARY_CURTAIN_HEIGHT = 4.0D;

    /**
     * How long a player may stand outside the plot before the round is forfeited.
     *
     * <p>Five seconds, fixed rather than a rule, and long enough to survive a one-second lag spike.
     */
    private static final long BOUNDARY_COUNTDOWN_MS = 5_000L;

    /**
     * Hold between a duel death and the round's reaction to it, in milliseconds.
     *
     * <p>Long enough for the death particles to play out and for the other side to see who fell.
     * Both the spectator hand-off and the settle wait this out instead of firing on the next tick.
     */
    private static final long DEATH_BEAT_MS = 2_500L;

    /**
     * How long a surrender request waits for the rest of the side, in milliseconds.
     *
     * <p>Thirty seconds covers a fight in progress without holding the whole match open on a
     * teammate who has walked away from the keyboard.
     */
    private static final long SURRENDER_WINDOW_MS = 30_000L;

    /**
     * How long a dead body outside a live round may stay before it is replaced, in milliseconds.
     *
     * <p>No death beat is playing there, so the only reason to wait at all is a client that is one
     * packet behind.
     */
    private static final long CORPSE_IDLE_MS = 500L;

    /**
     * Hard ceiling on one session's roster.
     *
     * <p>Four sides of five is the largest shape the roster panel can draw. Enforced when the
     * roster is submitted, so a malformed client packet is refused.
     */
    private static final int MAX_PARTICIPANTS = 20;

    /** Sides the roster panel can draw. Below two there is nobody to fight. */
    private static final int MIN_TEAMS = 2;
    private static final int MAX_TEAMS = 4;

    /**
     * Key is {@code sessionId/uuid}: when the player crossed out, and the last second announced.
     */
    private record BoundaryExit(long since, int announced) {
    }

    /** Session id -> when the preparation window ends, and the last whole second announced. */
    private record Prepare(long at, int announced) {
    }

    /** One reaction held until its wall-clock deadline. */
    private record DelayedReaction(long dueAt, Runnable body) {
    }

    /**
     * A surrender waiting on the rest of the side.
     *
     * <p>One per session: a second request while one is open would make "everybody agreed"
     * ambiguous, so the second caller is refused instead.
     *
     * <p>{@code agreed} holds uuids and starts with the requester, whose consent is the request
     * itself. {@code announced} is the last whole second pushed to the clients, so the countdown
     * costs one packet per second rather than one per tick.
     */
    private static final class SurrenderRequest {
        final String requester;
        final int team;
        final long deadline;
        final Set<String> agreed = new LinkedHashSet<>();
        int announced = -1;

        SurrenderRequest(String requester, int team, long deadline) {
            this.requester = requester;
            this.team = team;
            this.deadline = deadline;
            this.agreed.add(requester);
        }
    }

    private final MinecraftServer server;
    private final PvPocketStorage storage;
    private final Map<String, DuelSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, String> sessionByPlayer = new HashMap<>();
    private final Map<UUID, String> inviteByTarget = new HashMap<>();
    /** Player uuid -> session id whose snapshot still has to be restored. Survives restarts. */
    private final Map<UUID, String> pendingRestore = new LinkedHashMap<>();
    private final Map<String, SessionJournal> journals = new HashMap<>();
    /** Per-player saved loadouts, usable with or without an open session. */
    private final LoadoutBook loadouts;
    private final List<Runnable> deferred = new ArrayList<>();
    /** Post-death reactions waiting out the particle beat, due at a wall-clock deadline. */
    private final List<DelayedReaction> delayedReactions = new ArrayList<>();
    /** Session id -> wall-clock start of the current round, for the opening grace window. */
    private final Map<String, Long> roundStartedAt = new HashMap<>();
    /** Session id -> wall-clock time the next round may begin, for the intermission. */
    private final Map<String, Long> roundReadyAt = new HashMap<>();
    /**
     * Session id -> when the boundary started waiting for a side with nobody present.
     *
     * <p>Separate from {@link #roundReadyAt}, which is retried every second: this one remembers
     * when the wait began so {@link #RECONNECT_HOLD_MS} can expire it into a cut.
     */
    private final Map<String, Long> absentSince = new HashMap<>();
    /**
     * Session id -> the intermission second last pushed to clients.
     *
     * <p>A countdown changes every second on its own, so it needs its own push. Storing the last
     * value keeps that to one packet per second per session.
     */
    private final Map<String, Integer> countdownPushed = new HashMap<>();
    /** Players currently outside their plot, waiting out the boundary countdown. */
    private final Map<String, BoundaryExit> boundaryExits = new HashMap<>();
    /**
     * {@code sessionId/playerUuid} of participants seen standing on their plot this round.
     *
     * <p>Keyed per player, since the chunk download finishes at a different moment for each side.
     */
    private final Set<String> settledIn = new HashSet<>();
    /**
     * Key is {@code sessionId/playerUuid}: wall clock of the last pull-back notice, for throttling.
     */
    private final Map<String, Long> boundaryNotices = new HashMap<>();
    /**
     * Player uuid -> why their next death is not a normal one, read once by {@link #onDeath}.
     *
     * <p>Only a forfeit writes here, and the death lands in the same tick, so the entry is removed
     * as it is read.
     */
    private final Map<UUID, String> forfeitReasons = new HashMap<>();
    /**
     * Player uuid -> when the sweep first saw them dead inside a session.
     *
     * <p>Drives {@link #tickCorpses}: a body is replaced once it has been down for the death beat,
     * since vanilla only does that on a client respawn request that may never arrive.
     */
    private final Map<UUID, Long> corpseSince = new HashMap<>();
    /** Session id -> the surrender waiting on the rest of the side. */
    private final Map<String, SurrenderRequest> surrenderRequests = new HashMap<>();
    /**
     * Player uuid -> the respawn point they had before a death moved it onto the plot.
     *
     * <p>Null is a real value here: a player whose respawn point is the world spawn has none, and
     * the restore has to clear it rather than leave the arena behind.
     */
    private final Map<UUID, PvCompat.RespawnPoint> respawnPoints = new HashMap<>();
    /** Rounds that have teleported everybody in but have not gone live yet. */
    private final Map<String, Prepare> prepareDeadlines = new HashMap<>();
    /**
     * Spectators, and the state needed to put them back.
     *
     * <p>Kept apart from the session fields, since watching a duel moves a player who is not in a
     * session.
     */
    private final Spectators spectators;
    /**
     * Where each player was before the arena. Outlives every session, so it covers a session that
     * died with somebody still inside.
     */
    private final HomePositions homes;
    /**
     * Watches for players standing in the arena with nothing to do there.
     *
     * <p>Separate from {@link Spectators}: spectatorship is a state somebody opted into, and this
     * is the absence of every such state.
     */
    private final ArenaGuard guard;
    /**
     * Lifetime counters and finished-match history. Outlives every session.
     */
    private final DuelStats stats;
    private int sweepTimer;

    private SessionManager(MinecraftServer server) {
        this.server = server;
        this.storage = new PvPocketStorage(server);
        this.loadouts = new LoadoutBook(storage);
        this.spectators = new Spectators(this);
        this.homes = HomePositions.load(server);
        this.guard = ArenaGuard.load(this, server);
        this.stats = new DuelStats(storage.root());
    }

    /** Package-private: {@link Spectators} needs the server for lookups and teleports. */
    MinecraftServer server() {
        return server;
    }

    /** Package-private: session lookup for the spectator layer. */
    DuelSession byId(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    /** Package-private: the match list iterates every session. */
    List<DuelSession> allSessions() {
        return new ArrayList<>(sessions.values());
    }

    /** Package-private: the stray-player guard needs the saved home positions. */
    HomePositions homes() {
        return homes;
    }

    /** Package-private: the guard has to distinguish "watching" from "just standing there". */
    boolean isSpectating(UUID uuid) {
        return spectators.isWatching(uuid);
    }

    /** Package-private: which session this player is a participant of, or null. */
    String sessionOf(UUID uuid) {
        return sessionByPlayer.get(uuid);
    }

    /**
     * Package-private: true while this player is a live participant of a session whose result page
     * is up.
     *
     * <p>The lobby is inside the duel dimension, and {@link #sessionByPlayer} is still empty there:
     * the match mapping is written by {@code freeze}, after the countdown. Without this the
     * stray-player guard would read every guest as somebody who walked in and move them out
     * mid-countdown.
     */
    boolean isAwaitingStart(UUID uuid) {
        String id = uuid.toString();
        for (DuelSession session : sessions.values()) {
            if (session.stage != SessionStage.RESULT) {
                continue;
            }
            Participant p = session.participant(id);
            if (p != null && !p.rejected) {
                return true;
            }
        }
        return false;
    }

    public static SessionManager create(MinecraftServer server) {
        instance = new SessionManager(server);
        return instance;
    }

    public static SessionManager get() {
        return instance;
    }

    public static void clear() {
        instance = null;
    }

    public PvPocketStorage storage() {
        return storage;
    }

    // ------------------------------------------------------------------ startup / shutdown

    public void load() {
        // Stats load first, so a session finishing this tick cannot write into an unread store.
        stats.load();
        try {
            storage.ensureLayout();
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot create pvpocket storage layout", e);
            return;
        }

        Path dir = storage.sessionsDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(this::loadOne);
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot list session directory", e);
        }
    }

    private void loadOne(Path file) {
        try {
            DuelSession session = storage.readJson(file, DuelSession.class);
            if (session != null && session.id != null) {
                sessions.put(session.id, session);
            }
        } catch (Exception e) {
            PvPocket.LOGGER.error("Cannot read session file {}", file, e);
        }
    }

    /**
     * Startup reconciliation: queue restore debt, then void unfinished sessions.
     *
     * <p>Every participant whose snapshot was written but never applied is queued for restore,
     * regardless of stage. Unfinished sessions are voided; no attempt is made to resume a round.
     */
    public void reconcile() {
        for (DuelSession session : sessions.values()) {
            for (Participant p : session.participants) {
                if (p.snapshotFile != null && !p.restored) {
                    pendingRestore.put(UUID.fromString(p.uuid), session.id);
                    PvPocket.LOGGER.warn("Outstanding restore for {} in session {} (stage {})",
                            p.name, session.id, session.stage);
                }
            }
        }

        for (DuelSession session : sessions.values()) {
            if (session.stage.isTerminal()) {
                continue;
            }
            if (session.stage.isPreFreeze()) {
                journal(session).append("SESSION_DROPPED", "stage", session.stage.name());
                session.voidReason = "SERVER_RESTART_BEFORE_START";
                transition(session, SessionStage.VOIDED);
                continue;
            }
            PvPocket.LOGGER.warn("Reconciling unfinished session {} at stage {}", session.id, session.stage);
            session.voidReason = "SERVER_CRASH";
            journal(session).append("ANOMALY", "detail", "unfinished session reconciled", "stage", session.stage.name());
            transition(session, SessionStage.VOIDED);
        }
    }

    public void shutdown() {
        // Eject spectators first, or a camera follows a player being teleported out of the arena.
        spectators.ejectEverything("SERVER_STOP");
        for (DuelSession session : sessions.values()) {
            if (session.stage == SessionStage.ROUND_ACTIVE) {
                journal(session).append("ROUND_ENDED",
                        "round", session.round, "reason", "SERVER_STOP", "winner", null);
                session.pendingWinner = null;
                session.pendingReason = null;
                roundStartedAt.remove(session.id);
                roundReadyAt.remove(session.id);
                transition(session, SessionStage.ROUND_SETTLED);
                // Straight to restore: there is no next round to prepare for.
                finishSeries(session, -1);
            } else if (session.stage == SessionStage.ROUND_READY) {
                roundReadyAt.remove(session.id);
                finishSeries(session, leaderTeam(session));
            }
        }
        CombatState.clearAll();
        // A borrowed respawn point would outlive the process, and the next death would land in a
        // plot that has since been reverted.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            restoreRespawnPoint(player);
        }
        ArenaManager.clearAll();
        journals.values().forEach(SessionJournal::close);
        journals.clear();
        // Last write before the process exits; these are the positions the next boot rescues to.
        homes.flush();
        stats.flush();
    }

    // ------------------------------------------------------------------ guard / reset

    /**
     * Turns the automatic removal of stray players on or off for one player.
     *
     * <p>The flag is per player and persisted, so an operator inspecting the dimension stays put
     * across a restart.
     *
     * @return whether the guard is enabled for this player after the call
     */
    public boolean setGuardEnabled(ServerPlayer player, boolean enabled) {
        boolean now = guard.setEnabled(player.getUUID(), enabled);
        PvPocket.LOGGER.info("Stray-player guard {} for {} ({} exemptions on record)",
                enabled ? "enabled" : "disabled", player.getName().getString(), guard.exemptCount());
        return now;
    }

    /**
     * Ends every live session without asking anybody.
     *
     * <p>Aborts rather than settles: aborting restores the snapshots, releases the lease and voids
     * the session, which is what has to happen before the terrain can be erased.
     *
     * @return how many sessions were ended
     */
    private int forceEndAll(String reason) {
        int ended = 0;
        for (DuelSession session : new ArrayList<>(sessions.values())) {
            if (session.stage.isTerminal()) {
                continue;
            }
            if (session.stage.isPreFreeze()) {
                session.voidReason = reason;
                journal(session).append("SESSION_DROPPED", "reason", reason,
                        "stage", session.stage.name());
                // RESULT cannot be dropped for free: the plot is leased and everybody is standing
                // in the lobby with a snapshot on disk. The earlier stages have nothing to undo.
                if (session.stage == SessionStage.RESULT) {
                    releaseLobby(session);
                }
                transition(session, SessionStage.VOIDED);
                ended++;
                continue;
            }
            journal(session).append("SESSION_FORCED_END", "reason", reason,
                    "stage", session.stage.name());
            abort(session, reason);
            ended++;
        }
        return ended;
    }

    /**
     * Hard reset of the whole duel dimension: end everything, wipe it, re-stamp the gallery.
     *
     * <p>The repair tool for a dimension that has gone wrong, since the map baseline is whatever
     * was in the world when the map was pasted.
     *
     * <p>Order matters. Spectators go first, then sessions (which restore inventories and release
     * leases), then stray players, and only then is the dimension cleared. The re-stamp is queued,
     * since five cells of about 150k blocks each would trip the watchdog.
     *
     * @return how many sessions were ended, or -1 if a rebuild was already running
     */
    public int resetArena() {
        if (ArenaManager.warmUpPending()) {
            return -1;
        }
        spectators.ejectEverything("ARENA_RESET");
        int ended = forceEndAll("ARENA_RESET");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // No-op for players the sessions already sent home.
            homes.evict(player);
            // Clear the client-side block pose too; clearAll() below is server-side only.
            PvPocketNetworking.broadcastBlocking(player, false);
        }
        CombatState.clearAll();
        // Before clearAll(), because the regions know how tall the debris can be. Covers the cells
        // the re-stamp will not touch: the flat plot and any spare cell.
        ArenaManager.wipeUnstampedCells(server);
        ArenaManager.clearAll();
        // The lobby owns no cell, so the re-stamp below never touches it; rebuild it here or a
        // reset would leave whatever was placed on the island.
        Lobby.build(server);
        ArenaManager.beginWarmUp();
        PvPocket.LOGGER.warn("Arena reset: {} session(s) ended, dimension wiped, re-stamping gallery",
                ended);
        return ended;
    }

    // ------------------------------------------------------------------ player events

    public void onJoin(ServerPlayer player) {
        // Unconditional: a stale legacy flag would leave the player with a permanent block penalty.
        PvPocketNetworking.sendState(player);
        // A client that reconnects mid-match has no HUD state, so re-send it here.
        pushHudTo(player);
        // Same for the spectator bar, which has no round boundary to repair it.
        spectators.pushState(player);

        // Deferred: JOIN fires before the player is in the PlayerManager, and the join sequence
        // sends its own position packet after this point.
        UUID joining = player.getUUID();
        deferred.add(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(joining);
            if (online != null) {
                homes.rescue(online);
                offerReconnect(online);
            }
        });
    }

    /**
     * Asks a returning participant whether they want their place back.
     *
     * <p>Asked rather than assumed: a player who logs in to check something else should not be
     * dropped into a fight. Until they answer they are a spectator, and a round already running
     * will not take them; the next one will.
     *
     * <p>Not asked before the freeze. Nothing has started there, so there is nothing to reconnect
     * to; the countdown puts them in like everybody else.
     */
    private void offerReconnect(ServerPlayer player) {
        DuelSession session = liveSessionOf(player);
        if (session == null) {
            return;
        }
        Participant p = session.participant(player.getStringUUID());
        if (p == null || p.surrendered || p.abandoned) {
            return;
        }
        if (session.stage == SessionStage.RESULT) {
            // Nothing has started and the countdown is itself the round boundary, so being back is
            // all it takes: no question, and no reason to be marked away.
            p.goneSince = 0;
            persist(session);
            enterLobby(session, p, player);
            return;
        }
        if (session.stage.isPreFreeze() || p.goneSince == 0) {
            return;
        }
        // Parked before the question: whatever they answer, a live round must not gain a body that
        // can hit or be hit. The spawn pad is where the next round will put them anyway.
        parkForIntermission(session, player, p, ArenaManager.region(session.id));
        journal(session).append("RECONNECT_OFFERED", "player", p.name,
                "stage", session.stage.name(), "round", session.round);
        player.displayClientMessage(PvLang.t("pvpocket.msg.reconnect.ask",
                        session.round, session.maxRounds, scoreLine(session))
                .withStyle(ChatFormatting.GOLD), false);
        player.displayClientMessage(PvLang.t("pvpocket.msg.reconnect.wait")
                .withStyle(ChatFormatting.GRAY), false);
        Style accept = PvCompat.hoverText(PvCompat.clickCommand(
                        Style.EMPTY.withColor(ChatFormatting.GREEN), "/pvpocket reconnect accept"),
                PvLang.t("pvpocket.msg.reconnect.accept_hover"));
        Style decline = PvCompat.hoverText(PvCompat.clickCommand(
                        Style.EMPTY.withColor(ChatFormatting.RED), "/pvpocket reconnect decline"),
                PvLang.t("pvpocket.msg.reconnect.decline_hover"));
        player.displayClientMessage(PvLang.t("pvpocket.msg.reconnect.accept").setStyle(accept)
                .append(Component.literal("  "))
                .append(PvLang.t("pvpocket.msg.reconnect.decline").setStyle(decline)), false);
    }

    /** The session this player is a live participant of, or null. */
    private DuelSession liveSessionOf(ServerPlayer player) {
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            return null;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage.isTerminal()) {
            return null;
        }
        return session;
    }

    /**
     * The returning player takes their place back.
     *
     * <p>Nothing moves them here: a running round keeps them on the spectator bench, and
     * {@code startRound} puts them in when the next one begins. That is the only moment a round can
     * absorb a player: mid-round the loadout, the spawn and the score are already settled.
     */
    public void reconnectAccept(ServerPlayer player) {
        DuelSession session = liveSessionOf(player);
        if (session == null) {
            fail(player, "pvpocket.msg.reconnect.none");
            return;
        }
        Participant p = session.participant(player.getStringUUID());
        if (p == null || p.surrendered || p.abandoned) {
            fail(player, "pvpocket.msg.reconnect.none");
            return;
        }
        if (p.goneSince == 0) {
            fail(player, "pvpocket.msg.reconnect.already");
            return;
        }
        p.goneSince = 0;
        if (session.stage == SessionStage.ROUND_ACTIVE && !p.eliminated) {
            // They are watching this round, not fighting it. A spectator that counts as standing
            // would keep the round from ever ending.
            p.eliminated = true;
        }
        journal(session).append("RECONNECT_ACCEPTED", "player", p.name,
                "stage", session.stage.name(), "round", session.round);
        persist(session);
        player.displayClientMessage(PvLang.t("pvpocket.msg.reconnect.accepted",
                session.round + 1).withStyle(ChatFormatting.GREEN), false);
    }

    /**
     * The returning player gives up their place.
     *
     * <p>Terminal for this series and only for them: the side keeps playing with whoever is left,
     * which is what makes this different from a surrender.
     */
    public void reconnectDecline(ServerPlayer player) {
        DuelSession session = liveSessionOf(player);
        if (session == null) {
            fail(player, "pvpocket.msg.reconnect.none");
            return;
        }
        Participant p = session.participant(player.getStringUUID());
        if (p == null || p.surrendered || p.abandoned) {
            fail(player, "pvpocket.msg.reconnect.none");
            return;
        }
        if (p.goneSince == 0) {
            fail(player, "pvpocket.msg.reconnect.already");
            return;
        }
        abandon(session, p, player);
    }

    /**
     * Takes one player out of the series without touching their side.
     *
     * <p>Mirrors {@code applySurrender} one member at a time: the snapshot goes back, the screen is
     * cleared, and the side keeps playing with whoever is left. The series only ends when this
     * leaves nobody contesting, or when the round loses its last opposition.
     */
    private void abandon(DuelSession session, Participant p, ServerPlayer player) {
        p.abandoned = true;
        p.goneSince = 0;
        p.eliminated = true;
        journal(session).append("PLAYER_ABANDONED", "player", p.name,
                "stage", session.stage.name(),
                "remaining", session.contestingMembers(p.teamIndex()).size(),
                "teams", session.contestingTeams().size());
        if (p.snapshotFile != null && restoreParticipant(session, p)) {
            // Restored and sent home; nothing else to clear.
        } else {
            clearMatchScreen(player);
        }
        broadcastHud(session, true);
        List<Integer> left = session.contestingTeams();
        if (left.size() <= 1) {
            forfeit(session, player, left.isEmpty() ? leaderTeam(session) : left.get(0));
            return;
        }
        if (session.stage == SessionStage.ROUND_ACTIVE) {
            // The round may have lost its last opposition rather than its last player.
            int winner = lastTeamStanding(session);
            if (winner >= 0) {
                settle(session, "DISCONNECT", winner);
                return;
            }
        }
        persist(session);
    }

    /**
     * Expires the branch window and the ballot.
     *
     * <p>Wall-clock deadlines rather than tick counters: a server that stalls for ten seconds still
     * owes the players the ten seconds, and a session file read back after a restart carries the
     * same instant. A zero deadline means "no window", which is every session written before
     * windows existed.
     *
     * <p>Expiry is the only place silence is interpreted. A branch window that runs out closes with
     * the branches it has (players who filed nothing are "no change"), and a ballot that runs out
     * is decided on the votes already cast, including none.
     */
    private void tickNegotiationWindows() {
        long now = System.currentTimeMillis();
        for (DuelSession session : sessions.values()) {
            if (session.stage == SessionStage.NEGOTIATING
                    && session.rulesWindowEndsAt > 0
                    && now >= session.rulesWindowEndsAt) {
                journal(session).append("WINDOW_EXPIRED",
                        "branches", session.branches.size(),
                        "players", liveCount(session));
                closeWindow(session);
            } else if (session.stage == SessionStage.VOTING
                    && session.voteWindowEndsAt > 0
                    && now >= session.voteWindowEndsAt) {
                journal(session).append("VOTE_EXPIRED", "items", session.voteItems.size());
                resolveVote(session, null);
            } else if (session.stage == SessionStage.RESULT
                    && session.resultEndsAt > 0
                    && now >= session.resultEndsAt) {
                journal(session).append("RESULT_EXPIRED", "items", session.voteItems.size());
                session.resultEndsAt = 0;
                settleNegotiation(session, null);
            } else if (session.startRetryAt > 0 && now >= session.startRetryAt) {
                session.startRetryAt = 0;
                settleNegotiation(session, null);
            }
        }
    }

    /**
     * Pushes the intermission countdown, at most once per second per session.
     *
     * <p>A countdown changes by itself, so it is compared against {@link #countdownPushed} instead
     * of being pushed on change.
     */
    private void tickIntermissionCountdown() {
        if (roundReadyAt.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : roundReadyAt.entrySet()) {
            DuelSession session = sessions.get(entry.getKey());
            if (session == null || session.stage != SessionStage.ROUND_READY) {
                continue;
            }
            long ms = entry.getValue() - now;
            int seconds = ms <= 0L ? 0 : (int) ((ms + 999L) / 1000L);
            Integer last = countdownPushed.get(entry.getKey());
            if (last != null && last == seconds) {
                continue;
            }
            countdownPushed.put(entry.getKey(), seconds);
            broadcastHud(session, true);
        }
    }

    /**
     * Starts any round whose intermission has elapsed.
     *
     * <p>Runs from {@code tick()}. A boundary with fewer than two sides present is held open for
     * {@link #RECONNECT_HOLD_MS} and then cut, rather than started short-handed or thrown out of
     * the tick loop.
     */
    private void startDueRounds() {
        if (roundReadyAt.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, Long> e : roundReadyAt.entrySet()) {
            if (e.getValue() <= now) {
                due.add(e.getKey());
            }
        }
        for (String id : due) {
            roundReadyAt.remove(id);
            DuelSession session = sessions.get(id);
            if (session == null || session.stage != SessionStage.ROUND_READY) {
                continue;
            }
            int before = session.round;
            if (anyDead(session)) {
                // Starting on a corpse revives the entity server-side while the client stays on its
                // death screen. The sweep replaces the entity within the death beat; wait for it.
                roundReadyAt.put(id, System.currentTimeMillis() + CORPSE_IDLE_MS);
                continue;
            }
            int present = presentSides(session);
            if (present < 2) {
                // Not enough people to fight. Hold the boundary open for a reconnect rather than
                // ending the series on the first dropped packet: this is where one can come back.
                Long since = absentSince.get(id);
                if (since == null) {
                    absentSince.put(id, now);
                    journal(session).append("ROUND_HELD", "reason", "sides_present", present);
                    broadcastParticipants(session, PvLang.t("pvpocket.msg.round.holding",
                            RECONNECT_HOLD_MS / 1000L));
                    roundReadyAt.put(id, now + 1000L);
                    continue;
                }
                if (now - since < RECONNECT_HOLD_MS) {
                    roundReadyAt.put(id, now + 1000L);
                    continue;
                }
                absentSince.remove(id);
                session.seriesEndReason = "CUT";
                journal(session).append("SERIES_CUT", "reason", "no_sides_present",
                        "waitedMs", now - since);
                finishSeries(session, leaderTeam(session, absentTeam(session)));
                continue;
            }
            absentSince.remove(id);
            try {
                startRound(session);
            } catch (RuntimeException e) {
                // startRound bumps the round counter before it can fail; a cut counts no round.
                session.round = before;
                if (session.participants.stream()
                        .anyMatch(p -> !p.surrendered && !p.abandoned && online(p) == null)) {
                    // A participant logged off during the intermission; that ends the series.
                    session.seriesEndReason = "CUT";
                    journal(session).append("SERIES_CUT", "reason", "participant_offline");
                    finishSeries(session, leaderTeam(session, absentTeam(session)));
                } else {
                    PvPocket.LOGGER.error("Failed to start round {} of {}", before + 1, session.id, e);
                    abort(session, "ROUND_START_FAILED");
                }
            }
        }
    }

    /**
     * Applies any deferred restores whose player is now online.
     *
     * <p>The entry is removed before the attempt, so a snapshot that cannot be applied produces one
     * error rather than one per tick.
     */
    private void flushPendingRestores() {
        if (pendingRestore.isEmpty()) {
            return;
        }
        List<UUID> ready = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : pendingRestore.entrySet()) {
            // Not just "in the player list": a player mid-respawn is listed but already discarded.
            ServerPlayer candidate = server.getPlayerList().getPlayer(entry.getKey());
            if (candidate != null && !candidate.isRemoved() && candidate.isAlive()) {
                ready.add(entry.getKey());
            }
        }
        for (UUID uuid : ready) {
            String sessionId = pendingRestore.remove(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || sessionId == null) {
                continue;
            }
            DuelSession session = sessions.get(sessionId);
            if (session == null) {
                PvPocket.LOGGER.error("Pending restore references unknown session {}", sessionId);
                continue;
            }
            Participant p = session.participant(uuid.toString());
            if (p == null) {
                continue;
            }
            if (!restoreParticipant(session, p)) {
                player.displayClientMessage(PvLang.t("pvpocket.msg.restore.failed", sessionId)
                        .withStyle(ChatFormatting.RED), false);
                continue;
            }
            // Two causes, with two different messages: voided by a crash, or left early.
            MutableComponent note = PvLang.t(session.voidReason == null
                    ? "pvpocket.msg.restore.done"
                    : "pvpocket.msg.restore.done_voided");
            player.displayClientMessage(note.withStyle(ChatFormatting.YELLOW), false);
        }
    }

    /**
     * Replaces the entity of a participant whose body outlived the death beat.
     *
     * <p>Vanilla swaps a dead player for a fresh entity only when the client asks for a respawn.
     * The client asks from the death screen, which is suppressed during a match, and a death
     * outside a live round is handled by no hand-off at all. The body then stands where it fell:
     * {@code ArenaManager.purge} skips players, and reviving the same entity server-side at the
     * next round start cannot clear the client's death state.
     *
     * <p>The replacement runs vanilla's own respawn, so the client receives the same packet a
     * player's own request would have produced, and {@link #onRespawn} takes over from there.
     */
    private void tickCorpses() {
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            String sessionId = sessionByPlayer.get(uuid);
            DuelSession session = sessionId == null ? null : sessions.get(sessionId);
            if (session == null || player.isAlive()) {
                corpseSince.remove(uuid);
                continue;
            }
            Long since = corpseSince.get(uuid);
            if (since == null) {
                corpseSince.put(uuid, now);
                continue;
            }
            // The beat belongs to a live round; anywhere else the body can go at once.
            long wait = session.stage == SessionStage.ROUND_ACTIVE ? DEATH_BEAT_MS : CORPSE_IDLE_MS;
            if (now - since < wait) {
                continue;
            }
            corpseSince.remove(uuid);
            journal(session).append("CORPSE_CLEARED", "player", player.getName().getString(),
                    "stage", session.stage.name(), "alive", survivorsOf(session));
            respawnNow(player);
        }
        // A player who left keeps no entry, or a rejoin would inherit a stale deadline.
        corpseSince.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
    }

    /**
     * Replaces a dead participant's entity the way a client respawn request does.
     *
     * @return the new entity, which is a different object from {@code player}; the argument itself
     *         when it is not dead
     */
    private ServerPlayer respawnNow(ServerPlayer player) {
        if (player.isAlive()) {
            return player;
        }
        ServerPlayer next = ((PlayerManagerInvoker) server.getPlayerList())
                .pvpocket$respawn(player, false, Entity.RemovalReason.KILLED);
        // The respawn hook normally puts the borrowed point back. Repeating it here is idempotent
        // and covers a loader whose hook skips a server-initiated respawn.
        restoreRespawnPoint(next);
        return next;
    }

    /** True while any participant of this session is a corpse. */
    private boolean anyDead(DuelSession session) {
        for (Participant p : session.participants) {
            if (p.surrendered || p.abandoned) {
                continue;
            }
            ServerPlayer player = online(p);
            if (player != null && !player.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sides that have somebody standing by for the next round.
     *
     * <p>Online and not away: a player sitting at the reconnect prompt is still deciding, so the
     * round does not count them until they answer.
     */
    private int presentSides(DuelSession session) {
        int sides = 0;
        for (int team : session.contestingTeams()) {
            for (Participant p : session.contestingMembers(team)) {
                if (p.goneSince == 0 && online(p) != null) {
                    sides++;
                    break;
                }
            }
        }
        return sides;
    }

    public void onDisconnect(ServerPlayer player) {
        // Before the participant check: a spectator is not a participant. Restoring the game mode
        // here makes the playerdata written during this disconnect record survival.
        spectators.onDisconnect(player);
        // The saved home is guaranteed current here, before the playerdata is written.
        homes.remember(player);
        homes.flush();
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            // Deferred, so the session is not mutated while the disconnect unwinds.
            DuelSession open = findOpenSession(player.getUUID());
            if (open != null && open.stage == SessionStage.NEGOTIATING) {
                String uuid = player.getStringUUID();
                deferred.add(() -> dropNegotiator(open, uuid));
            }
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage.isTerminal()) {
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null || me.surrendered || me.abandoned) {
            return;
        }
        String uuid = player.getStringUUID();
        long now = System.currentTimeMillis();
        // Deferred, so the session is not mutated while the disconnect unwinds.
        deferred.add(() -> {
            DuelSession live = sessions.get(sessionId);
            if (live == null || live.stage.isTerminal()) {
                return;
            }
            Participant p = live.participant(uuid);
            if (p == null || p.surrendered || p.abandoned) {
                return;
            }
            // Recorded for every stage, not only the live round: somebody who drops between
            // rounds is still away when the next one starts.
            p.goneSince = now;
            journal(live).append("PLAYER_AWAY", "player", p.name, "stage", live.stage.name());
            if (live.stage == SessionStage.ROUND_ACTIVE) {
                // The body is gone, so nobody can kill it and the round would only end on the
                // clock. Elimination is per round, so the next round can still have them back.
                eliminate(live, p, "DISCONNECT");
            } else {
                persist(live);
            }
        });
    }

    /**
     * Vetoes damage between team-mates when {@code b.friendlyFire} is off.
     *
     * <p>Only the direct attacker is checked: a teammate's arrow or TNT counts, but damage from a
     * teammate's fire does not, which is the line vanilla draws for its own team checks.
     *
     * <p>Scoped to {@code ROUND_ACTIVE}, since nobody can be hurt in the other stages anyway.
     *
     * @return false when the damage must be cancelled
     */
    public boolean allowDamage(ServerPlayer victim, DamageSource source) {
        String sessionId = sessionByPlayer.get(victim.getUUID());
        if (sessionId == null) {
            return true;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            return true;
        }
        if (!allowEnvironmentDamage(session, source)) {
            return false;
        }
        if (!(source.getEntity() instanceof ServerPlayer attacker)
                || attacker == victim) {
            return true;
        }
        if (session.rulebook().friendlyFire()) {
            return true;
        }
        Participant mine = session.participant(victim.getStringUUID());
        Participant theirs = session.participant(attacker.getStringUUID());
        if (mine == null || theirs == null || mine.teamIndex() != theirs.teamIndex()) {
            return true;
        }
        // Journalled, so a blocked hit is distinguishable from a lag spike.
        journal(session).append("FRIENDLY_FIRE_BLOCKED",
                "attacker", theirs.name, "victim", mine.name, "team", mine.teamIndex());
        return false;
    }

    /**
     * The hazards the rule set can switch off: falling, lava, the void, and starvation.
     *
     * <p>Not journalled: with the hazard off this path runs every few ticks and would bury the rest
     * of the journal.
     */
    private boolean allowEnvironmentDamage(DuelSession session, DamageSource source) {
        Rulebook book = session.rulebook();
        if (source.is(DamageTypeTags.IS_FALL)) {
            return book.fallDamage();
        }
        if (source.is(DamageTypes.LAVA)) {
            return book.lavaHazard();
        }
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return book.voidHazard();
        }
        if (source.is(DamageTypes.STARVE)) {
            return book.hunger();
        }
        return true;
    }

    /**
     * Is this player's hunger allowed to drain? Asked from {@code PlayerEntity#addExhaustion}.
     *
     * <p>Static and null-safe, since it is called from a mixin that also runs on the client.
     */
    public static boolean hungerEnabled(UUID uuid) {
        DuelSession session = activeSessionOf(uuid);
        return session == null || session.rulebook().hunger();
    }

    /** Same, for {@code c.naturalRegen}. Asked from {@code PlayerEntity#canFoodHeal}. */
    public static boolean naturalRegenAllowed(UUID uuid) {
        DuelSession session = activeSessionOf(uuid);
        return session == null || session.rulebook().naturalRegen();
    }

    /** Answers {@code d.allowPickup}, asked from {@code ItemEntity#onPlayerCollision}. */
    public static boolean pickupAllowed(UUID uuid) {
        DuelSession session = activeSessionOf(uuid);
        return session == null || session.rulebook().allowPickup();
    }

    /** Answers {@code d.allowCrafting}, asked from {@code Slot#canTakeItems} on a result slot. */
    public static boolean craftingAllowed(UUID uuid) {
        DuelSession session = activeSessionOf(uuid);
        return session == null || session.rulebook().allowCrafting();
    }

    /**
     * Sentinel returned by {@link #deathBurstTier(ServerPlayer)} when the player is not duelling.
     */
    public static final int NO_DUEL = -2;

    /**
     * Decides what a death should do, asked from the death-loot mixin in one lookup.
     *
     * <p>{@link #NO_DUEL} keeps vanilla drops for anyone outside a live round. Inside a duel the
     * drops are always cancelled; the return value is then a {@link DeathFx} tier, which may be
     * {@link DeathFx#OFF} when the particle rule is disabled. Callers cancel the drop for every
     * value except {@link #NO_DUEL} and pass the tier straight to {@link DeathFx#spawn}.
     */
    public static int deathBurstTier(ServerPlayer player) {
        DuelSession session = activeSessionOf(player.getUUID());
        if (session == null) {
            return NO_DUEL;
        }
        Rulebook book = session.rulebook();
        if (!book.deathParticles()) {
            return DeathFx.OFF;
        }
        return book.deathParticleIntensity();
    }

    /** The session whose round this player is currently fighting, or null. */
    private static DuelSession activeSessionOf(UUID uuid) {
        SessionManager manager = instance;
        if (manager == null) {
            return null;
        }
        String id = manager.sessionByPlayer.get(uuid);
        if (id == null) {
            return null;
        }
        DuelSession session = manager.sessions.get(id);
        return session != null && session.stage == SessionStage.ROUND_ACTIVE ? session : null;
    }

    /** Minimum gap between two "you cannot use that" hints, in ticks. */
    private static final int USE_WARN_COOLDOWN = 20;

    /**
     * Last tick each player was told why their click did nothing. Server thread only.
     */
    private static final Map<UUID, Integer> useWarnAt = new HashMap<>();

    /**
     * Tells a player why their click did nothing.
     *
     * <p>Only the server speaks, and the message is throttled because a held button retries every
     * few ticks.
     */
    public static void reportUseDenial(Player player, UsePolicy.Denial denial) {
        if (denial == UsePolicy.Denial.NONE || !(player instanceof ServerPlayer server)) {
            return;
        }
        MutableComponent message = switch (denial) {
            case PLACE -> PvLang.t("pvpocket.msg.use.place");
            case CONSUMABLE -> PvLang.t("pvpocket.msg.use.consumable");
            case PEARL -> PvLang.t("pvpocket.msg.use.pearl");
            case SHIELD -> PvLang.t("pvpocket.msg.use.shield");
            case NONE -> null;
        };
        warnThrottled(server, message);
    }

    /**
     * {@code d.allowCrafting} refused a take from a result slot.
     *
     * <p>Worth a sentence: a crafting result that will not come out looks like a broken slot.
     */
    public static void reportCraftingDenial(Player player) {
        if (player instanceof ServerPlayer server) {
            warnThrottled(server, PvLang.t("pvpocket.msg.use.craft"));
        }
    }

    /**
     * A loadout item that refuses to leave the inventory.
     *
     * <p>Throttled with the other refusals because the drop key repeats while held.
     */
    public static void reportDropDenial(Player player) {
        if (player instanceof ServerPlayer server) {
            warnThrottled(server, PvLang.t("pvpocket.msg.loadout.no_drop"));
        }
    }

    /**
     * {@code g.chatScope}: who gets to read a line of chat sent during a round.
     *
     * <p>Registered on {@code ServerMessageEvents.ALLOW_CHAT_MESSAGE}, which fires at the head of
     * {@code PlayerManager#broadcast}; returning false cancels vanilla's fan-out and leaves
     * delivery here. The line still goes through {@code applyChatDecoration} for the speaker name.
     *
     * <p>Scoped to {@code ROUND_ACTIVE}, like the damage veto.
     *
     * @return false when the caller must not broadcast the line
     */
    public static boolean allowChat(ServerPlayer sender, Component decorated) {
        SessionManager manager = instance;
        if (manager == null) {
            return true;
        }
        DuelSession session = activeSessionOf(sender.getUUID());
        if (session == null) {
            return true;
        }
        Rulebook.ChatScope scope = session.rulebook().chatScope();
        if (scope == Rulebook.ChatScope.SERVER) {
            return true;
        }
        if (scope == Rulebook.ChatScope.MUTED) {
            warnThrottled(sender, PvLang.t("pvpocket.msg.chat.muted"));
            return false;
        }
        Participant mine = session.participant(sender.getStringUUID());
        if (mine == null) {
            // In the session but not on a side: a spectator. The line goes back to them alone.
            sender.displayClientMessage(decorated, false);
            return false;
        }
        for (Participant p : session.participants) {
            if (p.teamIndex() != mine.teamIndex()) {
                continue;
            }
            ServerPlayer target = manager.online(p);
            if (target != null) {
                target.displayClientMessage(decorated, false);
            }
        }
        return false;
    }

    /**
     * One grey action-bar hint per {@link #USE_WARN_COOLDOWN} ticks, per player.
     *
     * <p>Every caller sits on a path the client retries while a button is held.
     */
    private static void warnThrottled(ServerPlayer player, MutableComponent message) {
        if (message == null) {
            return;
        }
        MinecraftServer mc = PvCompat.server(player);
        if (mc == null) {
            return;
        }
        int now = mc.getTickCount();
        Integer last = useWarnAt.get(player.getUUID());
        if (last != null && now - last < USE_WARN_COOLDOWN) {
            return;
        }
        useWarnAt.put(player.getUUID(), now);
        player.displayClientMessage(message.withStyle(ChatFormatting.GRAY), true);
    }

    /**
     * Publishes this round's item-use gate to both sides.
     *
     * <p>Assembled here so {@code Rulebook} stays free of the combat layer's bit layout.
     */
    private static void applyUseGate(DuelSession session, ServerPlayer player) {
        Rulebook book = session.rulebook();
        int gate = 0;
        if (book.allowPlace()) {
            gate |= UsePolicy.ALLOW_PLACE;
        }
        if (book.allowConsumables()) {
            gate |= UsePolicy.ALLOW_CONSUMABLES;
        }
        if (book.allowEnderPearl()) {
            gate |= UsePolicy.ALLOW_ENDER_PEARL;
        }
        CombatState.setUseGate(player.getUUID(), gate);
    }

    /**
     * The round clock for {@code a.roundLimit}.
     *
     * <p>Starts when the round starts, not when the preparation countdown does. A limit above zero
     * always ends the round; there is no separate switch to disable a timeout win.
     *
     * @return true when the round ended here, so the caller must not keep judging it
     */
    private boolean tickRoundLimit(DuelSession session) {
        Rulebook book = session.rulebook();
        int limit = book.roundLimitSeconds();
        if (limit <= 0) {
            return false;
        }
        Long started = roundStartedAt.get(session.id);
        if (started == null || System.currentTimeMillis() - started < limit * 1000L) {
            return false;
        }

        Rulebook.TimeoutVerdict verdict = book.timeoutVerdict();
        int winner = verdict == Rulebook.TimeoutVerdict.HIGHEST_HEALTH ? healthLeader(session) : -1;
        boolean byHealth = verdict == Rulebook.TimeoutVerdict.HIGHEST_HEALTH && winner >= 0;
        String key = switch (verdict) {
            case HIGHEST_HEALTH -> byHealth
                    ? "pvpocket.msg.round.timeout.health"
                    : "pvpocket.msg.round.timeout.draw";
            case DRAW -> "pvpocket.msg.round.timeout.draw";
            case DOUBLE_LOSS -> "pvpocket.msg.round.timeout.double";
        };
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player == null) {
                continue;
            }
            // Built per player: a Text mutated for one recipient is the same object the next gets.
            MutableComponent line = byHealth ? PvLang.t(key, teamName(session, winner)) : PvLang.t(key);
            player.displayClientMessage(line.withStyle(ChatFormatting.YELLOW), false);
        }

        String reason = switch (verdict) {
            case HIGHEST_HEALTH -> byHealth ? "TIMEOUT" : "TIMEOUT_DRAW";
            case DRAW -> "TIMEOUT_DRAW";
            case DOUBLE_LOSS -> "TIMEOUT_DOUBLE";
        };
        // Deferred, like every other settle reached from this loop: settling runs the intermission
        // and can finish the series while the caller is iterating the sessions.
        deferred.add(() -> settle(session, reason, winner));
        return true;
    }

    /**
     * The side with the most health left, or {@code -1} when the lead is shared.
     *
     * <p>Health of the whole side, and eliminated players count as zero.
     */
    private int healthLeader(DuelSession session) {
        Map<Integer, Double> totals = new LinkedHashMap<>();
        for (Participant p : session.participants) {
            // A side that left is out of the verdict too: its players are standing in survival with
            // full health, which would read as a lead.
            if (p.rejected || p.surrendered || p.abandoned) {
                continue;
            }
            ServerPlayer player = online(p);
            double hp = player != null && player.isAlive() && !p.eliminated ? player.getHealth() : 0D;
            totals.merge(p.teamIndex(), hp, Double::sum);
        }
        int best = -1;
        double bestHp = -1D;
        boolean tie = false;
        for (Map.Entry<Integer, Double> entry : totals.entrySet()) {
            double hp = entry.getValue();
            if (hp > bestHp + 1e-6D) {
                bestHp = hp;
                best = entry.getKey();
                tie = false;
            } else if (Math.abs(hp - bestHp) <= 1e-6D) {
                tie = true;
            }
        }
        return tie ? -1 : best;
    }

    public void onDeath(ServerPlayer player) {
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            // A death outside a live round has no round bookkeeping to do, and the corpse it leaves
            // is the sweep's problem rather than this method's.
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null || me.eliminated) {
            return;
        }
        // Set before the client can ask for its respawn, which the suppressed death screen sends a
        // tick later: the death and everything after it stay inside the arena.
        respawnOnPlot(session, me, player);
        // Marked here and settled below, so a crash between the two still leaves a journalled
        // outcome.
        me.eliminated = true;
        recordDeath(session, me, player);
        // A forfeit brings its own reason, so the history says "out of bounds" rather than "kill".
        String reason = forfeitReasons.getOrDefault(player.getUUID(), "DEATH");
        forfeitReasons.remove(player.getUUID());
        // A round that is already decided keeps its decision. The player who just fell may be the
        // side that won it, and overwriting the record would leave the round with no winner.
        boolean decided = session.pendingWinnerTeam >= 0;
        int winner = decided ? session.pendingWinnerTeam : lastTeamStanding(session);
        // Nobody left standing is a mutual kill, not an ongoing round. Without this the round stays
        // open with every side out: no settle, no next round, and a body in the arena.
        boolean wiped = !decided && winner < 0 && session.survivingTeams().isEmpty();
        if (!decided) {
            session.pendingWinner = me.uuid;
            session.pendingReason = wiped ? "MUTUAL" : reason;
            session.pendingWinnerTeam = winner;
        }
        journal(session).append("ROUND_DECIDED", "reason", wiped ? "MUTUAL" : reason,
                "player", me.name,
                "winnerTeam", winner,
                "alreadyDecided", decided,
                "alive", survivorsOf(session));
        persist(session);

        // The head greys out on this packet, before the beat finishes. The mode switch stays with
        // the respawn hand-off: changing mode here ends the fall beat and hides the send-off.
        if (winner < 0) {
            broadcastHud(session, true);
        }

        if (wiped) {
            afterDeathBeat(() -> settle(session, "MUTUAL", -1));
            return;
        }
        // A mid-series round settles after the death beat, without waiting for the respawn. Only
        // the deciding round waits, because finishSeries restores the snapshot onto a live entity.
        if (winner >= 0 && !decidesSeries(session, winner)) {
            String settled = decided && session.pendingReason != null ? session.pendingReason : reason;
            afterDeathBeat(() -> settle(session, settled, winner));
        }
    }

    /**
     * Whether a win by {@code winner} would end the series.
     *
     * <p>Asked before the win is recorded, so the count is off by one.
     */
    private static boolean decidesSeries(DuelSession session, int winner) {
        return session.wins(winner) + 1 >= session.winsNeeded
                || session.round >= session.maxRounds;
    }

    /**
     * Settles a round whose decision was deferred to the respawn. Everything is looked up by uuid,
     * since the entity is replaced.
     *
     * <p>If their side still has somebody standing, the round goes on and the dead player is put
     * back on the plot as a spectator.
     */
    public void onRespawn(ServerPlayer player) {
        // A death inside the plot borrows the player's respawn point; it goes back first, before
        // anything else can read the arena as where they belong.
        restoreRespawnPoint(player);
        String sessionId = sessionByPlayer.get(player.getUUID());
        DuelSession session = sessionId == null ? null : sessions.get(sessionId);
        Participant quitter = session == null ? null : session.participant(player.getStringUUID());
        if (quitter != null && quitter.surrendered) {
            // A side that left waits for its queued restore, not for the round flow: parking or
            // spectating would put it back into a match it is out of.
            return;
        }
        // A respawn replaces the server-side entity, so re-send the HUD state; nothing else will
        // push it before the next boundary.
        pushHudTo(player);
        if (session == null) {
            return;
        }
        if (session.stage == SessionStage.ROUND_READY) {
            // A participant who respawned during the intermission is parked on the plot.
            Participant p = session.participant(player.getStringUUID());
            ArenaRegion region = ArenaManager.region(session.id);
            if (p != null) {
                deferred.add(() -> parkForIntermission(session, player, p, region));
            }
            return;
        }
        if (session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        if (session.pendingWinnerTeam >= 0) {
            int team = session.pendingWinnerTeam;
            String reason = session.pendingReason;
            // The client asks for its respawn the moment the death screen would have shown, and
            // the settle is the only thing that comes later: without this the player whose death
            // decided the round stands on the plot, alive and in survival, for the whole beat.
            // The verdict stays on the session — it is what the settle reads.
            Participant me = session.participant(player.getStringUUID());
            if (me != null && me.eliminated) {
                handToSpectator(session, me, player);
            }
            afterDeathBeat(() -> settle(session, reason, team));
            return;
        }
        // The death beat already played while they were down, and the respawn put them on the
        // plot, so the hand-off needs no second hold.
        deferred.add(() -> watchRestOfRound(session, player));
    }

    /**
     * Points a dying participant's respawn at their own pad.
     *
     * <p>Vanilla sends a dead player to the overworld, and the hand-off then pulls them back in:
     * a trip through another dimension for a death that belongs to the plot. A forced point on the
     * pad keeps the whole thing inside the arena. The point the player came in with is put back on
     * the respawn.
     */
    private void respawnOnPlot(DuelSession session, Participant p, ServerPlayer player) {
        ArenaRegion region = ArenaManager.region(session.id);
        if (region == null) {
            return;
        }
        ServerLevel world = ArenaManager.world(PvCompat.server(player));
        BlockPos pad = ArenaManager.spawnBlock(region, p.teamIndex());
        if (!ArenaManager.respawnable(world, pad)) {
            // No usable pad. Vanilla's overworld fallback beats a point inside a block.
            return;
        }
        // Kept once per death, since a second death before the respawn must not overwrite the
        // player's own point with the borrowed one.
        if (!respawnPoints.containsKey(player.getUUID())) {
            respawnPoints.put(player.getUUID(), PvCompat.respawnPoint(player));
        }
        PvCompat.setRespawnPoint(player, new PvCompat.RespawnPoint(world.dimension(), pad,
                region.spawnYaw(p.teamIndex()), 0.0F, true));
    }

    /** Puts back the respawn point a death took over, if one was taken. */
    private void restoreRespawnPoint(ServerPlayer player) {
        if (respawnPoints.containsKey(player.getUUID())) {
            PvCompat.setRespawnPoint(player, respawnPoints.remove(player.getUUID()));
        }
    }

    /**
     * Puts an eliminated participant back on the plot as a spectator for the rest of the round.
     *
     * <p>The round start puts them back into survival at their spawn.
     */
    private void watchRestOfRound(DuelSession session, ServerPlayer player) {
        if (session.stage != SessionStage.ROUND_ACTIVE || !player.isAlive()) {
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null || me.surrendered || !me.eliminated) {
            return;
        }
        if (!handToSpectator(session, me, player)) {
            return;
        }
        // The round is still live, so the "decided but unapplied" record is stale.
        session.pendingWinner = null;
        session.pendingReason = null;
        persist(session);
        player.displayClientMessage(PvLang.t("pvpocket.msg.round.eliminated")
                .withStyle(ChatFormatting.GRAY), false);
    }

    /**
     * Puts an eliminated participant on their spawn as a spectator.
     *
     * <p>Shared by the two ways a death can play out: while the round goes on, and for the death
     * that decided it. Either way the player is out, and a live body in survival is something
     * neither the round nor the settle can use. The bookkeeping stays with the caller, since one
     * of them has to keep the pending verdict for the settle and the other has to clear it.
     *
     * @return whether the player was handed over; false when there is no plot to put them on
     */
    private boolean handToSpectator(DuelSession session, Participant me, ServerPlayer player) {
        ArenaRegion region = ArenaManager.region(session.id);
        if (region == null) {
            return false;
        }
        Sandbox.changeGameMode(player, GameType.SPECTATOR);
        ArenaManager.teleportToSpawn(player, region, me.teamIndex(), me.member,
                Math.max(1, session.teamMembers(me.teamIndex()).size()));
        return true;
    }

    /**
     * Parks one player three blocks above their spawn for the intermission: spectator mode, able to
     * move but not to hit.
     *
     * <p>The terrain is restored block by block during the intermission, so a player standing on
     * the ground would fall as the blocks under them disappear.
     */
    private void parkForIntermission(DuelSession session, ServerPlayer player, Participant p,
                                     ArenaRegion region) {
        if (region == null || p.surrendered || p.abandoned) {
            return;
        }
        Sandbox.changeGameMode(player, GameType.SPECTATOR);
        int members = Math.max(1, session.teamMembers(p.teamIndex()).size());
        if (ArenaManager.teleportToSpawn(player, region, p.teamIndex(), p.member, members)) {
            player.teleportTo(player.getX(), player.getY() + 3.0D, player.getZ());
        }
    }

    // ------------------------------------------------------------------ tick

    /**
     * Keeps the pre-arena position record current.
     *
     * <p>Sampling every player every tick rather than hooking movement, so a player who logs in and
     * walks nowhere is still recorded.
     */
    private void tickHomes() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            homes.remember(player);
        }
        homes.flushIfDue();
    }

    /**
     * Holds a post-death reaction until the particle beat has elapsed.
     *
     * <p>Both callers check the round stage when they run, so a series that ended some other way
     * during the hold makes the queued action a no-op.
     *
     * @param body the reaction to run after the hold
     */
    private void afterDeathBeat(Runnable body) {
        delayedReactions.add(new DelayedReaction(
                System.currentTimeMillis() + DEATH_BEAT_MS, body));
    }

    public void tick() {
        if (!deferred.isEmpty()) {
            List<Runnable> copy = new ArrayList<>(deferred);
            deferred.clear();
            copy.forEach(Runnable::run);
        }
        if (!delayedReactions.isEmpty()) {
            long now = System.currentTimeMillis();
            List<Runnable> due = new ArrayList<>();
            delayedReactions.removeIf(reaction -> {
                if (reaction.dueAt > now) {
                    return false;
                }
                due.add(reaction.body);
                return true;
            });
            due.forEach(Runnable::run);
        }
        flushPendingRestores();
        // Before startDueRounds: a body still on its death screen blocks the round it would play.
        tickCorpses();
        startDueRounds();
        // After startDueRounds: a round that just started has dropped its roundReadyAt entry.
        tickIntermissionCountdown();
        tickRoundClock();
        tickPrepare();
        tickNegotiationWindows();
        // After the windows: a session that just left RESULT has nobody in the lobby to catch.
        tickLobby();
        tickSurrender();
        // Drops watchers of finished series and repairs cameras on replaced bodies.
        spectators.tick();
        // After the spectator pass, which can teleport a watcher out in this same tick.
        guard.tick();
        tickHomes();
        // Natural spawning is cancelled in the arena dimension; this catches spawn eggs, spawner
        // blocks and /summon.
        if (++sweepTimer >= SWEEP_INTERVAL) {
            sweepTimer = 0;
            int swept = ArenaManager.sweepMobs(server);
            if (swept > 0) {
                PvPocket.LOGGER.info("Swept {} stray mobs from leased arena plots", swept);
            }
        }
        for (DuelSession session : sessions.values()) {
            if (session.stage != SessionStage.ROUND_ACTIVE) {
                continue;
            }
            // The clock runs before the boundary pass and can end the round.
            if (tickRoundLimit(session)) {
                continue;
            }
            ArenaRegion region = ArenaManager.region(session.id);
            if (region == null) {
                continue;
            }
            for (Participant p : session.participants) {
                ServerPlayer player = online(p);
                if (player == null) {
                    continue;
                }
                String key = session.id + "/" + p.uuid;
                if (p.surrendered || p.abandoned) {
                    // Back in survival, standing in their own world by design: the plot's bounds
                    // are not theirs to keep, and the countdown would kill them a second time.
                    boundaryExits.remove(key);
                    continue;
                }
                if (p.eliminated) {
                    // Already out of the round, so the countdown is answered: no re-arming for a
                    // corpse or for a watcher who stands outside the plot by design.
                    boundaryExits.remove(key);
                    continue;
                }
                if (ArenaManager.isInside(player, region)) {
                    // First sighting on the plot settles this player.
                    settledIn.add(key);
                    // Coming back cancels the countdown, and the player is told so.
                    if (boundaryExits.remove(key) != null) {
                        journal(session).append("BOUNDARY_RETURNED", "player", p.name);
                        player.displayClientMessage(PvLang.t("pvpocket.msg.boundary.returned")
                                .withStyle(ChatFormatting.GREEN), false);
                        // Clears the big number instead of leaving a stale "1" on screen.
                        player.connection.send(new ClientboundClearTitlesPacket(false));
                    }
                    continue;
                }
                // Logged with coordinates: position separates a walk-out from a stuck teleport.
                // Snapping back covers the first sighting and the push-back rule.
                if (awaitingFirstEntry(session, key) || boundaryIsPushBack(session)) {
                    boundaryExits.remove(key);
                    ArenaManager.pullInside(player, region);
                    notifyPushBack(session, player, key, awaitingFirstEntry(session, key));
                    continue;
                }
                long now = System.currentTimeMillis();
                BoundaryExit exit = boundaryExits.get(key);
                if (exit == null) {
                    boundaryExits.put(key, new BoundaryExit(now, 5));
                    journal(session).append("BOUNDARY_EXIT",
                            "player", p.name,
                            "region", region.id,
                            "pos", String.format("%.2f,%.2f,%.2f",
                                    player.getX(), player.getY(), player.getZ()),
                            "world", PvCompat.dimensionId(player.level().dimension()).toString(),
                            "countdownMs", BOUNDARY_COUNTDOWN_MS);
                    player.displayClientMessage(PvLang.t("pvpocket.msg.boundary.left",
                                    BOUNDARY_COUNTDOWN_MS / 1000)
                            .withStyle(ChatFormatting.RED), false);
                    announceBoundary(player, 5);
                    continue;
                }
                long remaining = BOUNDARY_COUNTDOWN_MS - (now - exit.since());
                if (remaining <= 0) {
                    boundaryExits.remove(key);
                    journal(session).append("BOUNDARY_FORFEIT", "player", p.name,
                            "elapsedMs", now - exit.since());
                    player.connection.send(new ClientboundClearTitlesPacket(false));
                    // A forfeit kills, so the fall beat, the burst and the hand-off to spectator
                    // mode all run. Marking alone would leave the player standing there alive.
                    deferred.add(() -> forfeit(session, p, "OUT_OF_BOUNDS"));
                    break;
                }
                int second = (int) Math.ceil(remaining / 1000.0D);
                if (second != exit.announced()) {
                    boundaryExits.put(key, new BoundaryExit(exit.since(), second));
                    announceBoundary(player, second);
                }
            }
        }
    }

    /**
     * True while this player has never once been observed standing on their plot this round.
     *
     * <p>A client that has never loaded the arena dimension reports a position from before the
     * teleport, and judging that as "walked out" would lose the round during the loading screen.
     *
     * <p>{@link #SETTLE_DEADLINE_MS} is the escape hatch: after thirty seconds the pull-back rule
     * applies regardless.
     */
    private boolean awaitingFirstEntry(DuelSession session, String key) {
        if (settledIn.contains(key)) {
            return false;
        }
        long startedAt = roundStartedAt.getOrDefault(session.id, 0L);
        return startedAt != 0L && System.currentTimeMillis() - startedAt < SETTLE_DEADLINE_MS;
    }

    private static boolean boundaryIsPushBack(DuelSession session) {
        return DuelRules.decode(session.rules).get("e.boundary") == 0;
    }

    /**
     * Tells a player who has been snapped back why, at most once every
     * {@link #BOUNDARY_NOTICE_GAP_MS}.
     *
     * <p>Action bar rather than chat, since this state repeats. The journal uses the same gate.
     */
    private void notifyPushBack(DuelSession session, ServerPlayer player, String key, boolean settling) {
        long now = System.currentTimeMillis();
        Long last = boundaryNotices.get(key);
        if (last != null && now - last < BOUNDARY_NOTICE_GAP_MS) {
            return;
        }
        boundaryNotices.put(key, now);
        journal(session).append("BOUNDARY_PUSHED_BACK", "player", player.getName().getString(),
                "settling", settling, "rule", settling ? "pending" : "push_back");
        player.displayClientMessage(PvLang.t(settling
                        ? "pvpocket.msg.boundary.pushing_back"
                        : "pvpocket.msg.boundary.at_edge")
                .withStyle(ChatFormatting.YELLOW), true);
    }

    /**
     * Draws the boundary countdown as a single large number in the middle of the screen.
     *
     * <p>Zero fade-in, 22 ticks of stay and a 4 tick fade-out, so each number replaces the last.
     */
    private static void announceBoundary(ServerPlayer player, int second) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 22, 4));
        // Not bold: a bold glyph is a second draw shifted one pixel, which smears at title scale.
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal(String.valueOf(second)).withStyle(ChatFormatting.RED)));
    }

    /**
     * Drops every per-player boundary state belonging to a session. Called at every round boundary.
     *
     * <p>{@code settledIn} goes too, since each round teleports everyone to a spawn pad again.
     */
    private void clearBoundaryExits(DuelSession session) {
        String prefix = session.id + "/";
        boundaryExits.keySet().removeIf(key -> key.startsWith(prefix));
        settledIn.removeIf(key -> key.startsWith(prefix));
        boundaryNotices.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ------------------------------------------------------------------ commands

    // ------------------------------------------------------------------ negotiation

    /**
     * Opens the roster panel: a draft session whose only member is the proposer.
     *
     * <p>The session exists from the moment the panel opens, in {@link SessionStage#DRAFT}, so the
     * proposer can close the panel and come back with {@code /pvpocket review}. Nothing is
     * snapshotted and no rule is in force yet.
     *
     * <p>The roster built in the panel is the participant list, so the sides are explicit rather
     * than dealt from an invite order.
     */
    public void openRoster(ServerPlayer proposer) {
        if (sessionByPlayer.containsKey(proposer.getUUID())) {
            fail(proposer, "pvpocket.msg.duel.self_busy");
            return;
        }
        if (proposer.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            fail(proposer, "pvpocket.msg.duel.spectator");
            return;
        }
        if (!ArenaManager.isReady(server)) {
            fail(proposer, "pvpocket.msg.duel.arena_not_ready");
            return;
        }
        DuelSession open = findOpenSession(proposer.getUUID());
        if (open != null) {
            if (open.stage != SessionStage.DRAFT) {
                // Somebody else is waiting on an answer, so the invite is not theirs to drop.
                fail(proposer, "pvpocket.msg.duel.own_pending");
                return;
            }
            // A draft has no other party and holds the drawn sides, so reopening it is what "start
            // a duel" means while a draft is in progress.
            openPanel(proposer, open, RulesOpenPayload.ROLE_DRAFT);
            return;
        }

        DuelSession session = new DuelSession();
        session.id = UUID.randomUUID().toString();
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        Participant me = new Participant(proposer.getStringUUID(),
                proposer.getName().getString(), 0, 0);
        // The proposer's own book becomes the match's set; everyone invited later inherits it.
        seedLoadout(me, loadouts.get(proposer.getUUID()));
        session.participants.add(me);
        session.teamCount = MIN_TEAMS;

        DuelRules rules = DuelRules.defaults();
        applyRulesToSession(session, rules);
        session.rulesRevision = 0;
        session.rulesAuthor = proposer.getStringUUID();
        session.proposerUuid = proposer.getStringUUID();
        // Rows are numbered before anything can be branched off them; see LoadoutEntry#ref.
        mainLoadout(session);

        sessions.put(session.id, session);
        journal(session).append("SESSION_DRAFTED", "proposer", me.name);
        persist(session);
        openPanel(proposer, session, RulesOpenPayload.ROLE_DRAFT);
    }

    /**
     * Starts a new series from a finished one: same people, same rules, same loadout.
     *
     * <p>Not a restart of the old session. The archived one keeps its score and its history; this
     * creates a fresh draft and sends it through the ordinary invite path, so everybody still gets
     * the panel and still answers. What it saves is the part nobody wants to repeat: drawing the
     * cards and setting every rule again.
     *
     * <p>Whoever is gone is dropped rather than refused. A rematch exists to start now, and an
     * invite that waits for somebody who logged off is not that.
     */
    public void rematch(ServerPlayer player, String sessionId) {
        if (sessionByPlayer.containsKey(player.getUUID())) {
            fail(player, "pvpocket.msg.duel.self_busy");
            return;
        }
        // One open session at a time: the invite path looks the session up by player, so a second
        // draft would be created and then never found again.
        if (findOpenSession(player.getUUID()) != null) {
            fail(player, "pvpocket.msg.duel.own_pending");
            return;
        }
        DuelSession old = sessionId == null || sessionId.isEmpty()
                ? lastFinishedFor(player.getUUID())
                : sessions.get(sessionId);
        if (old == null || old.stage != SessionStage.ARCHIVED
                || old.participant(player.getStringUUID()) == null) {
            fail(player, "pvpocket.msg.rematch.none");
            return;
        }
        if (System.currentTimeMillis() - old.updatedAt > REMATCH_WINDOW_MS) {
            fail(player, "pvpocket.msg.rematch.expired");
            return;
        }
        DuelRules rules = DuelRules.decode(old.rules);
        DuelSession fresh = createDraftFrom(player, old, rules);
        String roster = onlineRoster(old, player.getUUID());
        List<List<String>> sides = RosterShape.decode(roster);
        if (sides == null || sides.size() < MIN_TEAMS) {
            // Not enough people left for two sides. Keep the terms and let the roster be redrawn,
            // rather than throwing the rules away with the players.
            journal(fresh).append("REMATCH_SHORT", "from", old.id,
                    "sides", sides == null ? 0 : sides.size());
            openPanel(player, fresh, RulesOpenPayload.ROLE_DRAFT);
            player.displayClientMessage(PvLang.t("pvpocket.msg.rematch.short")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }
        journal(fresh).append("REMATCH_STARTED", "from", old.id, "roster", roster);
        // The panel's own send path: same validation, same messages, same stage change.
        onRulesSubmit(player, RulesSubmitPayload.ACTION_PROPOSE, rules.encode(), roster);
        if (fresh.stage == SessionStage.DRAFT) {
            // The invite was refused, most likely because somebody became busy in between.
            openPanel(player, fresh, RulesOpenPayload.ROLE_DRAFT);
        }
    }

    /**
     * The newest archived series this player was in, for the command without an id.
     *
     * <p>Scanned rather than remembered: sessions are never dropped, and the command has to work
     * after a restart, when no in-memory pointer survives.
     */
    private DuelSession lastFinishedFor(UUID uuid) {
        String id = uuid.toString();
        DuelSession best = null;
        for (DuelSession session : sessions.values()) {
            if (session.stage != SessionStage.ARCHIVED || session.participant(id) == null) {
                continue;
            }
            if (best == null || session.updatedAt > best.updatedAt) {
                best = session;
            }
        }
        return best;
    }

    /**
     * A fresh draft carrying a finished series' terms and the exact set it fought with.
     *
     * <p>The loadout is seeded onto the proposer before the roster is submitted, because
     * {@code submitRoster} seeds every newcomer from {@code sharedLoadout}, which is the first
     * participant holding a set. That is how the same items come back instead of the proposer's
     * personal book, which may have been edited since.
     */
    private DuelSession createDraftFrom(ServerPlayer proposer, DuelSession from, DuelRules rules) {
        DuelSession session = new DuelSession();
        session.id = UUID.randomUUID().toString();
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        Participant me = new Participant(proposer.getStringUUID(), proposer.getName().getString(),
                0, 0);
        Loadout previous = sharedLoadout(from);
        me.loadout = previous.encode();
        me.loadoutDigest = previous.digest();
        session.participants.add(me);
        session.teamCount = MIN_TEAMS;
        applyRulesToSession(session, rules);
        session.rulesRevision = 0;
        session.rulesAuthor = proposer.getStringUUID();
        session.proposerUuid = proposer.getStringUUID();
        // Rows are numbered before anything can be branched off them; see LoadoutEntry#ref.
        mainLoadout(session);
        sessions.put(session.id, session);
        journal(session).append("SESSION_DRAFTED", "proposer", me.name, "rematchOf", from.id);
        persist(session);
        return session;
    }

    /**
     * The finished series' arrangement with everybody who is gone dropped.
     *
     * <p>Busy counts as gone: somebody already in another duel would be refused by
     * {@code submitRoster}, and that refusal would take the whole rematch down with it.
     */
    private String onlineRoster(DuelSession from, UUID proposer) {
        List<List<String>> sides = new ArrayList<>();
        for (int team = 0; team < Math.max(1, from.teamCount); team++) {
            List<Participant> members = new ArrayList<>(from.activeMembers(team));
            members.sort(Comparator.comparingInt(p -> p.member));
            List<String> side = new ArrayList<>();
            for (Participant p : members) {
                UUID uuid = UUID.fromString(p.uuid);
                boolean here = server.getPlayerList().getPlayer(uuid) != null;
                boolean free = uuid.equals(proposer)
                        || (sessionByPlayer.get(uuid) == null && findOpenSession(uuid) == null);
                if (here && free) {
                    side.add(p.uuid);
                }
            }
            if (!side.isEmpty()) {
                sides.add(side);
            }
        }
        return sides.isEmpty() ? "" : RosterShape.encode(sides);
    }

    /**
     * Turns the roster the proposer assembled into the participant list, then invites everybody.
     *
     * <p>Encoding is {@code "uuid,uuid;uuid"}: {@code ,} between team-mates, {@code ;} between
     * sides. An empty side is dropped, so team indices stay contiguous. Validated before it is
     * applied.
     *
     * @return true when the roster was accepted and the session moved to negotiation
     */
    private boolean submitRoster(DuelSession session, ServerPlayer proposer, String encoded,
                                 DuelRules rules) {
        List<List<UUID>> sides = new ArrayList<>();
        for (String sideText : encoded.split(";", -1)) {
            List<UUID> side = new ArrayList<>();
            for (String token : sideText.split(",")) {
                if (token.isBlank()) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(token.trim());
                    if (!side.contains(uuid)) {
                        side.add(uuid);
                    }
                } catch (IllegalArgumentException e) {
                    fail(proposer, "pvpocket.msg.roster.bad_packet");
                    return false;
                }
            }
            sides.add(side);
        }

        if (sides.size() < MIN_TEAMS || sides.size() > MAX_TEAMS) {
            fail(proposer, "pvpocket.msg.roster.bad_team_count", MIN_TEAMS, MAX_TEAMS);
            return false;
        }
        int teamSize = rules.get("b.teamSize");
        int total = 0;
        Set<UUID> seen = new LinkedHashSet<>();
        for (List<UUID> side : sides) {
            if (side.size() > teamSize) {
                fail(proposer, "pvpocket.msg.rules.team_too_big", side.size(), teamSize);
                return false;
            }
            for (UUID uuid : side) {
                if (!seen.add(uuid)) {
                    fail(proposer, "pvpocket.msg.roster.duplicate",
                            nameOf(uuid));
                    return false;
                }
            }
            total += side.size();
        }
        int cap = Math.min(MAX_PARTICIPANTS, rules.get("b.maxPlayers"));
        if (total > cap) {
            fail(proposer, "pvpocket.msg.rules.too_many_players", total, cap);
            return false;
        }
        if (total < 2 || sides.stream().filter(s -> !s.isEmpty()).count() < 2) {
            fail(proposer, "pvpocket.msg.roster.not_enough", total);
            return false;
        }
        if (!seen.contains(proposer.getUUID())) {
            fail(proposer, "pvpocket.msg.roster.self_missing");
            return false;
        }

        // An empty card is dropped here, so "team index" and "playing side" stay the same number
        // everywhere downstream.
        List<List<UUID>> playing = new ArrayList<>();
        for (List<UUID> side : sides) {
            if (!side.isEmpty()) {
                playing.add(side);
            }
        }

        // Presence is checked last, since it is the only part that depends on other players.
        List<Participant> built = new ArrayList<>();
        for (int team = 0; team < playing.size(); team++) {
            List<UUID> side = playing.get(team);
            for (int member = 0; member < side.size(); member++) {
                UUID uuid = side.get(member);
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player == null) {
                    fail(proposer, "pvpocket.msg.roster.offline", nameOf(uuid));
                    return false;
                }
                if (!uuid.equals(proposer.getUUID())) {
                    if (sessionByPlayer.containsKey(uuid)) {
                        fail(proposer, "pvpocket.msg.duel.target_busy",
                                player.getName().getString());
                        return false;
                    }
                    if (findOpenSession(uuid) != null) {
                        fail(proposer, "pvpocket.msg.duel.target_pending",
                                player.getName().getString());
                        return false;
                    }
                    if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                        fail(proposer, "pvpocket.msg.duel.spectator");
                        return false;
                    }
                }
                Participant p = new Participant(uuid.toString(), player.getName().getString(),
                        team, member);
                // A rebuild keeps existing loadouts; only new participants are seeded.
                Participant previous = session.participant(uuid.toString());
                if (previous != null && previous.loadout != null) {
                    p.loadout = previous.loadout;
                    p.loadoutDigest = previous.loadoutDigest;
                    p.agreedLoadoutDigest = previous.agreedLoadoutDigest;
                } else {
                    // A newcomer inherits the match set, seeded from the proposer's book.
                    seedLoadout(p, sharedLoadout(session));
                }
                built.add(p);
            }
        }

        session.participants.clear();
        session.participants.addAll(built);
        session.teamCount = playing.size();
        journal(session).append("ROSTER_SUBMITTED",
                "size", built.size(),
                "teams", playing.size(),
                "shape", shapeOf(session));
        persist(session);
        return true;
    }

    /**
     * Writes {@code seed} onto a participant that has no loadout yet.
     *
     * <p>The proposer's book becomes the match's set, and every later participant is seeded from
     * that set rather than from their own book.
     *
     * <p>Only for newcomers. A roster rebuild must carry the existing record's loadout over; see
     * {@link #submitRoster}.
     */
    private void seedLoadout(Participant p, Loadout seed) {
        seed.autoAssignSlots();
        p.loadout = seed.encode();
        p.loadoutDigest = seed.digest();
    }

    /**
     * The one set this match fights with.
     *
     * <p>Both sides carry the same items, attributes and enchantments; only the wool colour
     * differs. Derived from the first live participant, so a session written by an older build
     * stays readable. The preset is the last resort.
     */
    private static Loadout sharedLoadout(DuelSession session) {
        for (Participant p : session.participants) {
            if (!p.rejected && p.loadout != null) {
                return Loadout.decode(p.loadout);
            }
        }
        return Loadout.defaultDuel();
    }

    /**
     * Writes one set onto every participant still in the match, and returns it.
     *
     * <p>The copies are what the panels draw and what the consent digest is computed from, so all
     * of them stay identical.
     */
    private static Loadout adoptSharedLoadout(DuelSession session, Loadout loadout) {
        String encoded = loadout.encode();
        String digest = loadout.digest();
        for (Participant p : session.participants) {
            if (p.rejected) {
                continue;
            }
            p.loadout = encoded;
            p.loadoutDigest = digest;
        }
        return loadout;
    }

    /** A display name for a uuid that may not be online, for refusal messages. */
    private String nameOf(UUID uuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            return player.getName().getString();
        }
        for (DuelSession session : sessions.values()) {
            Participant p = session.participant(uuid.toString());
            if (p != null) {
                return p.name;
            }
        }
        return uuid.toString().substring(0, 8);
    }

    /**
     * Refuses a roster the rules cannot hold, before anything is frozen.
     *
     * <p>Empty cards are allowed: they are how the roster says "this side does not play". Refused
     * are a side over the size cap and a roster with fewer than two occupied sides.
     */
    private boolean validateRoster(DuelSession session, DuelRules rules, ServerPlayer player) {
        int teamSize = rules.get("b.teamSize");
        int total = 0;
        for (int team = 0; team < session.teamCount; team++) {
            int members = session.activeMembers(team).size();
            if (members > teamSize) {
                fail(player, "pvpocket.msg.rules.team_too_big", members, teamSize);
                return false;
            }
            total += members;
        }
        int playing = session.playingTeams().size();
        if (total < 2 || playing < 2) {
            fail(player, "pvpocket.msg.roster.not_enough", total);
            return false;
        }
        int maxPlayers = rules.get("b.maxPlayers");
        if (total > maxPlayers) {
            fail(player, "pvpocket.msg.rules.too_many_players", total, maxPlayers);
            return false;
        }
        return true;
    }

    /**
     * How many players each side brought, such as {@code "2+1"}, for the journal and result line.
     */
    private static String shapeOf(DuelSession session) {
        StringBuilder sb = new StringBuilder();
        for (int team = 0; team < session.teamCount; team++) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(session.activeMembers(team).size());
        }
        return sb.toString();
    }

    /**
     * {@code "2+0"}: how many of each side are still standing.
     *
     * <p>Separate from {@link #shapeOf}, since the roster size and the survivor count differ once
     * the round is under way.
     */
    private static String survivorsOf(DuelSession session) {
        StringBuilder sb = new StringBuilder();
        for (int team = 0; team < session.teamCount; team++) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            int alive = 0;
            for (Participant p : session.activeMembers(team)) {
                if (!p.eliminated) {
                    alive++;
                }
            }
            sb.append(alive);
        }
        return sb.toString();
    }

    /** Every spawn pad as {@code "x,y,z"}, one per side, for the round-start journal line. */
    private static String spawnsOf(ArenaRegion region) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ArenaRegion.PAD_COUNT; i++) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(region.spawnX(i)).append(',').append(region.spawnY(i))
                    .append(',').append(region.spawnZ(i));
        }
        return sb.toString();
    }

    /** Sends the panel to a player, from the packet path or from /pvpocket review. */
    private void openPanel(ServerPlayer player, DuelSession session, int role) {
        // The tally is "how many have answered", not "how many agreed to the loadout".
        int agreed = acceptedCount(session);
        // A player with a branch sees their own set, so reopening the panel resumes the edit rather
        // than silently throwing it away. Everyone else sees main.
        RuleBranch branch = session.branchOf(player.getStringUUID());
        Participant me = session.participant(player.getStringUUID());
        String shown = branch != null ? branch.rules : session.rules;
        RulesOpenPayload.Window window = session.stage == SessionStage.NEGOTIATING
                ? new RulesOpenPayload.Window(
                        deadlineLeft(session.rulesWindowEndsAt),
                        me != null && answered(session, me) ? 1 : 0,
                        branch != null ? branch.rules : "",
                        branch != null && branch.roster != null ? branch.roster : "")
                : RulesOpenPayload.Window.none();
        PvPocketNetworking.openRules(player, new RulesOpenPayload(
                session.id,
                shown,
                role,
                session.rulesRevision,
                new RulesOpenPayload.Details(
                        otherName(session, player.getStringUUID()),
                        loadoutCounts(session),
                        agreed,
                        liveCount(session),
                        // Recomputed on every open: a map frees up when the other duel ends.
                        ArenaManager.busyMapMask(session.id),
                        // Only the draft edits the roster, so only it gets the candidate list.
                        role == RulesOpenPayload.ROLE_DRAFT
                                ? candidates(player)
                                : ""),
                window));
    }

    /**
     * Players this proposer could add to a card right now, as {@code "name|uuid"} pairs.
     *
     * <p>Filtered server-side, since only the server knows who is in a duel or has an invite
     * waiting. Ordered by name, and capped by the caller through {@code b.maxPlayers}.
     */
    private String candidates(ServerPlayer reader) {
        List<String> out = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                continue;
            }
            UUID uuid = player.getUUID();
            if (!uuid.equals(reader.getUUID())) {
                if (sessionByPlayer.containsKey(uuid) || findOpenSession(uuid) != null) {
                    continue;
                }
            }
            out.add(player.getName().getString() + "|" + uuid);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(",", out);
    }

    /**
     * {@code Dev1|uuid:8:0:1} - item count, side and answer state, one record per participant.
     *
     * <p>The fourth field is the answer state: {@code 0} has not answered, {@code 1} accepted the
     * current revision, {@code 2} declined.
     *
     * <p>Encoded rather than formatted because the reader's language is not known here. Player
     * names cannot contain {@code :}, {@code ,} or {@code |}, so the format needs no escaping. The
     * uuid rides along because the proposer sends the roster back and a name cannot be resolved.
     */
    private static String loadoutCounts(DuelSession session) {
        StringBuilder sb = new StringBuilder();
        for (Participant p : session.participants) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.name).append('|').append(p.uuid).append(':')
                    .append(p.loadout == null ? 0 : Loadout.decode(p.loadout).size())
                    .append(':').append(p.teamIndex())
                    .append(':').append(p.rejected ? 2
                            : p.acceptedRevision == session.rulesRevision ? 1 : 0);
        }
        return sb.toString();
    }

    /**
     * One fingerprint over everybody's loadout, order-independent.
     *
     * <p>Sorted by uuid, so two sessions with the same loadouts produce the same digest whatever
     * order the participants were added in.
     */
    private static String combinedLoadoutDigest(DuelSession session) {
        StringBuilder sb = new StringBuilder();
        session.participants.stream()
                .filter(p -> !p.rejected)
                .sorted(Comparator.comparing(p -> p.uuid))
                .forEach(p -> sb.append(p.uuid).append(':')
                        .append(p.loadoutDigest == null ? "-" : p.loadoutDigest).append(';'));
        return PvPocketStorage.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    /**
     * True when every participant who has answered also accepted the loadout as it stands.
     *
     * <p>Silent about players who have not answered yet; the branch window tracks those.
     */
    private static boolean allLoadoutsAgreed(DuelSession session) {
        String combined = combinedLoadoutDigest(session);
        for (Participant p : session.participants) {
            if (p.acceptedRevision != session.rulesRevision) {
                continue;
            }
            if (!combined.equals(p.agreedLoadoutDigest)) {
                return false;
            }
        }
        return true;
    }

    /** Re-opens the panel for whoever is currently expected to answer. Command fallback path. */
    public void review(ServerPlayer player) {
        DuelSession session = findOpenSession(player.getUUID());
        if (session == null) {
            fail(player, "pvpocket.msg.rules.none_pending");
            return;
        }
        if (session.stage == SessionStage.VOTING) {
            // The ballot is the panel in this stage; there is nothing left to edit.
            openVotePanel(player, session);
            return;
        }
        if (session.stage == SessionStage.RESULT) {
            openResultPanel(player, session);
            return;
        }
        openPanel(player, session, roleFor(session, player.getStringUUID()));
        player.displayClientMessage(PvLang.t("pvpocket.msg.rules.version",
                        session.rulesRevision, shortDigest(session))
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /**
     * Handles one panel outcome.
     *
     * <p>Past the draft these are branch operations, not a negotiation round: a player either files
     * one alternative (and may amend it until the window closes) or says "nothing to change".
     * Neither answer invalidates anybody else's: one edit does not re-ask the whole roster.
     *
     * <p>Every branch re-derives permission from the session record; the client is never trusted
     * about who may act or what an action means.
     */
    public void onRulesSubmit(ServerPlayer player, int action, String encoded,
                              String encodedRoster) {
        String uuid = player.getStringUUID();
        DuelSession session = findOpenSession(player.getUUID());
        if (session == null) {
            fail(player, "pvpocket.msg.rules.none_pending");
            return;
        }

        if (action == RulesSubmitPayload.ACTION_DISMISS) {
            // A dismissed draft leaves nothing behind: no snapshot exists yet, so voiding is free.
            if (session.stage == SessionStage.DRAFT) {
                journal(session).append("SESSION_DROPPED", "stage", "DRAFT");
                session.voidReason = "PROPOSER_DISMISSED";
                transition(session, SessionStage.VOIDED);
                closePanels(session);
            }
            // Past the draft, closing the panel decides nothing: the window runs on and the player
            // reopens with /pvpocket review. A refusal is an explicit button, not an Esc.
            return;
        }

        if (action == RulesSubmitPayload.ACTION_WITHDRAW) {
            if (!uuid.equals(proposerOf(session))) {
                // Everyone else already has a way out: "reject" drops them, not the invite.
                fail(player, "pvpocket.msg.rules.not_proposer");
                return;
            }
            if (session.stage != SessionStage.NEGOTIATING) {
                // A ballot under way has votes from people the withdrawer does not own.
                fail(player, "pvpocket.msg.rules.vote_closed");
                return;
            }
            withdrawInvite(session, player);
            return;
        }

        if (action == RulesSubmitPayload.ACTION_REJECT) {
            // A refusal takes that player out; the rest of the invite carries on. The match is
            // voided only when what is left cannot field two sides.
            Participant me = session.participant(uuid);
            if (me == null) {
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            if (session.stage == SessionStage.DRAFT) {
                // In a draft the only member is the proposer, so this is a cancel.
                journal(session).append("SESSION_DROPPED", "stage", "DRAFT", "reason", "REJECTED");
                session.voidReason = "PROPOSER_REJECTED";
                transition(session, SessionStage.VOIDED);
                closePanels(session);
                return;
            }
            if (session.stage != SessionStage.NEGOTIATING) {
                // Voting is already under way; leaving is handled by the disconnect path, and a
                // refusal here would re-shape a roster the ballot was built against.
                fail(player, "pvpocket.msg.rules.vote_closed");
                return;
            }
            me.rejected = true;
            me.acceptedRevision = -1;
            session.removeBranch(uuid);
            journal(session).append("PLAYER_REJECTED", "player", me.name,
                    "revision", session.rulesRevision,
                    "remaining", session.participants.size() - rejectedCount(session),
                    "playingTeams", session.playingTeams().size());
            persist(session);
            player.displayClientMessage(PvLang.t("pvpocket.msg.rules.rejected_self")
                    .withStyle(ChatFormatting.GRAY), false);
            broadcastNegotiation(session, uuid,
                    PvLang.t("pvpocket.msg.rules.rejected_by", me.name));
            if (session.playingTeams().size() < 2) {
                settleNegotiation(session, player);
            } else {
                maybeCloseWindow(session);
            }
            return;
        }

        if (action == RulesSubmitPayload.ACTION_PROPOSE) {
            Participant me = session.participant(uuid);
            if (me == null) {
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            if (me.rejected && session.stage != SessionStage.DRAFT) {
                // A draft rebuilds the participant list on every send, so an old refusal is gone.
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            DuelRules proposed = DuelRules.decode(encoded);
            if (session.stage == SessionStage.DRAFT) {
                // Validated against the rules just sent, not against the defaults the draft
                // opened with.
                if (!submitRoster(session, player, encodedRoster, proposed)) {
                    // No reopen: the client keeps its draft panel up, so the cards stay put.
                    return;
                }
                me = session.participant(uuid);
                applyRulesToSession(session, proposed);
                session.rulesRevision++;
                session.rulesAuthor = uuid;
                session.awaitingUuid = null;
                openWindow(session);
                journal(session).append("RULES_PROPOSED",
                        "revision", session.rulesRevision,
                        "author", player.getName().getString(),
                        "digest", session.rulesDigest,
                        "teams", session.teamCount,
                        "changed", String.join(",", DuelRules.decode(session.rules)
                                .differences(DuelRules.defaults())));
                // Authoring main is also answering: this player has nothing else to file.
                me.acceptedRevision = session.rulesRevision;
                me.agreedLoadoutDigest = combinedLoadoutDigest(session);
                journal(session).append("LOADOUT_AGREED", "player", me.name,
                        "combined", me.agreedLoadoutDigest);
                transition(session, SessionStage.NEGOTIATING);

                List<String> others = new ArrayList<>();
                for (Participant p : session.participants) {
                    if (!p.uuid.equals(uuid) && !p.rejected) {
                        others.add(p.name);
                    }
                }
                player.displayClientMessage(PvLang.t("pvpocket.msg.rules.sent",
                                session.rulesRevision, String.join(", ", others))
                        .withStyle(ChatFormatting.GRAY), false);
                for (Participant p : session.participants) {
                    if (p.uuid.equals(uuid) || p.rejected) {
                        continue;
                    }
                    inviteByTarget.put(UUID.fromString(p.uuid), session.id);
                    ServerPlayer target = online(p);
                    if (target == null) {
                        continue;
                    }
                    target.displayClientMessage(PvLang.t("pvpocket.msg.rules.incoming",
                                    player.getName().getString(), session.rulesRevision,
                                    proposed.summaryText())
                            .withStyle(ChatFormatting.GOLD), false);
                    target.displayClientMessage(PvLang.t("pvpocket.msg.rules.window_open",
                                    RULES_WINDOW_MS / 60_000L)
                            .withStyle(ChatFormatting.DARK_GRAY), false);
                    openPanel(target, session, RulesOpenPayload.ROLE_REVIEWER);
                }
                openPanel(player, session, RulesOpenPayload.ROLE_PROPOSER);
                return;
            }
            if (session.stage != SessionStage.NEGOTIATING) {
                fail(player, "pvpocket.msg.rules.vote_closed");
                return;
            }
            if (answered(session, me)) {
                // One answer per player: a second submit would move the moment the window closes
                // and make "everybody has answered" mean nothing.
                fail(player, "pvpocket.msg.rules.already_answered");
                return;
            }
            fileBranch(session, me, proposed, null, encodedRoster, true);
            return;
        }

        if (action == RulesSubmitPayload.ACTION_ACCEPT) {
            if (session.stage == SessionStage.DRAFT) {
                fail(player, "pvpocket.msg.rules.nothing_to_accept");
                return;
            }
            if (session.stage != SessionStage.NEGOTIATING) {
                fail(player, "pvpocket.msg.rules.vote_closed");
                return;
            }
            Participant me = session.participant(uuid);
            if (me == null) {
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            if (answered(session, me)) {
                fail(player, "pvpocket.msg.rules.already_answered");
                return;
            }
            me.acceptedRevision = session.rulesRevision;
            me.agreedLoadoutDigest = combinedLoadoutDigest(session);
            journal(session).append("RULES_AGREED",
                    "player", me.name,
                    "revision", session.rulesRevision,
                    "agreed", acceptedCount(session), "of", liveCount(session));
            persist(session);
            int agreed = acceptedCount(session);
            player.displayClientMessage(PvLang.t("pvpocket.msg.rules.accepted_waiting",
                            agreed, liveCount(session))
                    .withStyle(ChatFormatting.GRAY), false);
            broadcastNegotiation(session, uuid,
                    PvLang.t("pvpocket.msg.rules.other_accepted",
                            player.getName().getString(), agreed, liveCount(session)));
            openPanel(player, session, roleFor(session, uuid));
            maybeCloseWindow(session);
        }
    }

    // ------------------------------------------------------------------ branch window

    /**
     * The match's one loadout, with every row numbered.
     *
     * <p>Numbering happens once, when the invite goes out; from then on a branch's rows are matched
     * against these numbers rather than against positions. A session written before the field
     * existed is numbered on first use, which produces the same numbers because it is list order.
     */
    private static Loadout mainLoadout(DuelSession session) {
        Loadout main = sharedLoadout(session);
        boolean missing = false;
        for (LoadoutEntry entry : main.entries) {
            if (entry.ref < 0) {
                missing = true;
                break;
            }
        }
        if (missing) {
            main.assignRefs();
            adoptSharedLoadout(session, main);
        }
        return main;
    }

    /** Opens the branch window and clears anything a previous window left behind. */
    private void openWindow(DuelSession session) {
        session.rulesWindowEndsAt = System.currentTimeMillis() + RULES_WINDOW_MS;
        session.voteWindowEndsAt = 0;
        session.branches.clear();
        session.voteItems.clear();
        session.votes.clear();
        mainLoadout(session);
    }

    /**
     * Closes the window once every live player has answered, or once the clock runs out.
     *
     * <p>An unanswered player holds it open; silence is what the deadline is for.
     */
    private void maybeCloseWindow(DuelSession session) {
        if (session.stage != SessionStage.NEGOTIATING) {
            return;
        }
        for (Participant p : session.participants) {
            if (p.rejected) {
                continue;
            }
            if (!answered(session, p)) {
                return;
            }
        }
        closeWindow(session);
    }

    /**
     * Freezes the branch set and either starts the match or opens the ballot.
     *
     * <p>No branches at all means nobody disagreed: the match starts on main immediately, which is
     * the "all accepted" path and skips voting entirely.
     */
    private void closeWindow(DuelSession session) {
        if (session.stage != SessionStage.NEGOTIATING) {
            return;
        }
        session.rulesWindowEndsAt = 0;
        List<RuleBranch> live = new ArrayList<>();
        for (Participant p : session.participants) {
            if (p.rejected) {
                continue;
            }
            RuleBranch branch = session.branchOf(p.uuid);
            if (branch != null) {
                live.add(branch);
            }
        }
        if (live.isEmpty()) {
            // Nobody disagreed, but the result page still goes up: it is the last chance to read
            // what the match will run on before the teleport.
            journal(session).append("WINDOW_CLOSED", "branches", 0);
            enterResult(session);
            return;
        }
        session.voteItems = VoteBallot.build(DuelRules.decode(session.rules),
                mainLoadout(session), RosterShape.of(session), live);
        if (session.voteItems.isEmpty()) {
            // Every branch turned out to be main: same outcome as no branch at all.
            journal(session).append("WINDOW_CLOSED", "branches", live.size(), "items", 0);
            enterResult(session);
            return;
        }
        session.voteWindowEndsAt = System.currentTimeMillis() + VOTE_WINDOW_MS;
        journal(session).append("VOTE_OPENED",
                "branches", live.size(),
                "items", session.voteItems.size(),
                "authors", String.join(",", live.stream().map(b -> b.name).toList()));
        transition(session, SessionStage.VOTING);
        // Rule panels first: the ballot replaces them, and a client cannot show both.
        closePanels(session);
        for (Participant p : session.participants) {
            if (p.rejected) {
                continue;
            }
            ServerPlayer target = online(p);
            if (target != null) {
                openVotePanel(target, session);
            }
        }
        broadcastParticipants(session, PvLang.t("pvpocket.msg.rules.vote_started",
                session.voteItems.size(), VOTE_WINDOW_MS / 60_000L));
    }

    /**
     * Files or replaces this player's branch, or drops it when it is identical to main.
     *
     * @param loadout the alternative loadout, or null to keep whatever this player already filed
     *                (or main, when they have not touched it)
     */
    private void fileBranch(DuelSession session, Participant me, DuelRules rules, Loadout loadout,
                            String roster, boolean answer) {
        Loadout main = mainLoadout(session);
        Loadout proposed;
        if (loadout != null) {
            proposed = loadout;
        } else {
            RuleBranch previous = session.branchOf(me.uuid);
            proposed = previous != null ? previous.decodedLoadout() : main.copy();
        }
        alignRefs(proposed, main);

        String mainRoster = RosterShape.of(session);
        String proposedRoster;
        if (roster != null && !roster.isEmpty()) {
            proposedRoster = RosterShape.normalise(roster);
            // Validated against main's caps, not the branch's: a client that sends a bigger
            // b.teamSize would otherwise get an arrangement past this check and fail at the start.
            String problem = proposedRoster == null ? "pvpocket.msg.roster.branch_bad"
                    : rosterProblem(session, proposedRoster, DuelRules.decode(session.rules));
            if (problem != null) {
                // Refused before anything is stored: a half-valid arrangement would be voted on.
                fail(online(me), problem);
                return;
            }
        } else {
            RuleBranch previous = session.branchOf(me.uuid);
            proposedRoster = previous != null && previous.roster != null
                    && !previous.roster.isEmpty() ? previous.roster : mainRoster;
        }

        boolean sameRules = rules.encode().equals(session.rules);
        boolean sameLoadout = proposed.digest().equals(main.digest());
        boolean sameRoster = proposedRoster.equals(mainRoster);
        session.removeBranch(me.uuid);
        if (sameRules && sameLoadout && sameRoster) {
            if (answer) {
                me.acceptedRevision = session.rulesRevision;
            }
            me.agreedLoadoutDigest = combinedLoadoutDigest(session);
            journal(session).append("BRANCH_RETRACTED", "player", me.name,
                    "reason", "IDENTICAL_TO_MAIN");
            persist(session);
        } else {
            RuleBranch branch = new RuleBranch(me.uuid, me.name, rules.encode(),
                    proposed.encode(), sameRoster ? "" : proposedRoster,
                    rules.digest() + ":" + proposed.digest() + ":"
                            + Integer.toHexString(proposedRoster.hashCode()));
            session.branches.add(branch);
            if (answer) {
                me.acceptedRevision = session.rulesRevision;
            }
            me.agreedLoadoutDigest = combinedLoadoutDigest(session);
            journal(session).append("BRANCH_FILED",
                    "player", me.name,
                    "revision", session.rulesRevision,
                    "changed", String.join(",",
                            DuelRules.decode(session.rules).differences(rules)),
                    "items", proposed.size(),
                    "roster", sameRoster ? "-" : "changed",
                    "digest", branch.digest);
            persist(session);
            broadcastNegotiation(session, me.uuid,
                    PvLang.t("pvpocket.msg.rules.branch_filed", me.name));
        }
        ServerPlayer actor = online(me);
        if (actor != null) {
            openPanel(actor, session, roleFor(session, me.uuid));
        }
        maybeCloseWindow(session);
    }

    /**
     * Why an arrangement cannot be voted on, or null when it can.
     *
     * <p>The participant set is fixed at the invite: an arrangement may move anybody anywhere, but
     * it cannot add, drop or duplicate a player. Every limit is checked against the frozen caps, so
     * a branch cannot propose a roster the match could not start with.
     */
    private String rosterProblem(DuelSession session, String roster, DuelRules rules) {
        List<List<String>> sides = RosterShape.decode(roster);
        if (sides == null) {
            return "pvpocket.msg.roster.branch_bad";
        }
        if (sides.size() < MIN_TEAMS || sides.size() > MAX_TEAMS) {
            return "pvpocket.msg.roster.branch_sides";
        }
        int teamSize = rules.get("b.teamSize");
        int cap = rules.get("b.maxPlayers");
        Set<String> live = new LinkedHashSet<>();
        for (Participant p : session.participants) {
            if (!p.rejected) {
                live.add(p.uuid);
            }
        }
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (List<String> side : sides) {
            if (side.size() > teamSize) {
                return "pvpocket.msg.roster.branch_full";
            }
            for (String uuid : side) {
                if (!seen.add(uuid)) {
                    return "pvpocket.msg.roster.branch_dup";
                }
                if (!live.contains(uuid)) {
                    return "pvpocket.msg.roster.branch_unknown";
                }
            }
            total += side.size();
        }
        if (total > cap) {
            return "pvpocket.msg.roster.branch_total";
        }
        return seen.size() < live.size() ? "pvpocket.msg.roster.branch_missing" : null;
    }

    /**
     * Numbers rows a client could not number, by matching them to main.
     *
     * <p>Rows keep their {@code ref} through the editor, so this normally does nothing. It covers
     * the paths that rebuild a set from nothing: "restore default", a preset loaded inside the
     * window, and any client that lost the field. Without it those rows would all read as
     * additions, and the ballot would offer to remove main's rows and add identical ones.
     *
     * <p>Matching is by {@code fingerprint}, first unused row wins, so two identical food stacks
     * still line up one to one.
     */
    private static void alignRefs(Loadout proposed, Loadout main) {
        java.util.Set<Integer> taken = new HashSet<>();
        for (LoadoutEntry entry : proposed.entries) {
            if (entry.ref >= 0 && main.byRef(entry.ref) != null) {
                taken.add(entry.ref);
            }
        }
        for (LoadoutEntry entry : proposed.entries) {
            if (entry.ref >= 0 && main.byRef(entry.ref) != null) {
                continue;
            }
            String fingerprint = entry.fingerprint();
            for (LoadoutEntry base : main.entries) {
                if (!taken.contains(base.ref) && base.fingerprint().equals(fingerprint)) {
                    entry.ref = base.ref;
                    taken.add(base.ref);
                    break;
                }
            }
        }
    }

    /**
     * Whether this player has given their one answer for the current revision.
     *
     * <p>Answering is a single act: a branch, or "no changes". The author of main counts as
     * answered from the start, because the invite is their answer.
     */
    private static boolean answered(DuelSession session, Participant p) {
        return p.acceptedRevision == session.rulesRevision;
    }

    /** The panel role for this player, derived from the stage and who authored main. */
    private static int roleFor(DuelSession session, String uuid) {
        if (session.stage == SessionStage.DRAFT) {
            return RulesOpenPayload.ROLE_DRAFT;
        }
        return uuid.equals(session.rulesAuthor)
                ? RulesOpenPayload.ROLE_PROPOSER
                : RulesOpenPayload.ROLE_REVIEWER;
    }

    // ------------------------------------------------------------------ voting

    /** Opens or refreshes the ballot for one player. */
    private void openVotePanel(ServerPlayer player, DuelSession session) {
        List<String> voters = liveUuids(session);
        int finished = VoteBallot.finishedVoters(session.voteItems, voters, session.votes);
        PvPocketNetworking.openVote(player, new VoteOpenPayload(session.id,
                new VoteOpenPayload.Body(
                        VoteItem.encodeAll(session.voteItems),
                        myVotes(session, player.getStringUUID()),
                        namesOf(session),
                        deadlineLeft(session.voteWindowEndsAt),
                        finished,
                        voters.size())));
    }

    /**
     * Records one vote.
     *
     * <p>The client may change its mind until the ballot closes, so a second vote on the same item
     * replaces the first rather than being refused.
     */
    public void onVoteSubmit(ServerPlayer player, String sessionId, String itemId, int option) {
        DuelSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.VOTING) {
            fail(player, "pvpocket.msg.rules.vote_closed");
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null || me.rejected) {
            return;
        }
        VoteItem item = null;
        for (VoteItem candidate : session.voteItems) {
            if (candidate.id.equals(itemId)) {
                item = candidate;
                break;
            }
        }
        if (item == null || option < 0 || option >= item.options.size()) {
            // Recorded rather than dropped silently: a ballot the client invented is worth a line.
            journal(session).append("VOTE_REJECTED", "player", me.name,
                    "item", itemId, "option", option);
            return;
        }
        session.votes.put(VoteBallot.voteKey(me.uuid, itemId), Integer.toString(option));
        persist(session);
        broadcastVoteProgress(session);
        List<String> voters = liveUuids(session);
        if (VoteBallot.everyoneVoted(session.voteItems, voters, session.votes)) {
            journal(session).append("VOTE_COMPLETE", "players", voters.size());
            resolveVote(session, player);
        }
    }

    /** Pushes participation counts to every live participant. */
    private void broadcastVoteProgress(DuelSession session) {
        List<String> voters = liveUuids(session);
        int finished = VoteBallot.finishedVoters(session.voteItems, voters, session.votes);
        for (Participant p : session.participants) {
            if (p.rejected) {
                continue;
            }
            ServerPlayer target = online(p);
            if (target == null) {
                continue;
            }
            PvPocketNetworking.sendVoteProgress(target, new VoteProgressPayload(
                    session.id, myVotes(session, p.uuid), finished, voters.size()));
        }
    }

    /**
     * Merges the ballot and starts the match.
     *
     * <p>Called when every vote is in, or when the vote window expires on the votes already cast.
     * The merged set becomes main: the revision is bumped once, so the journal still says which
     * rule set ran.
     */
    private void resolveVote(DuelSession session, ServerPlayer actor) {
        if (session.stage != SessionStage.VOTING) {
            return;
        }
        List<String> voters = liveUuids(session);
        Loadout main = mainLoadout(session);
        Loadout merged = VoteBallot.resolveLoadout(main, session.voteItems, voters, session.votes);
        if (merged.isEmpty() || !merged.validate().isEmpty()) {
            // Only reachable if every row was voted away, or if an unusual number of additions
            // pushed past the cap. The match still has to be playable, so main stands.
            journal(session).append("VOTE_LOADOUT_DROPPED",
                    "reason", merged.isEmpty() ? "empty" : "invalid",
                    "items", merged.size());
            merged = main.copy();
        }
        DuelRules mergedRules = mergeRules(session, DuelRules.decode(session.rules), voters);
        String mainRoster = RosterShape.of(session);
        String mergedRoster = VoteBallot.resolveRoster(mainRoster, session.voteItems, voters,
                session.votes);
        boolean rosterChanged = !mergedRoster.equals(mainRoster);
        if (rosterChanged) {
            applyRoster(session, mergedRoster);
        }

        StringBuilder tallies = new StringBuilder();
        for (VoteItem item : session.voteItems) {
            if (tallies.length() > 0) {
                tallies.append(' ');
            }
            tallies.append(VoteBallot.tallyLine(item, voters, session.votes));
        }
        session.voteWindowEndsAt = 0;
        applyRulesToSession(session, mergedRules);
        session.rulesRevision++;
        // No single author: the roster did this, and naming one player would misattribute it.
        session.rulesAuthor = null;
        adoptSharedLoadout(session, merged);
        journal(session).append("VOTE_RESOLVED",
                "revision", session.rulesRevision,
                "voters", voters.size(),
                "items", session.voteItems.size(),
                "changed", changedFromDefaults(mergedRules),
                "digest", session.rulesDigest,
                "loadoutDigest", merged.digest(),
                "roster", rosterChanged ? mergedRoster : "-",
                "tallies", tallies.toString());
        closePanels(session);
        enterResult(session);
    }

    /**
     * Publishes the result page, builds the plot and moves everybody into the waiting lobby.
     *
     * <p>Every path into the match goes through here. The order is fixed: lease and build the plot,
     * write each participant's survival state while they still stand in their own world, then move
     * them to the lobby island. The countdown starts once the plot is ready, so its five seconds
     * are reading time rather than a wait for generation.
     *
     * <p>A player is never moved before their snapshot is on disk, and never captured after the
     * move: the snapshot holds the position to send them back to when the series ends.
     */
    private void enterResult(DuelSession session) {
        if (session.stage != SessionStage.NEGOTIATING && session.stage != SessionStage.VOTING
                && session.stage != SessionStage.RESULT) {
            return;
        }
        if (session.playingTeams().size() < 2) {
            settleNegotiation(session, null);
            return;
        }
        journal(session).append("RESULT_PUBLISHED",
                "revision", session.rulesRevision,
                "items", session.voteItems.size(),
                "roster", RosterShape.of(session),
                "digest", session.rulesDigest);
        transition(session, SessionStage.RESULT);
        closePanels(session);
        // Seconds of server thread: a cell-sized clear plus a paste is measured at 4-5 s cold.
        // Paid here so the countdown ends with a plot rather than with a wait.
        long buildMs = prepareArena(session);
        session.resultEndsAt = System.currentTimeMillis() + RESULT_WINDOW_MS;
        persist(session);
        journal(session).append("RESULT_WINDOW", "ms", RESULT_WINDOW_MS, "arenaBuildMs", buildMs);
        for (Participant p : session.participants) {
            if (p.rejected) {
                continue;
            }
            ServerPlayer target = online(p);
            if (target != null) {
                enterLobby(session, p, target);
                openResultPanel(target, session);
            }
        }
    }

    /**
     * Leases and builds the plot while the result page is up.
     *
     * <p>This is the part of a start that can take seconds: clearing the cell, pasting the map or
     * laying the flat platform, and building the barrier shelf. Taking the lease here also reserves
     * the cell for the countdown, so no other session can take it in between.
     *
     * <p>A refusal is not fatal: {@code startAccepted} re-attempts the lease, and its own retry
     * covers a grid that filled up in the meantime.
     *
     * @return how long the build took in milliseconds, or -1 when nothing was built
     */
    private long prepareArena(DuelSession session) {
        if (!ArenaManager.isReady(server)) {
            journal(session).append("ARENA_PREPARE_SKIPPED", "reason", "world_missing");
            return -1;
        }
        // A reset re-stamps one map per tick, and a lease taken mid-rebuild would race the stamp.
        if (ArenaManager.warmUpPending()) {
            journal(session).append("ARENA_PREPARE_SKIPPED", "reason", "warming_up");
            return -1;
        }
        DuelRules rules = DuelRules.decode(session.rules);
        int mapIndex = rules.get("e.template");
        if (ArenaManager.isMapBusy(mapIndex, session.id)) {
            journal(session).append("ARENA_PREPARE_SKIPPED", "reason", "map_busy",
                    "map", ArenaMaps.byIndex(mapIndex).id);
            return -1;
        }
        long started = System.nanoTime();
        ArenaRegion region = ArenaManager.allocate(server, session.id,
                rules.get("e.sizeX"), rules.get("e.sizeY"), rules.get("e.sizeZ"),
                ArenaMaps.byIndex(mapIndex),
                rules.flag("f.protectTerrain"), rules.flag("f.allowBreakOwn"));
        long ms = (System.nanoTime() - started) / 1_000_000L;
        if (region == null) {
            journal(session).append("ARENA_PREPARE_SKIPPED", "reason", "arena_full");
            return -1;
        }
        session.arenaId = region.id;
        journal(session).append("ARENA_PREPARED", "region", region.id,
                "size", region.sizeX + "x" + region.sizeY + "x" + region.sizeZ,
                "map", region.map.id,
                "origin", region.minX + "," + region.minZ,
                "protectTerrain", region.protectTerrain,
                "allowBreakOwn", region.allowBreakOwn,
                "ms", ms);
        return ms;
    }

    /**
     * Writes one participant's survival state, then puts them on the lobby island.
     *
     * <p>The snapshot is taken here rather than in {@code freeze} because this is the last moment
     * the player stands in their own world: a capture after the teleport would record the lobby as
     * the place to send them back to. Written before the move, so nobody is ever in the dimension
     * without a way out.
     */
    private void enterLobby(DuelSession session, Participant p, ServerPlayer player) {
        if (p.snapshotFile == null) {
            PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
            Path file = storage.snapshotFile(session.id, p.uuid);
            try {
                snapshot.writeTo(file);
            } catch (IOException e) {
                // Left where they are: a guest in the lobby with no snapshot has no way home.
                PvPocket.LOGGER.error("Cannot snapshot {} for the lobby; leaving them outside",
                        p.name, e);
                journal(session).append("LOBBY_SKIPPED", "player", p.name,
                        "reason", "snapshot_failed");
                return;
            }
            p.snapshotFile = file.getFileName().toString();
            p.fingerprint = snapshot.fingerprint();
            journal(session).append("SNAPSHOT_WRITTEN",
                    "player", p.name, "fingerprint", p.fingerprint, "stage", "RESULT");
        }
        // A clean floor before anybody lands on it: the lobby keeps whatever the world save has
        // been holding, and a guest who picks up a leftover item carries it into the round.
        int litter = Lobby.clearDroppedItems(ArenaManager.world(PvCompat.server(player)));
        if (litter > 0) {
            journal(session).append("LOBBY_SWEPT", "player", p.name, "items", litter);
        }
        if (Lobby.teleport(player)) {
            journal(session).append("LOBBY_ENTERED", "player", p.name);
        } else {
            journal(session).append("LOBBY_SKIPPED", "player", p.name, "reason", "world_missing");
        }
    }

    /**
     * Undoes what the result page did: the plot goes back to the pool, and anybody standing in the
     * lobby is restored.
     *
     * <p>Called from every exit between the page and the freeze. Without it a session voided during
     * the countdown would leave its plot leased and its players in the duel dimension holding a
     * snapshot nobody applies.
     */
    private void releaseLobby(DuelSession session) {
        broadcastPrepare(session, false);
        for (Participant p : session.participants) {
            if (p.snapshotFile != null) {
                restoreParticipant(session, p);
            }
        }
        ArenaManager.release(session.id);
    }

    /**
     * Catches a lobby guest who walked off the island.
     *
     * <p>The lobby floats in a void dimension, so falling past it is a death with no ground to
     * respawn on. Only {@code RESULT} is scanned: that is the only stage anybody is up there.
     */
    private void tickLobby() {
        for (DuelSession session : sessions.values()) {
            if (session.stage != SessionStage.RESULT) {
                continue;
            }
            for (Participant p : session.participants) {
                if (p.rejected) {
                    continue;
                }
                ServerPlayer player = online(p);
                if (player == null || !Lobby.belowIsland(player)) {
                    continue;
                }
                journal(session).append("LOBBY_CAUGHT", "player", p.name,
                        "y", String.format("%.1f", player.getY()));
                Lobby.teleport(player);
            }
        }
    }

    /** Opens or refreshes the result page for one player. */
    private void openResultPanel(ServerPlayer player, DuelSession session) {
        List<String> voters = liveUuids(session);
        PvPocketNetworking.openResult(player, new ResultOpenPayload(
                session.id,
                VoteItem.encodeAll(session.voteItems),
                tallySheet(session, voters),
                RosterShape.of(session),
                namesOf(session),
                deadlineLeft(session.resultEndsAt)));
    }

    /** Per-item outcome as {@code id 	 winner 	 counts}, one line per item. */
    /** Every participant as {@code uuid|name} pairs, for drawing arrangements by name. */
    private static String namesOf(DuelSession session) {
        StringBuilder sb = new StringBuilder();
        for (Participant p : session.participants) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.uuid).append('|').append(p.name);
        }
        return sb.toString();
    }

    private static String tallySheet(DuelSession session, List<String> voters) {
        StringBuilder sb = new StringBuilder();
        for (VoteItem item : session.voteItems) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            int[] counts = VoteBallot.counts(item, voters, session.votes);
            sb.append(item.id).append('\t').append(VoteBallot.winner(counts)).append('\t');
            for (int i = 0; i < counts.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(counts[i]);
            }
        }
        return sb.toString();
    }

    /**
     * Writes an arrangement onto the session.
     *
     * <p>Teams are renumbered from the arrangement itself, so an arrangement that merges two sides
     * leaves no hole: team 0 is the first non-empty side, and every member's index within it is
     * recomputed because that index decides the spawn offset on the pad.
     */
    private static void applyRoster(DuelSession session, String roster) {
        List<List<String>> sides = RosterShape.decode(roster);
        if (sides == null) {
            return;
        }
        for (Participant p : session.participants) {
            p.team = -1;
            p.member = 0;
        }
        for (int team = 0; team < sides.size(); team++) {
            List<String> side = sides.get(team);
            for (int member = 0; member < side.size(); member++) {
                Participant p = session.participant(side.get(member));
                if (p != null) {
                    p.team = team;
                    p.member = member;
                    p.spawnIndex = team;
                }
            }
        }
        session.teamCount = Math.max(MIN_TEAMS, sides.size());
    }

    /**
     * The merged rules, with roster-coupled values dropped item by item.
     *
     * <p>{@code b.teamSize} and {@code b.maxPlayers} describe a roster that was drawn before the
     * vote and is not part of it. A winning value that cannot hold that roster is vetoed on its
     * own, rather than discarding every other winning change with it, and rather than leaving a
     * set that {@code startAccepted} would refuse with nobody left to fix it.
     */
    private DuelRules mergeRules(DuelSession session, DuelRules main, List<String> voters) {
        java.util.Set<String> vetoed = new HashSet<>();
        DuelRules merged = VoteBallot.resolveRules(main, session.voteItems, voters,
                session.votes, vetoed);
        if (!rosterHolds(session, merged)) {
            vetoed.add("b.teamSize");
            merged = VoteBallot.resolveRules(main, session.voteItems, voters, session.votes, vetoed);
            journal(session).append("VOTE_VETOED", "rule", "b.teamSize", "reason", "roster");
        }
        if (!rosterHolds(session, merged)) {
            vetoed.add("b.maxPlayers");
            merged = VoteBallot.resolveRules(main, session.voteItems, voters, session.votes, vetoed);
            journal(session).append("VOTE_VETOED", "rule", "b.maxPlayers", "reason", "roster");
        }
        return merged;
    }

    /** Whether the roster still fits the rules: the same two limits {@code validateRoster} uses. */
    private static boolean rosterHolds(DuelSession session, DuelRules rules) {
        int teamSize = rules.get("b.teamSize");
        int total = 0;
        for (int team = 0; team < session.teamCount; team++) {
            int members = session.activeMembers(team).size();
            if (members > teamSize) {
                return false;
            }
            total += members;
        }
        return total <= rules.get("b.maxPlayers");
    }

    /** Everybody still in the match, as uuids: the electorate, in roster order. */
    private static List<String> liveUuids(DuelSession session) {
        List<String> out = new ArrayList<>();
        for (Participant p : session.participants) {
            if (!p.rejected) {
                out.add(p.uuid);
            }
        }
        return out;
    }

    /** This player's votes as {@code itemId=option;...}, for restoring a panel. */
    private static String myVotes(DuelSession session, String uuid) {
        StringBuilder sb = new StringBuilder();
        for (VoteItem item : session.voteItems) {
            String raw = session.votes.get(VoteBallot.voteKey(uuid, item.id));
            if (raw == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(item.id).append('=').append(raw);
        }
        return sb.toString();
    }

    /** Milliseconds left before {@code deadlineAt}, clamped to zero and to an int. */
    private static int deadlineLeft(long deadlineAt) {
        if (deadlineAt <= 0) {
            return 0;
        }
        long left = deadlineAt - System.currentTimeMillis();
        if (left <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, left);
    }

    /**
     * Every live player's consent, written at once just before the match starts.
     *
     * <p>One answer per player plus a merge everybody is bound by, so consent is recorded once, on
     * the final set, rather than compared rule set by rule set.
     */
    private static void sealConsent(DuelSession session) {
        String combined = combinedLoadoutDigest(session);
        for (Participant p : session.participants) {
            if (p.rejected) {
                continue;
            }
            p.acceptedRevision = session.rulesRevision;
            p.agreedLoadoutDigest = combined;
        }
    }

    /** Participants who have said yes to the revision currently on the table. */
    private static int acceptedCount(DuelSession session) {
        int count = 0;
        for (Participant p : session.participants) {
            if (!p.rejected && p.acceptedRevision == session.rulesRevision) {
                count++;
            }
        }
        return count;
    }

    /** Participants still in the match, with declined invites excluded. */
    private static int liveCount(DuelSession session) {
        int count = 0;
        for (Participant p : session.participants) {
            if (!p.rejected) {
                count++;
            }
        }
        return count;
    }

    private static int rejectedCount(DuelSession session) {
        return session.participants.size() - liveCount(session);
    }

    /**
     * Starts the match, or ends the invite because what is left cannot field two sides.
     *
     * <p>Everybody must have answered, and what remains must be at least two sides with somebody on
     * them. Sides need not be the same size: a 2v1 after one refusal out of four is a match.
     */
    private void settleNegotiation(DuelSession session, ServerPlayer actor) {
        // Reachable from the branch window, the ballot and the result countdown. All three are
        // still pre-freeze, so all three may start the match.
        if (session.stage != SessionStage.NEGOTIATING && session.stage != SessionStage.VOTING
                && session.stage != SessionStage.RESULT) {
            return;
        }
        if (session.playingTeams().size() < 2) {
            journal(session).append("SESSION_DROPPED", "reason", "NOT_ENOUGH_TEAMS",
                    "remaining", liveCount(session),
                    "playingTeams", session.playingTeams().size());
            session.voidReason = "NOT_ENOUGH_TEAMS";
            // A session dropped from the result page has already leased a plot and moved its
            // players into the lobby, so the pre-freeze shortcut does not apply here.
            releaseLobby(session);
            transition(session, SessionStage.VOIDED);
            inviteByTarget.values().removeIf(session.id::equals);
            closePanels(session);
            for (Participant p : session.participants) {
                ServerPlayer other = online(p);
                if (other != null) {
                    other.displayClientMessage(PvLang.t("pvpocket.msg.roster.cancelled",
                                    liveCount(session), session.playingTeams().size())
                            .withStyle(ChatFormatting.RED), false);
                }
            }
            return;
        }
        // One answer per player is already on record; the final rule set is what they are bound
        // by, so consent is sealed onto it here rather than compared revision by revision.
        sealConsent(session);
        session.startRetryAt = 0;
        startAccepted(session, actor);
        if (!session.stage.isTerminal() && session.stage.isPreFreeze()) {
            // The arena refused: full grid, a rebuild, a map taken a second ago. Retried from
            // the tick, because the ballot left nobody with a button to press.
            session.startRetryAt = System.currentTimeMillis() + START_RETRY_MS;
            journal(session).append("START_DEFERRED", "stage", session.stage.name());
            persist(session);
        }
    }

    /**
     * A player left while the invite was still being negotiated.
     *
     * <p>Recorded as a refusal, since the rest of the roster may still field two sides. The line is
     * worded differently from a deliberate refusal, because nobody chose anything.
     */
    private void dropNegotiator(DuelSession session, String uuid) {
        if (session.stage != SessionStage.NEGOTIATING) {
            return;
        }
        Participant p = session.participant(uuid);
        if (p == null || p.rejected) {
            return;
        }
        p.rejected = true;
        p.acceptedRevision = -1;
        // Their branch goes with them: it is an opinion, and they are out of the match.
        session.removeBranch(uuid);
        journal(session).append("PLAYER_LEFT", "player", p.name,
                "revision", session.rulesRevision,
                "remaining", session.participants.size() - rejectedCount(session),
                "playingTeams", session.playingTeams().size());
        persist(session);
        broadcastNegotiation(session, uuid,
                PvLang.t("pvpocket.msg.rules.left_match", p.name));
        if (session.playingTeams().size() < 2) {
            settleNegotiation(session, null);
        } else {
            maybeCloseWindow(session);
        }
    }

    /** One line to everybody still in the match, except {@code exceptUuid}. */
    private void broadcastNegotiation(DuelSession session, String exceptUuid, MutableComponent line) {
        for (Participant p : session.participants) {
            if (p.rejected || p.uuid.equals(exceptUuid)) {
                continue;
            }
            ServerPlayer other = online(p);
            if (other != null) {
                other.displayClientMessage(line.copy().withStyle(ChatFormatting.DARK_GRAY), false);
            }
        }
    }

    /**
     * The player who sent the invite.
     *
     * <p>{@link DuelSession#proposerUuid} is authoritative. The fallback covers a session written
     * before the field existed: anything still in {@code NEGOTIATING} has not merged a ballot, so
     * its {@code rulesAuthor} is still the proposer.
     */
    private static String proposerOf(DuelSession session) {
        return session.proposerUuid != null ? session.proposerUuid : session.rulesAuthor;
    }

    /**
     * Takes an invite back and voids the session for everybody.
     *
     * <p>Only reachable from {@code NEGOTIATING}, which is before the freeze: no snapshot has been
     * written and no plot leased, so there is nothing to roll back. Branches filed so far are
     * discarded with the session; the journal records how many, because a withdraw that silently
     * ate three people's edits is otherwise indistinguishable from one that ate none.
     */
    private void withdrawInvite(DuelSession session, ServerPlayer actor) {
        journal(session).append("INVITE_WITHDRAWN",
                "proposer", actor.getName().getString(),
                "revision", session.rulesRevision,
                "branches", session.branches.size(),
                "players", liveCount(session));
        session.voidReason = "WITHDRAWN";
        transition(session, SessionStage.VOIDED);
        // Before the panels close: this line is the only thing that explains why they shut.
        broadcastParticipants(session,
                PvLang.t("pvpocket.msg.rules.withdrawn", actor.getName().getString()));
        closePanels(session);
    }

    /** Freeze, snapshot, teleport, start. The rule panel is the only caller. */
    private void startAccepted(DuelSession session, ServerPlayer accepter) {
        if (!ArenaManager.isReady(server)) {
            fail(accepter, "pvpocket.msg.rules.arena_not_ready");
            return;
        }
        // Checked again here rather than trusting the caller: the other side may have edited their
        // loadout between the proposal and this click.
        if (!allLoadoutsAgreed(session)) {
            fail(accepter, "pvpocket.msg.rules.loadout_not_all_agreed");
            return;
        }
        // The lease is taken before anything is frozen, so a full grid fails while still
        // NEGOTIATING.
        DuelRules rules = DuelRules.decode(session.rules);
        if (!validateRoster(session, rules, accepter)) {
            return;
        }
        int mapIndex = rules.get("e.template");
        ArenaRegion region;
        if (ArenaManager.leaseMatches(session.id, rules.get("e.sizeX"), rules.get("e.sizeY"),
                rules.get("e.sizeZ"), ArenaMaps.byIndex(mapIndex),
                rules.flag("f.protectTerrain"), rules.flag("f.allowBreakOwn"))) {
            // Built while the result page was up, so the start has nothing left to wait for. No
            // prepare notice either: there is no wait for it to explain.
            region = ArenaManager.region(session.id);
            journal(session).append("ARENA_REUSED", "region", region.id,
                    "map", region.map.id,
                    "size", region.sizeX + "x" + region.sizeY + "x" + region.sizeZ);
        } else {
            // A reset re-stamps one map per tick, and a lease taken mid-rebuild would race the
            // stamp. A plot already leased and built cannot be raced by the warm-up.
            if (ArenaManager.warmUpPending()) {
                fail(accepter, "pvpocket.msg.rules.arena_rebuilding");
                return;
            }
            // Checked here as well as in the panel, which may have been open for a minute while
            // somebody else took the map.
            if (ArenaManager.isMapBusy(mapIndex, session.id)) {
                fail(accepter, "pvpocket.msg.rules.map_busy",
                        ArenaMaps.byIndex(mapIndex).labelText());
                return;
            }
            // Announced before the lease: stamping a cell can block this thread for seconds, and a
            // notice sent afterwards would arrive once the wait it explains is over.
            broadcastPrepare(session, true);
            region = ArenaManager.allocate(server, session.id,
                    rules.get("e.sizeX"), rules.get("e.sizeY"), rules.get("e.sizeZ"),
                    ArenaMaps.byIndex(mapIndex),
                    rules.flag("f.protectTerrain"), rules.flag("f.allowBreakOwn"));
            if (region == null) {
                broadcastPrepare(session, false);
                fail(accepter, "pvpocket.msg.rules.arena_full");
                return;
            }
            session.arenaId = region.id;
            journal(session).append("ARENA_LEASED", "region", region.id,
                    "size", region.sizeX + "x" + region.sizeY + "x" + region.sizeZ,
                    "map", region.map.id,
                    "mapBlocks", region.templateBlockCount(),
                    "origin", region.minX + "," + region.minZ,
                    "protectTerrain", region.protectTerrain,
                    "allowBreakOwn", region.allowBreakOwn);
        }
        journal(session).append("ROSTER",
                "size", session.participants.size(),
                "teams", session.teamCount,
                "shape", shapeOf(session));
        journal(session).append("RULES_ACCEPTED",
                "revision", session.rulesRevision,
                "author", session.rulesAuthor,
                // Null when somebody leaving triggered the start rather than a click.
                "acceptedBy", accepter == null ? "(auto)" : accepter.getName().getString(),
                "digest", session.rulesDigest,
                // The ids that differ from the catalog defaults; the encoded string is not
                // readable by hand.
                "changed", changedFromDefaults(rules),
                // Values normalisation had to move on the way in. Empty in an ordinary match.
                "clamped", rules.clampedValues(),
                "rules", session.rules);
        inviteByTarget.values().removeIf(session.id::equals);
        closePanels(session);
        beginStart(session, 0);
    }

    /**
     * Freezes and starts the first round, once every participant is alive and online.
     *
     * <p>A dead or absent participant is waited for rather than refused. Only an offline
     * participant, or one who stays dead past {@link #START_WAIT_TICKS}, voids the series.
     *
     * <p>The wait happens before {@link #freeze}, so the snapshot describes the player the round
     * starts with. A death in 1.21.1 also discards the entity, so "dead" arrives as both
     * {@code !isAlive()} and {@code isRemoved()}.
     */
    private void beginStart(DuelSession session, int waited) {
        if (session.stage != SessionStage.NEGOTIATING && session.stage != SessionStage.VOTING
                && session.stage != SessionStage.RESULT) {
            return;   // voided or forfeited during the wait
        }
        // Three shapes, and only one is something the player can fix. A dead player is also removed
        // in 1.21.1, so isRemoved is tested last.
        Participant blocked = null;
        ServerPlayer blockedPlayer = null;
        String reason = null;
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player == null) {
                blocked = p;
                reason = "PARTICIPANT_OFFLINE";
            } else if (!player.isAlive()) {
                blocked = p;
                blockedPlayer = player;
                reason = "PARTICIPANT_DEAD";
            } else if (player.isRemoved()) {
                blocked = p;
                blockedPlayer = player;
                reason = "PARTICIPANT_REPLACED";   // alive, but the entity is already on its way out
            }
            if (blocked != null) {
                break;
            }
        }
        if (blocked == null) {
            try {
                freeze(session);
                transition(session, SessionStage.ROUND_READY);
                startRound(session);
            } catch (RuntimeException e) {
                PvPocket.LOGGER.error("Failed to start session {}", session.id, e);
                broadcastParticipants(session, PvLang.t("pvpocket.msg.duel.start_failed"));
                abort(session, "START_FAILED");
            }
            return;
        }
        if (waited == 0) {
            journal(session).append("START_WAITING", "player", blocked.name, "reason", reason);
            if (!"PARTICIPANT_DEAD".equals(reason)) {
                // Not something the player can act on, so it goes to the log, not to chat.
                PvPocket.LOGGER.warn("Session {} cannot start: {} is {} (online: {})", session.id,
                        blocked.name, reason, server.getPlayerList().getPlayers().stream()
                                .map(p -> PvCompat.profileName(p.getGameProfile())).toList());
            } else {
                // Said once: the player only needs to know the duel is waiting, not broken.
                blockedPlayer.displayClientMessage(
                        PvLang.t("pvpocket.msg.duel.waiting_respawn")
                                .withStyle(ChatFormatting.YELLOW), false);
            }
        }
        int limit = "PARTICIPANT_DEAD".equals(reason) ? START_WAIT_TICKS : ABSENT_GRACE_TICKS;
        if (waited >= limit) {
            journal(session).append("SESSION_ABORTED", "reason", reason,
                    "player", blocked.name, "waited", waited);
            // Say why: a voided match with no explanation reads as the mod losing the request.
            broadcastParticipants(session, PvLang.t("PARTICIPANT_DEAD".equals(reason)
                    ? "pvpocket.msg.duel.start_blocked_dead"
                    : "pvpocket.msg.duel.start_blocked_offline", blocked.name));
            abort(session, reason);
            return;
        }
        deferred.add(() -> beginStart(session, waited + 1));
    }

    /**
     * Gives up the series on behalf of a whole side.
     *
     * <p>A side that quits leaves the match; it does not end it. The members get their survival
     * state back, their card stays on the scoreboard greyed out, and the remaining sides play on.
     * Only when nobody is left to play does the series end, and then the last side standing takes
     * it. A two-side match therefore behaves as it always did: the one who quits hands the series
     * to the opponent.
     *
     * <p>On a side with more than one player the request only opens: every team-mate has to agree
     * before the side leaves, so one player cannot end a match the others are still in.
     */
    public void surrender(ServerPlayer player) {
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            fail(player, "pvpocket.msg.duel.not_in");
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null
                || (session.stage != SessionStage.ROUND_ACTIVE && session.stage != SessionStage.ROUND_READY)) {
            fail(player, "pvpocket.msg.duel.no_active");
            return;
        }
        if (session.pendingWinnerTeam >= 0) {
            // The round is already decided, so settling again would award a second round for one
            // death. Keyed on the winning side, so a 2v2 survivor can still surrender.
            fail(player, "pvpocket.msg.duel.round_settled");
            return;
        }
        if (!session.rulebook().allowSurrender()) {
            fail(player, "pvpocket.msg.duel.surrender_disabled");
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null) {
            fail(player, "pvpocket.msg.duel.not_in");
            return;
        }
        if (me.surrendered) {
            fail(player, "pvpocket.msg.duel.surrender_done");
            return;
        }
        if (session.contestingMembers(me.teamIndex()).size() <= 1) {
            // Nobody to ask, so the request is the decision.
            applySurrender(session, me.teamIndex(), player);
            return;
        }
        if (surrenderRequests.containsKey(session.id)) {
            fail(player, "pvpocket.msg.duel.surrender_pending");
            return;
        }
        SurrenderRequest request = new SurrenderRequest(me.uuid, me.teamIndex(),
                System.currentTimeMillis() + SURRENDER_WINDOW_MS);
        surrenderRequests.put(session.id, request);
        journal(session).append("SURRENDER_REQUESTED", "player", me.name,
                "team", me.teamIndex(), "members", session.contestingMembers(me.teamIndex()).size());
        player.displayClientMessage(PvLang.t("pvpocket.msg.duel.surrender_asking")
                .withStyle(ChatFormatting.YELLOW), false);
        for (Participant p : session.contestingMembers(me.teamIndex())) {
            if (p.uuid.equals(me.uuid)) {
                continue;
            }
            ServerPlayer member = online(p);
            if (member == null) {
                continue;
            }
            member.displayClientMessage(PvLang.t("pvpocket.msg.duel.surrender_asked", me.name)
                    .withStyle(ChatFormatting.YELLOW), false);
            sendSurrender(member, request, session, true);
        }
    }

    /**
     * Records one team-mate's answer to an open surrender request.
     *
     * <p>Any refusal cancels the request outright: the point of asking is that nobody is dragged
     * out of a match they want to keep playing. A missing answer is not a refusal, so the request
     * stays open until its window runs out.
     */
    public void onSurrenderAnswer(ServerPlayer player, boolean approve) {
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        SurrenderRequest request = surrenderRequests.get(session.id);
        if (request == null) {
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null || me.teamIndex() != request.team || me.uuid.equals(request.requester)) {
            return;
        }
        if (!approve) {
            surrenderRequests.remove(session.id);
            journal(session).append("SURRENDER_DECLINED", "player", me.name, "team", request.team);
            broadcastSurrenderClosed(session, request, "pvpocket.msg.duel.surrender_declined",
                    me.name);
            return;
        }
        if (!request.agreed.add(me.uuid)) {
            return;   // already counted; a double tap must not push a second prompt
        }
        journal(session).append("SURRENDER_AGREED", "player", me.name, "team", request.team,
                "agreed", request.agreed.size());
        if (surrenderSatisfied(session, request)) {
            approveSurrender(session, request);
            return;
        }
        for (Participant p : session.contestingMembers(request.team)) {
            ServerPlayer member = online(p);
            if (member != null && !p.uuid.equals(request.requester)) {
                sendSurrender(member, request, session, true);
            }
        }
    }

    /**
     * Expires, resolves or re-announces open surrender requests.
     *
     * <p>The window is wall-clock, like every other deadline in a session, so a stalled server does
     * not extend it. A request whose session left the round is dropped here rather than in each
     * caller that can end a round.
     */
    private void tickSurrender() {
        if (surrenderRequests.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        // A copy: approving a request removes it, and that happens inside this loop.
        for (Map.Entry<String, SurrenderRequest> entry
                : new ArrayList<>(surrenderRequests.entrySet())) {
            DuelSession session = sessions.get(entry.getKey());
            SurrenderRequest request = entry.getValue();
            if (session == null
                    || (session.stage != SessionStage.ROUND_ACTIVE
                    && session.stage != SessionStage.ROUND_READY)
                    || session.pendingWinnerTeam >= 0) {
                stale.add(entry.getKey());
                if (session != null) {
                    closeSurrenderPrompt(session, request);
                }
                continue;
            }
            if (now >= request.deadline) {
                stale.add(entry.getKey());
                broadcastSurrenderClosed(session, request, "pvpocket.msg.duel.surrender_expired", "");
                continue;
            }
            // A team-mate who left cannot be waited for; the side may already be complete.
            if (surrenderSatisfied(session, request)) {
                approveSurrender(session, request);
                continue;
            }
            int seconds = (int) ((request.deadline - now + 999L) / 1000L);
            if (seconds != request.announced) {
                request.announced = seconds;
                for (Participant p : session.contestingMembers(request.team)) {
                    ServerPlayer member = online(p);
                    if (member != null && !p.uuid.equals(request.requester)) {
                        sendSurrender(member, request, session, true);
                    }
                }
            }
        }
        for (String id : stale) {
            surrenderRequests.remove(id);
        }
    }

    /**
     * Whether every team-mate who is still here has agreed.
     *
     * <p>An offline team-mate is skipped rather than counted as a refusal: a disconnect already
     * ends the series on the next settle, so blocking on one would only strand the side.
     */
    private boolean surrenderSatisfied(DuelSession session, SurrenderRequest request) {
        for (Participant p : session.contestingMembers(request.team)) {
            if (p.uuid.equals(request.requester) || request.agreed.contains(p.uuid)) {
                continue;
            }
            if (online(p) != null) {
                return false;
            }
        }
        return true;
    }

    /** Carries out a request every team-mate agreed to. */
    private void approveSurrender(DuelSession session, SurrenderRequest request) {
        surrenderRequests.remove(session.id);
        journal(session).append("SURRENDER_APPROVED", "team", request.team,
                "agreed", request.agreed.size());
        ServerPlayer requester = server.getPlayerList().getPlayer(UUID.fromString(request.requester));
        broadcastSurrenderClosed(session, request, "pvpocket.msg.duel.surrender_agreed", "");
        applySurrender(session, request.team, requester);
    }

    /**
     * Takes one side out of the series and lets the rest play on.
     *
     * <p>The side keeps its roster, its score and its card on the scoreboard; the card greys out
     * because every member is now marked. Its players get their survival state back immediately
     * rather than at the end of the series.
     *
     * <p>The series only ends here when this was the second-to-last side: the one still contesting
     * takes it, and {@code forfeit} writes the end-of-series bookkeeping. With three or more sides
     * the remaining ones keep their rounds, and a round that just lost its last opposition is
     * settled in their favour.
     */
    private void applySurrender(DuelSession session, int team, ServerPlayer requester) {
        // Before the flags: the prompt has to reach the side that asked, which is about to stop
        // counting as contesting. A no-op when the caller already closed its own request.
        SurrenderRequest open = surrenderRequests.remove(session.id);
        if (open != null) {
            closeSurrenderPrompt(session, open);
        }
        for (Participant p : session.contestingMembers(team)) {
            p.surrendered = true;
        }
        List<Integer> left = session.contestingTeams();
        journal(session).append("TEAM_SURRENDERED", "team", team,
                "players", session.activeMembers(team).size(),
                "remaining", left.size());
        // Written now: the remaining sides play on, so the flag has to survive a restart without
        // waiting for the next settle to persist it.
        persist(session);

        MutableComponent line = PvLang.t("pvpocket.msg.duel.team_surrendered",
                teamName(session, team)).withStyle(ChatFormatting.RED);
        for (Participant p : session.participants) {
            ServerPlayer member = online(p);
            if (member != null) {
                member.displayClientMessage(line.copy(), false);
            }
        }
        // Ahead of the restores: these players leave the plot here, so it stops being theirs.
        for (Participant p : session.activeMembers(team)) {
            ServerPlayer member = online(p);
            if (member != null) {
                // A boundary countdown title left over from the moment they quit would hang there.
                member.connection.send(new ClientboundClearTitlesPacket(false));
            }
            if (p.snapshotFile != null && restoreParticipant(session, p)) {
                continue;
            }
            // No snapshot to put back, or a restore deferred to the respawn: the screen still has
            // to lose the match now, or the bar stays up over a player who has already left.
            if (member != null) {
                clearMatchScreen(member);
            }
        }
        broadcastHud(session, true);

        if (left.size() <= 1) {
            // Nobody left to play: the series goes to the side that stayed, or on score when the
            // last side walked out too.
            forfeit(session, requester, left.isEmpty() ? leaderTeam(session) : left.get(0));
            return;
        }
        if (session.stage == SessionStage.ROUND_ACTIVE) {
            // The round may have lost its last opposition rather than its last player.
            int winner = lastTeamStanding(session);
            if (winner >= 0) {
                settle(session, "SURRENDER", winner);
            }
        }
    }

    /** Tells the side a request ended, and clears the prompt on their clients. */
    private void broadcastSurrenderClosed(DuelSession session, SurrenderRequest request,
                                          String key, String name) {
        MutableComponent line = name.isEmpty() ? PvLang.t(key) : PvLang.t(key, name);
        for (Participant p : session.contestingMembers(request.team)) {
            ServerPlayer member = online(p);
            if (member == null) {
                continue;
            }
            member.displayClientMessage(line.copy().withStyle(ChatFormatting.YELLOW), false);
        }
        closeSurrenderPrompt(session, request);
    }

    /**
     * Clears the prompt on the side's clients without a chat line.
     *
     * <p>Used where the request ends because the round did, which the chat already announces.
     */
    private void closeSurrenderPrompt(DuelSession session, SurrenderRequest request) {
        for (Participant p : session.contestingMembers(request.team)) {
            ServerPlayer member = online(p);
            if (member != null) {
                sendSurrender(member, request, session, false);
            }
        }
    }

    /** One surrender prompt packet; {@code active=false} closes it. */
    private static void sendSurrender(ServerPlayer player, SurrenderRequest request,
                                      DuelSession session, boolean active) {
        int agreed = 0;
        int total = 0;
        for (Participant p : session.contestingMembers(request.team)) {
            total++;
            if (request.agreed.contains(p.uuid)) {
                agreed++;
            }
        }
        int seconds = active
                ? (int) Math.max(0L, (request.deadline - System.currentTimeMillis() + 999L) / 1000L)
                : 0;
        String requester = "";
        Participant author = session.participant(request.requester);
        if (author != null) {
            requester = author.name;
        }
        PvPocketNetworking.sendSurrender(player,
                new SurrenderStatePayload(active, requester, agreed, total, seconds));
    }

    /**
     * Ends the series on a surrender: {@code winnerTeam} takes it.
     *
     * <p>Reached only when the last other side walked out, since a surrender in a three- or
     * four-side match leaves the rest playing. Works from {@code ROUND_READY} as well as
     * {@code ROUND_ACTIVE}: a side that quits during the intermission should not have to start
     * another round first.
     *
     * <p>{@code player} may be null: a surrender agreed to by the team can be carried out after the
     * player who asked for it has left, and there is then nobody to name.
     * </p>
     */
    private void forfeit(DuelSession session, ServerPlayer player, int winnerTeam) {
        journal(session).append("SERIES_FORFEITED",
                "player", player == null ? "-" : player.getName().getString(),
                "winnerTeam", winnerTeam,
                "score", scoreLine(session),
                "round", session.round,
                "stage", session.stage.name());
        if (session.stage == SessionStage.ROUND_ACTIVE) {
            // The round is logged with no winner; the series has one.
            journal(session).append("ROUND_ENDED",
                    "round", session.round, "reason", "SURRENDER", "winner", null);
            // Logged with no winner, since recordSeries refuses a series with no settled round.
            long startedAt = roundStartedAt.getOrDefault(session.id, System.currentTimeMillis());
            session.roundLog.add(new DuelSession.RoundLog(-1, "SURRENDER",
                    Math.max(0L, System.currentTimeMillis() - startedAt)));
            session.pendingWinner = null;
            session.pendingReason = null;
            roundStartedAt.remove(session.id);
            clearBoundaryExits(session);
            transition(session, SessionStage.ROUND_SETTLED);
        }
        roundReadyAt.remove(session.id);
        session.seriesEndReason = "SURRENDER";
        // No per-player line here: the surrender was announced to the whole session by the caller.
        finishSeries(session, winnerTeam);
    }

    // ------------------------------------------------------------------ rule plumbing

    /** Rule ids whose value differs from the catalog default, comma-separated; "-" when none do. */
    private static String changedFromDefaults(DuelRules rules) {
        List<String> ids = rules.differences(DuelRules.defaults());
        return ids.isEmpty() ? "-" : String.join(",", ids);
    }

    /** Copies a rule set onto the session and refreshes the derived fields plus the fingerprint. */
    private static void applyRulesToSession(DuelSession session, DuelRules rules) {
        session.mode = rules.get("c.mode") == 1 ? CombatMode.LEGACY_1_8.id() : CombatMode.MODERN.id();
        // The two series numbers are clamped against each other here, the one place every path
        // goes through: "win 4 of at most 3" is a state the sliders can sit in.
        session.maxRounds = Math.max(1, rules.get("a.maxRounds"));
        session.winsNeeded = Math.max(1, Math.min(session.maxRounds, rules.get("a.winsNeeded")));
        session.rules = rules.encode();
        session.rulesDigest = rules.digest();
    }

    /** True once the series is decided: somebody reached the target, or the round cap is hit. */
    private static boolean seriesDecided(DuelSession session) {
        if (session.round >= Math.max(1, session.maxRounds)) {
            return true;
        }
        for (int t = 0; t < Math.max(1, session.teamCount); t++) {
            if (session.wins(t) >= Math.max(1, session.winsNeeded)) {
                return true;
            }
        }
        return false;
    }

    private DuelSession findOpenSession(UUID uuid) {
        String id = uuid.toString();
        for (DuelSession session : sessions.values()) {
            // Every pre-freeze stage is "open": the draft, the branch window and the ballot all
            // want the player to be able to reopen their panel.
            if (session.stage.isPreFreeze()) {
                // Only a session this player is still in: a refusal takes them off the roster.
                Participant p = session.participant(id);
                if (p != null && !p.rejected) {
                    return session;
                }
            }
        }
        return null;
    }

    /**
     * Everybody on the other side, comma-separated, or an empty string when there is nobody else.
     *
     * <p>Empty rather than a word: the word for "opponent" is language-specific, and the client
     * substitutes its own term when the field is blank.
     */
    private static String otherName(DuelSession session, String uuid) {
        StringBuilder sb = new StringBuilder();
        for (Participant p : session.opponentsOf(uuid)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.name);
        }
        return sb.toString();
    }

    private static String shortDigest(DuelSession session) {
        return session.rulesDigest == null ? "--------" : session.rulesDigest;
    }

    /** Drops any rule panel still open for this session on either side. */
    private void closePanels(DuelSession session) {
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player != null) {
                PvPocketNetworking.closeRules(player, session.id);
            }
        }
    }

    private static MutableComponent modeLabel(CombatMode mode) {
        return PvLang.t(mode == CombatMode.LEGACY_1_8
                ? "pvpocket.mode.legacy" : "pvpocket.mode.modern");
    }

    /**
     * Opens the loadout editor, either for an open session or for the player's own saved loadout.
     *
     * <p>Reachable from either stage: a proposer configures before sending, a reviewer before
     * accepting.
     *
     * <p>With no open session it opens the personal loadout, marked by an empty session id, which
     * tells the submit path to write to {@link LoadoutBook} rather than a participant.
     */
    public void onLoadoutRequest(ServerPlayer player) {
        DuelSession session = findOpenSession(player.getUUID());
        if (session == null) {
            Loadout personal = loadouts.get(player.getUUID());
            personal.autoAssignSlots();
            PvPocketNetworking.openLoadout(player, new com.leipishu.pvpocket.network.LoadoutOpenPayload(
                    "", personal.encode(), RulesOpenPayload.ROLE_PROPOSER, ""));
            return;
        }
        Participant p = session.participant(player.getStringUUID());
        if (p == null) {
            return;
        }
        if (session.stage == SessionStage.VOTING) {
            // The ballot was built from the rows on the table when the window closed, so editing
            // a kit now would change nothing that is being voted on.
            fail(player, "pvpocket.msg.loadout.voting");
            return;
        }
        if (answered(session, p)) {
            // The answer is in; the kit it carried is what gets voted on.
            fail(player, "pvpocket.msg.rules.already_answered");
            return;
        }
        int role = session.stage == SessionStage.DRAFT
                ? RulesOpenPayload.ROLE_PROPOSER
                : RulesOpenPayload.ROLE_REVIEWER;
        // A player with a branch edits their own alternative; everyone else edits main. Either way
        // the panel shows the rows numbered, so what comes back can be matched against main.
        RuleBranch branch = session.branchOf(player.getStringUUID());
        String encoded = branch != null ? branch.loadout : mainLoadout(session).encode();
        PvPocketNetworking.openLoadout(player, new com.leipishu.pvpocket.network.LoadoutOpenPayload(
                session.id, encoded, role, otherName(session, player.getStringUUID())));
    }

    /**
     * Stores a loadout submitted by a client, after re-validating it.
     *
     * <p>The client runs the same validation, but this is the pass that decides whether a loadout
     * becomes items. A rejected loadout leaves the previous one untouched.
     *
     * <p>An empty session id means the personal loadout: it is saved to the book and mirrored onto
     * the participant if the session has not started. A non-empty id writes the book as well.
     */
    public void onLoadoutSubmit(ServerPlayer player, String sessionId, String encoded) {
        if (sessionId == null || sessionId.isEmpty()) {
            submitPersonal(player, encoded);
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage.isTerminal()) {
            // Recorded, not just said in chat, so a refused save leaves a trace.
            rejectSubmit(session, player, "session_gone", sessionId);
            fail(player, "pvpocket.msg.loadout.ended");
            return;
        }
        if (!session.isParticipant(player.getStringUUID())) {
            rejectSubmit(session, player, "not_participant", sessionId);
            fail(player, "pvpocket.msg.loadout.not_participant");
            return;
        }
        if (!session.stage.isPreFreeze()) {
            // The loadout was agreed before the first round; mid-series it cannot change.
            rejectSubmit(session, player, "frozen", session.stage.name());
            fail(player, "pvpocket.msg.loadout.frozen");
            return;
        }
        Loadout loadout = Loadout.decode(encoded);
        List<Component> problems = loadout.validate();
        if (!problems.isEmpty()) {
            rejectSubmit(session, player, "invalid", problems.get(0).getString());
            fail(player, "pvpocket.msg.loadout.invalid", problems.get(0));
            return;
        }
        Participant p = session.participant(player.getStringUUID());
        if (p == null) {
            rejectSubmit(session, player, "no_participant_record", player.getStringUUID());
            return;
        }
        loadouts.put(player.getUUID(), loadout);
        if (session.stage == SessionStage.NEGOTIATING) {
            if (answered(session, p)) {
                rejectSubmit(session, player, "answered", player.getStringUUID());
                fail(player, "pvpocket.msg.rules.already_answered");
                return;
            }
            // A save inside the window is this player's branch, not a rewrite of everybody's kit:
            // main is what the roster votes on, and one player cannot change it unilaterally.
            journal(session).append("LOADOUT_BRANCHED",
                    "player", p.name,
                    "digest", loadout.digest(),
                    "items", loadout.size());
            persist(session);
            player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.saved_branch",
                            loadout.size())
                    .withStyle(ChatFormatting.GREEN), false);
            fileBranch(session, p, DuelRules.decode(session.rules), loadout, null, false);
            return;
        }
        // Draft: the proposer is still authoring main, so a save writes the set directly.
        adoptSharedLoadout(session, loadout);
        journal(session).append("LOADOUT_SAVED",
                "player", p.name,
                "digest", p.loadoutDigest,
                "items", loadout.size(),
                "summary", loadout.name,
                "scope", "match");
        persist(session);
        // The stacks the players carry were built from the previous loadout, so the locks are
        // re-applied to everybody.
        for (Participant target : session.participants) {
            if (target.rejected) {
                continue;
            }
            ServerPlayer holder = online(target);
            if (holder != null) {
                LoadoutLock.restamp(holder, loadout);
            }
        }
        PvPocket.LOGGER.info("Loadout saved for {} in {} ({} items, digest {}, match-wide)",
                p.name, session.id, loadout.size(), p.loadoutDigest);
        player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.saved",
                        loadout.size(), p.loadoutDigest)
                .withStyle(ChatFormatting.GREEN), false);
        int players = liveCount(session);
        if (players > 1) {
            // Only worth saying once there is somebody else: in a draft this is the proposer alone.
            player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.saved_match", players)
                    .withStyle(ChatFormatting.GOLD), false);
        }
    }

    private void broadcastParticipants(DuelSession session, Component line) {
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player != null) {
                player.displayClientMessage(line.copy().withStyle(ChatFormatting.GOLD), false);
            }
        }
    }

    /** Saves a loadout to the player's book, and mirrors it onto any unstarted session. */
    private void submitPersonal(ServerPlayer player, String encoded) {
        Loadout loadout = Loadout.decode(encoded);
        List<Component> problems = loadout.validate();
        if (!problems.isEmpty()) {
            fail(player, "pvpocket.msg.loadout.invalid", problems.get(0));
            return;
        }
        loadout.autoAssignSlots();
        loadouts.put(player.getUUID(), loadout);

        MutableComponent mirrored = PvLang.t("pvpocket.msg.loadout.personal_only");
        DuelSession session = findOpenSession(player.getUUID());
        if (session != null && session.stage.isPreFreeze()) {
            Participant p = session.participant(player.getStringUUID());
            if (p != null) {
                if (session.stage == SessionStage.DRAFT) {
                    // The same one-set-per-match write as onLoadoutSubmit.
                    adoptSharedLoadout(session, loadout);
                    journal(session).append("LOADOUT_SAVED",
                            "player", p.name,
                            "digest", p.loadoutDigest,
                            "items", loadout.size(),
                            "summary", loadout.name,
                            "scope", "personal");
                    persist(session);
                    for (Participant q : session.participants) {
                        if (q.rejected) {
                            continue;
                        }
                        ServerPlayer holder = online(q);
                        if (holder != null) {
                            LoadoutLock.restamp(holder, loadout);
                        }
                    }
                    mirrored = PvLang.t("pvpocket.msg.loadout.mirrored");
                } else if (session.stage == SessionStage.NEGOTIATING) {
                    // Inside the window this is a branch like any other save, so it is voted on
                    // rather than silently replacing what everybody is looking at.
                    journal(session).append("LOADOUT_BRANCHED",
                            "player", p.name,
                            "digest", loadout.digest(),
                            "items", loadout.size(),
                            "scope", "personal");
                    persist(session);
                    fileBranch(session, p, DuelRules.decode(session.rules), loadout, null, false);
                    mirrored = PvLang.t("pvpocket.msg.loadout.mirrored_branch");
                } else {
                    mirrored = PvLang.t("pvpocket.msg.loadout.mirrored_voting");
                }
            }
        }
        // Restamped against the loadout that will be granted, not the one just saved.
        LoadoutLock.restamp(player, loadoutFor(player));
        PvPocket.LOGGER.info("Personal loadout saved for {} ({} items, digest {})",
                player.getName().getString(), loadout.size(), loadout.digest());
        player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.saved_personal",
                        loadout.size(), loadout.digest(), mirrored)
                .withStyle(ChatFormatting.GREEN), false);
    }

    /**
     * Clears a player's saved loadout, and mirrors the fallback onto any negotiation in progress.
     *
     * <p>Lives here so the command cannot become a second way to edit a loadout without telling the
     * session.
     */
    public void resetPersonalLoadout(ServerPlayer player) {
        loadouts.clear(player.getUUID());
        DuelSession session = findOpenSession(player.getUUID());
        if (session == null || !session.stage.isPreFreeze()) {
            // The cleared book describes nothing held, so the locks come off too.
            LoadoutLock.restamp(player, loadoutFor(player));
            player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.reset_personal")
                    .withStyle(ChatFormatting.GREEN), false);
            return;
        }
        Participant p = session.participant(player.getStringUUID());
        if (p == null) {
            return;
        }
        Loadout fallback = Loadout.defaultDuel();
        if (session.stage == SessionStage.VOTING) {
            // The ballot is already built; a reset now would not be part of it.
            fail(player, "pvpocket.msg.loadout.voting");
            return;
        }
        if (session.stage == SessionStage.NEGOTIATING) {
            // Same rule as a save inside the window: the fallback becomes this player's branch.
            journal(session).append("LOADOUT_RESET", "player", p.name,
                    "digest", fallback.digest(), "scope", "branch");
            persist(session);
            fileBranch(session, p, DuelRules.decode(session.rules), fallback, null, false);
            return;
        }
        // Everyone, for the same reason a save writes everyone: the match has one set.
        adoptSharedLoadout(session, fallback);
        journal(session).append("LOADOUT_RESET", "player", p.name, "digest", p.loadoutDigest);
        persist(session);
        for (Participant q : session.participants) {
            if (q.rejected) {
                continue;
            }
            ServerPlayer holder = online(q);
            if (holder != null) {
                LoadoutLock.restamp(holder, fallback);
            }
        }
        player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.reset_personal_preset",
                        fallback.size())
                .withStyle(ChatFormatting.GREEN), false);
    }

    /**
     * The loadout this player would enter with right now.
     *
     * <p>Falls back to the player's saved loadout rather than returning null, so the diagnostic
     * command and a first duel show the same thing.
     */
    public Loadout loadoutFor(ServerPlayer player) {
        String uuid = player.getStringUUID();
        for (DuelSession session : sessions.values()) {
            if (session.stage.isTerminal()) {
                continue;
            }
            Participant p = session.participant(uuid);
            if (p != null && p.loadout != null) {
                return Loadout.decode(p.loadout);
            }
        }
        return loadouts.get(player.getUUID());
    }

    /** The book itself, so the command layer can reset a saved loadout. */
    public LoadoutBook loadoutBook() {
        return loadouts;
    }

    public void status(ServerPlayer player) {
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            player.displayClientMessage(PvLang.t("pvpocket.msg.duel.not_in")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        // Every opponent, not just one: "/pvpocket status" is what people paste into chat.
        String others = otherName(session, player.getStringUUID());
        player.displayClientMessage(PvLang.t("pvpocket.msg.status.line",
                        shortId(session.id),
                        PvLang.t(session.stage.labelKey()),
                        session.round, session.maxRounds,
                        scoreLine(session),
                        others.isEmpty() ? PvLang.t("pvpocket.value.none") : others)
                .withStyle(ChatFormatting.AQUA), false);
        player.displayClientMessage(PvLang.t("pvpocket.msg.status.rules",
                        session.rulesRevision, shortDigest(session),
                        DuelRules.decode(session.rules).summaryText())
                .withStyle(ChatFormatting.DARK_GRAY), false);
        if (me != null) {
            PvPocket.LOGGER.debug("Status requested by {}", me.name);
        }
    }

    // ------------------------------------------------------------------ spectating

    /**
     * Single entry point for every spectator intent that arrives from a client.
     *
     * <p>The action byte is re-validated here rather than trusted: an unknown action is ignored
     * instead of throwing.
     */
    public void onSpectateRequest(ServerPlayer player, SpectateRequestPayload request) {
        switch (request.action()) {
            case SpectateRequestPayload.ACTION_LIST -> spectators.openList(player);
            case SpectateRequestPayload.ACTION_JOIN -> spectators.join(player, request.sessionId());
            case SpectateRequestPayload.ACTION_LEAVE -> {
                if (!spectators.leave(player, "REQUEST", true)) {
                    player.displayClientMessage(PvLang.t("pvpocket.msg.spectate.not_watching")
                            .withStyle(ChatFormatting.GRAY), false);
                }
            }
            case SpectateRequestPayload.ACTION_CYCLE -> spectators.cycle(player);
            case SpectateRequestPayload.ACTION_DETACH -> spectators.detach(player);
            default -> PvPocket.LOGGER.debug("Ignoring unknown spectate action {} from {}",
                    request.action(), player.getName().getString());
        }
    }

    /** Command path for {@code /pvpocket list}. */
    public void listMatches(ServerPlayer player) {
        spectators.openList(player);
    }

    /** Command path for {@code /pvpocket spectate leave}. */
    public void stopSpectating(ServerPlayer player) {
        if (!spectators.leave(player, "COMMAND", true)) {
            player.displayClientMessage(PvLang.t("pvpocket.msg.spectate.not_watching")
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    /** Command path for {@code /pvpocket spectate <session>}; prefix match, for typing. */
    public void spectate(ServerPlayer player, String sessionPrefix) {
        DuelSession match = null;
        for (DuelSession session : sessions.values()) {
            if (session.id.startsWith(sessionPrefix)) {
                match = session;
                break;
            }
        }
        if (match == null) {
            player.displayClientMessage(PvLang.t("pvpocket.msg.spectate.no_match", sessionPrefix)
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        spectators.join(player, match.id);
    }

    /** Diagnostics for {@code /pvpocket list} from the console. */
    public List<String> spectatorSummary() {
        return new ArrayList<>(spectators.describe());
    }

    // ------------------------------------------------------------------ internals

    private void freeze(DuelSession session) {
        // One set for the whole match. A preset set is announced once before the first round.
        Loadout shared = sharedLoadout(session);
        journal(session).append("LOADOUT_FROZEN",
                "digest", shared.digest(), "items", shared.size(),
                "locked", shared.lockedCount(), "players", liveCount(session));
        if (shared.encode().equals(Loadout.defaultDuel().encode())) {
            journal(session).append("LOADOUT_DEFAULT_PRESET", "digest", shared.digest());
            for (Participant q : session.participants) {
                ServerPlayer told = online(q);
                if (told != null) {
                    told.displayClientMessage(PvLang.t("pvpocket.msg.loadout.match_default")
                            .withStyle(ChatFormatting.GRAY), false);
                }
            }
        }
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player == null) {
                throw new IllegalStateException("participant offline: " + p.name);
            }
            if (p.snapshotFile == null) {
                PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
                Path file = storage.snapshotFile(session.id, p.uuid);
                try {
                    snapshot.writeTo(file);
                } catch (IOException e) {
                    throw new IllegalStateException("cannot write snapshot for " + p.name, e);
                }
                p.snapshotFile = file.getFileName().toString();
                p.fingerprint = snapshot.fingerprint();
                journal(session).append("SNAPSHOT_WRITTEN",
                        "player", p.name, "fingerprint", p.fingerprint);
            } else {
                // Written when the result page sent them to the lobby. Capturing again here would
                // record the lobby as the place to restore them to.
                journal(session).append("SNAPSHOT_REUSED",
                        "player", p.name, "fingerprint", p.fingerprint);
            }
            sessionByPlayer.put(player.getUUID(), session.id);
        }
        transition(session, SessionStage.FROZEN);
    }

    private void startRound(DuelSession session) {
        session.round++;
        CombatMode mode = CombatMode.byId(session.mode);
        DuelRules rules = DuelRules.decode(session.rules);
        int maxHealth = rules.get("c.maxHealth");
        ArenaRegion region = ArenaManager.region(session.id);
        if (region == null) {
            throw new IllegalStateException("no arena plot leased for session " + session.id);
        }
        var arenaWorld = ArenaManager.world(server);
        applyEnvironment(arenaWorld, rules);
        clearBoundaryExits(session);
        int prepare = Math.max(0, rules.get("a.countdown"));
        for (Participant p : session.participants) {
            if (p.surrendered || p.abandoned) {
                // A side that left the series is not teleported in, armed, or counted as absent:
                // its members are back in survival and have no part in this round.
                p.eliminated = true;
                continue;
            }
            ServerPlayer player = online(p);
            // Away: a dropped connection, or a prompt not answered yet. Out of this round; the
            // next start can take them back. Only reachable when every side has somebody present.
            if (player == null || p.goneSince > 0) {
                p.eliminated = true;
                if (p.goneSince == 0) {
                    p.goneSince = System.currentTimeMillis();
                }
                journal(session).append("ROUND_START_ABSENT", "player", p.name,
                        "online", player != null, "round", session.round);
                continue;
            }
            p.eliminated = false;
            p.goneSince = 0;
            if (!player.isAlive()) {
                // Reachable after a crash or a stop taken on the death screen. Reviving keeps every
                // round starting from the same state.
                journal(session).append("ROUND_START_REVIVE", "player", p.name,
                        "health", player.getHealth(), "removed", player.isRemoved());
                player.setHealth(player.getMaxHealth());
                if (!player.isAlive()) {
                    throw new IllegalStateException("cannot revive participant: " + p.name);
                }
            }
            if (!ArenaManager.teleportToSpawn(player, region, p.teamIndex(),
                    p.member, Math.max(1, session.teamMembers(p.teamIndex()).size()))) {
                throw new IllegalStateException("arena world unavailable");
            }
            // Goes through Sandbox, so the mode rule does not refuse the round-start switch.
            Sandbox.changeGameMode(player, GameType.SURVIVAL);
            // The snapshot restores attributes wholesale, so no separate undo is needed.
            var health = player.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(maxHealth);
                // Read back immediately, so a modifier from another mod shows up in the journal.
                if (Math.abs(player.getMaxHealth() - maxHealth) > 0.01F) {
                    journal(session).append("ROUND_START_HEALTH_MISMATCH", "player", p.name,
                            "wanted", maxHealth, "actual", player.getMaxHealth());
                }
            } else {
                journal(session).append("ROUND_START_NO_HEALTH_ATTRIBUTE", "player", p.name);
            }
            // Per round rather than at freeze, so a reloaded session cannot keep the old value.
            var knockback = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (knockback != null) {
                knockback.setBaseValue(session.rulebook().knockbackResistance());
            }
            // Every round starts from the same state: health and effects are not items.
            resetRoundState(player, maxHealth);
            CombatState.setMode(player.getUUID(), mode);
            // The item-use gate travels with the mode; only the server knows the rules.
            applyUseGate(session, player);
            // Granted here, not at freeze(): the snapshot must hold the survival inventory.
            // grant() clears the inventory first, so nothing from the previous round survives.
            Loadout loadout = sharedLoadout(session);
            // 1.8 has no shields, and a surviving shield takes the right-click from the sword
            // block. Dropped here rather than in the editor.
            if (mode == CombatMode.LEGACY_1_8) {
                int shields = loadout.shieldCount();
                if (shields > 0) {
                    loadout = loadout.withoutShields();
                    journal(session).append("LOADOUT_SHIELD_DROPPED", "player", p.name,
                            "count", shields, "round", session.round);
                }
            }
            int granted = LoadoutBuilder.grant(player, loadout, p.teamIndex());
            p.loadoutApplied = true;
            // The digest of the loadout that became items, not the recorded one.
            journal(session).append("LOADOUT_APPLIED", "player", p.name,
                    "digest", loadout.digest(), "items", granted,
                    "locked", loadout.lockedCount(), "round", session.round);
            // The client mirrors the rule set, so it is told the mode before the first hit.
            PvPocketNetworking.sendState(player);
            if (prepare > 0) {
                // Enforced rather than announced: invulnerability covers both directions.
                player.setInvulnerable(true);
            }
        }
        // Dismiss the preparing screen once the teleport is done and clients load the arena, not at
        // activation: a preparation countdown keeps the round from going live for several seconds.
        broadcastPrepare(session, false);
        if (prepare > 0) {
            journal(session).append("ROUND_PREPARING", "round", session.round, "seconds", prepare);
            prepareDeadlines.put(session.id,
                    new Prepare(System.currentTimeMillis() + prepare * 1000L, prepare));
            // The ring stops players wandering out before the round goes live.
            int wall = ArenaManager.buildPrepareWall(arenaWorld, region, session.teamCount);
            journal(session).append("PREPARE_WALL_BUILT", "blocks", wall,
                    "screens", region.screenCount(),
                    "top", ArenaManager.prepareWallTop(region));
            broadcastHud(session, true);
            broadcastBounds(session, true);
            for (Participant p : session.participants) {
                if (p.surrendered || p.abandoned) {
                    continue;
                }
                ServerPlayer player = online(p);
                if (player != null) {
                    player.displayClientMessage(PvLang.t("pvpocket.msg.round.prepare", prepare)
                            .withStyle(ChatFormatting.YELLOW), false);
                }
            }
            return;
        }
        activateRound(session);
    }

    /**
     * The round goes live: drop the preparation shield, start the clock, tell both sides.
     *
     * <p>Split from {@link #startRound} so everything that measures the round starts when the fight
     * can begin, not when the players were teleported in.
     */
    private void activateRound(DuelSession session) {
        prepareDeadlines.remove(session.id);
        // Torn down before the players are released from invulnerability.
        ArenaRegion sealed = ArenaManager.region(session.id);
        int removed = ArenaManager.removePrepareWall(ArenaManager.world(server), sealed);
        if (removed > 0) {
            journal(session).append("PREPARE_WALL_REMOVED", "blocks", removed);
        }
        for (Participant p : session.participants) {
            if (p.surrendered || p.abandoned) {
                continue;
            }
            ServerPlayer player = online(p);
            if (player == null) {
                // Dropped during the preparation window, so they never fought. Out of this round
                // only; the next start can take them back.
                p.eliminated = true;
                if (p.goneSince == 0) {
                    p.goneSince = System.currentTimeMillis();
                }
                journal(session).append("ROUND_START_ABSENT", "player", p.name,
                        "online", false, "round", session.round);
                continue;
            }
            player.setInvulnerable(false);
        }
        CombatMode mode = CombatMode.byId(session.mode);
        DuelRules rules = DuelRules.decode(session.rules);
        ArenaRegion region = ArenaManager.region(session.id);
        journal(session).append("ROUND_STARTED", "round", session.round,
                "digest", session.rulesDigest,
                // The boundary rule in force, spelled out; the digest cannot be read back.
                "boundary", rules.get("e.boundary") == 0 ? "push_back" : "countdown",
                // The health cap, the one rule whose effect is a number on the HUD.
                "maxHealth", rules.get("c.maxHealth"),
                // The round clock runs whenever a limit is set.
                "roundLimit", session.rulebook().roundLimitSeconds() == 0
                        ? "off" : session.rulebook().roundLimitSeconds() + "s",
                "region", region == null ? "-" : region.id,
                "teams", session.teamCount,
                "shape", shapeOf(session),
                // Every pad the match used, so the journal can say where each player spawned.
                "spawns", region == null ? "-" : spawnsOf(region));
        roundStartedAt.put(session.id, System.currentTimeMillis());
        transition(session, SessionStage.ROUND_ACTIVE);
        // A round that starts with fewer than two sides standing is not a round: settle it now,
        // because the clock would otherwise run on a fight nobody can have.
        List<Integer> standing = session.survivingTeams();
        if (standing.size() < 2) {
            settle(session, "DISCONNECT", standing.isEmpty() ? leaderTeam(session) : standing.get(0));
            return;
        }
        broadcastHud(session, true);
        // Re-pushed, so the client drops the white preparation curtain for the open arena.
        broadcastBounds(session, true);
        for (Participant p : session.participants) {
            if (p.surrendered || p.abandoned) {
                continue;
            }
            ServerPlayer player = online(p);
            if (player != null) {
                // Three pieces: the "first to N" clause only exists for a series.
                MutableComponent line = PvLang.t("pvpocket.msg.round.started",
                        session.round, session.maxRounds,
                        modeLabel(mode), rules.summaryText());
                if (session.maxRounds > 1) {
                    line.append(PvLang.t("pvpocket.msg.round.first_to_win", session.winsNeeded));
                }
                line.append(PvLang.t("pvpocket.msg.round.surrender_hint"));
                player.displayClientMessage(line.withStyle(ChatFormatting.GREEN), false);
            }
        }
    }

    /**
     * Drives the preparation countdown, one chat line per whole second.
     *
     * <p>Chat rather than the action bar, since this is a message the player has several seconds to
     * read. The boundary countdown uses a title instead, because it interrupts a fight.
     */
    private void tickPrepare() {
        if (prepareDeadlines.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> due = new ArrayList<>();
        for (Map.Entry<String, Prepare> entry : prepareDeadlines.entrySet()) {
            String id = entry.getKey();
            DuelSession session = sessions.get(id);
            if (session == null) {
                due.add(id);
                continue;
            }
            long remaining = entry.getValue().at() - now;
            if (remaining <= 0) {
                due.add(id);
                continue;
            }
            int second = (int) Math.ceil(remaining / 1000.0D);
            if (second != entry.getValue().announced()) {
                prepareDeadlines.put(id, new Prepare(entry.getValue().at(), second));
                for (Participant p : session.participants) {
                    if (p.surrendered || p.abandoned) {
                        continue;
                    }
                    ServerPlayer player = online(p);
                    if (player != null) {
                        player.displayClientMessage(PvLang.t("pvpocket.msg.round.prepare_tick", second)
                                .withStyle(ChatFormatting.YELLOW), false);
                    }
                }
            }
        }
        for (String id : due) {
            prepareDeadlines.remove(id);
            countdownPushed.remove(id);
            DuelSession session = sessions.get(id);
            if (session == null || session.stage != SessionStage.ROUND_READY) {
                continue;
            }
            activateRound(session);
        }
    }

    /**
     * Applies the round's sky: fixed time of day and weather.
     *
     * <p>Both are per-world properties, so two matches side by side cannot have different weather.
     * Last one to start wins, which is what the rule hint says.
     *
     * <p>The daylight cycle is switched off for a fixed time and back on for the natural setting.
     */
    private void applyEnvironment(ServerLevel world, DuelRules rules) {
        if (world == null) {
            return;
        }
        int time = rules.get("e.timeOfDay");
        // 0 means natural; the rest are fixed ticks. 1000 is shortly after sunrise, 6000 noon,
        // 12000 dusk and 18000 midnight.
        boolean frozen = time > 0;
        PvCompat.setDaylightCycle(world, !frozen);
        if (frozen) {
            PvCompat.setDayTime(world, switch (time) {
                case 2 -> 6000L;
                case 3 -> 12000L;
                case 4 -> 18000L;
                default -> 1000L;
            });
        }
        int weather = rules.get("e.weather");
        if (weather == 1) {
            PvCompat.setWeather(world, 0, 12_000, true, false);
        } else if (weather == 2) {
            PvCompat.setWeather(world, 0, 12_000, true, true);
        } else {
            PvCompat.setWeather(world, 12_000, 0, false, false);
        }
    }

    /**
     * Puts the arena dimension's clock and weather back once nothing is running in it.
     *
     * <p>{@code e.timeOfDay} and {@code e.weather} are per-world properties, so a fixed time is
     * applied by switching {@code DO_DAYLIGHT_CYCLE} off, and this is what switches it back on.
     *
     * <p>Guarded on "nothing is running", since two matches share one clock.
     */
    private void restoreEnvironmentWhenIdle(ServerLevel world) {
        if (world == null) {
            return;
        }
        for (DuelSession other : sessions.values()) {
            if (other.stage == SessionStage.ROUND_READY
                    || other.stage == SessionStage.ROUND_ACTIVE
                    || other.stage == SessionStage.ROUND_SETTLED
                    || other.stage == SessionStage.FROZEN) {
                return;
            }
        }
        PvCompat.setDaylightCycle(world, true);
        PvCompat.setWeather(world, 12_000, 0, false, false);
    }

    /**
     * Takes one player out of the round by killing them.
     *
     * <p>A forfeit has to look like a death. The fall beat, the particle burst and the hand-off to
     * spectator mode all hang off the death event, so a player who is only marked as eliminated
     * keeps standing there, alive, wearing a grey head.
     *
     * @param reason kept for the round history, so it does not read as a kill
     */
    private void forfeit(DuelSession session, Participant p, String reason) {
        if (session.stage != SessionStage.ROUND_ACTIVE || p.eliminated) {
            return;
        }
        ServerPlayer player = online(p);
        if (player == null || !player.isAlive()) {
            // No body to kill, so the bookkeeping is the whole removal.
            eliminate(session, p, reason);
            return;
        }
        forfeitReasons.put(player.getUUID(), reason);
        // Nobody's kill: a hit taken a moment earlier must not earn the credit for this death.
        player.setLastHurtByMob(null);
        PvCompat.kill(player);
        if (!p.eliminated) {
            // Damage refused, which a live round should never allow: still take the player out.
            forfeitReasons.remove(player.getUUID());
            eliminate(session, p, reason);
        }
    }

    /**
     * Takes one player out of the round.
     *
     * <p>The round ends when the last member of a side is gone, not when the first player dies. A
     * 1v1 ends on the first death and a 3v3 continues while two team-mates stand.
     */
    private void eliminate(DuelSession session, Participant p, String reason) {
        if (session.stage != SessionStage.ROUND_ACTIVE || p.eliminated) {
            return;
        }
        p.eliminated = true;
        int winner = lastTeamStanding(session);
        journal(session).append("PLAYER_ELIMINATED",
                "player", p.name, "reason", reason,
                "alive", survivorsOf(session),
                "winnerTeam", winner);
        if (winner >= 0) {
            settle(session, reason, winner);
        } else {
            persist(session);
        }
    }

    /** The only side still standing, or {@code -1} while two or more are. */
    private static int lastTeamStanding(DuelSession session) {
        List<Integer> alive = session.survivingTeams();
        return alive.size() == 1 ? alive.get(0) : -1;
    }

    private void settle(DuelSession session, String reason, int winnerTeam) {
        if (session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        journal(session).append("ROUND_ENDED",
                "round", session.round, "reason", reason, "winnerTeam", winnerTeam);
        // Logged before the start time is dropped, and persisted with the session.
        long startedAt = roundStartedAt.getOrDefault(session.id, System.currentTimeMillis());
        session.roundLog.add(new DuelSession.RoundLog(winnerTeam, reason,
                Math.max(0L, System.currentTimeMillis() - startedAt)));
        if (winnerTeam >= 0) {
            session.addWin(winnerTeam);
        }
        transition(session, SessionStage.ROUND_SETTLED);
        session.pendingWinner = null;
        session.pendingReason = null;
        session.pendingWinnerTeam = -1;
        // Cleared here: the "seen on the plot" memory belongs to the round that just ended.
        roundStartedAt.remove(session.id);
        clearBoundaryExits(session);
        // A surrender asked for in the round that just ended has nothing left to forfeit.
        SurrenderRequest request = surrenderRequests.remove(session.id);
        if (request != null) {
            closeSurrenderPrompt(session, request);
        }
        prepareDeadlines.remove(session.id);
        countdownPushed.remove(session.id);

        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);

        int seriesWinner = seriesWinnerTeam(session);
        boolean decided = seriesDecided(session);
        // An offline participant does not cut the series here: the round boundary is where they
        // can come back, and startDueRounds holds it open for them.
        if (!decided) {
            beginIntermission(session, region, arenaWorld, winnerTeam);
            return;
        }
        if (seriesWinner < 0) {
            // The cap is reached and the lead is shared. "No winner" and "drawn" are different
            // outcomes with the same score.
            session.seriesEndReason = "DRAW";
            journal(session).append("SERIES_DRAWN", "rounds", session.round,
                    "score", scoreLine(session));
        }
        finishSeries(session, seriesWinner);
    }

    // ------------------------------------------------------------------ stats and history

    /**
     * Counts one death, and one kill for whoever landed the last hit when that was an opponent.
     *
     * <p>Kills are attributed at the moment the victim dies. The victim's own side is excluded, so
     * a team kill is a death and never a kill.
     */
    private void recordDeath(DuelSession session, Participant me, ServerPlayer victim) {
        stats.addDeath(me.uuid);
        stats.name(me.uuid, me.name);
        if (!(victim.getLastHurtByMob() instanceof ServerPlayer killer)
                || killer.getUUID().equals(victim.getUUID())) {
            return;
        }
        Participant kp = session.participant(killer.getStringUUID());
        if (kp == null || kp.rejected || kp.teamIndex() == me.teamIndex()) {
            return;
        }
        stats.addKill(kp.uuid);
        stats.name(kp.uuid, kp.name);
    }

    /**
     * Damage dealt to an opponent, called from the {@code applyDamage} hook.
     *
     * <p>Static and defensive because the hook runs for every living entity on the server. Anything
     * that is not two players on different sides of one live session is dropped here.
     */
    public static void noteDamage(ServerPlayer victim, ServerPlayer attacker,
                                  float amount) {
        SessionManager self = instance;
        if (self == null || amount <= 0F || victim == null || attacker == null
                || attacker.getUUID().equals(victim.getUUID())) {
            return;
        }
        String sessionId = self.sessionByPlayer.get(victim.getUUID());
        if (sessionId == null) {
            return;
        }
        DuelSession session = self.sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        Participant v = session.participant(victim.getStringUUID());
        Participant a = session.participant(attacker.getStringUUID());
        if (v == null || a == null || v.rejected || a.rejected || v.teamIndex() == a.teamIndex()) {
            return;
        }
        self.stats.addDamage(a.uuid, amount);
    }

    /**
     * Writes one {@link MatchRecord} per participant at the end of a series.
     *
     * <p>Three exclusions: a server stop is not a finished match, a series with no settled round is
     * a failed start, and a declined invite is not a participant.
     */
    private void recordSeries(DuelSession session, int winnerTeam) {
        if (server.isStopped() || session.roundLog.isEmpty()) {
            return;
        }
        List<Integer> sides = session.playingTeams();
        if (sides.size() < 2) {
            return;
        }
        long now = System.currentTimeMillis();
        String map = spectators.mapId(session);
        String reason = session.seriesEndReason == null ? "" : session.seriesEndReason;

        for (int side : sides) {
            int myScore = session.wins(side);
            int theirScore = 0;
            for (int other : sides) {
                if (other != side) {
                    theirScore = Math.max(theirScore, session.wins(other));
                }
            }
            String rounds = roundLine(session, side);
            for (Participant p : session.activeMembers(side)) {
                MatchRecord record = new MatchRecord();
                record.id = session.id;
                record.t = now;
                record.map = map;
                record.mode = session.mode;
                record.teams = sides.size();
                record.teamSize = session.activeMembers(side).size();
                record.myScore = myScore;
                record.theirScore = theirScore;
                record.won = winnerTeam >= 0 && winnerTeam == side;
                record.reason = reason;
                record.duration = Math.max(0L, now - session.createdAt);
                record.rounds = rounds;
                record.opponents = namesOf(session.opponentsOf(p.uuid));
                record.teamMates = namesOf(without(session.activeMembers(side), p.uuid));
                stats.name(p.uuid, p.name);
                stats.record(p.uuid, record);
            }
        }
        // Flushed here as well as on shutdown, since a match record is written exactly once.
        stats.flush();
    }

    /**
     * One {@code won,reason,ms} entry per settled round, with {@code won} relative to {@code side}.
     */
    private static String roundLine(DuelSession session, int side) {
        StringBuilder sb = new StringBuilder();
        for (DuelSession.RoundLog round : session.roundLog) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(round.winner == side ? 1 : 0).append(',')
                    .append(round.reason == null ? "" : round.reason).append(',')
                    .append(round.ms);
        }
        return sb.toString();
    }

    private static String namesOf(List<Participant> participants) {
        StringBuilder sb = new StringBuilder();
        for (Participant p : participants) {
            if (p.rejected) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.name);
        }
        return sb.toString();
    }

    private static List<Participant> without(List<Participant> participants, String uuid) {
        List<Participant> out = new ArrayList<>(participants.size());
        for (Participant p : participants) {
            if (!p.uuid.equals(uuid)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The home screen's snapshot: counters, history, live matches, and what the reader may do.
     *
     * <p>Built on demand rather than pushed, since only this screen needs the data. History is
     * capped at {@link #HOME_HISTORY} rows.
     */
    public void sendHomeState(ServerPlayer player) {
        String uuid = player.getStringUUID();
        PlayerStats s = stats.of(uuid);
        stats.name(uuid, player.getName().getString());

        int flags = 0;
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId != null) {
            DuelSession live = sessions.get(sessionId);
            if (live != null) {
                flags |= live.stage == SessionStage.DRAFT
                        ? HomeStatePayload.FLAG_DRAFT
                        : HomeStatePayload.FLAG_IN_DUEL;
            }
        }
        if (spectators.isWatching(player.getUUID())) {
            flags |= HomeStatePayload.FLAG_SPECTATING;
        }
        if (s.matches > 0) {
            flags |= HomeStatePayload.FLAG_HAS_STATS;
        }
        boolean operator = PvCompat.hasPermission(player, 3);
        if (operator) {
            flags |= HomeStatePayload.FLAG_OPERATOR;
        }

        StringBuilder statLine = new StringBuilder()
                .append(s.matches).append(',')
                .append(s.wins).append(',')
                .append(s.rounds).append(',')
                .append(s.roundWins).append(',')
                .append(Math.round(s.damage)).append(',')
                .append(s.kills).append(',')
                .append(s.deaths).append(',')
                .append(s.streak).append(',')
                .append(s.bestStreak).append(',')
                .append(s.lastPlayed);

        StringBuilder history = new StringBuilder();
        for (MatchRecord record : stats.history(uuid, HOME_HISTORY)) {
            if (history.length() > 0) {
                history.append('\n');
            }
            history.append(record.id).append('|')
                    .append(record.t).append('|')
                    .append(record.map).append('|')
                    .append(record.mode).append('|')
                    .append(record.teams).append('|')
                    .append(record.teamSize).append('|')
                    .append(record.myScore).append('|')
                    .append(record.theirScore).append('|')
                    .append(record.won ? 1 : 0).append('|')
                    .append(record.reason).append('|')
                    .append(record.duration).append('|')
                    .append(record.rounds).append('|')
                    .append(record.opponents).append('|')
                    .append(record.teamMates);
        }

        PvPocketNetworking.sendHomeState(player, new HomeStatePayload(
                statLine.toString(), history.toString(), spectators.rowsFor(player), flags,
                operator ? arenaBlock(player) : ""));
    }

    /**
     * The arena section of the snapshot: one header line, then one line per map.
     *
     * <p>Only built for operators, since only their panel reads it.
     *
     * <p>{@code guard} is the reader's own flag, not a server-wide number, so two operators each
     * see their own switch.
     */
    private String arenaBlock(ServerPlayer player) {
        java.util.Map<String, Integer> leasedPerMap = new java.util.HashMap<>();
        for (Map.Entry<String, com.leipishu.pvpocket.arena.ArenaRegion> lease
                : com.leipishu.pvpocket.arena.ArenaManager.leased()) {
            leasedPerMap.merge(lease.getValue().map.id, 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder()
                .append(guard.isExempt(player.getUUID()) ? 0 : 1).append(',')
                .append(com.leipishu.pvpocket.arena.ArenaManager.stampedCount()).append(',')
                .append(com.leipishu.pvpocket.arena.ArenaManager.slotCount()).append(',')
                .append(com.leipishu.pvpocket.arena.ArenaManager.leasedCount()).append(',')
                .append(com.leipishu.pvpocket.arena.ArenaManager.warmUpPending() ? 1 : 0);
        for (com.leipishu.pvpocket.arena.ArenaMap map : com.leipishu.pvpocket.arena.ArenaMaps.ALL) {
            out.append('\n').append(map.id).append(',')
                    .append(com.leipishu.pvpocket.arena.ArenaManager.copiesOf(map)).append(',')
                    .append(leasedPerMap.getOrDefault(map.id, 0));
        }
        return out.toString();
    }

    /** Rows of history one snapshot carries. The list shows three at a time. */
    private static final int HOME_HISTORY = 30;

    /**
     * The home screen's primary action: open a draft, with an optional saved rule set or loadout.
     *
     * <p>All three duel actions end in {@link #openRoster}, which already handles busy, spectator
     * and arena-not-ready, and hands back a draft that is still open.
     */
    public void onHomeAction(ServerPlayer player, HomeActionPayload action) {
        switch (action.action()) {
            case HomeActionPayload.ACTION_OPEN_DUEL -> openRoster(player);
            case HomeActionPayload.ACTION_DUEL_WITH_RULES -> {
                openRoster(player);
                applyPresetRules(player, action.arg());
            }
            case HomeActionPayload.ACTION_DUEL_WITH_LOADOUT -> {
                openRoster(player);
                applyPresetLoadout(player, action.arg());
            }
            case HomeActionPayload.ACTION_ARENA_COPY -> {
                if (requireOperator(player, "copy")) {
                    addArenaCopy(player, action.arg());
                }
            }
            case HomeActionPayload.ACTION_ARENA_REMOVE -> {
                if (requireOperator(player, "remove")) {
                    removeArenaCopy(player, action.arg());
                }
            }
            case HomeActionPayload.ACTION_REMATCH -> rematch(player, action.arg());
            case HomeActionPayload.ACTION_ARENA_RESET -> {
                if (requireOperator(player, "reset")) {
                    resetArenaFromPanel(player);
                }
            }
            case HomeActionPayload.ACTION_GUARD -> {
                if (requireOperator(player, "guard")) {
                    setGuardEnabled(player, "1".equals(action.arg()));
                    // The switch shows the stored state, so the answer is a fresh snapshot.
                    sendHomeState(player);
                }
            }
            default -> PvPocket.LOGGER.debug("Ignoring unknown home action {} from {}",
                    action.action(), player.getName().getString());
        }
    }

    /**
     * The permission check behind the operator page, and the only one that matters.
     *
     * <p>The panel not drawing these buttons is a convenience. The same level the commands require
     * is the level the panel requires.
     */
    private boolean requireOperator(ServerPlayer player, String what) {
        if (PvCompat.hasPermission(player, 3)) {
            return true;
        }
        PvPocket.LOGGER.warn("Refused home action '{}' from {} (not an operator)",
                what, player.getName().getString());
        return false;
    }

    /**
     * {@code /pvpocket arena copy} from the panel: one more plot for the named map.
     *
     * <p>An unknown id is a log line rather than an exception, since the ids come from the snapshot
     * the server sent and a mismatch means a stale panel.
     */
    private void addArenaCopy(ServerPlayer player, String mapId) {
        com.leipishu.pvpocket.arena.ArenaMap target = arenaMap(mapId);
        if (target == null) {
            PvPocket.LOGGER.warn("No map called '{}' for arena copy from {}",
                    mapId, player.getName().getString());
            return;
        }
        int slot = com.leipishu.pvpocket.arena.ArenaManager.addCopy(server(), target);
        if (slot < 0) {
            player.displayClientMessage(PvLang.t("pvpocket.command.arena.copy_full",
                    com.leipishu.pvpocket.arena.ArenaManager.slotCount()), false);
        } else {
            PvPocket.LOGGER.info("{} added a copy of {} as cell {}",
                    player.getName().getString(), target.id, slot);
        }
        sendHomeState(player);
    }

    /**
     * {@code /pvpocket arena remove} from the panel: one plot taken away from the named map.
     *
     * <p>Both refusals are reported: a leased plot is a wait, and a map with nothing left means a
     * stale panel.
     */
    private void removeArenaCopy(ServerPlayer player, String mapId) {
        com.leipishu.pvpocket.arena.ArenaMap target = arenaMap(mapId);
        if (target == null) {
            PvPocket.LOGGER.warn("No map called '{}' for arena remove from {}",
                    mapId, player.getName().getString());
            return;
        }
        int slot = com.leipishu.pvpocket.arena.ArenaManager.removeCopy(server(), target);
        if (slot == com.leipishu.pvpocket.arena.ArenaManager.REMOVE_BUSY) {
            player.displayClientMessage(PvLang.t("pvpocket.command.arena.remove_busy",
                    target.labelText()), false);
        } else if (slot < 0) {
            player.displayClientMessage(PvLang.t("pvpocket.command.arena.remove_none",
                    target.labelText()), false);
        } else {
            int left = com.leipishu.pvpocket.arena.ArenaManager.copiesOf(target);
            PvPocket.LOGGER.info("{} removed a copy of {} at cell {} ({} left)",
                    player.getName().getString(), target.id, slot, left);
            player.displayClientMessage(PvLang.t("pvpocket.command.arena.remove_done",
                    target.labelText(), slot, left), false);
            if (left == 0) {
                player.displayClientMessage(PvLang.t("pvpocket.command.arena.remove_empty",
                        target.labelText()), false);
            }
        }
        sendHomeState(player);
    }

    /** A map by id, or null. Both operator actions take an id straight off the wire. */
    private com.leipishu.pvpocket.arena.ArenaMap arenaMap(String mapId) {
        for (com.leipishu.pvpocket.arena.ArenaMap map : com.leipishu.pvpocket.arena.ArenaMaps.ALL) {
            if (map.id.equalsIgnoreCase(mapId)) {
                return map;
            }
        }
        return null;
    }

    /**
     * {@code /pvpocket arena reset} from the panel.
     *
     * <p>Announces once to the whole player list, the actor included.
     */
    private void resetArenaFromPanel(ServerPlayer player) {
        int ended = resetArena();
        if (ended < 0) {
            player.displayClientMessage(PvLang.t("pvpocket.command.arena.rebuilding"), false);
            return;
        }
        net.minecraft.network.chat.MutableComponent line = PvLang.t("pvpocket.command.arena.reset_done", ended)
                .withStyle(net.minecraft.ChatFormatting.AQUA);
        for (ServerPlayer other : server().getPlayerList().getPlayers()) {
            other.displayClientMessage(line, false);
        }
        sendHomeState(player);
    }

    /**
     * Overwrites the draft's rule set with a saved one.
     *
     * <p>Applied after {@code openRoster}, the only path that also handles an existing draft. A
     * string that does not decode is dropped with a message.
     */
    private void applyPresetRules(ServerPlayer player, String encoded) {
        DuelSession session = draftOf(player);
        if (session == null || encoded == null || encoded.isEmpty()) {
            return;
        }
        try {
            DuelRules rules = DuelRules.decode(encoded);
            applyRulesToSession(session, rules);
            // Bumped like any other edit, so the panel sees this as a change.
            session.rulesRevision++;
            session.rulesAuthor = player.getStringUUID();
            persist(session);
            journal(session).append("RULES_PRESET_APPLIED", "player", player.getName().getString(),
                    "digest", session.rulesDigest);
            PvPocket.LOGGER.info("Applied rule preset for {}", player.getName().getString());
        } catch (RuntimeException e) {
            PvPocket.LOGGER.warn("Dropping undecodable rule preset from {}",
                    player.getName().getString(), e);
            player.displayClientMessage(PvLang.t("pvpocket.msg.home.preset_broken")
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    /** Replaces the caller's own loadout in the draft with a saved one. */
    private void applyPresetLoadout(ServerPlayer player, String encoded) {
        DuelSession session = draftOf(player);
        if (session == null || encoded == null || encoded.isEmpty()) {
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null) {
            return;
        }
        try {
            Loadout loadout = Loadout.decode(encoded);
            // Match-wide, like every other write: the whole match fights with one set.
            adoptSharedLoadout(session, loadout);
            persist(session);
            journal(session).append("LOADOUT_PRESET_APPLIED",
                    "player", player.getName().getString(), "digest", me.loadoutDigest);
            PvPocket.LOGGER.info("Applied loadout preset for {}", player.getName().getString());
        } catch (RuntimeException e) {
            PvPocket.LOGGER.warn("Dropping undecodable loadout preset from {}",
                    player.getName().getString(), e);
            player.displayClientMessage(PvLang.t("pvpocket.msg.home.preset_broken")
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    /** The caller's own session when it is still a draft, else null. */
    private DuelSession draftOf(ServerPlayer player) {
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            return null;
        }
        DuelSession session = sessions.get(sessionId);
        return session != null && session.stage == SessionStage.DRAFT ? session : null;
    }

    /**
     * Ends the series: restore everybody, put the terrain back, release the lease, archive.
     *
     * <p>Separate from {@link #settle} because shutdown needs it without the round bookkeeping, and
     * the snapshots have to be applied while the players are still connected.
     */
    private void finishSeries(DuelSession session, int winnerTeam) {
        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);

        // Defensive: a series can end during the preparation window, and a leftover barrier ring
        // would be an invisible cage.
        ArenaManager.removePrepareWall(arenaWorld, region);
        // Nothing is left to forfeit once the series is over.
        SurrenderRequest pendingSurrender = surrenderRequests.remove(session.id);
        if (pendingSurrender != null) {
            closeSurrenderPrompt(session, pendingSurrender);
        }
        // A boundary that was waiting for a reconnect has nothing left to wait for.
        absentSince.remove(session.id);
        // Ejected before the snapshots are restored, because restoring teleports the participants.
        spectators.ejectAll(session.id, "SERIES_END");

        int cleared = ArenaManager.purge(arenaWorld, region);
        journal(session).append("ARENA_CLEARED", "removed", cleared);
        journal(session).append("SERIES_ENDED",
                "winnerTeam", winnerTeam,
                "winner", winnerTeam < 0 ? "-" : teamName(session, winnerTeam),
                "score", scoreLine(session),
                "rounds", session.round,
                "maxRounds", session.maxRounds,
                "winsNeeded", session.winsNeeded);

        // Written while the region is still leased, since the map id is read off the plot.
        recordSeries(session, winnerTeam);

        transition(session, SessionStage.RESTORING);

        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player != null && !player.isAlive()) {
                // A body on its death screen cannot take the snapshot. Replacing the entity here is
                // what keeps the restore from being deferred with the corpse left in the arena.
                respawnNow(player);
            }
            restoreParticipant(session, p);
        }

        // Blocks are reverted after the restore, so nobody is standing on the floor when it goes.
        if (region != null) {
            int tracked = region.changeCount();
            int reverted = ArenaManager.revertRegion(arenaWorld, region);
            journal(session).append("ARENA_REVERTED", "region", region.id,
                    "blocks", reverted, "tracked", tracked);
        }
        ArenaManager.release(session.id);
        journal(session).append("ARENA_RELEASED", "region", session.arenaId);
        restoreEnvironmentWhenIdle(arenaWorld);

        // Hidden before ARCHIVED, so the client never draws a scoreboard for a gone session.
        broadcastHud(session, false);
        broadcastBounds(session, false);
        transition(session, SessionStage.ARCHIVED);
        inviteByTarget.values().removeIf(session.id::equals);
        announceSeriesResult(session, winnerTeam);
    }

    /**
     * The winner of the series as a side index, or {@code -1} when there is none.
     *
     * <p>{@code -1} covers a live series and a series that ran out of rounds with the lead shared;
     * the caller tells them apart with {@link #seriesDecided}.
     */
    private static int seriesWinnerTeam(DuelSession session) {
        int need = Math.max(1, session.winsNeeded);
        for (int t = 0; t < Math.max(1, session.teamCount); t++) {
            if (session.wins(t) >= need) {
                return t;
            }
        }
        if (session.round >= Math.max(1, session.maxRounds)) {
            return leaderTeam(session);
        }
        return -1;
    }

    /** The current leader, or {@code -1} when the lead is shared. */
    private static int leaderTeam(DuelSession session) {
        return leaderTeam(session, -1);
    }

    /**
     * The side that has nobody left online, or {@code -1} when every side still has a player.
     *
     * <p>A cut is a forfeit by whoever walked out, so that side cannot also be the side that wins.
     * {@code -1} when two or more sides are absent, since there is no forfeit to attribute. A
     * surrendered side is skipped: its members leaving afterwards is not a cut.
     */
    private int absentTeam(DuelSession session) {
        int absent = -1;
        for (int t = 0; t < Math.max(1, session.teamCount); t++) {
            if (session.contestingMembers(t).isEmpty()) {
                continue;
            }
            boolean anyOnline = false;
            for (Participant p : session.participants) {
                if (p.teamIndex() == t && !p.rejected && online(p) != null) {
                    anyOnline = true;
                    break;
                }
            }
            if (!anyOnline) {
                if (absent >= 0) {
                    return -1;
                }
                absent = t;
            }
        }
        return absent;
    }

    /**
     * The best-scoring side other than {@code exclude}, or {@code -1} when they are tied.
     *
     * <p>{@code exclude} exists for surrender: a side that quits cannot be its own winner, so the
     * tie is judged among the sides that stayed. A side that already surrendered is skipped for the
     * same reason, whatever its score says.
     */
    private static int leaderTeam(DuelSession session, int exclude) {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        boolean tied = false;
        for (int t = 0; t < Math.max(1, session.teamCount); t++) {
            if (t == exclude || session.contestingMembers(t).isEmpty()) {
                continue;
            }
            int wins = session.wins(t);
            if (wins > bestScore) {
                bestScore = wins;
                best = t;
                tied = false;
            } else if (wins == bestScore) {
                tied = true;
            }
        }
        return tied ? -1 : best;
    }

    /**
     * "Dev1 + Dev2": a side spelled out for chat lines that have no room for a roster.
     *
     * <p>Player names, so no translation key: only the separator is localisable, and a separator
     * that changed with the language would be worse than one that does not.
     */
    private static String teamName(DuelSession session, int team) {
        StringBuilder sb = new StringBuilder();
        for (Participant p : session.activeMembers(team)) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(p.name);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /**
     * Between rounds: revert the terrain but keep the lease, then wait out {@code a.intermission}.
     *
     * <p>The snapshot is not re-applied here, since it is the only copy of the survival inventory.
     * Items return to the initial state by granting the loadout again at the next round start.
     */
    private void beginIntermission(DuelSession session, ArenaRegion region, ServerLevel world,
                                   int winnerTeam) {
        int cleared = ArenaManager.purge(world, region);
        journal(session).append("ARENA_CLEARED", "removed", cleared);
        if (region != null) {
            int tracked = region.changeCount();
            int reverted = ArenaManager.revertRegion(world, region);
            journal(session).append("ARENA_REVERTED", "region", region.id,
                    "blocks", reverted, "tracked", tracked, "round", session.round);
        }
        int seconds = DuelRules.decode(session.rules).get("a.intermission");
        roundReadyAt.put(session.id, System.currentTimeMillis() + seconds * 1000L);
        countdownPushed.remove(session.id);
        transition(session, SessionStage.ROUND_READY);
        // Nobody walks during the intermission: spectator mode, parked above the spawn. Dead
        // players are skipped and parked by onRespawn once they are alive.
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player == null || p.rejected || p.surrendered || !player.isAlive()) {
                continue;
            }
            parkForIntermission(session, player, p, region);
        }
        broadcastHud(session, true);
        // Two keys, since with three or four sides the score alone does not say which row is which.
        MutableComponent line = winnerTeam < 0
                ? PvLang.t("pvpocket.msg.round.ended", session.round, scoreLine(session), seconds)
                : PvLang.t("pvpocket.msg.round.ended_team", session.round,
                        teamName(session, winnerTeam), scoreLine(session), seconds);
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player != null) {
                player.displayClientMessage(line.copy().withStyle(ChatFormatting.GOLD), false);
            }
        }
    }

    private void announceSeriesResult(DuelSession session, int winnerTeam) {
        // A surrender or a disconnect can end a series with the winner trailing on rounds, so the
        // sentence says why.
        String becauseKey = switch (session.seriesEndReason == null ? "" : session.seriesEndReason) {
            case "SURRENDER" -> "pvpocket.msg.series.because.surrender";
            case "CUT" -> "pvpocket.msg.series.because.cut";
            case "DRAW" -> "pvpocket.msg.series.because.draw";
            default -> null;
        };
        MutableComponent because = becauseKey == null ? Component.empty() : PvLang.t(becauseKey);
        MutableComponent result = winnerTeam >= 0
                ? PvLang.t("pvpocket.msg.series.winner", teamName(session, winnerTeam))
                : "DRAW".equals(session.seriesEndReason)
                ? PvLang.t("pvpocket.msg.series.draw")
                : PvLang.t("pvpocket.msg.series.no_winner");
        MutableComponent line = PvLang.t("pvpocket.msg.series.ended",
                result, because, scoreLine(session));
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player != null) {
                player.displayClientMessage(line.copy()
                        .withStyle(winnerTeam >= 0 && p.teamIndex() == winnerTeam
                                ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
                // The card goes out next to the sentence, which is what the player reads later.
                if (!p.rejected) {
                    PvPocketNetworking.sendSeriesResult(player, resultCard(session, p, winnerTeam));
                }
            }
        }
    }

    /**
     * The result card for one reader, as the client needs it to draw and to export.
     *
     * <p>Built per participant, since {@code mySide} decides which row is highlighted, which round
     * marks count as wins, and whether the headline is a victory. Read off the session rather than
     * off the {@link MatchRecord}. Format documented on {@link SeriesResultPayload}.
     */
    private SeriesResultPayload resultCard(DuelSession session, Participant reader, int winnerTeam) {
        long now = System.currentTimeMillis();
        String head = String.join("|",
                session.id,
                spectators.mapId(session),
                session.mode,
                Integer.toString(reader.teamIndex()),
                Integer.toString(winnerTeam),
                Long.toString(now),
                Long.toString(Math.max(0L, now - session.createdAt)),
                session.seriesEndReason == null ? "" : session.seriesEndReason);

        StringBuilder rounds = new StringBuilder();
        for (DuelSession.RoundLog round : session.roundLog) {
            if (rounds.length() > 0) {
                rounds.append('\n');
            }
            rounds.append(round.winner).append(',')
                    .append(round.reason == null ? "" : round.reason).append(',')
                    .append(round.ms);
        }

        StringBuilder sides = new StringBuilder();
        for (int side : session.playingTeams()) {
            if (sides.length() > 0) {
                sides.append('\n');
            }
            sides.append(side).append(',').append(session.wins(side));
            for (Participant member : session.activeMembers(side)) {
                sides.append(',').append(member.name);
            }
        }
        return new SeriesResultPayload(head, rounds.toString(), sides.toString());
    }

    // ------------------------------------------------------------------ scoreboard HUD

    /**
     * Pushes the plot outline to every participant, or clears it.
     *
     * <p>Called from {@code startRound} rather than {@code activateRound}, so the outline is
     * visible during the preparation window. Spectators get it too.
     */
    private void broadcastBounds(DuelSession session, boolean active) {
        broadcastBounds(session, active, DuelRules.decode(session.rules).get("e.boundary"));
    }

    private void broadcastBounds(DuelSession session, boolean active, int mode) {
        ArenaRegion region = ArenaManager.region(session.id);
        ArenaBoundsPayload payload = !active || region == null
                ? ArenaBoundsPayload.hidden()
                : boundsOf(region, mode);
        PrepareScreensPayload screens = screensOf(session);
        for (Participant p : session.participants) {
            if (p.surrendered || p.abandoned) {
                continue;
            }
            ServerPlayer player = online(p);
            if (player != null) {
                PvPocketNetworking.sendBounds(player, payload);
                PvPocketNetworking.sendScreens(player, screens);
            }
        }
        for (ServerPlayer watcher : spectators.watchersOf(session.id)) {
            PvPocketNetworking.sendBounds(watcher, payload);
            PvPocketNetworking.sendScreens(watcher, screens);
        }
        // One line per round boundary, so a missing curtain can be traced to a missing packet.
        PvPocket.LOGGER.info("Arena bounds {} for session {} ({}, mode {}, screens {})",
                active ? "pushed" : "cleared", session.id,
                region == null ? "no plot" : (region.sizeX + "x" + region.sizeZ), mode,
                screens.screens().size());
    }

    /**
     * The screens standing in front of the sides right now, or none.
     *
     * <p>Derived from the stage rather than stored, so a client that reconnected mid-countdown gets
     * the payload from the stage alone.
     */
    private static PrepareScreensPayload screensOf(DuelSession session) {
        ArenaRegion region = ArenaManager.region(session.id);
        if (region == null || session.stage != SessionStage.ROUND_READY) {
            return PrepareScreensPayload.none();
        }
        List<ArenaManager.Screen> planes = ArenaManager.prepareScreens(region, session.teamCount);
        if (planes.isEmpty()) {
            return PrepareScreensPayload.none();
        }
        List<PrepareScreensPayload.Screen> out = new ArrayList<>(planes.size());
        for (ArenaManager.Screen plane : planes) {
            out.add(new PrepareScreensPayload.Screen(plane.alongZ(), plane.plane(),
                    plane.from(), plane.to(), plane.floorY(), plane.height()));
        }
        return new PrepareScreensPayload(List.copyOf(out));
    }

    /**
     * The outline packet for one plot.
     *
     * <p>Uses the fight rectangle, not the plot box: on a map with an inset the box is the outer
     * wall, so a curtain on the box would be drawn behind the seating.
     */
    private static ArenaBoundsPayload boundsOf(ArenaRegion region, int mode) {
        // Always the rule's own numbers; the preparation window draws its screens separately.
        return new ArenaBoundsPayload(true,
                region.pitMinX(), region.pitMinZ(),
                region.pitMaxX() + 1.0D, region.pitMaxZ() + 1.0D,
                ArenaRegion.SURFACE_Y, BOUNDARY_CURTAIN_HEIGHT, mode);
    }

    /** Shows or dismisses the preparing screen for every participant of a session. */
    private void broadcastPrepare(DuelSession session, boolean active) {
        PrepareStatusPayload payload = new PrepareStatusPayload(session.id, active);
        for (Participant p : session.participants) {
            if (p.surrendered || p.abandoned) {
                continue;
            }
            ServerPlayer player = online(p);
            if (player != null) {
                PvPocketNetworking.sendPrepareStatus(player, payload);
            }
        }
    }

    private void broadcastHud(DuelSession session, boolean active) {
        MatchHudPayload payload = active ? buildHud(session) : MatchHudPayload.hidden();
        for (Participant p : session.participants) {
            // A side that left has its own screen cleared once, on the way out; a live scoreboard
            // pushed at it again every round would read as "still in the match".
            if (p.surrendered || p.abandoned) {
                continue;
            }
            ServerPlayer player = online(p);
            if (player != null) {
                PvPocketNetworking.sendHud(player, payload);
            }
        }
        for (ServerPlayer watcher : spectators.watchersOf(session.id)) {
            PvPocketNetworking.sendHud(watcher, payload);
        }
    }

    /**
     * Flattens the session into the HUD payload.
     *
     * <p>Three flat strings rather than one field per side, since the payload is hand-written
     * around {@code PacketCodec.tuple}'s six-component limit. {@code scores} is {@code "2,1"} and
     * {@code teams} is {@code "Dev1|uuid,Dev2|uuid;Dev3|uuid"}, with {@code ,} between members of a
     * side, {@code ;} between sides and {@code |} between a name and its uuid. {@code eliminated}
     * lists the uuids of players who are out of this round.
     */
    private MatchHudPayload buildHud(DuelSession session) {
        int teams = Math.max(1, session.teamCount);
        StringBuilder scores = new StringBuilder();
        StringBuilder roster = new StringBuilder();
        StringBuilder eliminated = new StringBuilder();
        int written = 0;
        for (int team = 0; team < teams; team++) {
            List<Participant> members = session.activeMembers(team);
            // A side with nobody left is skipped in both lists at once, so they stay index-aligned.
            if (members.isEmpty()) {
                continue;
            }
            if (written++ > 0) {
                scores.append(',');
                roster.append(';');
            }
            scores.append(session.wins(team));
            boolean first = true;
            for (Participant p : members) {
                if (!first) {
                    roster.append(',');
                }
                roster.append(p.name).append('|').append(p.uuid);
                first = false;
            }
        }
        // A surrendered side is greyed for the rest of the series, and an away player is greyed
        // because they are not fighting. Both fold into the same per-round field.
        for (Participant p : session.participants) {
            if ((!p.eliminated && !p.surrendered && p.goneSince == 0) || p.rejected) {
                continue;
            }
            if (eliminated.length() > 0) {
                eliminated.append(',');
            }
            eliminated.append(p.uuid);
        }
        return new MatchHudPayload(true, session.round, session.maxRounds, session.winsNeeded,
                scores.toString(), roster.toString(), eliminated.toString(),
                intermissionSecondsLeft(session), roundClock(session), roundClockDown(session));
    }

    /**
     * The in-round clock in whole seconds, or -1 outside a live timed or untimed round.
     *
     * <p>Counted down from {@code a.roundLimit}, up from zero without one. Derived from the same
     * {@code roundStartedAt} the round log uses, so the HUD and the journal agree on duration.
     */
    private int roundClock(DuelSession session) {
        if (session.stage != SessionStage.ROUND_ACTIVE) {
            return -1;
        }
        Long started = roundStartedAt.get(session.id);
        if (started == null) {
            return -1;
        }
        long elapsed = (System.currentTimeMillis() - started) / 1000L;
        int limit = session.rulebook().roundLimitSeconds();
        if (limit <= 0) {
            return (int) elapsed;
        }
        return (int) Math.max(0L, limit - elapsed);
    }

    /** Whether {@link #roundClock} counts down (a limit is set) rather than up. */
    private static boolean roundClockDown(DuelSession session) {
        return session.rulebook().roundLimitSeconds() > 0;
    }

    /**
     * Pushes the in-round clock at most once per second per session, only when it changes.
     *
     * <p>The clock is server-derived, so a client in the arena and one still loading see the same
     * number. Sharing {@link #countdownPushed} is safe: a session is never in the intermission and
     * the round at once, and both write the whole payload on change.
     */
    private void tickRoundClock() {
        for (DuelSession session : sessions.values()) {
            if (session.stage != SessionStage.ROUND_ACTIVE) {
                continue;
            }
            int shown = roundClock(session);
            if (shown < 0) {
                continue;
            }
            // The intermission key is the session id too; active rounds have no roundReadyAt.
            Integer last = countdownPushed.get(session.id);
            if (last != null && last == shown) {
                continue;
            }
            countdownPushed.put(session.id, shown);
            broadcastHud(session, true);
        }
    }

    /**
     * Whole seconds left in the intermission, or {@code 0} outside one.
     *
     * <p>Rounded up, so a three-second intermission shows 3 on its first frame. Sent as remaining
     * milliseconds rather than an end time, so the client does not compare clocks.
     */
    private int intermissionSecondsLeft(DuelSession session) {
        Long until = roundReadyAt.get(session.id);
        if (until == null) {
            return 0;
        }
        long ms = until - System.currentTimeMillis();
        return ms <= 0L ? 0 : (int) ((ms + 999L) / 1000L);
    }

    /**
     * Takes every trace of a match off one client's screen: bar, plot outline and curtain.
     *
     * <p>Three payloads rather than one, because a client that keeps any of them draws an arena
     * around a player who is standing in their own world.
     */
    private static void clearMatchScreen(ServerPlayer player) {
        PvPocketNetworking.sendHud(player, MatchHudPayload.hidden());
        PvPocketNetworking.sendBounds(player, ArenaBoundsPayload.hidden());
        PvPocketNetworking.sendScreens(player, PrepareScreensPayload.none());
    }

    /**
     * Re-sends the HUD to one player, or tells them to hide it.
     *
     * <p>Needed on join: the payload is pushed only when the score changes.
     */
    private void pushHudTo(ServerPlayer player) {
        String id = sessionByPlayer.get(player.getUUID());
        DuelSession session = id == null ? null : sessions.get(id);
        if (session == null || session.stage.isTerminal()) {
            clearMatchScreen(player);
            return;
        }
        PvPocketNetworking.sendHud(player,
                session.stage == SessionStage.DRAFT || session.stage == SessionStage.NEGOTIATING
                        || session.stage == SessionStage.FROZEN
                        ? MatchHudPayload.hidden()
                        : buildHud(session));
        // The plot outline is pushed on round boundaries, so a reconnect mid-round needs it too.
        if (session.stage == SessionStage.ROUND_READY || session.stage == SessionStage.ROUND_ACTIVE) {
            ArenaRegion region = ArenaManager.region(session.id);
            if (region != null) {
                PvPocketNetworking.sendBounds(player, boundsOf(region, boundsMode(session)));
            }
        } else {
            PvPocketNetworking.sendBounds(player, ArenaBoundsPayload.hidden());
        }
        // screensOf already answers "none" for anything that is not the countdown.
        PvPocketNetworking.sendScreens(player, screensOf(session));
    }

    /**
     * Which colour the curtain should be right now.
     *
     * <p>Always the rule's own mode. The preparation screens are a separate payload, not a boundary
     * mode.
     */
    private static int boundsMode(DuelSession session) {
        return DuelRules.decode(session.rules).get("e.boundary");
    }

    /**
     * Gives a freshly joined spectator the same overlay the participants have.
     *
     * <p>Not routed through {@link #pushHudTo}, which looks the player up as a participant.
     */
    void pushSpectatorView(ServerPlayer player, DuelSession session) {
        PvPocketNetworking.sendHud(player, buildHud(session));
        ArenaRegion region = ArenaManager.region(session.id);
        PvPocketNetworking.sendBounds(player, region == null
                ? ArenaBoundsPayload.hidden()
                : boundsOf(region, boundsMode(session)));
        PvPocketNetworking.sendScreens(player, screensOf(session));
    }

    /**
     * Takes back everything {@link #pushSpectatorView} handed out.
     *
     * <p>The score bar and the boundary curtain belong to a match the spectator has left, and no
     * other broadcast reaches them.
     */
    void clearSpectatorView(ServerPlayer player) {
        PvPocketNetworking.sendHud(player, MatchHudPayload.hidden());
        PvPocketNetworking.sendBounds(player, ArenaBoundsPayload.hidden());
        PvPocketNetworking.sendScreens(player, PrepareScreensPayload.none());
    }

    /**
     * Puts a player back into the state a fresh round starts from.
     *
     * <p>Health, hunger, effects and fire survive a round and would otherwise carry a poisoned or
     * half-dead player into the next one.
     */
    private static void resetRoundState(ServerPlayer player, int maxHealth) {
        player.removeAllEffects();
        player.clearFire();
        player.setRemainingFireTicks(0);
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0;
        PvCompat.resetFood(player);
        player.setHealth(Math.min(maxHealth, player.getMaxHealth()));
    }


    /** Rolls a session back after a failed start. Restores anyone already frozen, then voids it. */
    private void abort(DuelSession session, String reason) {
        session.voidReason = reason;
        closePanels(session);
        roundReadyAt.remove(session.id);
        roundStartedAt.remove(session.id);
        clearBoundaryExits(session);
        prepareDeadlines.remove(session.id);
        countdownPushed.remove(session.id);
        SurrenderRequest pendingSurrender = surrenderRequests.remove(session.id);
        if (pendingSurrender != null) {
            closeSurrenderPrompt(session, pendingSurrender);
        }
        ArenaManager.removePrepareWall(ArenaManager.world(server), ArenaManager.region(session.id));
        spectators.ejectAll(session.id, "ABORTED");
        broadcastHud(session, false);
        // A failed start must not leave the preparing screen up until the client watchdog fires.
        broadcastBounds(session, false);
        // Restores anybody the result page moved into the lobby, then returns the plot.
        releaseLobby(session);
        journal(session).append("SESSION_ABORTED", "reason", reason);
        transition(session, SessionStage.VOIDED);
    }

    private boolean restoreParticipant(DuelSession session, Participant p) {
        if (p.restored) {
            // Re-applying an old snapshot would roll the player back, so this is idempotent.
            return true;
        }
        UUID uuid = UUID.fromString(p.uuid);
        // Combat state must be dropped even if the player is offline.
        CombatState.clear(uuid);
        ServerPlayer player = online(p);
        // Dead counts as absent: the respawn replaces the entity, so the write would be lost.
        if (player == null || !player.isAlive()) {
            pendingRestore.put(uuid, session.id);
            journal(session).append("RESTORE_DEFERRED", "player", p.name,
                    "reason", player == null ? "OFFLINE" : "DEAD");
            return false;
        }
        // The unconditional push: an offline-then-online player keeps a stale legacy flag.
        PvPocketNetworking.sendState(player);
        // And the same for other clients, which draw the block pose from their own flag.
        PvPocketNetworking.broadcastBlocking(player, false);
        try {
            Path file = storage.snapshotDir(session.id).resolve(p.snapshotFile);
            PlayerSnapshot snapshot = PlayerSnapshot.readFrom(file);
            snapshot.restore(player);
            boolean verified = snapshot.verify(player);
            p.restored = true;
            // Persisted here, since the deferred restore path has no transition of its own.
            persist(session);
            // The snapshot reaches playerdata only at the next autosave or a clean disconnect.
            ((PlayerManagerInvoker) PvCompat.server(player).getPlayerList())
                    .pvpocket$savePlayerData(player);
            sessionByPlayer.remove(player.getUUID());
            if (p.surrendered || p.abandoned) {
                // Nothing of the match may stay on the screen of a player who left it: the bar,
                // the plot outline and the preparation curtain all go with the session mapping.
                clearMatchScreen(player);
            }
            journal(session).append("RESTORE_COMPLETED", "player", p.name, "verified", verified);
            if (!verified) {
                // Naming the field turns "something differs" into a line somebody can act on.
                String diff = snapshot.mismatch(player);
                journal(session).append("ANOMALY", "player", p.name,
                        "detail", "state digest mismatch after restore",
                        "fields", diff);
                PvPocket.LOGGER.warn("Restore digest mismatch for {} in session {}: {}",
                        p.name, session.id, diff);
            }
            // A side that surrendered, or one player who gave up their own place, gets its state
            // back mid-series: neither should be told the match is over.
            player.displayClientMessage(PvLang.t(p.surrendered
                            ? "pvpocket.msg.duel.surrendered_restored"
                            : p.abandoned
                                    ? "pvpocket.msg.reconnect.left"
                                    : "pvpocket.msg.series.restored")
                    .withStyle(ChatFormatting.GREEN), false);
            return true;
        } catch (Exception e) {
            PvPocket.LOGGER.error("Restore failed for {} in session {}", p.name, session.id, e);
            journal(session).append("ANOMALY", "player", p.name, "detail", "restore failed: " + e);
            // Not re-queued, or a broken snapshot would be retried every tick.
            return false;
        }
    }

    private void transition(DuelSession session, SessionStage next) {
        journal(session).append("STAGE_CHANGED",
                "from", session.stage.name(), "to", next.name(), "revision", session.revision + 1);
        session.stage = next;
        session.revision++;
        session.updatedAt = System.currentTimeMillis();
        persist(session);
    }

    private void persist(DuelSession session) {
        try {
            storage.writeJson(storage.sessionFile(session.id), session);
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot persist session {}", session.id, e);
        }
    }

    /** Package-private: the spectator layer writes its own lines into the same journal. */
    SessionJournal journal(DuelSession session) {
        return journals.computeIfAbsent(session.id, id -> new SessionJournal(storage.journalFile(id)));
    }

    /**
     * Records a loadout that arrived and was not applied.
     *
     * <p>The counterpart of {@code LOADOUT_SAVED}: every refused save writes a line with the reason
     * rather than the sentence, so the file stays parseable when the language changes.
     *
     * <p>{@code session} is null when the id names a missing session, and the reason goes to the
     * log only.
     */
    private void rejectSubmit(DuelSession session, ServerPlayer player, String reason, String detail) {
        String name = player.getName().getString();
        PvPocket.LOGGER.info("Loadout submit refused for {}: {} ({})", name, reason, detail);
        if (session != null) {
            journal(session).append("LOADOUT_REJECTED",
                    "player", name, "reason", reason, "detail", detail,
                    "stage", session.stage.name());
        }
    }

    ServerPlayer online(Participant p) {
        // "In the player list", not "standing": callers that must not touch a corpse say so
        // themselves.
        return server.getPlayerList().getPlayer(UUID.fromString(p.uuid));
    }

    /**
     * Reports a refusal.
     *
     * <p>Takes a translation key and its arguments, not a sentence: the server decides what went
     * wrong, and the client resolves the key in its own language.
     */
    private static void fail(ServerPlayer player, String key, Object... args) {
        if (player == null) {
            // No addressee when the refusal comes from somebody leaving rather than a click.
            PvPocket.LOGGER.info("Refusal with no player to tell: {} {}",
                    key, java.util.Arrays.toString(args));
            return;
        }
        player.displayClientMessage(PvLang.t(key, args).withStyle(ChatFormatting.RED), false);
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /**
     * Rounds won, one per side, in team order: {@code "2:1"}, or {@code "1:0:0"} for three sides.
     */
    private static String scoreLine(DuelSession session) {
        int teams = Math.max(1, session.teamCount);
        StringBuilder sb = new StringBuilder();
        for (int team = 0; team < teams; team++) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(session.wins(team));
        }
        return sb.toString();
    }
}
