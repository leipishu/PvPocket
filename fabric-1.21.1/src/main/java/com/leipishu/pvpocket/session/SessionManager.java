package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.ArenaMaps;
import com.leipishu.pvpocket.arena.ArenaRegion;
import com.leipishu.pvpocket.arena.Sandbox;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.UsePolicy;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutBook;
import com.leipishu.pvpocket.loadout.LoadoutBuilder;
import com.leipishu.pvpocket.loadout.LoadoutLock;
import com.leipishu.pvpocket.mixin.PlayerManagerInvoker;
import com.leipishu.pvpocket.network.ArenaBoundsPayload;
import com.leipishu.pvpocket.network.HomeActionPayload;
import com.leipishu.pvpocket.network.HomeStatePayload;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.network.PrepareScreensPayload;
import com.leipishu.pvpocket.network.PvPocketNetworking;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.RulesSubmitPayload;
import com.leipishu.pvpocket.network.SeriesResultPayload;
import com.leipishu.pvpocket.network.SpectateRequestPayload;
import com.leipishu.pvpocket.rules.DuelRules;
import com.leipishu.pvpocket.rules.Rulebook;
import com.leipishu.pvpocket.snapshot.PlayerSnapshot;
import com.leipishu.pvpocket.stats.DuelStats;
import com.leipishu.pvpocket.stats.MatchRecord;
import com.leipishu.pvpocket.stats.PlayerStats;
import com.leipishu.pvpocket.storage.HomePositions;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import com.leipishu.pvpocket.storage.SessionJournal;
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
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

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
 * <p>Two rules drive everything here:</p>
 * <ol>
 *   <li>The journal is written before the state change it describes.</li>
 *   <li>Player state is only touched after the snapshot is safely on disk.</li>
 * </ol>
 */
public final class SessionManager {

    private static SessionManager instance;

    /** How often stray mobs are swept out of leased plots, in server ticks. */
    private static final int SWEEP_INTERVAL = 60;

    /**
     * Upper bound on how long a participant can stay "not yet on the plot" before the boundary rule
     * applies to them anyway, in milliseconds.
     *
     * <p>This is a safety valve, not a grace window. See {@link #awaitingFirstEntry}: the normal
     * case is settled on the first tick the player is observed inside, which is immediate.</p>
     */
    private static final long SETTLE_DEADLINE_MS = 30_000L;

    /**
     * Minimum gap between two boundary notices to the same player, in milliseconds.
     *
     * <p>A player leaning on the edge produces one pull-back per tick; without a gate that is a
     * chat flood, twenty journal lines a second, and a client that spends the whole fight being
     * teleported half a block.</p>
     */
    private static final long BOUNDARY_NOTICE_GAP_MS = 1_500L;

    /**
     * The minimum height the client draws the boundary curtain at, in blocks.
     *
     * <p>A minimum, not the height: the client raises the curtain to clear its own camera, because a
     * fixed band stops being visible the moment somebody climbs. Four is what a player standing on
     * the ground needs — tall enough to see without looking up, short enough that the arena still
     * reads as open sky above.</p>
     */
    private static final double BOUNDARY_CURTAIN_HEIGHT = 4.0D;

    /**
     * How long a player may stand outside the plot before the round is forfeited.
     *
     * <p>Five seconds, fixed rather than a rule, because it is the answer to "how long does it take
     * to notice you are out and walk back", not a balance knob. It is also long enough to survive a
     * one-second lag spike, which is the only false positive that matters.</p>
     */
    private static final long BOUNDARY_COUNTDOWN_MS = 5_000L;

    /**
     * Hard ceiling on one session's roster.
     *
     * <p>Four sides of five is the largest shape the roster panel can draw, so anything above
     * twenty is a request the interface cannot express. Enforced when the roster is submitted so a
     * malformed client packet is refused rather than creating a session that could never start.</p>
     */
    private static final int MAX_PARTICIPANTS = 20;

    /** Sides the roster panel can draw. Below two there is nobody to fight. */
    private static final int MIN_TEAMS = 2;
    private static final int MAX_TEAMS = 4;

    /** session id + "/" + uuid -> when they crossed out, and the last whole second announced. */
    private record BoundaryExit(long since, int announced) {
    }

    /** Session id -> when the preparation window ends, and the last whole second announced. */
    private record Prepare(long at, int announced) {
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
    /** Session id -> wall-clock start of the current round, for the opening grace window. */
    private final Map<String, Long> roundStartedAt = new HashMap<>();
    /** Session id -> wall-clock time the next round may begin, for the intermission. */
    private final Map<String, Long> roundReadyAt = new HashMap<>();
    /** Players currently outside their plot, waiting out the boundary countdown. */
    private final Map<String, BoundaryExit> boundaryExits = new HashMap<>();
    /**
     * {@code sessionId/playerUuid} of participants already seen standing on their plot this round.
     *
     * <p>Keyed per player, not per session, because the thing being waited for is a client finishing
     * its chunk download — which happens at a different moment for each side.</p>
     */
    private final Set<String> settledIn = new HashSet<>();
    /** {@code sessionId/playerUuid} -> wall clock of the last pull-back notice, for throttling. */
    private final Map<String, Long> boundaryNotices = new HashMap<>();
    /** Rounds that have teleported everybody in but have not gone live yet. */
    private final Map<String, Prepare> prepareDeadlines = new HashMap<>();
    /**
     * Spectators, and the state needed to put them back.
     *
     * <p>A separate object rather than another dozen fields here: watching a duel is the only thing
     * in this class that moves a player who is not in a session, and keeping that boundary explicit
     * is what stops "restore the participant" logic from quietly growing a second meaning.</p>
     */
    private final Spectators spectators;
    /**
     * Where each player was before the arena. Loaded here rather than owned by a session because it
     * outlives every session: it exists precisely for the case where a session died with somebody
     * still inside.
     */
    private final HomePositions homes;
    /**
     * Watches for players standing in the arena with nothing to do there.
     *
     * <p>Owned here rather than by {@link Spectators} because the two answer different questions:
     * spectatorship is a state somebody opted into, and this is the absence of every such state.</p>
     */
    private final ArenaGuard guard;
    /**
     * Lifetime counters and finished-match history.
     *
     * <p>Owned here rather than by a session because it outlives every session — the whole point of
     * a career record is that it survives the match that produced it.</p>
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
        // Counters first: a session that finishes during the same tick as the first join would
        // otherwise write into a store that has not read the file yet, and overwrite a career.
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
     * Startup reconciliation.
     *
     * <p>Two independent jobs, in this order:</p>
     * <ol>
     *   <li><b>Restore debt.</b> Any participant whose snapshot was written but never applied is
     *       queued for restore, <em>regardless of the session's stage</em>. This is deliberately not
     *       limited to unfinished sessions: a player who disconnects mid-round gets a deferred
     *       restore, the session then completes normally and becomes {@link SessionStage#ARCHIVED},
     *       and a stage-filtered sweep would drop the debt silently on the next restart — losing
     *       exactly the survival state this mod exists to protect.</li>
     *   <li><b>Rollback.</b> Anything unfinished is voided. No attempt is made to resume a round.</li>
     * </ol>
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
        // First, before any session is finished: the rest of this method teleports participants out
        // of the arena and writes their snapshots, and a spectator whose camera is on a player being
        // teleported across dimensions is a spectator watching nothing.
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
                // Straight to restore: there is no next round to prepare for, and leaving the
                // snapshots unapplied would turn a clean stop into a restore debt the next boot has
                // to discover and act on.
                finishSeries(session, -1);
            } else if (session.stage == SessionStage.ROUND_READY) {
                roundReadyAt.remove(session.id);
                finishSeries(session, leaderTeam(session));
            }
        }
        CombatState.clearAll();
        ArenaManager.clearAll();
        journals.values().forEach(SessionJournal::close);
        journals.clear();
        // Last write before the process goes away. Everything above has already moved players out
        // of the arena, so these are the positions they will be rescued to if the next boot finds
        // anybody still standing on a plot.
        homes.flush();
        stats.flush();
    }

    // ------------------------------------------------------------------ guard / reset

    /**
     * Turns the automatic removal of stray players on or off for one player.
     *
     * <p>The flag is per player and persisted, which is what makes it usable as an operator tool:
     * "I am going in to look at the dimension, do not throw me out" has to survive the restart that
     * often follows a look at a broken dimension.</p>
     *
     * @return the new state, i.e. whether the guard is enabled for this player
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
     * <p>Uses {@link #abort} rather than the settle path on purpose. Settling is a game action: it
     * awards the round to somebody and can fall through to an intermission, which is the opposite of
     * what a reset wants. Aborting restores the snapshots, releases the lease and voids the session,
     * which is the complete list of things that have to happen before the terrain can be erased.</p>
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
                // Nothing was frozen and no plot was leased, so there is nothing to undo.
                session.voidReason = reason;
                journal(session).append("SESSION_DROPPED", "reason", reason,
                        "stage", session.stage.name());
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
     * <p>The repair tool for a dimension that has gone wrong — debris left by a crash, a map whose
     * baseline was captured from a half-edited cell, two duels that somehow shared a plot. Nothing
     * short of re-stamping fixes those, because the baseline <em>is</em> what was in the world when
     * the map was pasted.</p>
     *
     * <p>Order matters at every step. Spectators go first, because a camera attached to a
     * participant who is about to be teleported out is a camera watching nothing. Sessions go second,
     * because aborting them is what restores the players' real inventories and releases the leases.
     * Stray players go third, so the wipe cannot pull the floor out from under somebody who was not
     * in a session to begin with. Only then is the dimension cleared.</p>
     *
     * <p>The re-stamp is queued rather than done inline: a full gallery is five cells of ~150k
     * blocks each, about 4.5 s of server thread, and blocking the tick for that long trips the
     * watchdog and times out every connected client. The queued path is the same one startup uses,
     * so it is already exercised on every boot.</p>
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
            // No-op for everybody the sessions already sent home; this is for the player who was in
            // the dimension for a reason that no longer exists.
            homes.evict(player);
            // CombatState.clearAll() below is server-side only; without this everybody keeps
            // seeing the last block pose that was pushed to them.
            PvPocketNetworking.broadcastBlocking(player, false);
        }
        CombatState.clearAll();
        // Before clearAll(), because the region objects are what know how tall the debris in a cell
        // can be. Covers the cells the re-stamp will not touch: the flat plot, which is rebuilt at
        // whatever size the next duel asks for, and any spare cell.
        ArenaManager.wipeUnstampedCells(server);
        ArenaManager.clearAll();
        ArenaManager.beginWarmUp();
        PvPocket.LOGGER.warn("Arena reset: {} session(s) ended, dimension wiped, re-stamping gallery",
                ended);
        return ended;
    }

    // ------------------------------------------------------------------ player events

    public void onJoin(ServerPlayer player) {
        // Unconditional, because the client mirror is a correctness requirement rather than a hint:
        // a stale legacy flag would leave the player with a permanent block penalty. One tiny packet
        // per join is a cheap price for never having to reason about how the state got there.
        PvPocketNetworking.sendState(player);
        // A client that reconnects mid-match has no HUD state, and the payload is only pushed on
        // score changes — so re-send it here instead of waiting for the next round boundary.
        pushHudTo(player);
        // Same for the spectator bar: a spectator who reconnects has no bar, and unlike the HUD
        // there is no round boundary that would eventually repair it.
        spectators.pushState(player);

        // Deliberately no restore attempt here. ServerPlayConnectionEvents.JOIN fires before the
        // player is registered with the PlayerManager, so a lookup by UUID still misses them and the
        // restore would be deferred forever. Deferred restores are drained from tick() instead,
        // which does not depend on event ordering and also covers every other path that can leave a
        // debt behind.
        //
        // The same ordering applies to the arena rescue, and for a second reason: the initial
        // position packet has not gone out yet, so a teleport issued now would be overwritten by
        // the one the join sequence sends a few lines later. Looked up by uuid rather than captured
        // because a respawn inside the same tick replaces the entity.
        UUID joining = player.getUUID();
        deferred.add(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(joining);
            if (online != null) {
                homes.rescue(online);
            }
        });
    }

    /**
     * Starts any round whose intermission has elapsed.
     *
     * <p>Runs from {@code tick()} rather than from a scheduler because everything else in this class
     * is already tick-driven, and a second timing mechanism would be a second thing to reason about
     * after a restart. A round that cannot start — a participant logged out during the intermission —
     * voids the series instead of throwing out of the tick loop.</p>
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
            try {
                startRound(session);
            } catch (RuntimeException e) {
                // startRound increments the round counter before it can fail, and a cut must not
                // count a round that was never played.
                session.round = before;
                if (session.participants.stream().anyMatch(p -> online(p) == null)) {
                    // Somebody logged off during the intermission. That is an ordinary end to a
                    // series, not a failure: abort() would discard the score and record a clean
                    // logoff as an anomaly the next boot has to reconcile.
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
     * <p>Runs every tick over a map that is empty in the common case, so the cost is a single
     * {@code isEmpty()} check. Attempts are one-shot: the entry is removed before the attempt, so a
     * snapshot that cannot be applied produces one error instead of one per tick. A subsequent
     * restart re-queues it from disk if the participant is still marked unrestored.</p>
     */
    private void flushPendingRestores() {
        if (pendingRestore.isEmpty()) {
            return;
        }
        List<UUID> ready = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : pendingRestore.entrySet()) {
            if (server.getPlayerList().getPlayer(entry.getKey()) != null) {
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
            // A deferred restore has two causes and they read very differently to a player: the
            // session was voided by a crash, or it finished normally and they simply left early.
            MutableComponent note = PvLang.t(session.voidReason == null
                    ? "pvpocket.msg.restore.done"
                    : "pvpocket.msg.restore.done_voided");
            player.displayClientMessage(note.withStyle(ChatFormatting.YELLOW), false);
        }
    }

    public void onDisconnect(ServerPlayer player) {
        // Before the participant check below, because a spectator is by definition not a
        // participant. Their game mode is restored here rather than deferred, so the playerdata
        // written during this same disconnect records survival rather than spectator — otherwise
        // they log back in inside the arena with no way to fly out.
        spectators.onDisconnect(player);
        // Last chance to record where they were: after this the playerdata is written and the
        // player is gone, and a disconnect is the one moment the saved home is guaranteed current.
        homes.remember(player);
        homes.flush();
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            // A negotiation is not in sessionByPlayer — nothing has started yet — but a player who
            // drops out of one leaves everybody else waiting for an answer that can no longer
            // arrive, and the panel on their side cannot tell "still thinking" from "gone".
            // Deferred so the session is not mutated in the middle of the disconnect unwinding.
            DuelSession open = findOpenSession(player.getUUID());
            if (open != null && open.stage == SessionStage.NEGOTIATING) {
                String uuid = player.getStringUUID();
                deferred.add(() -> dropNegotiator(open, uuid));
            }
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null) {
            return;
        }
        // Leaving is an elimination, not a verdict on the whole round: in a 2v2 the other member of
        // the side is still standing and can win it. The round only ends when the last member of a
        // side is gone, which for a 1v1 is the same instant as before.
        deferred.add(() -> eliminate(session, me, "DISCONNECT"));
    }

    /**
     * Vetoes damage between team-mates when {@code b.friendlyFire} is off.
     *
     * <p>A veto rather than a reduction: "teammates cannot hurt each other" is a rule, not a
     * difficulty slider, and a half-damage version would still let a side kill itself. Only the
     * direct attacker is checked — a teammate's arrow, trident or TNT counts, because the source
     * carries who fired it, but environmental damage from a teammate's fire does not, which is the
     * same line vanilla draws for its own team checks.</p>
     *
     * <p>Scoped to {@code ROUND_ACTIVE} on purpose. In the preparation window and between rounds
     * nobody can be hurt anyway (the participants are invulnerable), and a veto that also covered
     * those stages would silently block damage in the survival world for anybody who happened to
     * share a session.</p>
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
        // Journalled, not just dropped: "my hit did nothing" is otherwise indistinguishable from a
        // lag spike, and a 2v2 where friendly fire is quietly eating hits is a bug report nobody
        // can reproduce from the chat log alone.
        journal(session).append("FRIENDLY_FIRE_BLOCKED",
                "attacker", theirs.name, "victim", mine.name, "team", mine.teamIndex());
        return false;
    }

    /**
     * The hazards the rule set can switch off: falling, lava, the void, and starvation.
     *
     * <p>Deliberately not journalled. Friendly fire is journalled because a blocked hit is rare and
     * looks like lag; standing in lava with the hazard off would write a line every ten ticks and
     * bury everything else. The rules themselves are already in the journal at session start, which
     * is the question someone debugging this actually asks.</p>
     *
     * <p>Starving is folded in here rather than in a second mixin: with {@code c.hunger} off,
     * exhaustion never accumulates, so the hunger bar cannot reach zero on its own — but a player
     * who starts a round already empty (a reloaded save) would still take starvation damage, and
     * "关闭后不会因饥饿掉血" has to be true for them too.</p>
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
     * <p>Static and null-safe because it is called from a mixin that runs on the client too: a
     * client has no manager, and a player who is not in a duel has no session, and in both cases
     * the answer is "vanilla".</p>
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

    /** {@code d.allowPickup}, asked from {@code ItemEntity#onPlayerCollision}. */
    public static boolean pickupAllowed(UUID uuid) {
        DuelSession session = activeSessionOf(uuid);
        return session == null || session.rulebook().allowPickup();
    }

    /** {@code d.allowCrafting}, asked from {@code Slot#canTakeItems} on a result slot. */
    public static boolean craftingAllowed(UUID uuid) {
        DuelSession session = activeSessionOf(uuid);
        return session == null || session.rulebook().allowCrafting();
    }

    /**
     * {@code f.allowExplosives}, asked from {@code Explosion#collectBlocksAndDamageEntities} and
     * {@code Explosion#affectWorld}.
     *
     * <p>The two sides ask different questions, because they know different things. The server looks
     * the plot up by position: an explosion has no owner — a creeper is nobody's — so the only
     * thing that identifies which rule set applies is where it went off. The client has no sessions
     * at all and only ever renders its own surroundings, so it uses the flag its own payload
     * carried.</p>
     *
     * <p>Getting the client copy right is not cosmetic. {@code affectWorld} destroys blocks on
     * whichever side runs it, so a client that has not been told would carve holes that the server
     * never confirms — a crater only the person who lit the fuse can see.</p>
     */
    public static boolean explosivesAllowed(Level world, Vec3 pos) {
        if (world == null) {
            return true;
        }
        if (world.isClientSide()) {
            return CombatState.clientExplosivesAllowed();
        }
        SessionManager manager = instance;
        if (manager == null || !ArenaManager.isArena(world)) {
            return true;
        }
        ArenaRegion region = ArenaManager.regionAt(pos.x, pos.z);
        if (region == null) {
            return true;
        }
        DuelSession session = manager.sessions.get(region.id);
        return session == null || session.stage != SessionStage.ROUND_ACTIVE
                || session.rulebook().explosives();
    }

    /** The same answer for one player, which is what the client mirror is built from. */
    public static boolean explosivesAllowedFor(ServerPlayer player) {
        DuelSession session = activeSessionOf(player.getUUID());
        return session == null || session.rulebook().explosives();
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
     * Last tick each player was told why their click did nothing. Server thread only — the client
     * copy of the veto returns before it touches this, and a client that never sees the map cannot
     * race it.
     */
    private static final Map<UUID, Integer> useWarnAt = new HashMap<>();

    /**
     * Tells a player why their click did nothing.
     *
     * <p>Only the server speaks. The client runs the same veto so its prediction stays honest, but a
     * sentence from there would be a local guess at the server's reason. Throttled because holding
     * right-click with a banned block re-attempts the placement every few ticks, and five lines a
     * second is not an explanation, it is a wall.</p>
     */
    public static void reportUseDenial(Player player, UsePolicy.Denial denial, ItemStack stack) {
        if (denial == UsePolicy.Denial.NONE || !(player instanceof ServerPlayer server)) {
            return;
        }
        MutableComponent message = switch (denial) {
            case PLACE -> PvLang.t("pvpocket.msg.use.place");
            // Named, not generic: "方块限制" is five switches, and "something is banned" leaves the
            // player ticking all five to find out which one they tripped.
            case BANNED -> PvLang.t("pvpocket.msg.use.banned", PvLang.t(
                    "pvpocket.rule.f.blockFilter.c" + UsePolicy.banIndex(UsePolicy.banOf(stack))));
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
     * <p>Worth a sentence, unlike a refused pickup: an item that stays on the ground explains
     * itself, but a crafting result that simply will not come out looks like a broken slot.</p>
     */
    public static void reportCraftingDenial(Player player) {
        if (player instanceof ServerPlayer server) {
            warnThrottled(server, PvLang.t("pvpocket.msg.use.craft"));
        }
    }

    /**
     * A loadout item with 禁止丢弃 refused to leave the inventory.
     *
     * <p>Silence would be indistinguishable from a dropped keybind: the item simply stays in the hand
     * and nothing happens. Throttled with the other refusals because the Q key repeats while held,
     * and "this item is locked" five times a second is a wall, not an explanation.</p>
     *
     * <p>Server only, like the rest: the client runs the same veto so its own prediction does not
     * remove the stack for a frame, but it does not speak — a sentence from there would be a local
     * guess at why the server said no.</p>
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
     * {@code PlayerManager#broadcast} — so returning false cancels vanilla's fan-out and leaves the
     * delivery to us. That is the point: "仅队友" is not a filter applied to each recipient, it is a
     * different recipient list.</p>
     *
     * <p>Taking the broadcast over means taking the decoration over too. Vanilla prefixes the line
     * with the speaker's name inside {@code broadcast}; skipping that step would deliver the raw
     * sentence and leave every line in a duel unattributed, so the same
     * {@code applyChatDecoration} call is made here.</p>
     *
     * <p>Scoped to {@code ROUND_ACTIVE} like the damage veto, and for the same reason: a rule that
     * outlives the round would silently mute the lobby for anybody who shares a session.</p>
     */
    public static boolean allowChat(PlayerChatMessage message, ServerPlayer sender,
                                    ChatType.Bound params) {
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
        Component decorated = params.decorate(message.decoratedContent());
        if (scope == Rulebook.ChatScope.MUTED) {
            warnThrottled(sender, PvLang.t("pvpocket.msg.chat.muted"));
            return false;
        }
        Participant mine = session.participant(sender.getStringUUID());
        if (mine == null) {
            // In the session but not on a side — a spectator. There is no team to talk to, so the
            // line goes back to them alone rather than to every side at once.
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
     * <p>Throttled because every caller sits on a path the client retries while a button is held:
     * five lines a second is not an explanation, it is a wall.</p>
     */
    private static void warnThrottled(ServerPlayer player, MutableComponent message) {
        if (message == null) {
            return;
        }
        MinecraftServer mc = player.getServer();
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
     * <p>Assembled here rather than inside {@code Rulebook} so the rules layer stays free of the
     * combat layer's bit layout: the rulebook answers "is placing allowed", the gate is how that
     * answer is shipped.</p>
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
        CombatState.setUseGate(player.getUUID(), gate, book.blockBans());
    }

    /**
     * The round clock: {@code a.roundLimit} was a promise with no timer behind it.
     *
     * <p>Two players who both refuse to engage could keep a round open indefinitely — the only exits
     * were a surrender or a disconnect, and a surrender can be switched off. The clock reuses the
     * timestamp the settle deadline already keeps, so it starts when the round actually starts
     * rather than when the preparation countdown does.</p>
     *
     * <p>Runs only when the timeout is also an accepted win condition: {@code a.winConditions}
     * without 超时 means "this round cannot end on a clock", and a timer that fires into a verdict
     * nobody accepts is worse than no timer.</p>
     *
     * @return true when the round ended here, so the caller must not keep judging it
     */
    private boolean tickRoundLimit(DuelSession session) {
        Rulebook book = session.rulebook();
        int limit = book.roundLimitSeconds();
        if (limit <= 0 || !book.winsBy(Rulebook.WinCondition.TIMEOUT)) {
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
            // Built per player rather than once and reused: a Text that is mutated for one recipient
            // is the same object the next one would get.
            MutableComponent line = byHealth ? PvLang.t(key, teamName(session, winner)) : PvLang.t(key);
            player.displayClientMessage(line.withStyle(ChatFormatting.YELLOW), false);
        }

        String reason = switch (verdict) {
            case HIGHEST_HEALTH -> byHealth ? "TIMEOUT" : "TIMEOUT_DRAW";
            case DRAW -> "TIMEOUT_DRAW";
            case DOUBLE_LOSS -> "TIMEOUT_DOUBLE";
        };
        // Deferred, like every other settle reached from this loop: settling runs the intermission
        // and can finish the series, and doing that while the caller is iterating the sessions is
        // how the boundary path used to take the server down.
        deferred.add(() -> settle(session, reason, winner));
        return true;
    }

    /**
     * The side with the most health left, or {@code -1} when the lead is shared.
     *
     * <p>Health of the whole side, not of one member: in a 2v2 one player on ten hearts and one on
     * one heart is not "the team with ten hearts". Eliminated players count as zero — they are
     * already out of the round.</p>
     */
    private int healthLeader(DuelSession session) {
        Map<Integer, Double> totals = new LinkedHashMap<>();
        for (Participant p : session.participants) {
            if (p.rejected) {
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
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null || me.eliminated) {
            return;
        }
        // Marked here but settled on respawn: a dead player cannot be teleported, and the settle
        // path teleports people. The decision is recorded now so a crash between death and respawn
        // still leaves a journalled outcome.
        me.eliminated = true;
        recordDeath(session, me, player);
        session.pendingWinner = me.uuid;
        session.pendingReason = "DEATH";
        session.pendingWinnerTeam = lastTeamStanding(session);
        journal(session).append("ROUND_DECIDED", "reason", "DEATH",
                "player", me.name,
                "winnerTeam", session.pendingWinnerTeam,
                "alive", survivorsOf(session));
        persist(session);
    }

    /**
     * A dead player cannot be teleported, so the settle happens once they are alive again. By then
     * the entity has been replaced, which is why everything is looked up by uuid.
     *
     * <p>If their side still has somebody standing, there is nothing to settle — the round goes on and
     * the dead player is put back into the plot as a spectator so they can watch it. That is the
     * behaviour a team match needs and a 1v1 never reaches.</p>
     */
    public void onRespawn(ServerPlayer player) {
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        if (session.pendingWinnerTeam >= 0) {
            int team = session.pendingWinnerTeam;
            String reason = session.pendingReason;
            deferred.add(() -> settle(session, reason, team));
            return;
        }
        deferred.add(() -> watchRestOfRound(session, player));
    }

    /**
     * Puts an eliminated participant back on the plot as a spectator for the rest of the round.
     *
     * <p>Without this they respawn wherever vanilla drops them — the survival world, with an empty
     * inventory, for the length of a round they are no longer part of. Spectating is both more useful
     * and more honest: they are still in the match, they just cannot affect it. The round start puts
     * them back into survival and at their spawn.</p>
     */
    private void watchRestOfRound(DuelSession session, ServerPlayer player) {
        if (session.stage != SessionStage.ROUND_ACTIVE || !player.isAlive()) {
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        ArenaRegion region = ArenaManager.region(session.id);
        if (me == null || region == null || !me.eliminated) {
            return;
        }
        Sandbox.changeGameMode(player, GameType.SPECTATOR);
        ArenaManager.teleportToSpawn(player, region, me.teamIndex(), me.member,
                Math.max(1, session.teamMembers(me.teamIndex()).size()));
        // The round is still live, so the "decided but unapplied" record is stale: it named this
        // player's respawn as the thing the settle was waiting for, and the settle is not coming.
        // Left in place, a later reader would see a decided round that never resolves.
        session.pendingWinner = null;
        session.pendingReason = null;
        persist(session);
        player.displayClientMessage(PvLang.t("pvpocket.msg.round.eliminated")
                .withStyle(ChatFormatting.GRAY), false);
    }

    // ------------------------------------------------------------------ tick

    /**
     * Keeps the "where was I before the arena" record current.
     *
     * <p>Sampling every player every tick rather than hooking movement: the store ignores anything
     * smaller than two blocks, so a stationary player costs one comparison, and hooking a movement
     * event would miss the case that matters most — a player who logs in, walks nowhere, and
     * disconnects from inside the arena.</p>
     */
    private void tickHomes() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            homes.remember(player);
        }
        homes.flushIfDue();
    }

    public void tick() {
        if (!deferred.isEmpty()) {
            List<Runnable> copy = new ArrayList<>(deferred);
            deferred.clear();
            copy.forEach(Runnable::run);
        }
        flushPendingRestores();
        startDueRounds();
        tickPrepare();
        // Spectators: drops watchers of finished series and repairs cameras pointing at a body that
        // was replaced by a respawn. Empty in the common case, so the cost is one isEmpty check.
        spectators.tick();
        // After the spectator pass, not before: that pass can end somebody's watch and teleport them
        // out in this same tick, and a guard running first would see them as a stray and race it.
        guard.tick();
        tickHomes();
        // Natural spawning is cancelled inside the arena dimension, so this is only a net for the
        // doors that bypass it (spawn eggs, spawner blocks, /summon). Three seconds is frequent
        // enough that a stray mob never gets to swing at anyone.
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
            // The clock runs before the boundary pass and can end the round; settling and then
            // walking the participants would judge a round that is already over.
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
                if (ArenaManager.isInside(player, region)) {
                    // First sighting on the plot settles this player. From here on the boundary rule
                    // is theirs to test — walking out is a decision, not a loading glitch.
                    settledIn.add(key);
                    // Coming back cancels the countdown. Announced, because a countdown that silently
                    // stops leaves the player unsure whether they are still at risk.
                    if (boundaryExits.remove(key) != null) {
                        journal(session).append("BOUNDARY_RETURNED", "player", p.name);
                        player.displayClientMessage(PvLang.t("pvpocket.msg.boundary.returned")
                                .withStyle(ChatFormatting.GREEN), false);
                        // Wipes the big number instead of leaving a stale "1" hanging in the middle
                        // of the screen after the player has already saved themselves.
                        player.connection.send(new ClientboundClearTitlesPacket(false));
                    }
                    continue;
                }
                // Logged with coordinates on purpose: "left the arena" has two very different
                // causes (a player walked out, or a teleport did not stick) and the position is
                // the only thing that tells them apart after the fact.
                //
                // Two reasons to snap back instead of judging:
                //   - the player has not been seen on the plot even once yet, so the position being
                //     read is a client that is still streaming a dimension it has never loaded;
                //   - the rule is 拉回, in which case snapping back is the rule.
                // Anything else falls through to the countdown. Note what is deliberately NOT here:
                // a fixed opening window. A 10-second window made the rule unobservable — every
                // setting pulled back identically for the first ten seconds, which read as "the
                // config panel does nothing".
                if (awaitingFirstEntry(session, key) || boundaryIsPushBack(session)
                        || !session.rulebook().winsBy(Rulebook.WinCondition.OUT_OF_BOUNDS)) {
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
                            "world", player.level().dimension().location().toString(),
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
                    // Through eliminate() rather than settle(): leaving the plot removes one player,
                    // and in a 2v2 that is not the same thing as losing the round.
                    deferred.add(() -> eliminate(session, p, "OUT_OF_BOUNDS"));
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
     * <p>This is what replaced a fixed ten-second opening window. The window was there for one
     * concrete failure — a client that has never loaded the arena dimension reports a position from
     * before the teleport for a moment, and judging that as "walked out" loses the round during the
     * loading screen. But a window is a blunt instrument: for its whole length the boundary rule is
     * ignored, so every setting behaves the same, and a player who walks out inside it is teleported
     * back over and over with no way to tell why. It looked exactly like the config panel doing
     * nothing.</p>
     *
     * <p>The condition that actually needs protecting is narrower: <em>has this player ever been on
     * the plot?</em> Once they have, their position is trustworthy and the rule applies from the very
     * next tick. The spawn pads are four blocks inside each end, so the first tick after the teleport
     * settles them — the protection is spent before anyone can move.</p>
     *
     * <p>{@link #SETTLE_DEADLINE_MS} is the escape hatch: a player who has somehow never been seen on
     * the plot for thirty seconds is not loading any more, and keeping them under the pull-back rule
     * would trap them forever.</p>
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
     * <p>Action bar, not chat: this is a state that repeats, and a repeated chat line pushes the
     * fight's own messages off the screen. The journal line is throttled by the same gate — nine
     * identical {@code BOUNDARY_PUSHED_BACK} rows for one player leaning on the edge is noise, not
     * evidence.</p>
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
     * <p>A title rather than the action bar because of where the player's eyes are: someone who has
     * just walked out of the arena is looking at the horizon, not at the hotbar. The fade is zero-in,
     * one-second stay, short fade-out so each number replaces the previous one instead of queueing
     * behind it.</p>
     */
    private static void announceBoundary(ServerPlayer player, int second) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 22, 4));
        // Not bold: vanilla bold is a second draw of the same glyph shifted one pixel, which the
        // bundled bitmap font has a hand-made sheet for but a resource pack's TrueType font does
        // not — the number reads as smeared at the four-times scale a title is drawn at.
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal(String.valueOf(second)).withStyle(ChatFormatting.RED)));
    }

    /**
     * Drops every per-player boundary state belonging to a session. Called at every round boundary.
     *
     * <p>{@code settledIn} goes too, and that is the important part: the "have I been on the plot
     * yet" answer is per round, because each round teleports everyone to a spawn pad again. Leaving a
     * stale entry would skip the loading protection on round two, which is exactly when a client has
     * the dimension cached and would not need it anyway — but the symmetry is cheaper to reason about
     * than the exception.</p>
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
     * Opens a negotiation. Nothing is snapshotted and no rule is in force yet — the session sits in
     * {@link SessionStage#DRAFT} until the challenger sends a first proposal, which is what makes a
     * abandoned panel harmless: reconcile drops drafts on restart instead of rolling anyone back.
     *
     * <p>Any number of targets, so a team match is one command rather than a lobby: the challenger
     * plus everyone invited form the participant list, and the sides are dealt out round-robin when
     * the match starts. Sides are <em>derived</em> from the order rather than stored on the invite,
     * which is what lets the proposer change {@code b.teamCount} in the panel and watch the roster
     * re-deal itself without anybody being re-invited.</p>
     */
    /**
     * Opens the roster panel: a draft session whose only member is the proposer.
     *
     * <p>The invite is no longer a command argument. {@code /pvpocket duel} used to take a player
     * selector, which could say "everyone here" but could not say "these two on one side and those
     * two on the other" — Brigadier has no grammar for a two-dimensional list, so the sides were
     * dealt round-robin from the invite order instead. Building the roster in the panel makes the
     * sides explicit, and it costs nothing: the same panel has to open anyway to agree on rules.</p>
     *
     * <p>The session exists from the moment the panel opens, in {@link SessionStage#DRAFT}. One
     * record per negotiation rather than a client-side draft plus a server-side session, and it is
     * what lets the proposer close the panel and come back with {@code /pvpocket review}.</p>
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
                // Somebody else is waiting on an answer here, so the old invite is not the
                // proposer's to throw away — they have to answer it or refuse it first.
                fail(proposer, "pvpocket.msg.duel.own_pending");
                return;
            }
            // A draft has no other party, and it is the only place the sides the proposer drew
            // exist. Voiding it to make room for a new one would throw that work away for no gain;
            // reopening is what "start a duel" means when a draft is already in progress, and it is
            // also how a client that crashed mid-draft gets its panel — and its roster — back.
            // Refusing here instead is what turned a crash into "this player can never start a duel
            // again", which is the lock this branch exists to remove.
            openPanel(proposer, open, RulesOpenPayload.ROLE_DRAFT);
            return;
        }

        DuelSession session = new DuelSession();
        session.id = UUID.randomUUID().toString();
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        Participant me = new Participant(proposer.getStringUUID(),
                proposer.getName().getString(), 0, 0);
        // The proposer's own book becomes the match's set. Everyone invited after this inherits it
        // instead of bringing their own — see seedLoadout.
        seedLoadout(me, loadouts.get(proposer.getUUID()));
        session.participants.add(me);
        session.teamCount = MIN_TEAMS;

        DuelRules rules = DuelRules.defaults();
        applyRulesToSession(session, rules);
        session.rulesRevision = 0;
        session.rulesAuthor = proposer.getStringUUID();

        sessions.put(session.id, session);
        journal(session).append("SESSION_DRAFTED", "proposer", me.name);
        persist(session);
        openPanel(proposer, session, RulesOpenPayload.ROLE_DRAFT);
    }

    /**
     * Turns the roster the proposer assembled into the participant list, then invites everybody.
     *
     * <p>Encoding is {@code "uuid,uuid;uuid"}: {@code ,} between team-mates, {@code ;} between
     * sides. An empty side is a side the proposer did not use and is dropped here, so team indices
     * stay contiguous. Uuids rather than names because a name can be changed and a uuid cannot.</p>
     *
     * <p>Validated before it is applied, not after. An invalid roster that had already been written
     * onto the session would put strangers into {@code findOpenSession} — they would be told they
     * have a pending invite to a match nobody ever offered them.</p>
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

        // An empty card is a card the proposer did not use, and it stops being meaningful the moment
        // the invite goes out: the panel that arrives at the other end is rebuilt from the roster, so
        // a gap in the middle would come back as a third side with nobody on it — a side the HUD
        // would draw a row for, with a wool colour no block in the arena matches. Compacting here
        // keeps "team index" and "playing side" the same number everywhere downstream.
        List<List<UUID>> playing = new ArrayList<>();
        for (List<UUID> side : sides) {
            if (!side.isEmpty()) {
                playing.add(side);
            }
        }

        // Presence is checked last because it is the only part that depends on other players, and a
        // name is more useful in the refusal than a rule id.
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
                // A roster rebuild changes who plays, not what they bring. 发送邀请 re-runs this
                // method over the same uuids, and seeding every participant from the saved book
                // here threw away a loadout edited in the panel moments earlier — the round then
                // handed out the previous items, which is indistinguishable from "the switch I
                // just flipped does nothing". Only genuinely new participants get seeded.
                Participant previous = session.participant(uuid.toString());
                if (previous != null && previous.loadout != null) {
                    p.loadout = previous.loadout;
                    p.loadoutDigest = previous.loadoutDigest;
                    p.pendingLoadout = previous.pendingLoadout;
                    p.pendingLoadoutDigest = previous.pendingLoadoutDigest;
                    p.acceptedLoadoutRevision = previous.acceptedLoadoutRevision;
                    p.agreedLoadoutDigest = previous.agreedLoadoutDigest;
                } else {
                    // A newcomer does not bring their own kit. The set belongs to the match, and it
                    // was seeded from the proposer's book when the session was created — inviting
                    // somebody must not swap the items out from under the players already in it.
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
     * <p>There is exactly one set per match, so the seed is never "this player's own saved
     * loadout" except for the very first participant: the proposer's book becomes the match's set,
     * and every player invited afterwards is seeded from that set instead of from their own book.
     * Handing each side its own kit is what used to make "his sword has Sharpness and mine does
     * not" possible in a duel that was supposed to be symmetric.</p>
     *
     * <p><b>Only for newcomers.</b> This seeds a participant that has never existed; a rebuild of
     * the roster must carry the existing record's loadout over instead, because the panel's loadout
     * editor writes into the participant and not into this book — see {@link #submitRoster}.</p>
     */
    private void seedLoadout(Participant p, Loadout seed) {
        seed.autoAssignSlots();
        p.loadout = seed.encode();
        p.loadoutDigest = seed.digest();
    }

    /**
     * The one set this match fights with.
     *
     * <p>Both sides carry the same items, the same attributes and the same enchantments; the only
     * thing that differs between them is the wool colour. That is not a rule any more — it used to
     * be {@code d.loadoutScope} (每人独立 / 每队统一) and the switch was deleted, because a match
     * with two different kits is not a duel, it is two players comparing their editors, and every
     * report of "some of my weapon's attributes only showed up on his side" came from exactly that
     * state being reachable.</p>
     *
     * <p>Derived from the participants rather than stored as a field: every write path copies the
     * set onto every participant ({@link #adoptSharedLoadout}), so the first live copy <em>is</em>
     * the set, and deriving it keeps a session written by an older build readable. The preset is
     * the last resort, for a match that never configured anything.</p>
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
     * <p>Everyone, not just whoever pressed 保存. The copies are what the panels draw and what the
     * consent digest is computed from, so a save that only touched the submitter's record left the
     * other players looking at — and agreeing to — a document the round would not read. Keeping all
     * copies identical is also what makes {@link #sharedLoadout} able to derive the set at all.</p>
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
     * <p>Empty cards are not an error here — they are how the roster says "this side does not
     * play". What is refused is a side that is over the size cap, and a roster that leaves fewer
     * than two sides with anybody on them, because that is a match with nobody to fight.</p>
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

    /** {@code "2+1"} — how many players each side brought, for the journal and the result line. */
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
     * {@code "2+0"} — how many of each side are still standing.
     *
     * <p>Separate from {@link #shapeOf} because "how many players did each side bring" and "how many
     * are left" are the same number only at the start of a round. A journal that logged the roster
     * size as the survivor count said {@code alive: "2+2"} after two of the four had been killed,
     * which is the one line somebody would read to work out why a round ended when it did.</p>
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

    /** Sends (or re-sends) the panel to a player. Used by the packet path and by /pvpocket review. */
    private void openPanel(ServerPlayer player, DuelSession session, int role) {
        // The tally on the panel is "how many have pressed Accept", not "how many have agreed to the
        // loadout": with three or more players the second number is always zero until the last
        // person answers, so showing it would tell everybody the negotiation is going nowhere.
        int agreed = acceptedCount(session);
        PvPocketNetworking.openRules(player, new RulesOpenPayload(
                session.id,
                session.rules,
                role,
                session.rulesRevision,
                new RulesOpenPayload.Details(
                        otherName(session, player.getStringUUID()),
                        loadoutCounts(session),
                        agreed,
                        liveCount(session),
                        // Recomputed on every open, not cached: a map frees up the moment the other
                        // duel ends, and a stale "in use" is a map nobody can pick until they
                        // reopen the panel.
                        ArenaManager.busyMapMask(session.id),
                        // Only the draft can edit the roster, so only the draft needs the candidate
                        // list. Sending it to a reviewer — or to a proposer who has already sent the
                        // invite — would be four hundred bytes of names they cannot act on.
                        role == RulesOpenPayload.ROLE_DRAFT
                                ? candidates(player)
                                : "")));
    }

    /**
     * Players this proposer could add to a card right now, as {@code "name|uuid"} pairs.
     *
     * <p>Filtered server-side because only the server knows who is already in a duel, already has an
     * invite waiting, or is watching rather than playing. A picker that offers a name and then
     * refuses it is worse than a picker that never offered it.</p>
     *
     * <p>Ordered by name so the list does not reshuffle between two opens, and capped by the caller
     * through {@code b.maxPlayers} rather than here: this is the pool, not the roster.</p>
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
     * {@code Dev1|uuid:8:0:1} — item count, side and answer state, one record per participant.
     *
     * <p>Every participant's count is shown, not just the reader's: the loadout is part of what is
     * being agreed to, so "what am I agreeing to" has to include what the other side is bringing.
     * The panel pairs this with the agreement counter, which is what turns a silent veto into a
     * visible one — a player who changed their loadout and broke everyone else's consent can see the
     * count drop back to 0/2.</p>
     *
     * <p>The side index is the third field because the panel is the only place a roster can be
     * checked. It used to be re-dealt round-robin from a rule; it is now whatever the proposer drew,
     * and without this field the cards on the review panel would have nothing to draw from.</p>
     *
     * <p>The fourth field is the answer state — {@code 0} has not answered, {@code 1} accepted the
     * current revision, {@code 2} declined. It rides here rather than in a field of its own because
     * the panel already walks this list to draw the cards, and a second list would be a second thing
     * that can disagree with the first.</p>
     *
     * <p>Encoded rather than formatted because the reader's language is not known here. Player names
     * cannot contain {@code :}, {@code ,} or {@code |}, so the format is unambiguous without
     * escaping. The uuid rides along because the proposer has to send the roster back: the cards are
     * rebuilt from this list, and a name is not something the server can resolve to an account.</p>
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
     * <p>Sorted by uuid because {@code participants} order is a list position, not a fact: two
     * sessions with the same loadouts must produce the same digest regardless of who was added
     * first, otherwise the value is not comparable across the two sides' panels.</p>
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
     * <p>Silent about players who have not answered yet — that is {@link #allResponded}'s job, and
     * keeping the two questions apart is what lets the panel say "you agreed, waiting for the other
     * two" instead of blaming the loadout for a consent that simply has not arrived.</p>
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

    /**
     * Invalidates the negotiation after somebody changed their loadout.
     *
     * <p>The locked rule is "修改后重新向对方征求意见", and the loadout is part of what was proposed.
     * So a change drops the session back to DRAFT and hands the pen back to whoever changed it: their
     * panel comes back in proposer mode, everyone else's comes back in reviewer mode, and the next
     * 发送征询 is what re-establishes consent.</p>
     *
     * <p>{@code before} is the combined fingerprint from just before the edit. A save that produced
     * the same fingerprint has not changed the terms — a player who opened the editor, changed
     * nothing and pressed save would otherwise watch both panels flicker and the agreement counter
     * drop to zero for no reason, which teaches people to ignore the one signal that matters.</p>
     */
    private void noteLoadoutChanged(DuelSession session, Participant changed, String before) {
        String after = combinedLoadoutDigest(session);
        if (before != null && before.equals(after)) {
            persist(session);
            return;
        }
        if (session.stage == SessionStage.DRAFT) {
            // Nothing has been sent yet, so there is no consent to invalidate. The author is kept
            // unless the session has none: the target can open their own loadout editor before the
            // first proposal, and that must not make them the proposer.
            if (session.awaitingUuid == null) {
                session.awaitingUuid = changed.uuid;
            }
            persist(session);
            return;
        }
        if (session.stage != SessionStage.NEGOTIATING) {
            return;
        }
        journal(session).append("LOADOUT_REOPENED",
                "player", changed.name, "digest", changed.loadoutDigest,
                "combined", combinedLoadoutDigest(session));
        transition(session, SessionStage.DRAFT);
        session.awaitingUuid = changed.uuid;
        persist(session);
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player != null) {
                openPanel(player, session, p.uuid.equals(changed.uuid)
                        ? RulesOpenPayload.ROLE_PROPOSER
                        : RulesOpenPayload.ROLE_REVIEWER);
            }
        }
        String name = changed.name;
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player != null) {
                player.displayClientMessage(PvLang.t("pvpocket.msg.rules.loadout_changed", name)
                        .withStyle(ChatFormatting.GOLD), false);
            }
        }
    }

    /** Re-opens the panel for whoever is currently expected to answer. Command fallback path. */
    public void review(ServerPlayer player) {
        DuelSession session = findOpenSession(player.getUUID());
        if (session == null) {
            fail(player, "pvpocket.msg.rules.none_pending");
            return;
        }
        // A draft belongs to exactly one player and is always theirs to edit: there is no reviewer
        // in it. Getting this wrong is what made a crashed draft unrecoverable — the proposer was
        // handed a reviewer panel whose only options are accept / reject / re-propose, and none of
        // those can touch a roster that was never sent. Rejecting marked the proposer rejected
        // inside their own draft, and nothing in the draft stage ever settles that mark.
        //
        // Past the draft the "proposer" is whoever authored the revision on the table, not whoever
        // is being waited on: the pen passes when somebody edits and re-sends, and the panel has to
        // follow the pen.
        int role = session.stage == SessionStage.DRAFT
                ? RulesOpenPayload.ROLE_DRAFT
                : player.getStringUUID().equals(session.rulesAuthor)
                ? RulesOpenPayload.ROLE_PROPOSER
                : RulesOpenPayload.ROLE_REVIEWER;
        openPanel(player, session, role);
        player.displayClientMessage(PvLang.t("pvpocket.msg.rules.version",
                        session.rulesRevision, shortDigest(session))
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /**
     * Handles one panel outcome.
     *
     * <p>Every branch re-derives permission from the session record. The client tells us what it
     * wants to do, never whether it is allowed to: only the player the session is waiting on may
     * act, and only the author of the current revision may replace it.</p>
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
                return;
            }
            // Past the draft, closing the panel is not "nothing happened". A reviewer who walks
            // away leaves everybody else waiting for an answer that will never come, and
            // allResponded never turns true — the match simply stops existing without saying so.
            // Their silence is recorded as a refusal so the session can settle. The author of the
            // revision on the table is left alone: their yes is already on the record, and all
            // they have to do to get the panel back is /pvpocket review.
            if (session.stage == SessionStage.NEGOTIATING && !uuid.equals(session.rulesAuthor)) {
                Participant me = session.participant(uuid);
                if (me != null && !me.rejected) {
                    me.rejected = true;
                    me.acceptedRevision = -1;
                    journal(session).append("PLAYER_WALKED_AWAY", "player", me.name,
                            "revision", session.rulesRevision,
                            "playingTeams", session.playingTeams().size());
                    persist(session);
                    player.displayClientMessage(PvLang.t("pvpocket.msg.rules.rejected_self")
                            .withStyle(ChatFormatting.GRAY), false);
                    broadcastNegotiation(session, uuid,
                            PvLang.t("pvpocket.msg.rules.rejected_by", me.name));
                    settleNegotiation(session, player);
                }
            }
            return;
        }

        if (action == RulesSubmitPayload.ACTION_REJECT) {
            // Refusing is not the same as cancelling any more. With an explicit roster a refusal
            // takes that player out and the rest of the invite carries on: four invited, one
            // declines, and the remaining three can still play as a 2v1. The match is only voided
            // when what is left cannot field two sides.
            Participant me = session.participant(uuid);
            if (me == null) {
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            if (session.stage == SessionStage.DRAFT) {
                // In a draft there is nobody to refuse: the only person in it is the proposer, so
                // this is a cancel wearing the wrong label. Marking them rejected instead left a
                // draft that could never be sent and never be answered — a permanent lock.
                journal(session).append("SESSION_DROPPED", "stage", "DRAFT", "reason", "REJECTED");
                session.voidReason = "PROPOSER_REJECTED";
                transition(session, SessionStage.VOIDED);
                closePanels(session);
                return;
            }
            me.rejected = true;
            me.acceptedRevision = -1;
            journal(session).append("PLAYER_REJECTED", "player", me.name,
                    "revision", session.rulesRevision,
                    "remaining", session.participants.size() - rejectedCount(session),
                    "playingTeams", session.playingTeams().size());
            persist(session);
            player.displayClientMessage(PvLang.t("pvpocket.msg.rules.rejected_self")
                    .withStyle(ChatFormatting.GRAY), false);
            broadcastNegotiation(session, uuid,
                    PvLang.t("pvpocket.msg.rules.rejected_by", me.name));
            settleNegotiation(session, player);
            return;
        }

        if (action == RulesSubmitPayload.ACTION_PROPOSE) {
            Participant me = session.participant(uuid);
            if (me == null) {
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            if (me.rejected && session.stage != SessionStage.DRAFT) {
                // A draft rebuilds the participant list from the roster on every send, so a refusal
                // recorded against the old list cannot outlive it. Refusing here instead would let
                // one bad click lock a draft that nobody else can see or release.
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            DuelRules proposed = DuelRules.decode(encoded);
            List<String> changed = DuelRules.decode(session.rules).differences(proposed);
            // Two different meanings, told apart by the stage. In DRAFT the proposer is still
            // building the invite and this is "send it"; in NEGOTIATING the roster is frozen and
            // this is "I changed the terms, ask again".
            if (session.stage == SessionStage.DRAFT) {
                // Validated against the rules that were just sent, not against the defaults the
                // draft opened with: a player who drops "每队人数" to 2 while three names sit on a
                // card has to be told so here, instead of being let into a match its own rules say
                // cannot exist.
                if (!submitRoster(session, player, encodedRoster, proposed)) {
                    // Deliberately no reopen: the client keeps its draft panel up after sending, so
                    // the cards are still on screen and the refusal is a chat line next to them.
                    // Re-sending a panel here would rebuild the cards from the session — which was
                    // not touched — and wipe the roster the player was halfway through fixing.
                    return;
                }
                me = session.participant(uuid);
            }
            applyRulesToSession(session, proposed);
            session.rulesRevision++;
            session.rulesAuthor = uuid;
            session.awaitingUuid = null;
            journal(session).append("RULES_PROPOSED",
                    "revision", session.rulesRevision,
                    "author", player.getName().getString(),
                    "digest", session.rulesDigest,
                    "teams", session.teamCount,
                    "changed", String.join(",", changed));
            // Proposing is also agreeing: the proposer is looking at this exact loadout set and
            // pressing send. Recording it here means a 1v1 needs no separate consent step from the
            // proposer, while a change by either side still invalidates it via the digest.
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
                target.displayClientMessage(PvLang.t("pvpocket.msg.rules.panel_hint")
                        .withStyle(ChatFormatting.DARK_GRAY), false);
                openPanel(target, session, RulesOpenPayload.ROLE_REVIEWER);
            }
            // A re-proposal invalidates every earlier yes, so the proposer's own panel has to come
            // back with the new revision on it — otherwise the number in the corner keeps claiming
            // a version nobody is looking at.
            openPanel(player, session, RulesOpenPayload.ROLE_PROPOSER);
            return;
        }

        if (action == RulesSubmitPayload.ACTION_ACCEPT) {
            if (session.stage != SessionStage.NEGOTIATING) {
                fail(player, "pvpocket.msg.rules.nothing_to_accept");
                return;
            }
            Participant me = session.participant(uuid);
            if (me == null) {
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            // Accepting is agreeing to the rules *and* to everybody's loadout as they stand. Both
            // agreements are recorded per participant: with three or more sides there is no single
            // "the other party" to hand the pen to, so the session starts when every participant has
            // said yes to the current revision — and a re-proposal bumps the revision, which
            // invalidates all earlier yeses by construction rather than by bookkeeping.
            //
            // The rules carried by an ACCEPT are deliberately not applied: the revision on the table
            // is what is being agreed to. A client with unsent edits therefore has to send PROPOSE
            // instead, and the panel refuses to let it send ACCEPT in that state — this branch only
            // sees the case of an older or hand-built client, and records it so the dropped edit is
            // visible afterwards instead of leaving no trace at all.
            if (encoded != null && !encoded.isEmpty() && !encoded.equals(session.rules)) {
                journal(session).append("RULES_ACCEPT_DROPPED_EDITS", "player", me.name,
                        "revision", session.rulesRevision);
            }
            me.acceptedRevision = session.rulesRevision;
            me.agreedLoadoutDigest = combinedLoadoutDigest(session);
            journal(session).append("RULES_AGREED", "player", me.name,
                    "revision", session.rulesRevision,
                    "agreed", acceptedCount(session), "of", liveCount(session));
            journal(session).append("LOADOUT_AGREED", "player", me.name,
                    "combined", me.agreedLoadoutDigest);
            persist(session);
            // Consent is checked before the loadout, not after. They look like the same question —
            // both are "has everybody said yes to what is on the table" — but a player who has not
            // answered yet is missing from both, and asking about the loadout first reported the
            // absence as "还有 N 人没同意当前配装" to somebody whose only real problem was that the
            // other players had not clicked yet. That sentence sends them to the loadout editor,
            // which is the one place the problem is not.
            if (!allResponded(session)) {
                int agreed = acceptedCount(session);
                player.displayClientMessage(PvLang.t("pvpocket.msg.rules.accepted_waiting",
                                agreed, liveCount(session))
                        .withStyle(ChatFormatting.GRAY), false);
                openPanel(player, session, RulesOpenPayload.ROLE_REVIEWER);
                broadcastNegotiation(session, uuid,
                        PvLang.t("pvpocket.msg.rules.other_accepted",
                                player.getName().getString(), agreed, liveCount(session)));
                return;
            }
            if (!allLoadoutsAgreed(session)) {
                // Reachable only if a loadout changed after everybody had already said yes. The
                // invalidation path normally prevents it, so this is a guard, not a route.
                fail(player, "pvpocket.msg.rules.loadout_not_all_agreed");
                openPanel(player, session, RulesOpenPayload.ROLE_REVIEWER);
                return;
            }
            settleNegotiation(session, player);
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

    /**
     * Everybody has answered — accepted the current revision, or declined.
     *
     * <p>Not "everybody accepted": a decline is an answer, and a negotiation that waits forever for
     * a player who has already said no is a negotiation nobody can get out of.</p>
     */
    private static boolean allResponded(DuelSession session) {
        for (Participant p : session.participants) {
            if (!p.responded(session.rulesRevision)) {
                return false;
            }
        }
        return true;
    }

    /** Participants still in the match — declined invites excluded. */
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
     * <p>The rule this encodes: everybody must have answered, and what remains must still be at
     * least two sides with somebody on them. Sides need not be the same size — a 2v1 after one
     * refusal out of four is a match, not an error. Two acceptances that both landed on the same
     * side are not.</p>
     */
    private void settleNegotiation(DuelSession session, ServerPlayer actor) {
        if (session.stage != SessionStage.NEGOTIATING) {
            return;
        }
        if (!allResponded(session)) {
            return;
        }
        if (session.playingTeams().size() < 2) {
            journal(session).append("SESSION_DROPPED", "reason", "NOT_ENOUGH_TEAMS",
                    "remaining", liveCount(session),
                    "playingTeams", session.playingTeams().size());
            session.voidReason = "NOT_ENOUGH_TEAMS";
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
        startAccepted(session, actor);
    }

    /**
     * A player left while the invite was still being negotiated.
     *
     * <p>Recorded as a refusal rather than as a cancellation, for the same reason a refusal is: the
     * rest of the roster may still be able to field two sides. The alternative is that everybody
     * else sits in front of a panel waiting for an answer from somebody who has already closed the
     * game, which is a hang with no visible cause on any screen.</p>
     *
     * <p>Unlike a deliberate refusal this one is not announced as "X declined" — nobody chose
     * anything, they lost their connection — so the line says so.</p>
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
        journal(session).append("PLAYER_LEFT", "player", p.name,
                "revision", session.rulesRevision,
                "remaining", session.participants.size() - rejectedCount(session),
                "playingTeams", session.playingTeams().size());
        persist(session);
        broadcastNegotiation(session, uuid,
                PvLang.t("pvpocket.msg.rules.left_match", p.name));
        settleNegotiation(session, null);
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

    /** Freeze, snapshot, teleport, start. The rule panel is the only caller. */
    private void startAccepted(DuelSession session, ServerPlayer accepter) {
        if (!ArenaManager.isReady(server)) {
            fail(accepter, "pvpocket.msg.rules.arena_not_ready");
            return;
        }
        // A reset re-stamps one map per tick. Leasing a cell mid-rebuild would race the stamp: the
        // warm-up skips cells that are leased, so the duel would work, but it would do so on a
        // gallery that is still half-empty and against a lease taken before the map was re-pasted.
        if (ArenaManager.warmUpPending()) {
            fail(accepter, "pvpocket.msg.rules.arena_rebuilding");
            return;
        }
        // Checked again here rather than trusting the caller: accepting agrees to the rules and to
        // everyone's loadout, but "everyone agreed" is not a property of the caller — the other side
        // may have edited their loadout between the proposal and this click.
        if (!allLoadoutsAgreed(session)) {
            fail(accepter, "pvpocket.msg.rules.loadout_not_all_agreed");
            return;
        }
        // The lease is taken before anything is frozen. A full grid must fail while the session is
        // still in NEGOTIATING — after the snapshot the only honest outcome would be a rollback of
        // two inventories for a reason that had nothing to do with the players.
        DuelRules rules = DuelRules.decode(session.rules);
        if (!validateRoster(session, rules, accepter)) {
            return;
        }
        int mapIndex = rules.get("e.template");
        // Checked here as well as in the panel. The panel is a hint — it greys out maps that were
        // taken when it was opened, and the player may have been looking at it for a minute while
        // somebody else started a duel on the map they picked. Two duels on one map is not a
        // cosmetic problem: they would be two copies of the same terrain, and a player told "the
        // colosseum" has no way to know which one they were sent to.
        if (ArenaManager.isMapBusy(mapIndex, session.id)) {
            fail(accepter, "pvpocket.msg.rules.map_busy",
                    ArenaMaps.byIndex(mapIndex).labelText());
            return;
        }
        ArenaRegion region = ArenaManager.allocate(server, session.id,
                rules.get("e.sizeX"), rules.get("e.sizeY"), rules.get("e.sizeZ"),
                ArenaMaps.byIndex(mapIndex),
                rules.flag("f.protectTerrain"), rules.flag("f.allowBreakOwn"));
        if (region == null) {
            fail(accepter, "pvpocket.msg.rules.arena_full");
            return;
        }
        session.arenaId = region.id;
        journal(session).append("ROSTER",
                "size", session.participants.size(),
                "teams", session.teamCount,
                "shape", shapeOf(session));
        journal(session).append("ARENA_LEASED", "region", region.id,
                "size", region.sizeX + "x" + region.sizeY + "x" + region.sizeZ,
                "map", region.map.id,
                "mapBlocks", region.templateBlockCount(),
                "origin", region.minX + "," + region.minZ,
                "protectTerrain", region.protectTerrain,
                "allowBreakOwn", region.allowBreakOwn);
        journal(session).append("RULES_ACCEPTED",
                "revision", session.rulesRevision,
                "author", session.rulesAuthor,
                // Null when the start was triggered by somebody leaving rather than by a click —
                // the last answer was already on the record in that case.
                "acceptedBy", accepter == null ? "(auto)" : accepter.getName().getString(),
                "digest", session.rulesDigest,
                // The ids that differ from the catalog defaults, spelled out. The encoded string is
                // also here, but reading it back means decoding thirty-eight pairs by hand, and the
                // question somebody actually asks of this file is "what was unusual about this
                // match" — which is a list of three ids, not a wall of defaults.
                "changed", changedFromDefaults(rules),
                // Values the normalisation had to move on the way in. Empty in every ordinary
                // match; non-empty means the rule set a player agreed to is not the one about to
                // run, which is otherwise invisible (the digest hashes what was stored).
                "clamped", rules.clampedValues(),
                "rules", session.rules);
        inviteByTarget.values().removeIf(session.id::equals);
        closePanels(session);
        try {
            freeze(session);
            transition(session, SessionStage.ROUND_READY);
            startRound(session);
        } catch (RuntimeException e) {
            PvPocket.LOGGER.error("Failed to start session {}", session.id, e);
            abort(session, "START_FAILED");
        }
    }

    /**
     * Forfeits the <em>whole series</em>, not the round.
     *
     * <p>Surrender is the way out of a match you no longer want to be in, not a way to drop one
     * round. Awarding a single round for it would leave the forfeiting player still in the series
     * — free to surrender again next round — which is not what anybody means by giving up. The
     * score is left untouched: nobody won this round, the series simply ends with the opponent as
     * its winner.</p>
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
            // The round is already decided and waiting for the loser to respawn. Settling again here
            // would award a second round for the same death — and, worse, could hand the round to
            // the player who just died.
            //
            // Keyed on the winning side, not on pendingWinner: a 2v2 that has lost one member has a
            // pendingWinner (the dead player's respawn is being watched) but no decided round, and
            // refusing the survivor's surrender there would trap them in a match they want out of.
            fail(player, "pvpocket.msg.duel.round_settled");
            return;
        }
        if (!session.rulebook().allowSurrender()
                || !session.rulebook().winsBy(Rulebook.WinCondition.SURRENDER)) {
            fail(player, "pvpocket.msg.duel.surrender_disabled");
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        // Whoever is surrendering gives up for their whole side, so the series goes to the best
        // other side. In a 1v1 that is simply "the opponent"; with three or four sides it is
        // "whoever is ahead of the rest", because a side that quits cannot hand the match to the
        // side it was beating.
        forfeit(session, player, leaderTeam(session, me == null ? -1 : me.teamIndex()));
    }

    /**
     * Ends the series by forfeit: the surrendering side loses, {@code winnerTeam} takes the series.
     *
     * <p>Works from {@code ROUND_READY} as well as {@code ROUND_ACTIVE} — a player who wants out
     * should not have to sit through the intermission and start another round to say so.</p>
     */
    private void forfeit(DuelSession session, ServerPlayer player, int winnerTeam) {
        journal(session).append("SERIES_FORFEITED",
                "player", player.getName().getString(),
                "winnerTeam", winnerTeam,
                "score", scoreLine(session),
                "round", session.round,
                "stage", session.stage.name());
        if (session.stage == SessionStage.ROUND_ACTIVE) {
            // winner=null on purpose: the round has no winner, the series does.
            journal(session).append("ROUND_ENDED",
                    "round", session.round, "reason", "SURRENDER", "winner", null);
            // The round goes into the log as well, with no winner, and this is not bookkeeping for
            // its own sake: the log is what {@link #recordSeries} counts, and it refuses a series
            // with no settled round. Surrender used to write its own ROUND_ENDED and skip the log,
            // so a forfeited series reached recordSeries empty and was dropped — the match never
            // appeared in the player's stats or history at all, while the journal showed it ending
            // normally. Logged before the start time is dropped, same as {@link #settle}.
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
        player.displayClientMessage(PvLang.t("pvpocket.msg.duel.surrendered")
                .withStyle(ChatFormatting.RED), false);
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
        // The two series numbers are clamped against each other here rather than in the panel,
        // because this is the one place every path goes through: "win 4 of at most 3" is a state the
        // sliders can sit in, and a session that stores it can never end on the win count and never
        // ends on the cap either.
        session.maxRounds = Math.max(1, rules.get("a.maxRounds"));
        session.winsNeeded = Math.max(1, Math.min(session.maxRounds, rules.get("a.winsNeeded")));
        session.rules = rules.encode();
        session.rulesDigest = rules.digest();
    }

    /** True once the series can no longer be extended: somebody reached the target, or the cap is hit. */
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
            if (session.stage == SessionStage.DRAFT || session.stage == SessionStage.NEGOTIATING) {
                // Only a session this player is still *in*. A refusal — deliberate, or a drop
                // recorded as one — takes them off the roster, and a session they have already
                // been removed from is not something they can answer, cancel, or re-propose. It
                // used to count anyway, which left the one player who could not act on it blocked
                // by it: /pvpocket duel said "you already have a pending invite" and every action
                // inside said "nothing is pending". The session still resolves on its own for the
                // people who are left; it just no longer holds the leaver hostage.
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
     * <p>All of them, not the first one: a 2v2 panel that names one opponent reads as a 1v1 panel,
     * and the reader has no way to tell whether the second name is missing because it does not exist
     * or because the field only holds one. The client truncates with an ellipsis if the list is too
     * long for the subtitle, which is a better failure than silently dropping a side.</p>
     *
     * <p>Empty rather than a word: the callers are a payload and two panel subtitles, and the word
     * for "opponent" is language-specific. The client substitutes its own term when the field is
     * blank, which is the only place the reader's language is known.</p>
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
     * <p>Deliberately reachable from both sides and from either stage: a player who is proposing
     * configures their loadout before sending, and a player who is reviewing configures it before
     * accepting. It is the same screen either way, which is the point.</p>
     *
     * <p>With no open session it opens the <em>personal</em> loadout instead of refusing. The request
     * was explicitly "configure before sending the request", and refusing there would mean the only
     * way to prepare is to challenge somebody first — which is backwards, and would also send a
     * challenge carrying a loadout nobody picked. The personal variant is marked by an empty session
     * id, which is what tells the submit path to write to {@link LoadoutBook} rather than a
     * participant.</p>
     */
    public void onLoadoutRequest(ServerPlayer player) {
        DuelSession session = findOpenSession(player.getUUID());
        if (session == null) {
            // d.allowSwapBetweenRounds. The series is running, so the editor is still the right
            // screen — what changes is where the result goes (a pending slot, not the live loadout).
            DuelSession series = seriesSessionOf(player.getUUID());
            if (series != null) {
                if (series.rulebook().allowSwapBetweenRounds()) {
                    openSeriesEditor(player, series);
                    return;
                }
                // Falling through to the personal loadout is the old behaviour, but doing it in
                // silence would leave a player believing they had just changed what they fight
                // with. The rule is off; say so.
                player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.swap_locked")
                        .withStyle(ChatFormatting.GRAY), false);
            }
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
        int role = session.stage == SessionStage.DRAFT
                ? RulesOpenPayload.ROLE_PROPOSER
                : RulesOpenPayload.ROLE_REVIEWER;
        // The panel draws the match's one set, whoever opened it. It used to draw this player's own
        // copy, which is how two players could look at the same "agreed" loadout and see different
        // items — and how an edit made by the second player landed on a document the round ignored.
        String encoded = sharedLoadout(session).encode();
        PvPocketNetworking.openLoadout(player, new com.leipishu.pvpocket.network.LoadoutOpenPayload(
                session.id, encoded, role, otherName(session, player.getStringUUID())));
    }

    /**
     * Stores a loadout submitted by a client, after re-validating it.
     *
     * <p>The client runs the same validation to keep its save button honest, but a client is not a
     * source of truth: this is the pass that actually decides whether a loadout becomes items. A
     * rejected loadout leaves the previous one untouched rather than half-applying.</p>
     *
     * <p><b>Either entry point writes both places.</b> An empty session id means "the personal
     * loadout": it is saved to the book and, if the player happens to be in a session that has not
     * started, mirrored onto their participant. A non-empty one is the panel opened from a duel, and
     * that path writes the book as well — the two used to be separate, which made the difference
     * between them invisible and made every 禁止丢弃 switch flipped during a negotiation come back at
     * the next invite. There is one loadout per player; the session merely holds the copy that was
     * agreed to.</p>
     */
    public void onLoadoutSubmit(ServerPlayer player, String sessionId, String encoded) {
        if (sessionId == null || sessionId.isEmpty()) {
            submitPersonal(player, encoded);
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage.isTerminal()) {
            // Recorded, not just said in chat. "My edit did nothing" is the report this whole path
            // keeps generating, and until now a refusal left no trace at all: the journal had a
            // LOADOUT_SAVED for every save that worked and silence for every one that did not, so a
            // refused save was indistinguishable from a client that never pressed the button.
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
            // d.allowSwapBetweenRounds: the series is already running, so the loadout can still
            // change but it cannot land on its own. See proposeLoadoutSwap.
            proposeLoadoutSwap(player, session, encoded);
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
        String before = combinedLoadoutDigest(session);
        // One set per match: whoever presses 保存 writes the set everybody fights with. Writing only
        // the submitter's participant is what made "some of the attributes only showed up on the
        // initiator's side" possible — the other copies kept whatever they were seeded with, and the
        // round then handed out two different kits inside one duel.
        adoptSharedLoadout(session, loadout);
        // The book is written too, so that "my loadout" means one thing wherever the panel was
        // opened from. Saving only the participant made the editor's own subtitle ("与 X 的决斗")
        // load-bearing in a way nothing on screen said: every switch flipped here came back the way
        // it was at the next invite, because seedLoadout() re-reads this book.
        loadouts.put(player.getUUID(), loadout);
        journal(session).append("LOADOUT_SAVED",
                "player", p.name,
                "digest", p.loadoutDigest,
                "items", loadout.size(),
                "summary", loadout.name,
                "scope", "match");
        persist(session);
        noteLoadoutChanged(session, p, before);
        // The stacks the players are already carrying were built from the previous loadout, so a
        // 禁止丢弃 switch flipped just now has to be re-applied to them — see LoadoutLock.restamp.
        // Everybody, because one save now describes all of their items.
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
            // Only worth saying once there is somebody else: in a draft this is the proposer alone,
            // and "every player in this match (1)" is a sentence about nothing.
            player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.saved_match", players)
                    .withStyle(ChatFormatting.GOLD), false);
        }
    }

    /**
     * A loadout change made after the series has started, held until everybody agrees to it.
     *
     * <p>The loadout is part of what the two sides agreed to before the first round, so changing it
     * afterwards is changing the terms. It is not forbidden — {@code d.allowSwapBetweenRounds}
     * exists because a series where one side is hard-countered and has no answer is not a series —
     * but it has to be re-agreed, which is the whole point of the rule's name.</p>
     *
     * <p>Stored as {@code pendingLoadout} rather than written over {@code loadout}: the change is
     * not in effect until it is accepted, and the old loadout has to survive a refusal. Nothing has
     * to be timed here either — the loadout is only written into an inventory at a round start, so
     * "it lands at the next round boundary" is a consequence of where it is read, not a deadline
     * anybody has to meet.</p>
     */
    private void proposeLoadoutSwap(ServerPlayer player, DuelSession session, String encoded) {
        String uuid = player.getStringUUID();
        Participant me = session.participant(uuid);
        if (me == null) {
            fail(player, "pvpocket.msg.loadout.not_participant");
            return;
        }
        if (!session.rulebook().allowSwapBetweenRounds()) {
            rejectSubmit(session, player, "swap_locked", "d.allowSwapBetweenRounds=0");
            fail(player, "pvpocket.msg.loadout.swap_locked");
            return;
        }
        if (session.stage != SessionStage.ROUND_READY && session.stage != SessionStage.ROUND_ACTIVE) {
            rejectSubmit(session, player, "frozen", session.stage.name());
            fail(player, "pvpocket.msg.loadout.frozen");
            return;
        }
        Loadout loadout = Loadout.decode(encoded);
        List<Component> problems = loadout.validate();
        if (!problems.isEmpty()) {
            fail(player, "pvpocket.msg.loadout.invalid", problems.get(0));
            return;
        }
        if (loadout.digest().equals(me.loadoutDigest)) {
            // Nothing changed. Saying so beats bumping the revision and making the other side
            // confirm a change that does not exist.
            player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.swap_unchanged")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        me.pendingLoadout = loadout.encode();
        me.pendingLoadoutDigest = loadout.digest();
        session.loadoutRevision++;
        me.acceptedLoadoutRevision = session.loadoutRevision;
        journal(session).append("LOADOUT_PROPOSED", "player", me.name,
                "digest", me.pendingLoadoutDigest, "items", loadout.size(),
                "revision", session.loadoutRevision, "round", session.round);
        persist(session);
        player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.swap_sent", loadout.size())
                .withStyle(ChatFormatting.GOLD), false);
        for (Participant p : session.participants) {
            if (p.rejected || p.uuid.equals(uuid)) {
                continue;
            }
            ServerPlayer target = online(p);
            if (target == null) {
                continue;
            }
            target.displayClientMessage(PvLang.t("pvpocket.msg.loadout.swap_asked", me.name)
                    .withStyle(ChatFormatting.GOLD), false);
            target.displayClientMessage(PvLang.t("pvpocket.msg.loadout.swap_hint")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
    }

    /** {@code /pvpocket loadout accept}: one player's yes to the change currently on the table. */
    public void acceptLoadoutSwap(ServerPlayer player) {
        DuelSession session = swapSessionOf(player);
        if (session == null) {
            fail(player, "pvpocket.msg.loadout.swap_none");
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        me.acceptedLoadoutRevision = session.loadoutRevision;
        journal(session).append("LOADOUT_SWAP_AGREED", "player", me.name,
                "revision", session.loadoutRevision);
        persist(session);
        if (!swapAgreed(session)) {
            player.displayClientMessage(PvLang.t("pvpocket.msg.loadout.swap_waiting",
                            swapAgreedCount(session), liveCount(session))
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        commitLoadoutSwap(session, "LOADOUT_COMMITTED");
    }

    /** {@code /pvpocket loadout decline}: the change is dropped and the old loadout stands. */
    public void declineLoadoutSwap(ServerPlayer player) {
        DuelSession session = swapSessionOf(player);
        if (session == null) {
            fail(player, "pvpocket.msg.loadout.swap_none");
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        // The table is reset rather than the revision rewound: every participant is marked as
        // having agreed to a revision nobody proposed anything under, which is the same state as
        // "no change pending" without having to remember what the previous revision was.
        clearLoadoutSwaps(session);
        journal(session).append("LOADOUT_SWAP_DECLINED", "player", me.name,
                "revision", session.loadoutRevision);
        persist(session);
        broadcastParticipants(session, PvLang.t("pvpocket.msg.loadout.swap_declined", me.name));
    }

    /**
     * The series this player is fighting, in the two stages where a loadout change means anything.
     *
     * <p>{@code ROUND_READY} is the intermission and {@code ROUND_ACTIVE} the round itself. The
     * proposal is accepted in either: the change only reaches an inventory at a round start, so
     * there is nothing to be gained by refusing to take the note during a round, and a player who
     * wants to think about it while fighting can.</p>
     */
    private DuelSession seriesSessionOf(UUID uuid) {
        String id = sessionByPlayer.get(uuid);
        DuelSession session = id == null ? null : sessions.get(id);
        if (session == null || session.stage.isTerminal()) {
            return null;
        }
        if (session.stage != SessionStage.ROUND_READY && session.stage != SessionStage.ROUND_ACTIVE) {
            return null;
        }
        Participant p = session.participant(uuid.toString());
        return p != null && !p.rejected ? session : null;
    }

    /** Opens the loadout editor against a running series; see {@link #proposeLoadoutSwap}. */
    private void openSeriesEditor(ServerPlayer player, DuelSession session) {
        Participant p = session.participant(player.getStringUUID());
        // The pending change if there is one, otherwise the match's set — not this player's copy,
        // which is the same document only as long as adoptSharedLoadout has written it.
        String encoded = p.pendingLoadout != null
                ? p.pendingLoadout
                : sharedLoadout(session).encode();
        PvPocketNetworking.openLoadout(player, new com.leipishu.pvpocket.network.LoadoutOpenPayload(
                session.id, encoded, RulesOpenPayload.ROLE_REVIEWER,
                otherName(session, player.getStringUUID())));
    }

    /**
     * The session a swap command may act on, or null.
     *
     * <p>Deliberately not {@code findOpenSession}: that one only knows about drafts and
     * negotiations, which is exactly the window a mid-series change is not in.</p>
     */
    private DuelSession swapSessionOf(ServerPlayer player) {        String id = sessionByPlayer.get(player.getUUID());
        DuelSession session = id == null ? null : sessions.get(id);
        if (session == null || session.stage.isTerminal()
                || session.participant(player.getStringUUID()) == null) {
            return null;
        }
        return swapPending(session) ? session : null;
    }

    private static boolean swapPending(DuelSession session) {
        for (Participant p : session.participants) {
            if (!p.rejected && p.pendingLoadout != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean swapAgreed(DuelSession session) {
        for (Participant p : session.participants) {
            if (!p.rejected && p.acceptedLoadoutRevision != session.loadoutRevision) {
                return false;
            }
        }
        return true;
    }

    private static int swapAgreedCount(DuelSession session) {
        int count = 0;
        for (Participant p : session.participants) {
            if (!p.rejected && p.acceptedLoadoutRevision == session.loadoutRevision) {
                count++;
            }
        }
        return count;
    }

    /** Writes the pending set into place and closes the table. */
    private void commitLoadoutSwap(DuelSession session, String reason) {
        Loadout pending = null;
        for (Participant p : session.participants) {
            if (!p.rejected && p.pendingLoadout != null) {
                pending = Loadout.decode(p.pendingLoadout);
                break;
            }
        }
        if (pending != null) {
            // The change is to the match's set, not to the proposer's copy. Only one participant can
            // have a pending change (the one who opened the editor), and moving just that record
            // would leave the other side holding the previous items — a duel with two kits, which
            // is the thing this whole path exists to prevent.
            adoptSharedLoadout(session, pending);
            for (Participant p : session.participants) {
                if (p.rejected) {
                    continue;
                }
                journal(session).append("LOADOUT_SWAPPED", "player", p.name,
                        "digest", p.loadoutDigest, "round", session.round);
            }
        }
        clearLoadoutSwaps(session);
        journal(session).append(reason, "revision", session.loadoutRevision, "round", session.round);
        persist(session);
        // Says "from the next round", not "now": the inventory is re-granted at every round start,
        // so a player reading "changed" while holding the old sword would go looking for a bug.
        broadcastParticipants(session, PvLang.t("pvpocket.msg.loadout.swap_done"));
    }

    private static void clearLoadoutSwaps(DuelSession session) {
        for (Participant p : session.participants) {
            p.pendingLoadout = null;
            p.pendingLoadoutDigest = null;
            p.acceptedLoadoutRevision = session.loadoutRevision;
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

    /** Saves a loadout to the player's book, and mirrors it onto any session that has not started. */
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
                String before = combinedLoadoutDigest(session);
                // The same one-set-per-match write as onLoadoutSubmit: this entry point exists
                // because the panel can be opened outside a duel, not because it means something
                // different. A personal save during a negotiation that only touched this player's
                // copy is how the two sides ended up entering with different items.
                adoptSharedLoadout(session, loadout);
                journal(session).append("LOADOUT_SAVED",
                        "player", p.name,
                        "digest", p.loadoutDigest,
                        "items", loadout.size(),
                        "summary", loadout.name,
                        "scope", "personal");
                persist(session);
                noteLoadoutChanged(session, p, before);
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
            }
        } else if (seriesSessionOf(player.getUUID()) != null) {
            // A running series keeps the set it agreed to, so this save cannot reach the items in
            // the player's hand. Saying so is not optional: the panel is the same one that edits a
            // duel's loadout, and a player who flips 禁止丢弃 between rounds otherwise concludes the
            // switch is broken — the sword stays locked for the rest of the match, and nothing on
            // screen ever said why.
            mirrored = PvLang.t("pvpocket.msg.loadout.series_locked");
        }
        // Restamped against the loadout that will actually be granted, not the one just saved: a
        // series that has already started keeps fighting with the loadout it agreed to, and marking
        // the player's sword droppable against a document the round does not read would be a lie.
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
     * <p>Lives here rather than in the command so the command cannot become a second way to edit a
     * loadout without telling the session. A player who resets from the console while a negotiation
     * is open would otherwise keep entering with the old items — exactly the state the consent
     * digest exists to catch.</p>
     */
    public void resetPersonalLoadout(ServerPlayer player) {
        loadouts.clear(player.getUUID());
        DuelSession session = findOpenSession(player.getUUID());
        if (session == null || !session.stage.isPreFreeze()) {
            // The cleared book no longer describes anything the player is holding, so the 禁止丢弃
            // marks come off too — a reset that leaves the old lock in place is a reset that only
            // half happened.
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
        String before = combinedLoadoutDigest(session);
        // Everyone, for the same reason a save writes everyone: the match has one set, and a reset
        // that left the other players on the old one would be a match with two sets again.
        adoptSharedLoadout(session, fallback);
        journal(session).append("LOADOUT_RESET", "player", p.name, "digest", p.loadoutDigest);
        persist(session);
        noteLoadoutChanged(session, p, before);
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
     * <p>Falls back to the player's saved loadout rather than returning null so that the diagnostic
     * command and a first duel show the same thing — a null here would make "no loadout yet" look
     * like "loadout support is broken".</p>
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
        // Every opponent, not just one: "/pvpocket status" is the line people paste into chat when
        // something is wrong, and in a 2v2 it has to say who the other three players are.
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
     * <p>One receiver for four actions because they are the same question asked four ways. The
     * action byte is re-validated here rather than trusted: an unknown action is ignored instead of
     * throwing, so a client built from a newer jar cannot disconnect itself by pressing a key this
     * server has never heard of.</p>
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
        // One set for the whole match. If it is still the built-in preset then nobody has ever
        // saved a loadout, and every panel draws a set either way — nothing on screen says "this
        // is the preset". Saying it once, before the first round, is the difference between "we
        // agreed to the same kit" and "somebody's items are mysteriously different".
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
            PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
            Path file = storage.snapshotFile(session.id, p.uuid);
            try {
                snapshot.writeTo(file);
            } catch (IOException e) {
                throw new IllegalStateException("cannot write snapshot for " + p.name, e);
            }
            p.snapshotFile = file.getFileName().toString();
            p.fingerprint = snapshot.fingerprint();
            sessionByPlayer.put(player.getUUID(), session.id);
            journal(session).append("SNAPSHOT_WRITTEN",
                    "player", p.name, "fingerprint", p.fingerprint);
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
        // Elimination is per round: a 3v3 where one side lost two players last round starts this one
        // with everybody standing again.
        for (Participant p : session.participants) {
            p.eliminated = false;
        }
        int prepare = Math.max(0, rules.get("a.countdown"));
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player == null) {
                throw new IllegalStateException("participant offline: " + p.name);
            }
            if (!player.isAlive()) {
                // Reachable after a crash or a stop taken while somebody was on the death screen:
                // their playerdata is saved with zero health and the next login does not always
                // respawn them. Reviving is the correct answer rather than throwing — the whole
                // point of resetRoundState is that every round starts from the same state, and
                // voiding a series over a state the mod itself created would be a bug report, not a
                // safety net. The health value is journalled so a real cause is still visible.
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
            // Authorised change: the round starts in survival, and the sandbox rule forbids players
            // from switching modes while they are standing in the dimension this just put them in.
            Sandbox.changeGameMode(player, GameType.SURVIVAL);
            // The snapshot restores attributes wholesale, so a modified base value here is rolled
            // back by the same mechanism that restores inventory — no separate undo bookkeeping.
            var health = player.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(maxHealth);
                // Read back immediately: if anything else on the server is holding this attribute
                // down (another mod, a leftover modifier), the player starts the round on a cap
                // nobody asked for and the only evidence would be a health bar. One journal line
                // turns that into a fact.
                if (Math.abs(player.getMaxHealth() - maxHealth) > 0.01F) {
                    journal(session).append("ROUND_START_HEALTH_MISMATCH", "player", p.name,
                            "wanted", maxHealth, "actual", player.getMaxHealth());
                }
            } else {
                journal(session).append("ROUND_START_NO_HEALTH_ATTRIBUTE", "player", p.name);
            }
            // Same mechanism, same reason. Applied per round rather than at freeze so a mid-series
            // rule change (or a reloaded session) cannot leave the previous value in place.
            var knockback = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (knockback != null) {
                knockback.setBaseValue(session.rulebook().knockbackResistance());
            }
            // Every round starts from the same state. Without this a player who ended round 1 on two
            // hearts, on fire, and poisoned would begin round 2 that way — the loadout is re-granted
            // but health and effects are not items.
            resetRoundState(player, maxHealth);
            CombatState.setMode(player.getUUID(), mode);
            // The item-use gate travels the same path as the mode, for the same reason: the client
            // has to refuse a placement before it predicts one, and only the server knows the rules.
            applyUseGate(session, player);
            // Loadout is granted here, not at freeze(): the snapshot must contain the survival
            // inventory, so anything written before it would be captured as if the player owned it.
            // Re-granting each round is also what "每一小局结束物品恢复到最初状态" means: grant()
            // clears the inventory before writing, so nothing from the previous round survives.
            // One set for the whole match — read fresh per participant rather than hoisted out of
            // the loop, because the legacy branch below may rewrite it and the rewrite must not
            // leak into the next player's grant.
            Loadout loadout = sharedLoadout(session);
            // 1.8 has no shields, and a shield that survives into the round does not merely sit
            // unused: right-clicking raises it, which both blocks damage outright and takes the
            // right-click away from the sword block this mode exists for. Dropped here rather than
            // in the editor so the same saved loadout stays valid for a modern round.
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
            // The digest of the loadout that actually became items, not the recorded one. The two
            // differ whenever this branch rewrites the set (a legacy round strips shields), and
            // logging the recorded digest made the journal agree with LOADOUT_SAVED by
            // construction — which is exactly the evidence a "my loadout change did nothing" report
            // needs to be able to contradict.
            journal(session).append("LOADOUT_APPLIED", "player", p.name,
                    "digest", loadout.digest(), "items", granted,
                    "locked", loadout.lockedCount(), "round", session.round);
            // The client mirrors the rule set to predict movement and to hide the attack cooldown
            // indicator, so it has to be told which mode this round runs before the first hit.
            PvPocketNetworking.sendState(player);
            if (prepare > 0) {
                // The preparation window is a promise that nobody can land a hit yet, so it has to
                // be enforced rather than announced: invulnerability is one line and covers both
                // directions, where a movement lock would need a whole new stage.
                player.setInvulnerable(true);
            }
        }
        if (prepare > 0) {
            journal(session).append("ROUND_PREPARING", "round", session.round, "seconds", prepare);
            prepareDeadlines.put(session.id,
                    new Prepare(System.currentTimeMillis() + prepare * 1000L, prepare));
            // The wall is real for the length of the preparation window. Invulnerability already
            // stops anyone landing a hit, so the only thing a player can usefully do in these
            // seconds is wander out and be somewhere strange when the round goes live — which is
            // exactly what a solid ring prevents and a walk-through curtain does not. The ring stops
            // them leaving; the screens raised in front of each side stop them meeting, which is the
            // other half of "the fight has not started yet".
            int wall = ArenaManager.buildPrepareWall(arenaWorld, region, session.teamCount);
            journal(session).append("PREPARE_WALL_BUILT", "blocks", wall,
                    "screens", region.screenCount(),
                    "top", ArenaManager.prepareWallTop(region));
            broadcastHud(session, true);
            broadcastBounds(session, true);
            for (Participant p : session.participants) {
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
     * <p>Split from {@link #startRound} because everything that measures "the round" — the opening
     * boundary grace, the scoreboard, the round timer — must start when the fight can actually
     * begin, not when the players were teleported in.</p>
     */
    private void activateRound(DuelSession session) {
        prepareDeadlines.remove(session.id);
        // Torn down before anything else, and before the players are released from invulnerability:
        // the wall's job ends the moment the round can be fought, and a round that starts with the
        // arena still sealed is a round where nobody can chase anybody to the edge.
        ArenaRegion sealed = ArenaManager.region(session.id);
        int removed = ArenaManager.removePrepareWall(ArenaManager.world(server), sealed);
        if (removed > 0) {
            journal(session).append("PREPARE_WALL_REMOVED", "blocks", removed);
        }
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player == null) {
                // Somebody left during the preparation window. Cut rather than throw: they never
                // fought, so there is nothing to judge and no round to award.
                session.seriesEndReason = "CUT";
                journal(session).append("SERIES_CUT", "reason", "participant_offline");
                finishSeries(session, leaderTeam(session, absentTeam(session)));
                return;
            }
            player.setInvulnerable(false);
        }
        CombatMode mode = CombatMode.byId(session.mode);
        DuelRules rules = DuelRules.decode(session.rules);
        ArenaRegion region = ArenaManager.region(session.id);
        journal(session).append("ROUND_STARTED", "round", session.round,
                "digest", session.rulesDigest,
                // The boundary rule in force, spelled out. Without it "the config panel did nothing"
                // is unfalsifiable after the fact: the digest is a hash, and a hash cannot be read
                // back into "which of the two boundary rules was actually running".
                "boundary", rules.get("e.boundary") == 0 ? "push_back" : "countdown",
                // Same reasoning for the health cap: it is the one rule whose effect is a number on
                // the HUD rather than a behaviour, and "I set it to 40 and started on 20" needs an
                // answer that does not depend on remembering which revision ran.
                "maxHealth", rules.get("c.maxHealth"),
                // And the round clock, for the same reason plus one of its own: the clock only runs
                // when 超时 is also an accepted win condition, so "I set a 15 minute limit and the
                // round ran for an hour" has two very different explanations and this line is what
                // tells them apart.
                "roundLimit", session.rulebook().roundLimitSeconds() == 0
                        || !session.rulebook().winsBy(Rulebook.WinCondition.TIMEOUT)
                        ? "off" : session.rulebook().roundLimitSeconds() + "s",
                "region", region == null ? "-" : region.id,
                "teams", session.teamCount,
                "shape", shapeOf(session),
                // Every pad the match actually used, not just the first two: in a 2v2 the third and
                // fourth pad are where half the deaths happen, and a journal that cannot say where
                // anybody was put is a journal that cannot answer "why did they spawn in the wall".
                "spawns", region == null ? "-" : spawnsOf(region));
        roundStartedAt.put(session.id, System.currentTimeMillis());
        transition(session, SessionStage.ROUND_ACTIVE);
        broadcastHud(session, true);
        // Re-pushed, not left as it was: the client is still holding the white preparation curtain,
        // and the round it is now watching is governed by a rule, not by a wall. Leaving the old
        // packet in place would show a solid white boundary over an open arena.
        broadcastBounds(session, true);
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player != null) {
                // Three pieces rather than one sentence with optional halves: the "first to N" clause
                // only exists for a series, and the surrender hint names a command that must stay
                // untranslated, so it is appended as its own translatable with the command as a
                // literal argument.
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
     * <p>Chat rather than the action bar: this is the one message a player has several seconds to
     * read, and the action bar is overwritten by whatever else the client wants to show. The
     * boundary countdown goes the other way round (centre of the screen) because there the number
     * has to interrupt someone who is mid-fight.</p>
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
     * <p>Both are per-<em>world</em> properties, not per-plot, so two matches running side by side
     * cannot have different weather. Last one to start wins, which is the honest behaviour of a
     * shared dimension; the rule hint says so rather than pretending otherwise.</p>
     *
     * <p>The daylight cycle is switched off when a fixed time is chosen, and switched back on for
     * "自然". Leaving it off would silently freeze the dimension's clock for the next match.</p>
     */
    private void applyEnvironment(ServerLevel world, DuelRules rules) {
        if (world == null) {
            return;
        }
        int time = rules.get("e.timeOfDay");
        // 0 = 自然; the rest are fixed ticks. 1000 is shortly after sunrise, 6000 noon, 12000 dusk,
        // 18000 midnight — the vanilla anchors, chosen so the four options read as four times of day
        // rather than four arbitrary numbers.
        boolean frozen = time > 0;
        world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(!frozen, server);
        if (frozen) {
            world.setDayTime(switch (time) {
                case 2 -> 6000L;
                case 3 -> 12000L;
                case 4 -> 18000L;
                default -> 1000L;
            });
        }
        int weather = rules.get("e.weather");
        if (weather == 1) {
            world.setWeatherParameters(0, 12_000, true, false);
        } else if (weather == 2) {
            world.setWeatherParameters(0, 12_000, true, true);
        } else {
            world.setWeatherParameters(12_000, 0, false, false);
        }
    }

    /**
     * Puts the arena dimension's clock and weather back once nothing is running in it.
     *
     * <p>{@code e.timeOfDay} and {@code e.weather} are per-<em>world</em> properties, so a fixed
     * time is applied by switching {@code DO_DAYLIGHT_CYCLE} off — and until this method existed
     * nothing ever switched it back on. One match set to 正午 froze the arena dimension's clock for
     * every match after it, and for the survival world's neighbours sharing that dimension, until
     * somebody happened to start a match that asked for 自然. The rule was not wrong; the cleanup
     * was missing.</p>
     *
     * <p>Guarded on "nothing is running" rather than done unconditionally: two matches share one
     * clock, and restoring it when the first of them ends would yank the time out from under the
     * second — the same shared-world problem {@link #applyEnvironment} documents, just at the other
     * end.</p>
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
        world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
        world.setWeatherParameters(12_000, 0, false, false);
    }

    /**
     * Takes one player out of the round.
     *
     * <p>The round ends when the last member of a side is gone, not when the first player dies. That
     * single rule covers both shapes: a 1v1 ends on the first death because the side had one member,
     * and a 3v3 continues while two team-mates are still standing.</p>
     *
     * <p>That is {@code a.winConditions} · 击杀, and it is asked rather than assumed. The bit is
     * locked on in the panel because the only alternative — respawn the wiped side and keep playing
     * — is a game mode, not a checkbox; but a bit that nothing reads is a bit whose lock protects
     * nothing, and a crafted packet could clear it. If it ever arrives cleared, the round still ends
     * (a round that cannot end is the worst failure mode there is) and the anomaly is journalled so
     * the contradiction is visible instead of silent.</p>
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
            if (!session.rulebook().wipeEndsRound()) {
                journal(session).append("ANOMALY", "kind", "wipe_without_kill_condition",
                        "team", winner, "round", session.round);
            }
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
        // Logged before the start time is dropped, and persisted with the session so a crash between
        // this round and the end of the series still leaves the per-round detail behind.
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
        // Cleared here rather than at the end: the "has been seen on the plot" memory belongs to the
        // round that just ended, and leaving it in place would carry a settled player into the next
        // round before they had been teleported into it.
        roundStartedAt.remove(session.id);
        clearBoundaryExits(session);
        prepareDeadlines.remove(session.id);

        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);

        int seriesWinner = seriesWinnerTeam(session);
        boolean decided = seriesDecided(session);
        if (!decided && session.participants.stream()
                .anyMatch(p -> !p.rejected && online(p) == null)) {
            // Somebody left. Starting a round they cannot join would only reach the intermission and
            // fail there, so the series is cut now. Recorded as its own event so "why did this
            // series end" has an answer in the journal.
            //
            // Cut always finishes, even when leaderTeam() returns -1: a tied series that is cut has
            // no winner, and falling through to the intermission would have tried to start a round
            // for a player who is not there.
            session.seriesEndReason = "CUT";
            journal(session).append("SERIES_CUT", "reason", "participant_offline");
            finishSeries(session, leaderTeam(session, absentTeam(session)));
            return;
        }
        if (!decided) {
            beginIntermission(session, region, arenaWorld, winnerTeam);
            return;
        }
        if (seriesWinner < 0) {
            // The cap is reached and the lead is shared. Named rather than left blank: "no winner"
            // and "drawn" are the same score and different outcomes, and only one of them is
            // something a player can be told the rules produced.
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
     * <p>Kills are attributed at the moment the victim dies rather than from the damage stream: a
     * player can be hit by four people and only one of them killed them, and the damage stream has
     * no way to say which hit was the last. The victim's own side is excluded, which also covers the
     * friendly-fire-allowed rule: a team kill is a death, never a kill.</p>
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
     * <p>Static and defensive because the hook runs for every living entity on the server: a mob
     * hitting a zombie, a player on fire outside a duel, a client-side prediction. Everything that is
     * not "two players, same live session, different sides" is dropped here rather than at the call
     * site, so the mixin stays one line and the rule stays in one place.</p>
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
     * <p>Three exclusions, each for a different reason: a server stop is not a finished match, a
     * series with no settled round is a failed start rather than a loss, and a declined invite is not
     * a participant. Without the middle one, every aborted draft that reached the preparation window
     * would count as a match somebody lost.</p>
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
        // Flushed here as well as on shutdown: a match record is the one thing in this file a player
        // expects to survive a crash, and it is written exactly once.
        stats.flush();
    }

    /** {@code won,reason,ms} per settled round, with {@code won} relative to {@code side}. */
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
     * The home screen's snapshot: counters, recent history, live matches, and what the reader may do.
     *
     * <p>Built on demand rather than pushed, because the screen is opened by the player and nothing
     * else needs this data. The history is capped at {@link #HOME_HISTORY} rows: the detail a row
     * carries is small, but a payload is sent over a reliable channel and there is no reason to send
     * two hundred rows to fill a list that shows three at a time.</p>
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
        boolean operator = player.hasPermissions(3);
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
     * <p>Only built for operators. The layout of the dimension is not a secret, but the panel that
     * reads this is drawn only for them, so sending it to everyone would be a field no screen on
     * that client can reach.</p>
     *
     * <p>{@code guard} is the reader's own flag rather than a server-wide number: the switch in the
     * panel is "do not throw <em>me</em> out", and a shared value would make the toggle look broken
     * to the second operator.</p>
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
     * <p>All three actions end in {@link #openRoster}, on purpose. It already answers the three
     * interesting cases — busy, spectator, arena not ready — and it already knows how to hand back a
     * draft that is still open. A second entry point would have to answer all three again and would
     * drift the moment one of them changed.</p>
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
            case HomeActionPayload.ACTION_ARENA_RESET -> {
                if (requireOperator(player, "reset")) {
                    resetArenaFromPanel(player);
                }
            }
            case HomeActionPayload.ACTION_GUARD -> {
                if (requireOperator(player, "guard")) {
                    setGuardEnabled(player, "1".equals(action.arg()));
                    // The switch has to show the state the server actually stored, not the one the
                    // click asked for, so the answer is a fresh snapshot rather than a local flip.
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
     * <p>The panel not drawing these buttons is a convenience; this is the lock. The same level the
     * commands require is the level the panel requires, so a client cannot reach a capability by
     * talking to the payload directly that it could not reach by typing the command.</p>
     */
    private boolean requireOperator(ServerPlayer player, String what) {
        if (player.hasPermissions(3)) {
            return true;
        }
        PvPocket.LOGGER.warn("Refused home action '{}' from {} (not an operator)",
                what, player.getName().getString());
        return false;
    }

    /**
     * {@code /pvpocket arena copy} from the panel: one more plot for the named map.
     *
     * <p>The map is looked up by id, and an unknown id is a log line rather than an exception — the
     * ids come from the snapshot the server itself sent, so a mismatch means a stale panel, which
     * the fresh snapshot sent afterwards fixes.</p>
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
     * <p>Both refusals are reported, because they mean different things and only one of them is
     * temporary. A leased plot is a wait; a map with nothing left is already at zero, and the panel
     * draws that button disabled — so reaching this at all means a stale panel, which the fresh
     * snapshot sent afterwards corrects.</p>
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
     * <p>The command's version sends its result to the command source and to everybody else
     * separately, because a console source is not a player. This one has a player and only a
     * player, so it announces once to the whole list — the actor included, since a panel that
     * rebuilds itself with no sentence about why reads as the click having done nothing.</p>
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
     * <p>Applied after {@code openRoster} rather than by building the session here, because
     * {@code openRoster} is the only path that also handles "you already have a draft". The rule set
     * is validated by decoding it: a string that does not decode is dropped with a message, not
     * applied as a pile of defaults that would look like a preset the player never saved.</p>
     */
    private void applyPresetRules(ServerPlayer player, String encoded) {
        DuelSession session = draftOf(player);
        if (session == null || encoded == null || encoded.isEmpty()) {
            return;
        }
        try {
            DuelRules rules = DuelRules.decode(encoded);
            applyRulesToSession(session, rules);
            // Bumped like any other edit, so the panel's dirty marker and the "you agreed to v1"
            // bookkeeping both see this as a change rather than as the state they arrived with.
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
            // Match-wide, like every other write: in a draft this is only the proposer, but the
            // moment the roster goes out the set has to be the one the whole match fights with.
            adoptSharedLoadout(session, loadout);
            me.acceptedLoadoutRevision = -1;
            session.loadoutRevision++;
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
     * <p>Separate from {@link #settle} because shutdown needs it without the round bookkeeping — the
     * server is going down, so there is no next round to prepare for and the snapshots have to be
     * applied while the players are still connected rather than left as a debt for the next boot.</p>
     */
    private void finishSeries(DuelSession session, int winnerTeam) {
        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);

        // Defensive: the wall is normally torn down at activateRound, but a series can end during
        // the preparation window (surrender, disconnect, server stop), and a leftover barrier ring
        // would be an invisible cage around a plot nobody is in.
        ArenaManager.removePrepareWall(arenaWorld, region);
        // Ejected before the snapshots are restored, because restoring teleports the participants
        // out and a spectator whose camera is attached to a player being teleported is a spectator
        // watching the inside of a chunk loader.
        spectators.ejectAll(session.id, "SERIES_END");

        int cleared = ArenaManager.clearDroppedItems(arenaWorld, region);
        journal(session).append("ARENA_CLEARED", "removed", cleared);
        journal(session).append("SERIES_ENDED",
                "winnerTeam", winnerTeam,
                "winner", winnerTeam < 0 ? "-" : teamName(session, winnerTeam),
                "score", scoreLine(session),
                "rounds", session.round,
                "maxRounds", session.maxRounds,
                "winsNeeded", session.winsNeeded);

        // Written while the region is still leased: the map id is read off the plot the players were
        // actually standing in, and the lease is released a few lines below.
        recordSeries(session, winnerTeam);

        transition(session, SessionStage.RESTORING);

        for (Participant p : session.participants) {
            restoreParticipant(session, p);
        }

        // Blocks are reverted after the restore, not before: restoring teleports the players out, so
        // by the time the floor disappears there is nobody standing on it.
        if (region != null) {
            int tracked = region.changeCount();
            int reverted = ArenaManager.revertRegion(arenaWorld, region);
            journal(session).append("ARENA_REVERTED", "region", region.id,
                    "blocks", reverted, "tracked", tracked);
        }
        ArenaManager.release(session.id);
        journal(session).append("ARENA_RELEASED", "region", session.arenaId);
        restoreEnvironmentWhenIdle(arenaWorld);

        // Hidden before ARCHIVED so the client never draws a scoreboard for a session that no longer
        // exists on the server — the payload is the only thing it has to go on.
        broadcastHud(session, false);
        broadcastBounds(session, false);
        transition(session, SessionStage.ARCHIVED);
        inviteByTarget.values().removeIf(session.id::equals);
        announceSeriesResult(session, winnerTeam);
    }

    /**
     * The winner of the series as a side index, or {@code -1} when there is none.
     *
     * <p>{@code -1} covers two different situations and the caller has to tell them apart with
     * {@link #seriesDecided}: a series that is still live, and a series that ran out of rounds with
     * the lead shared. The old derivation — odd {@code bestOf}, first to {@code bestOf/2+1} — could
     * not produce a draw by construction; two independent numbers can, and "3 rounds, win 2" ending
     * 1:1:1 is exactly the case the old shape could not express.</p>
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
     * The surrender path already says this out loud — "a side that quits cannot hand the match to
     * the side it was beating" — and passes its own side as {@code exclude}. The three cut paths
     * passed no exclusion at all, which is how a 1v1 that stood at 0:1 ended with "Dev1 wins
     * (opponent disconnected)" announced to the player who stayed: the score was right and the
     * sentence was nonsense.</p>
     *
     * <p>{@code -1} when two sides are gone as well as when none is: with more than one side absent
     * there is no forfeit to attribute, and the lead decides or nobody does.</p>
     */
    private int absentTeam(DuelSession session) {
        int absent = -1;
        for (int t = 0; t < Math.max(1, session.teamCount); t++) {
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
     * <p>{@code -1} on a tie matters: a cut series with 2:2 has no winner, and picking the first
     * team — which the map order made look like a lead — announced "Dev1 获胜（2:2）". The
     * {@code exclude} parameter exists for surrender: a side that quits cannot be its own winner,
     * so the tie is judged among the sides that stayed.</p>
     */
    private static int leaderTeam(DuelSession session, int exclude) {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        boolean tied = false;
        for (int t = 0; t < Math.max(1, session.teamCount); t++) {
            if (t == exclude) {
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
     * "Dev1 + Dev2" — a side spelled out for chat lines that have no room for a roster.
     *
     * <p>Player names, so no translation key: the only localisable part of this is the separator,
     * and a separator that changed with the language would be worse than one that does not.</p>
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
     * <p>The snapshot is deliberately <em>not</em> re-applied here. It is the only copy of the
     * survival inventory, and restoring it mid-series would hand the players their real items while
     * the match is still running. "Items back to the initial state" is instead achieved by granting
     * the loadout again at the start of the next round, which clears whatever the previous round
     * left behind.</p>
     */
    private void beginIntermission(DuelSession session, ArenaRegion region, ServerLevel world,
                                   int winnerTeam) {
        int cleared = ArenaManager.clearDroppedItems(world, region);
        journal(session).append("ARENA_CLEARED", "removed", cleared);
        if (region != null) {
            int tracked = region.changeCount();
            int reverted = ArenaManager.revertRegion(world, region);
            journal(session).append("ARENA_REVERTED", "region", region.id,
                    "blocks", reverted, "tracked", tracked, "round", session.round);
        }
        int seconds = DuelRules.decode(session.rules).get("a.intermission");
        roundReadyAt.put(session.id, System.currentTimeMillis() + seconds * 1000L);
        transition(session, SessionStage.ROUND_READY);
        broadcastHud(session, true);
        // Two keys rather than one with an optional name: in a 1v1 "who won" is the same question as
        // "who lost", but with three or four sides the score alone ("1:0:1") does not say which row
        // is which, so the line has to name the side. A round that ends without a winner — a cut
        // series, a stop — has no name to print and keeps the plain sentence.
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
        // Both a surrender and a disconnect can end a series with the winner trailing on rounds.
        // Saying so in the same sentence is the difference between "the rules worked" and "the score
        // is broken".
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
                // The card goes out next to the sentence, not instead of it: the sentence is what a
                // player reads in the chat log a week later, the card is what they look at for the
                // ten seconds after the last round. A declined invite is skipped — it was never a
                // match for that player, and a victory card would be a lie in the other direction.
                if (!p.rejected) {
                    PvPocketNetworking.sendSeriesResult(player, resultCard(session, p, winnerTeam));
                }
            }
        }
    }

    /**
     * The result card for one reader, as the client needs it to draw and to export.
     *
     * <p>Built per participant rather than once per series because {@code mySide} is the whole
     * perspective: it decides which row of the score block is highlighted, which round marks count as
     * wins, and whether the headline is a victory. The alternative — sending the sides and letting
     * each client work out which one it is on — would need the reader's name or uuid in the payload
     * anyway, and the server already knows the answer.</p>
     *
     * <p>Read off the session rather than off the {@link MatchRecord} written a few lines earlier:
     * the record is the reader's own view with the reasons flattened, while this needs both sides'
     * rosters and every round's absolute winner. Format documented on {@link SeriesResultPayload}.</p>
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
     * <p>Called from {@code startRound} rather than {@code activateRound} on purpose: the whole
     * point of the preparation window is to look around and see where you are, and an arena with no
     * visible edge is the one thing you cannot see.</p>
     *
     * <p>Spectators get it too. They are in the same plot with the same wall, and a spectator whose
     * client draws no boundary is a spectator who cannot tell whether the player they are watching
     * is about to be judged out of bounds.</p>
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
        // One line per round boundary, mirroring the "Rules panel ->" line. If the curtain ever fails
        // to appear, the first question is whether the client was told about the plot at all, and
        // this answers it without a debugger.
        PvPocket.LOGGER.info("Arena bounds {} for session {} ({}, mode {}, screens {})",
                active ? "pushed" : "cleared", session.id,
                region == null ? "no plot" : (region.sizeX + "x" + region.sizeZ), mode,
                screens.screens().size());
    }

    /**
     * The screens standing in front of the sides right now, or none.
     *
     * <p>Derived from the stage rather than stored, so there is one answer to "is a screen up". The
     * blocks and this payload are built from the same plane list at the same moment; a client that
     * reconnected mid-countdown gets the payload from the stage alone, which is the case that a
     * cached field would have got wrong.</p>
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
     * <p>Uses the fight rectangle, not the plot box. The two differ only on maps that declare an
     * inset, and on those the difference is the whole point: the colosseum's box is its outer wall,
     * so a curtain on the box would be drawn behind ten rows of seating.</p>
     */
    private static ArenaBoundsPayload boundsOf(ArenaRegion region, int mode) {
        // Always the rule's own numbers. The preparation window used to override them with the
        // barrier ring's real height and paint the curtain white; that is now the screens' job, and
        // the boundary keeps behaving like a boundary for the whole match.
        return new ArenaBoundsPayload(true,
                region.pitMinX(), region.pitMinZ(),
                region.pitMaxX() + 1.0D, region.pitMaxZ() + 1.0D,
                ArenaRegion.SURFACE_Y, BOUNDARY_CURTAIN_HEIGHT, mode);
    }

    private void broadcastHud(DuelSession session, boolean active) {
        MatchHudPayload payload = active ? buildHud(session) : MatchHudPayload.hidden();
        for (Participant p : session.participants) {
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
     * <p>Two flat strings rather than one field per side, because a match has one to four sides and
     * the payload is hand-written around {@code PacketCodec.tuple}'s six-component limit. The client
     * splits them; nothing here needs to know how many sides exist.</p>
     *
     * <p>{@code scores} is {@code "2,1"} and {@code teams} is
     * {@code "Dev1|uuid,Dev2|uuid;Dev3|uuid"}: {@code ,} between members of a side, {@code ;}
     * between sides, {@code |} between a name and its uuid. None of those three characters can occur
     * in a Minecraft username or in a uuid string, so no escaping is needed.</p>
     */
    private static MatchHudPayload buildHud(DuelSession session) {
        int teams = Math.max(1, session.teamCount);
        StringBuilder scores = new StringBuilder();
        StringBuilder roster = new StringBuilder();
        int written = 0;
        for (int team = 0; team < teams; team++) {
            List<Participant> members = session.activeMembers(team);
            // A side with nobody left on it is not a side. It is skipped in both lists at once so
            // the two stay index-aligned, and so the HUD never draws a row that is a wool colour,
            // a zero and no heads — which is what a three-side match looks like after one side has
            // been emptied by refusals.
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
        return new MatchHudPayload(true, session.round, session.maxRounds, session.winsNeeded,
                scores.toString(), roster.toString(), session.rulebook().showOpponentHealth());
    }

    /**
     * Re-sends the HUD to one player, or tells them to hide it.
     *
     * <p>Needed on join: the payload is pushed only when the score changes, so a client that
     * reconnected mid-match would otherwise stare at an empty arena until the next round.</p>
     */
    private void pushHudTo(ServerPlayer player) {
        String id = sessionByPlayer.get(player.getUUID());
        DuelSession session = id == null ? null : sessions.get(id);
        if (session == null || session.stage.isTerminal()) {
            PvPocketNetworking.sendHud(player, MatchHudPayload.hidden());
            PvPocketNetworking.sendBounds(player, ArenaBoundsPayload.hidden());
            PvPocketNetworking.sendScreens(player, PrepareScreensPayload.none());
            return;
        }
        PvPocketNetworking.sendHud(player,
                session.stage == SessionStage.DRAFT || session.stage == SessionStage.NEGOTIATING
                        || session.stage == SessionStage.FROZEN
                        ? MatchHudPayload.hidden()
                        : buildHud(session));
        // Same reasoning for the plot outline: it is pushed on round boundaries, so a client that
        // reconnects mid-round would see an arena with no visible edge at all — the exact problem
        // the outline exists to solve.
        if (session.stage == SessionStage.ROUND_READY || session.stage == SessionStage.ROUND_ACTIVE) {
            ArenaRegion region = ArenaManager.region(session.id);
            if (region != null) {
                PvPocketNetworking.sendBounds(player, boundsOf(region, boundsMode(session)));
            }
        } else {
            PvPocketNetworking.sendBounds(player, ArenaBoundsPayload.hidden());
        }
        // Screens are not gated on the stage here: screensOf already answers "none" for anything
        // that is not the countdown, so the reconnect path cannot drift from the broadcast path.
        PvPocketNetworking.sendScreens(player, screensOf(session));
    }

    /**
     * Which colour the curtain should be right now.
     *
     * <p>Always the rule. The preparation window used to be its own mode — white and solid, because
     * a real barrier ring stood at those coordinates — but that made the boundary look like a
     * different object for five seconds and hid the colour that tells you which rule is in force.
     * The solid white belongs to the screens in front of the sides, which are not the boundary and
     * now travel in their own payload.</p>
     */
    private static int boundsMode(DuelSession session) {
        return DuelRules.decode(session.rules).get("e.boundary");
    }

    /**
     * Gives a freshly joined spectator the same overlay the participants have.
     *
     * <p>Not routed through {@link #pushHudTo}: that method looks the player up as a participant and
     * would answer "no session" for a spectator. Spectating is a different relationship to a match,
     * so it gets its own push rather than a special case inside the participant path.</p>
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
     * <p>Both of those payloads are borrowed state: the score bar and the boundary curtain belong to
     * a match the spectator is no longer part of. Nothing else will ever clear them — the match's own
     * broadcasts only reach its participants and its current watchers — so a spectator who leaves
     * mid-series would carry a live scoreboard over their survival world until the match ended.</p>
     */
    void clearSpectatorView(ServerPlayer player) {
        PvPocketNetworking.sendHud(player, MatchHudPayload.hidden());
        PvPocketNetworking.sendBounds(player, ArenaBoundsPayload.hidden());
        PvPocketNetworking.sendScreens(player, PrepareScreensPayload.none());
    }

    /**
     * Puts a player back into the state a fresh round starts from.
     *
     * <p>This is the "items return to their initial state" half of the per-round reset that is not
     * covered by re-granting the loadout: health, hunger, effects and fire all survive a round and
     * would otherwise carry a poisoned or half-dead player into the next one.</p>
     */
    private static void resetRoundState(ServerPlayer player, int maxHealth) {
        player.removeAllEffects();
        player.clearFire();
        player.setRemainingFireTicks(0);
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0;
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.getFoodData().setExhaustion(0.0F);
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
        ArenaManager.removePrepareWall(ArenaManager.world(server), ArenaManager.region(session.id));
        spectators.ejectAll(session.id, "ABORTED");
        broadcastHud(session, false);
        broadcastBounds(session, false);
        for (Participant p : session.participants) {
            if (p.snapshotFile != null) {
                restoreParticipant(session, p);
            }
        }
        ArenaManager.release(session.id);
        journal(session).append("SESSION_ABORTED", "reason", reason);
        transition(session, SessionStage.VOIDED);
    }

    private boolean restoreParticipant(DuelSession session, Participant p) {
        if (p.restored) {
            // Idempotence is the whole point of the crash-safety design. Re-applying an old snapshot
            // is never correct: it would roll the player back past everything they did since the
            // first restore. Callers therefore do not need to know whether this already ran.
            return true;
        }
        UUID uuid = UUID.fromString(p.uuid);
        // Combat state is server-side bookkeeping and must be dropped even if the player is offline,
        // otherwise a later round would start with a stale legacy flag.
        CombatState.clear(uuid);
        ServerPlayer player = online(p);
        if (player == null) {
            pendingRestore.put(uuid, session.id);
            journal(session).append("RESTORE_DEFERRED", "player", p.name);
            return false;
        }
        // Cleared above, so this is the unconditional push: an offline-then-online player would
        // otherwise keep a stale legacy flag and a phantom block penalty on their client.
        PvPocketNetworking.sendState(player);
        // And the same for everyone else's screen. The block pose is drawn from a flag that lives
        // on other clients, and CombatState.clear above only reaches the server's own copy.
        PvPocketNetworking.broadcastBlocking(player, false);
        try {
            Path file = storage.snapshotDir(session.id).resolve(p.snapshotFile);
            PlayerSnapshot snapshot = PlayerSnapshot.readFrom(file);
            snapshot.restore(player);
            boolean verified = snapshot.verify(player);
            p.restored = true;
            // Persisted here rather than left to the caller's later transition(). The deferred
            // restore path (the tick drainer) has no transition of its own, so without this the flag
            // would live only in memory: the next restart would re-queue the same participant and
            // apply the snapshot a second time, wiping everything the player earned after the first
            // restore. Writing per participant also makes the restore loop itself crash-safe — a
            // crash halfway through leaves a correct intermediate state that names exactly who is
            // still owed a restore.
            persist(session);
            // The snapshot is applied to the live entity, and nothing writes it to playerdata until
            // the next autosave (up to five minutes) or a clean disconnect. A hard stop inside that
            // window reloads the pre-match inventory on the next login — and because the following
            // match snapshots whatever it finds, the loadout stops being a temporary state and
            // becomes the baseline that every later restore faithfully restores. That is exactly the
            // "the duel items are still on me" report: the restore had worked, and then the world was
            // killed before it reached the disk. One explicit save closes the window.
            ((PlayerManagerInvoker) player.getServer().getPlayerList())
                    .pvpocket$savePlayerData(player);
            sessionByPlayer.remove(player.getUUID());
            journal(session).append("RESTORE_COMPLETED", "player", p.name, "verified", verified);
            if (!verified) {
                journal(session).append("ANOMALY", "player", p.name,
                        "detail", "state digest mismatch after restore");
                PvPocket.LOGGER.warn("Restore digest mismatch for {} in session {}", p.name, session.id);
            }
            player.displayClientMessage(PvLang.t("pvpocket.msg.series.restored")
                    .withStyle(ChatFormatting.GREEN), false);
            return true;
        } catch (Exception e) {
            PvPocket.LOGGER.error("Restore failed for {} in session {}", p.name, session.id, e);
            journal(session).append("ANOMALY", "player", p.name, "detail", "restore failed: " + e);
            // Not re-queued on purpose. The tick drainer removes the entry before attempting, so
            // re-queueing here would retry a genuinely broken snapshot every tick forever. The
            // participant stays marked unrestored on disk, so a restart will offer one more attempt.
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
     * <p>The counterpart of {@code LOADOUT_SAVED}, and the one line that was missing. A player who
     * edits their set and is then handed the default one has no way to tell "the panel never sent
     * it" from "the server threw it away", and neither did the journal: every successful save wrote
     * a line and every refused one wrote nothing, which reads exactly like a client that never
     * pressed 保存. The reason is journalled rather than the sentence, so the file stays parseable
     * when the language changes.</p>
     *
     * <p>{@code session} is null when the id named a session that no longer exists — there is no
     * journal to write to then, so the reason goes to the log only.</p>
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
        return server.getPlayerList().getPlayer(UUID.fromString(p.uuid));
    }

    /**
     * Reports a refusal.
     *
     * <p>Takes a translation key and its arguments, not a sentence: the server decides <em>what</em>
     * went wrong, never how it is spelled. The client resolves the key in its own language when it
     * draws the chat line.</p>
     */
    private static void fail(ServerPlayer player, String key, Object... args) {
        if (player == null) {
            // There is no addressee when the refusal is triggered by somebody leaving rather than
            // by somebody clicking. Logged instead of thrown: the session is already in the right
            // state, and losing the sentence is not worth losing the session over.
            PvPocket.LOGGER.info("Refusal with no player to tell: {} {}",
                    key, java.util.Arrays.toString(args));
            return;
        }
        player.displayClientMessage(PvLang.t(key, args).withStyle(ChatFormatting.RED), false);
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Rounds won, one number per side, in team order — {@code "2:1"}, or {@code "1:0:0"} for three. */
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
