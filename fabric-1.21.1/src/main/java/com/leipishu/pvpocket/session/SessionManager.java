package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.ArenaMaps;
import com.leipishu.pvpocket.arena.ArenaRegion;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutBook;
import com.leipishu.pvpocket.loadout.LoadoutBuilder;
import com.leipishu.pvpocket.network.ArenaBoundsPayload;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.network.PvPocketNetworking;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.RulesSubmitPayload;
import com.leipishu.pvpocket.network.SpectateRequestPayload;
import com.leipishu.pvpocket.rules.DuelRules;
import com.leipishu.pvpocket.snapshot.PlayerSnapshot;
import com.leipishu.pvpocket.storage.HomePositions;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import com.leipishu.pvpocket.storage.SessionJournal;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private int sweepTimer;

    private SessionManager(MinecraftServer server) {
        this.server = server;
        this.storage = new PvPocketStorage(server);
        this.loadouts = new LoadoutBook(storage);
        this.spectators = new Spectators(this);
        this.homes = HomePositions.load(server);
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
                finishSeries(session, null);
            } else if (session.stage == SessionStage.ROUND_READY) {
                roundReadyAt.remove(session.id);
                finishSeries(session, leaderOf(session));
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
    }

    // ------------------------------------------------------------------ player events

    public void onJoin(ServerPlayerEntity player) {
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
        UUID joining = player.getUuid();
        deferred.add(() -> {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(joining);
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
                    finishSeries(session, leaderOf(session));
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
            if (server.getPlayerManager().getPlayer(entry.getKey()) != null) {
                ready.add(entry.getKey());
            }
        }
        for (UUID uuid : ready) {
            String sessionId = pendingRestore.remove(uuid);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
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
                player.sendMessage(Text.literal("生存状态恢复失败，请联系管理员（会话 " + sessionId + "）。")
                        .formatted(Formatting.RED), false);
                continue;
            }
            // A deferred restore has two causes and they read very differently to a player: the
            // session was voided by a crash, or it finished normally and they simply left early.
            String note = session.voidReason == null
                    ? "上一场决斗已结束，你的生存状态已恢复。"
                    : "上一场决斗因服务器中断已作废，你的生存状态已恢复。";
            player.sendMessage(Text.literal(note).formatted(Formatting.YELLOW), false);
        }
    }

    public void onDisconnect(ServerPlayerEntity player) {
        // Before the participant check below, because a spectator is by definition not a
        // participant. Their game mode is restored here rather than deferred, so the playerdata
        // written during this same disconnect records survival rather than spectator — otherwise
        // they log back in inside the arena with no way to fly out.
        spectators.onDisconnect(player);
        // Last chance to record where they were: after this the playerdata is written and the
        // player is gone, and a disconnect is the one moment the saved home is guaranteed current.
        homes.remember(player);
        homes.flush();
        String sessionId = sessionByPlayer.get(player.getUuid());
        if (sessionId == null) {
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        Participant opponent = session.opponentOf(player.getUuidAsString());
        deferred.add(() -> settle(session, "DISCONNECT", opponent == null ? null : opponent.uuid));
    }

    public void onDeath(ServerPlayerEntity player) {
        String sessionId = sessionByPlayer.get(player.getUuid());
        if (sessionId == null) {
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        Participant opponent = session.opponentOf(player.getUuidAsString());
        session.pendingWinner = opponent == null ? null : opponent.uuid;
        session.pendingReason = "DEATH";
        journal(session).append("ROUND_DECIDED", "reason", "DEATH", "winner", session.pendingWinner);
        persist(session);
    }

    /**
     * A dead player cannot be teleported, so the settle happens once they are alive again. By then
     * the entity has been replaced, which is why everything is looked up by uuid.
     */
    public void onRespawn(ServerPlayerEntity player) {
        String sessionId = sessionByPlayer.get(player.getUuid());
        if (sessionId == null) {
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE || session.pendingWinner == null) {
            return;
        }
        deferred.add(() -> settle(session, session.pendingReason, session.pendingWinner));
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
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
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
            ArenaRegion region = ArenaManager.region(session.id);
            if (region == null) {
                continue;
            }
            for (Participant p : session.participants) {
                ServerPlayerEntity player = online(p);
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
                        player.sendMessage(Text.literal("已回到场地内。").formatted(Formatting.GREEN), false);
                        // Wipes the big number instead of leaving a stale "1" hanging in the middle
                        // of the screen after the player has already saved themselves.
                        player.networkHandler.sendPacket(new ClearTitleS2CPacket(false));
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
                            "world", player.getWorld().getRegistryKey().getValue().toString(),
                            "countdownMs", BOUNDARY_COUNTDOWN_MS);
                    player.sendMessage(Text.literal("你离开了场地边界，5 秒内不返回本局判负。")
                            .formatted(Formatting.RED), false);
                    announceBoundary(player, 5);
                    continue;
                }
                long remaining = BOUNDARY_COUNTDOWN_MS - (now - exit.since());
                if (remaining <= 0) {
                    boundaryExits.remove(key);
                    Participant opponent = session.opponentOf(p.uuid);
                    journal(session).append("BOUNDARY_FORFEIT", "player", p.name,
                            "elapsedMs", now - exit.since());
                    player.networkHandler.sendPacket(new ClearTitleS2CPacket(false));
                    deferred.add(() -> settle(session, "OUT_OF_BOUNDS",
                            opponent == null ? null : opponent.uuid));
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
    private void notifyPushBack(DuelSession session, ServerPlayerEntity player, String key, boolean settling) {
        long now = System.currentTimeMillis();
        Long last = boundaryNotices.get(key);
        if (last != null && now - last < BOUNDARY_NOTICE_GAP_MS) {
            return;
        }
        boundaryNotices.put(key, now);
        journal(session).append("BOUNDARY_PUSHED_BACK", "player", player.getName().getString(),
                "settling", settling, "rule", settling ? "pending" : "push_back");
        player.sendMessage(Text.literal(settling
                        ? "正在把你放回场地内…"
                        : "已到场地边界，继续往外会被拉回。")
                .formatted(Formatting.YELLOW), true);
    }

    /**
     * Draws the boundary countdown as a single large number in the middle of the screen.
     *
     * <p>A title rather than the action bar because of where the player's eyes are: someone who has
     * just walked out of the arena is looking at the horizon, not at the hotbar. The fade is zero-in,
     * one-second stay, short fade-out so each number replaces the previous one instead of queueing
     * behind it.</p>
     */
    private static void announceBoundary(ServerPlayerEntity player, int second) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 22, 4));
        player.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal(String.valueOf(second)).formatted(Formatting.RED, Formatting.BOLD)));
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
     */
    public void requestDuel(ServerPlayerEntity challenger, ServerPlayerEntity target, CombatMode mode) {
        if (challenger.getUuid().equals(target.getUuid())) {
            fail(challenger, "不能向自己发起决斗。");
            return;
        }
        if (sessionByPlayer.containsKey(challenger.getUuid())) {
            fail(challenger, "你已经在决斗中。");
            return;
        }
        if (sessionByPlayer.containsKey(target.getUuid())) {
            fail(challenger, target.getName().getString() + " 已在决斗中。");
            return;
        }
        if (findOpenSession(target.getUuid()) != null) {
            fail(challenger, target.getName().getString() + " 有待处理的邀请。");
            return;
        }
        if (findOpenSession(challenger.getUuid()) != null) {
            fail(challenger, "你还有一个未完成的规则提案，先处理它。");
            return;
        }
        if (challenger.interactionManager.getGameMode() == GameMode.SPECTATOR
                || target.interactionManager.getGameMode() == GameMode.SPECTATOR) {
            fail(challenger, "旁观模式无法参与决斗。");
            return;
        }
        if (!ArenaManager.isReady(server)) {
            fail(challenger, "决斗维度未加载，无法发起。");
            return;
        }

        DuelSession session = new DuelSession();
        session.id = UUID.randomUUID().toString();
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        session.participants.add(new Participant(challenger.getUuidAsString(), challenger.getName().getString(), 0));
        session.participants.add(new Participant(target.getUuidAsString(), target.getName().getString(), 1));

        // Both sides start from their own saved loadout, not from a shared preset. A player who spent
        // time on a sword should not have to re-enter it every time they challenge someone, and the
        // target's side should be what *they* configured, not what the challenger happens to carry.
        for (Participant p : session.participants) {
            Loadout seed = loadouts.get(UUID.fromString(p.uuid));
            seed.autoAssignSlots();
            p.loadout = seed.encode();
            p.loadoutDigest = seed.digest();
        }

        // The `legacy` literal on the command is a seed for c.mode, not a separate code path: it
        // pre-selects the rule so the panel opens on the mode the player asked for, and the other
        // side still has to consent to it in the panel.
        DuelRules rules = DuelRules.defaults();
        rules.set("c.mode", mode == CombatMode.LEGACY_1_8 ? 1 : 0);
        applyRulesToSession(session, rules);
        session.rulesRevision = 0;
        session.rulesAuthor = challenger.getUuidAsString();
        session.awaitingUuid = challenger.getUuidAsString();

        sessions.put(session.id, session);
        journal(session).append("SESSION_CREATED",
                "challenger", challenger.getName().getString(),
                "target", target.getName().getString(),
                "seedMode", mode.id());
        journal(session).append("RULES_DRAFTED", "revision", 0, "digest", session.rulesDigest);
        persist(session);

        challenger.sendMessage(Text.literal("已打开规则面板。设置完成后点击「发送征询」提交给 "
                + target.getName().getString() + "。").formatted(Formatting.GRAY), false);
        openPanel(challenger, session, RulesOpenPayload.ROLE_PROPOSER);
    }

    /** Sends (or re-sends) the panel to a player. Used by the packet path and by /pvpocket review. */
    private void openPanel(ServerPlayerEntity player, DuelSession session, int role) {
        PvPocketNetworking.openRules(player, new RulesOpenPayload(
                session.id,
                session.rules,
                role,
                session.rulesRevision,
                new RulesOpenPayload.Details(
                        otherName(session, player.getUuidAsString()),
                        loadoutSummary(session),
                        // Recomputed on every open, not cached: a map frees up the moment the other
                        // duel ends, and a stale "使用中" is a map nobody can pick until they
                        // reopen the panel.
                        ArenaManager.busyMapMask(session.id))));
    }

    /**
     * "Dev1 8 / Dev2 9 · 同意 1/2", for the panel header.
     *
     * <p>Every participant's item count is shown, not just the reader's: the loadout is part of what
     * is being agreed to, so "what am I agreeing to" has to include what the other side is bringing.
     * The agreement counter is what turns a silent veto into a visible one — a player who changed
     * their loadout and broke everyone else's consent can see the count drop back to 0/2.</p>
     */
    private static String loadoutSummary(DuelSession session) {
        String combined = combinedLoadoutDigest(session);
        StringBuilder sb = new StringBuilder("配装 ");
        int agreed = 0;
        for (Participant p : session.participants) {
            if (sb.length() > 3) {
                sb.append(" · ");
            }
            sb.append(p.name).append(' ')
                    .append(p.loadout == null ? "-" : String.valueOf(Loadout.decode(p.loadout).size()));
            if (combined.equals(p.agreedLoadoutDigest)) {
                agreed++;
            }
        }
        return sb.append(" · 同意 ").append(agreed).append('/').append(session.participants.size()).toString();
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
                .sorted(Comparator.comparing(p -> p.uuid))
                .forEach(p -> sb.append(p.uuid).append(':')
                        .append(p.loadoutDigest == null ? "-" : p.loadoutDigest).append(';'));
        return PvPocketStorage.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    /** True only when every participant has agreed to the loadout set as it stands right now. */
    private static boolean allLoadoutsAgreed(DuelSession session) {
        String combined = combinedLoadoutDigest(session);
        for (Participant p : session.participants) {
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
            ServerPlayerEntity player = online(p);
            if (player != null) {
                openPanel(player, session, p.uuid.equals(changed.uuid)
                        ? RulesOpenPayload.ROLE_PROPOSER
                        : RulesOpenPayload.ROLE_REVIEWER);
            }
        }
        String name = changed.name;
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                player.sendMessage(Text.literal(name + " 改了配装，规则需要重新征询。")
                        .formatted(Formatting.GOLD), false);
            }
        }
    }

    /** Re-opens the panel for whoever is currently expected to answer. Command fallback path. */
    public void review(ServerPlayerEntity player) {
        DuelSession session = findOpenSession(player.getUuid());
        if (session == null) {
            fail(player, "当前没有待处理的规则提案。");
            return;
        }
        int role = player.getUuidAsString().equals(session.awaitingUuid)
                ? (session.stage == SessionStage.DRAFT
                    ? RulesOpenPayload.ROLE_PROPOSER
                    : RulesOpenPayload.ROLE_REVIEWER)
                : RulesOpenPayload.ROLE_REVIEWER;
        openPanel(player, session, role);
        player.sendMessage(Text.literal("规则版本 v" + session.rulesRevision
                + "（" + shortDigest(session) + "）").formatted(Formatting.DARK_GRAY), false);
    }

    /**
     * Handles one panel outcome.
     *
     * <p>Every branch re-derives permission from the session record. The client tells us what it
     * wants to do, never whether it is allowed to: only the player the session is waiting on may
     * act, and only the author of the current revision may replace it.</p>
     */
    public void onRulesSubmit(ServerPlayerEntity player, int action, String encoded) {
        String uuid = player.getUuidAsString();
        DuelSession session = findOpenSession(player.getUuid());
        if (session == null) {
            fail(player, "当前没有待处理的规则提案。");
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
            return;
        }

        if (action == RulesSubmitPayload.ACTION_REJECT) {
            if (!uuid.equals(session.awaitingUuid)) {
                fail(player, "现在轮到对方决定。");
                return;
            }
            journal(session).append("SESSION_DENIED", "player", player.getName().getString(),
                    "revision", session.rulesRevision);
            session.voidReason = "DENIED";
            transition(session, SessionStage.VOIDED);
            inviteByTarget.values().removeIf(session.id::equals);
            closePanels(session);
            ServerPlayerEntity other = online(otherParticipant(session, uuid));
            if (other != null) {
                other.sendMessage(Text.literal("对方拒绝了决斗。").formatted(Formatting.GRAY), false);
            }
            player.sendMessage(Text.literal("已拒绝决斗。").formatted(Formatting.GRAY), false);
            return;
        }

        if (action == RulesSubmitPayload.ACTION_PROPOSE) {
            if (!uuid.equals(session.awaitingUuid)) {
                fail(player, "现在轮到对方决定。");
                return;
            }
            DuelRules proposed = DuelRules.decode(encoded);
            Participant other = otherParticipant(session, uuid);
            if (other == null) {
                return;
            }
            List<String> changed = DuelRules.decode(session.rules).differences(proposed);
            applyRulesToSession(session, proposed);
            session.rulesRevision++;
            session.rulesAuthor = uuid;
            session.awaitingUuid = other.uuid;
            inviteByTarget.put(UUID.fromString(other.uuid), session.id);
            journal(session).append("RULES_PROPOSED",
                    "revision", session.rulesRevision,
                    "author", player.getName().getString(),
                    "digest", session.rulesDigest,
                    "changed", String.join(",", changed));
            // Proposing is also agreeing: the proposer is looking at this exact loadout set and
            // pressing send. Recording it here means a 1v1 needs no separate consent step from the
            // proposer, while a change by either side still invalidates it via the digest.
            Participant me = session.participant(uuid);
            if (me != null) {
                me.agreedLoadoutDigest = combinedLoadoutDigest(session);
                journal(session).append("LOADOUT_AGREED", "player", me.name,
                        "combined", me.agreedLoadoutDigest);
            }
            transition(session, SessionStage.NEGOTIATING);

            player.sendMessage(Text.literal("规则 v" + session.rulesRevision + " 已发送给 "
                    + other.name + "，等待对方同意。").formatted(Formatting.GRAY), false);
            ServerPlayerEntity target = online(other);
            if (target != null) {
                target.sendMessage(Text.literal(player.getName().getString() + " 向你发起决斗（v"
                                + session.rulesRevision + "）：" + proposed.summary())
                        .formatted(Formatting.GOLD), false);
                target.sendMessage(Text.literal("规则面板已打开。也可以输入 /pvpocket accept 直接同意。")
                        .formatted(Formatting.DARK_GRAY), false);
                openPanel(target, session, RulesOpenPayload.ROLE_REVIEWER);
            }
            return;
        }

        if (action == RulesSubmitPayload.ACTION_ACCEPT) {
            if (session.stage != SessionStage.NEGOTIATING) {
                fail(player, "还没有可接受的规则提案。");
                return;
            }
            if (!uuid.equals(session.awaitingUuid)) {
                fail(player, "现在轮到对方决定。");
                return;
            }
            // Accepting is agreeing to the rules *and* to everybody's loadout as they stand. The
            // agreement is recorded against the combined fingerprint, so if the other side had
            // changed their loadout since the proposal this one no longer counts and the panel is
            // reopened rather than starting a match on terms nobody agreed to.
            Participant me = session.participant(uuid);
            if (me != null) {
                me.agreedLoadoutDigest = combinedLoadoutDigest(session);
                journal(session).append("LOADOUT_AGREED", "player", me.name,
                        "combined", me.agreedLoadoutDigest);
                persist(session);
            }
            if (!allLoadoutsAgreed(session)) {
                int agreed = 0;
                for (Participant p : session.participants) {
                    if (combinedLoadoutDigest(session).equals(p.agreedLoadoutDigest)) {
                        agreed++;
                    }
                }
                fail(player, "还有 " + (session.participants.size() - agreed)
                        + " 人没同意当前配装，暂不能开始。");
                openPanel(player, session, RulesOpenPayload.ROLE_REVIEWER);
                return;
            }
            startAccepted(session, player);
        }
    }

    /** Freeze, snapshot, teleport, start. Shared by the panel path and the /accept shortcut. */
    private void startAccepted(DuelSession session, ServerPlayerEntity accepter) {
        if (!ArenaManager.isReady(server)) {
            fail(accepter, "决斗维度未加载。");
            return;
        }
        // Checked again here rather than trusting the caller: /pvpocket accept reaches this method
        // through a different branch, and "everyone agreed to these loadouts" is not a property of
        // the caller but of the session.
        if (!allLoadoutsAgreed(session)) {
            fail(accepter, "配装还没有全员同意，暂不能开始。");
            return;
        }
        // The lease is taken before anything is frozen. A full grid must fail while the session is
        // still in NEGOTIATING — after the snapshot the only honest outcome would be a rollback of
        // two inventories for a reason that had nothing to do with the players.
        DuelRules rules = DuelRules.decode(session.rules);
        int mapIndex = rules.get("e.template");
        // Checked here as well as in the panel. The panel is a hint — it greys out maps that were
        // taken when it was opened, and the player may have been looking at it for a minute while
        // somebody else started a duel on the map they picked. Two duels on one map is not a
        // cosmetic problem: they would be two copies of the same terrain, and a player told "the
        // colosseum" has no way to know which one they were sent to.
        if (ArenaManager.isMapBusy(mapIndex, session.id)) {
            fail(accepter, "「" + ArenaMaps.byIndex(mapIndex).label
                    + "」正在被另一场决斗使用，请换一张地图后再开始。");
            return;
        }
        ArenaRegion region = ArenaManager.allocate(server, session.id,
                rules.get("e.sizeX"), rules.get("e.sizeY"), rules.get("e.sizeZ"),
                ArenaMaps.byIndex(mapIndex),
                rules.flag("f.protectTerrain"), rules.flag("f.allowBreakOwn"));
        if (region == null) {
            fail(accepter, "场地已满，暂时无法开始新的决斗。");
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
        journal(session).append("RULES_ACCEPTED",
                "revision", session.rulesRevision,
                "author", session.rulesAuthor,
                "acceptedBy", accepter.getName().getString(),
                "digest", session.rulesDigest,
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

    /** Command shortcut: accepts whatever revision is on the table. Same validation as the panel. */
    public void accept(ServerPlayerEntity player) {
        DuelSession session = findOpenSession(player.getUuid());
        if (session == null) {
            fail(player, "没有待接受的邀请。");
            return;
        }
        if (session.stage != SessionStage.NEGOTIATING
                || !player.getUuidAsString().equals(session.awaitingUuid)) {
            fail(player, "没有待接受的邀请。");
            return;
        }
        Participant me = session.participant(player.getUuidAsString());
        if (me != null) {
            me.agreedLoadoutDigest = combinedLoadoutDigest(session);
            journal(session).append("LOADOUT_AGREED", "player", me.name,
                    "combined", me.agreedLoadoutDigest);
            persist(session);
        }
        if (!allLoadoutsAgreed(session)) {
            fail(player, "还有参与者没同意当前配装，暂不能开始。");
            return;
        }
        startAccepted(session, player);
    }

    /**
     * Command shortcut for the panel's 「发送征询」.
     *
     * <p>It re-submits the current revision unchanged, which is exactly what pressing the button
     * without editing anything does — so the command path exercises the same code as the GUI rather
     * than a parallel one. Without it, a duel could not be driven from the console at all: the panel
     * is the only way out of {@link SessionStage#DRAFT}.</p>
     */
    public void propose(ServerPlayerEntity player) {
        DuelSession session = findOpenSession(player.getUuid());
        if (session == null) {
            fail(player, "没有待发送的规则提案。");
            return;
        }
        onRulesSubmit(player, RulesSubmitPayload.ACTION_PROPOSE, session.rules);
    }

    public void deny(ServerPlayerEntity player) {
        onRulesSubmit(player, RulesSubmitPayload.ACTION_REJECT, "");
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
    public void surrender(ServerPlayerEntity player) {
        String sessionId = sessionByPlayer.get(player.getUuid());
        if (sessionId == null) {
            fail(player, "你不在决斗中。");
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null
                || (session.stage != SessionStage.ROUND_ACTIVE && session.stage != SessionStage.ROUND_READY)) {
            fail(player, "当前没有进行中的决斗。");
            return;
        }
        if (session.pendingWinner != null) {
            // The round is already decided and waiting for the loser to respawn. Settling again here
            // would award a second round for the same death — and, worse, could hand the round to
            // the player who just died.
            fail(player, "这一局已经分出胜负，等待结算。");
            return;
        }
        if (!DuelRules.decode(session.rules).flag("g.allowSurrender")) {
            fail(player, "本次决斗不允许投降。");
            return;
        }
        Participant opponent = session.opponentOf(player.getUuidAsString());
        forfeit(session, player, opponent);
    }

    /**
     * Ends the series by forfeit: the surrendering side loses, the opponent takes the series.
     *
     * <p>Works from {@code ROUND_READY} as well as {@code ROUND_ACTIVE} — a player who wants out
     * should not have to sit through the intermission and start another round to say so.</p>
     */
    private void forfeit(DuelSession session, ServerPlayerEntity player, Participant opponent) {
        String winner = opponent == null ? null : opponent.uuid;
        journal(session).append("SERIES_FORFEITED",
                "player", player.getName().getString(),
                "winner", winner,
                "score", scoreLine(session),
                "round", session.round,
                "stage", session.stage.name());
        if (session.stage == SessionStage.ROUND_ACTIVE) {
            // winner=null on purpose: the round has no winner, the series does.
            journal(session).append("ROUND_ENDED",
                    "round", session.round, "reason", "SURRENDER", "winner", null);
            session.pendingWinner = null;
            session.pendingReason = null;
            roundStartedAt.remove(session.id);
            clearBoundaryExits(session);
            transition(session, SessionStage.ROUND_SETTLED);
        }
        roundReadyAt.remove(session.id);
        session.seriesEndReason = "SURRENDER";
        player.sendMessage(Text.literal("你认输了，整场决斗结束。").formatted(Formatting.RED), false);
        finishSeries(session, winner);
    }

    // ------------------------------------------------------------------ rule plumbing

    /** Copies a rule set onto the session and refreshes the derived fields plus the fingerprint. */
    private static void applyRulesToSession(DuelSession session, DuelRules rules) {
        session.mode = rules.get("c.mode") == 1 ? CombatMode.LEGACY_1_8.id() : CombatMode.MODERN.id();
        // a.series is an index into the panel's list of odd round counts: 0 -> 1, 1 -> 3, ... 7 -> 15.
        // Deriving it here rather than storing it keeps one source of truth, and a hand-edited or
        // out-of-range value clamps to the longest series instead of producing a negative bestOf.
        session.bestOf = 2 * Math.max(0, Math.min(7, rules.get("a.series"))) + 1;
        session.rules = rules.encode();
        session.rulesDigest = rules.digest();
    }

    /** Wins required to take the series: 3 rounds -> 2 wins, 15 rounds -> 8 wins. */
    private static int winsNeeded(DuelSession session) {
        return session.bestOf / 2 + 1;
    }

    private DuelSession findOpenSession(UUID uuid) {
        String id = uuid.toString();
        for (DuelSession session : sessions.values()) {
            if (session.stage == SessionStage.DRAFT || session.stage == SessionStage.NEGOTIATING) {
                if (session.isParticipant(id)) {
                    return session;
                }
            }
        }
        return null;
    }

    private static Participant otherParticipant(DuelSession session, String uuid) {
        for (Participant p : session.participants) {
            if (!p.uuid.equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    private static String otherName(DuelSession session, String uuid) {
        Participant other = otherParticipant(session, uuid);
        return other == null ? "对手" : other.name;
    }

    private static String shortDigest(DuelSession session) {
        return session.rulesDigest == null ? "--------" : session.rulesDigest;
    }

    /** Drops any rule panel still open for this session on either side. */
    private void closePanels(DuelSession session) {
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                PvPocketNetworking.closeRules(player, session.id);
            }
        }
    }

    private static String modeLabel(CombatMode mode) {
        return mode == CombatMode.LEGACY_1_8 ? "1.8 战斗" : "现代战斗";
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
    public void onLoadoutRequest(ServerPlayerEntity player) {
        DuelSession session = findOpenSession(player.getUuid());
        if (session == null) {
            Loadout personal = loadouts.get(player.getUuid());
            personal.autoAssignSlots();
            PvPocketNetworking.openLoadout(player, new com.leipishu.pvpocket.network.LoadoutOpenPayload(
                    "", personal.encode(), RulesOpenPayload.ROLE_PROPOSER, ""));
            return;
        }
        Participant p = session.participant(player.getUuidAsString());
        if (p == null) {
            return;
        }
        if (p.loadout == null) {
            Loadout seed = loadouts.get(player.getUuid());
            seed.autoAssignSlots();
            p.loadout = seed.encode();
            p.loadoutDigest = seed.digest();
        }
        int role = session.stage == SessionStage.DRAFT
                ? RulesOpenPayload.ROLE_PROPOSER
                : RulesOpenPayload.ROLE_REVIEWER;
        PvPocketNetworking.openLoadout(player, new com.leipishu.pvpocket.network.LoadoutOpenPayload(
                session.id, p.loadout, role, otherName(session, player.getUuidAsString())));
    }

    /**
     * Stores a loadout submitted by a client, after re-validating it.
     *
     * <p>The client runs the same validation to keep its save button honest, but a client is not a
     * source of truth: this is the pass that actually decides whether a loadout becomes items. A
     * rejected loadout leaves the previous one untouched rather than half-applying.</p>
     *
     * <p>An empty session id means "the personal loadout". It is saved to the book <em>and</em>, if
     * the player happens to be in a session that has not started, mirrored onto their participant —
     * otherwise a player who edits from the command line during a negotiation would be told "saved"
     * while the duel still carries the old one.</p>
     */
    public void onLoadoutSubmit(ServerPlayerEntity player, String sessionId, String encoded) {
        if (sessionId == null || sessionId.isEmpty()) {
            submitPersonal(player, encoded);
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage.isTerminal()) {
            fail(player, "这场决斗已经结束。");
            return;
        }
        if (!session.isParticipant(player.getUuidAsString())) {
            fail(player, "你不在这场决斗里。");
            return;
        }
        if (!session.stage.isPreFreeze()) {
            fail(player, "决斗已经开始，配装不能再改。");
            return;
        }
        Loadout loadout = Loadout.decode(encoded);
        List<String> problems = loadout.validate();
        if (!problems.isEmpty()) {
            fail(player, "配装未保存：" + problems.get(0));
            return;
        }
        Participant p = session.participant(player.getUuidAsString());
        if (p == null) {
            return;
        }
        String before = combinedLoadoutDigest(session);
        p.loadout = loadout.encode();
        p.loadoutDigest = loadout.digest();
        journal(session).append("LOADOUT_SAVED",
                "player", p.name,
                "digest", p.loadoutDigest,
                "items", loadout.size(),
                "summary", loadout.name);
        persist(session);
        noteLoadoutChanged(session, p, before);
        player.sendMessage(Text.literal("配装已保存（" + loadout.size() + " 项 · "
                + p.loadoutDigest + "）。").formatted(Formatting.GREEN), false);
    }

    /** Saves a loadout to the player's book, and mirrors it onto any session that has not started. */
    private void submitPersonal(ServerPlayerEntity player, String encoded) {
        Loadout loadout = Loadout.decode(encoded);
        List<String> problems = loadout.validate();
        if (!problems.isEmpty()) {
            fail(player, "配装未保存：" + problems.get(0));
            return;
        }
        loadout.autoAssignSlots();
        loadouts.put(player.getUuid(), loadout);

        String mirrored = "仅保存为个人配装";
        DuelSession session = findOpenSession(player.getUuid());
        if (session != null && session.stage.isPreFreeze()) {
            Participant p = session.participant(player.getUuidAsString());
            if (p != null) {
                String before = combinedLoadoutDigest(session);
                p.loadout = loadout.encode();
                p.loadoutDigest = loadout.digest();
                journal(session).append("LOADOUT_SAVED",
                        "player", p.name,
                        "digest", p.loadoutDigest,
                        "items", loadout.size(),
                        "summary", loadout.name,
                        "scope", "personal");
                persist(session);
                noteLoadoutChanged(session, p, before);
                mirrored = "已同步到当前决斗";
            }
        }
        player.sendMessage(Text.literal("配装已保存（" + loadout.size() + " 项 · "
                + loadout.digest() + "，" + mirrored + "）。").formatted(Formatting.GREEN), false);
    }

    /**
     * Clears a player's saved loadout, and mirrors the fallback onto any negotiation in progress.
     *
     * <p>Lives here rather than in the command so the command cannot become a second way to edit a
     * loadout without telling the session. A player who resets from the console while a negotiation
     * is open would otherwise keep entering with the old items — exactly the state the consent
     * digest exists to catch.</p>
     */
    public void resetPersonalLoadout(ServerPlayerEntity player) {
        loadouts.clear(player.getUuid());
        DuelSession session = findOpenSession(player.getUuid());
        if (session == null || !session.stage.isPreFreeze()) {
            player.sendMessage(Text.literal("个人配装已清空，下次发起决斗将使用默认预设。")
                    .formatted(Formatting.GREEN), false);
            return;
        }
        Participant p = session.participant(player.getUuidAsString());
        if (p == null) {
            return;
        }
        Loadout fallback = Loadout.defaultDuel();
        String before = combinedLoadoutDigest(session);
        p.loadout = fallback.encode();
        p.loadoutDigest = fallback.digest();
        journal(session).append("LOADOUT_RESET", "player", p.name, "digest", p.loadoutDigest);
        persist(session);
        noteLoadoutChanged(session, p, before);
        player.sendMessage(Text.literal("个人配装已清空并回到默认预设（" + fallback.size() + " 项）。")
                .formatted(Formatting.GREEN), false);
    }

    /**
     * The loadout this player would enter with right now.
     *
     * <p>Falls back to the player's saved loadout rather than returning null so that the diagnostic
     * command and a first duel show the same thing — a null here would make "no loadout yet" look
     * like "loadout support is broken".</p>
     */
    public Loadout loadoutFor(ServerPlayerEntity player) {
        String uuid = player.getUuidAsString();
        for (DuelSession session : sessions.values()) {
            if (session.stage.isTerminal()) {
                continue;
            }
            Participant p = session.participant(uuid);
            if (p != null && p.loadout != null) {
                return Loadout.decode(p.loadout);
            }
        }
        return loadouts.get(player.getUuid());
    }

    /** The book itself, so the command layer can reset a saved loadout. */
    public LoadoutBook loadoutBook() {
        return loadouts;
    }

    public void status(ServerPlayerEntity player) {
        String sessionId = sessionByPlayer.get(player.getUuid());
        if (sessionId == null) {
            player.sendMessage(Text.literal("你不在决斗中。").formatted(Formatting.GRAY), false);
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        Participant me = session.participant(player.getUuidAsString());
        Participant opponent = session.opponentOf(player.getUuidAsString());
        player.sendMessage(Text.literal("会话 " + shortId(session.id)
                + " | 阶段 " + session.stage
                + " | 回合 " + session.round + "/" + session.bestOf
                + " | 比分 " + scoreLine(session)
                + (opponent == null ? "" : " | 对手 " + opponent.name))
                .formatted(Formatting.AQUA), false);
        player.sendMessage(Text.literal("规则 v" + session.rulesRevision + "（" + shortDigest(session)
                        + "）：" + DuelRules.decode(session.rules).summary())
                .formatted(Formatting.DARK_GRAY), false);
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
    public void onSpectateRequest(ServerPlayerEntity player, SpectateRequestPayload request) {
        switch (request.action()) {
            case SpectateRequestPayload.ACTION_LIST -> spectators.openList(player);
            case SpectateRequestPayload.ACTION_JOIN -> spectators.join(player, request.sessionId());
            case SpectateRequestPayload.ACTION_LEAVE -> {
                if (!spectators.leave(player, "REQUEST", true)) {
                    player.sendMessage(Text.literal("你当前不在观战中。").formatted(Formatting.GRAY), false);
                }
            }
            case SpectateRequestPayload.ACTION_CYCLE -> spectators.cycle(player);
            case SpectateRequestPayload.ACTION_DETACH -> spectators.detach(player);
            default -> PvPocket.LOGGER.debug("Ignoring unknown spectate action {} from {}",
                    request.action(), player.getName().getString());
        }
    }

    /** Command path for {@code /pvpocket list}. */
    public void listMatches(ServerPlayerEntity player) {
        spectators.openList(player);
    }

    /** Command path for {@code /pvpocket spectate leave}. */
    public void stopSpectating(ServerPlayerEntity player) {
        if (!spectators.leave(player, "COMMAND", true)) {
            player.sendMessage(Text.literal("你当前不在观战中。").formatted(Formatting.GRAY), false);
        }
    }

    /** Command path for {@code /pvpocket spectate <session>}; prefix match, for typing. */
    public void spectate(ServerPlayerEntity player, String sessionPrefix) {
        DuelSession match = null;
        for (DuelSession session : sessions.values()) {
            if (session.id.startsWith(sessionPrefix)) {
                match = session;
                break;
            }
        }
        if (match == null) {
            player.sendMessage(Text.literal("找不到以 " + sessionPrefix + " 开头的对局。")
                    .formatted(Formatting.RED), false);
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
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
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
            sessionByPlayer.put(player.getUuid(), session.id);
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
        int prepare = Math.max(0, rules.get("a.countdown"));
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
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
            if (!ArenaManager.teleportToSpawn(player, region, p.teamIndex())) {
                throw new IllegalStateException("arena world unavailable");
            }
            player.changeGameMode(GameMode.SURVIVAL);
            // The snapshot restores attributes wholesale, so a modified base value here is rolled
            // back by the same mechanism that restores inventory — no separate undo bookkeeping.
            var health = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(maxHealth);
            }
            // Every round starts from the same state. Without this a player who ended round 1 on two
            // hearts, on fire, and poisoned would begin round 2 that way — the loadout is re-granted
            // but health and effects are not items.
            resetRoundState(player, maxHealth);
            CombatState.setMode(player.getUuid(), mode);
            // Loadout is granted here, not at freeze(): the snapshot must contain the survival
            // inventory, so anything written before it would be captured as if the player owned it.
            // Re-granting each round is also what "每一小局结束物品恢复到最初状态" means: grant()
            // clears the inventory before writing, so nothing from the previous round survives.
            Loadout loadout = p.loadout == null ? Loadout.defaultDuel() : Loadout.decode(p.loadout);
            int granted = LoadoutBuilder.grant(player, loadout, p.teamIndex());
            p.loadoutApplied = true;
            journal(session).append("LOADOUT_APPLIED", "player", p.name,
                    "digest", p.loadoutDigest, "items", granted, "round", session.round);
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
            // exactly what a solid ring prevents and a walk-through curtain does not.
            int wall = ArenaManager.buildPrepareWall(arenaWorld, region);
            journal(session).append("PREPARE_WALL_BUILT", "blocks", wall,
                    "top", ArenaManager.prepareWallTop(region));
            broadcastHud(session, true);
            broadcastBounds(session, true, ArenaBoundsPayload.MODE_PREPARE);
            for (Participant p : session.participants) {
                ServerPlayerEntity player = online(p);
                if (player != null) {
                    player.sendMessage(Text.literal("回合准备：" + prepare + " 秒，倒计时结束开始交火。")
                            .formatted(Formatting.YELLOW), false);
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
            ServerPlayerEntity player = online(p);
            if (player == null) {
                // Somebody left during the preparation window. Cut rather than throw: they never
                // fought, so there is nothing to judge and no round to award.
                session.seriesEndReason = "CUT";
                journal(session).append("SERIES_CUT", "reason", "participant_offline");
                finishSeries(session, leaderOf(session));
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
                "region", region == null ? "-" : region.id,
                "spawns", region == null ? "-" : region.spawnX(0) + "," + region.spawnY(0)
                        + "," + region.spawnZ(0)
                        + " | " + region.spawnX(1) + "," + region.spawnY(1) + "," + region.spawnZ(1));
        roundStartedAt.put(session.id, System.currentTimeMillis());
        transition(session, SessionStage.ROUND_ACTIVE);
        broadcastHud(session, true);
        // Re-pushed, not left as it was: the client is still holding the white preparation curtain,
        // and the round it is now watching is governed by a rule, not by a wall. Leaving the old
        // packet in place would show a solid white boundary over an open arena.
        broadcastBounds(session, true);
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                player.sendMessage(Text.literal("第 " + session.round + " / " + session.bestOf + " 局开始（"
                                + modeLabel(mode) + "，" + rules.summary() + "）。"
                                + (session.bestOf > 1 ? "先赢 " + winsNeeded(session) + " 局者胜。" : "")
                                + "输入 /pvpocket surrender 认输（输掉整场）。")
                        .formatted(Formatting.GREEN), false);
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
                    ServerPlayerEntity player = online(p);
                    if (player != null) {
                        player.sendMessage(Text.literal("回合准备：" + second + " 秒")
                                .formatted(Formatting.YELLOW), false);
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
    private void applyEnvironment(ServerWorld world, DuelRules rules) {
        if (world == null) {
            return;
        }
        int time = rules.get("e.timeOfDay");
        // 0 = 自然; the rest are fixed ticks. 1000 is shortly after sunrise, 6000 noon, 12000 dusk,
        // 18000 midnight — the vanilla anchors, chosen so the four options read as four times of day
        // rather than four arbitrary numbers.
        boolean frozen = time > 0;
        world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(!frozen, server);
        if (frozen) {
            world.setTimeOfDay(switch (time) {
                case 2 -> 6000L;
                case 3 -> 12000L;
                case 4 -> 18000L;
                default -> 1000L;
            });
        }
        int weather = rules.get("e.weather");
        if (weather == 1) {
            world.setWeather(0, 12_000, true, false);
        } else if (weather == 2) {
            world.setWeather(0, 12_000, true, true);
        } else {
            world.setWeather(12_000, 0, false, false);
        }
    }

    private void settle(DuelSession session, String reason, String winnerUuid) {
        if (session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        journal(session).append("ROUND_ENDED",
                "round", session.round, "reason", reason, "winner", winnerUuid);
        if (winnerUuid != null) {
            session.score.merge(winnerUuid, 1, Integer::sum);
        }
        transition(session, SessionStage.ROUND_SETTLED);
        session.pendingWinner = null;
        session.pendingReason = null;
        // Cleared here rather than at the end: the "has been seen on the plot" memory belongs to the
        // round that just ended, and leaving it in place would carry a settled player into the next
        // round before they had been teleported into it.
        roundStartedAt.remove(session.id);
        clearBoundaryExits(session);
        prepareDeadlines.remove(session.id);

        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);

        String seriesWinner = seriesWinner(session);
        if (seriesWinner == null && session.participants.stream().anyMatch(p -> online(p) == null)) {
            // Somebody left. Starting a round they cannot join would only reach the intermission and
            // fail there, so the series is cut now. Recorded as its own event so "why did this
            // series end" has an answer in the journal.
            //
            // Cut always finishes, even when leaderOf() returns null: a tied series that is cut has
            // no winner, and falling through to the intermission would have tried to start a round
            // for a player who is not there.
            session.seriesEndReason = "CUT";
            journal(session).append("SERIES_CUT", "reason", "participant_offline");
            finishSeries(session, leaderOf(session));
            return;
        }
        if (seriesWinner == null) {
            beginIntermission(session, region, arenaWorld);
            return;
        }
        finishSeries(session, seriesWinner);
    }

    /**
     * Ends the series: restore everybody, put the terrain back, release the lease, archive.
     *
     * <p>Separate from {@link #settle} because shutdown needs it without the round bookkeeping — the
     * server is going down, so there is no next round to prepare for and the snapshots have to be
     * applied while the players are still connected rather than left as a debt for the next boot.</p>
     */
    private void finishSeries(DuelSession session, String winnerUuid) {
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
                "winner", winnerUuid, "score", scoreLine(session),
                "rounds", session.round, "bestOf", session.bestOf);

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

        // Hidden before ARCHIVED so the client never draws a scoreboard for a session that no longer
        // exists on the server — the payload is the only thing it has to go on.
        broadcastHud(session, false);
        broadcastBounds(session, false);
        transition(session, SessionStage.ARCHIVED);
        inviteByTarget.values().removeIf(session.id::equals);
        announceSeriesResult(session, winnerUuid);
    }

    /**
     * The winner of the series, or null while it is still live.
     *
     * <p>With an odd {@code bestOf} and a first-to-{@code bestOf/2+1} rule, a full series cannot be
     * drawn — one side must have reached the target before the last round is played. The
     * {@code round >= bestOf} branch is therefore unreachable by construction, and exists only so a
     * hand-edited session file cannot hang a match forever.</p>
     */
    private static String seriesWinner(DuelSession session) {
        int need = winsNeeded(session);
        for (Map.Entry<String, Integer> e : session.score.entrySet()) {
            if (e.getValue() >= need) {
                return e.getKey();
            }
        }
        if (session.round >= session.bestOf) {
            return leaderOf(session);
        }
        return null;
    }

    /**
     * The current leader, or null when the lead is shared.
     *
     * <p>Null on a tie matters: a cut series with 2:2 has no winner, and picking the first
     * participant — which the list order made look like a lead — announced "Dev1 获胜（2:2）".</p>
     */
    private static String leaderOf(DuelSession session) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean tied = false;
        for (Participant p : session.participants) {
            int wins = session.score.getOrDefault(p.uuid, 0);
            if (wins > bestScore) {
                bestScore = wins;
                best = p.uuid;
                tied = false;
            } else if (wins == bestScore) {
                tied = true;
            }
        }
        return tied ? null : best;
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
    private void beginIntermission(DuelSession session, ArenaRegion region, ServerWorld world) {
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
        String line = "第 " + session.round + " 局结束，比分 " + scoreLine(session)
                + "。下一局 " + seconds + " 秒后开始。";
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                player.sendMessage(Text.literal(line).formatted(Formatting.GOLD), false);
            }
        }
    }

    private void announceSeriesResult(DuelSession session, String winnerUuid) {
        Participant winner = session.participant(winnerUuid);
        // Both 认输 and 掉线 can end a series with the winner trailing on rounds. Saying so in the
        // same sentence is the difference between "the rules worked" and "the score is broken".
        String because = switch (session.seriesEndReason == null ? "" : session.seriesEndReason) {
            case "SURRENDER" -> "对方认输，";
            case "CUT" -> "对手掉线，";
            default -> "";
        };
        String line = "决斗结束：" + (winner == null ? "无人获胜" : winner.name + " 获胜")
                + "（" + because + "比分 " + scoreLine(session) + "）。";
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                player.sendMessage(Text.literal(line)
                        .formatted(p.uuid.equals(winnerUuid) ? Formatting.GREEN : Formatting.GRAY), false);
            }
        }
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
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                PvPocketNetworking.sendBounds(player, payload);
            }
        }
        for (ServerPlayerEntity watcher : spectators.watchersOf(session.id)) {
            PvPocketNetworking.sendBounds(watcher, payload);
        }
        // One line per round boundary, mirroring the "Rules panel ->" line. If the curtain ever fails
        // to appear, the first question is whether the client was told about the plot at all, and
        // this answers it without a debugger.
        PvPocket.LOGGER.info("Arena bounds {} for session {} ({}, mode {})",
                active ? "pushed" : "cleared", session.id,
                region == null ? "no plot" : (region.sizeX + "x" + region.sizeZ), mode);
    }

    /**
     * The outline packet for one plot.
     *
     * <p>Uses the fight rectangle, not the plot box. The two differ only on maps that declare an
     * inset, and on those the difference is the whole point: the colosseum's box is its outer wall,
     * so a curtain on the box would be drawn behind ten rows of seating.</p>
     */
    private static ArenaBoundsPayload boundsOf(ArenaRegion region, int mode) {
        // The preparation wall is a real thing with a real height, so the client is told that
        // height and starts at the wall's own bottom block. A rule boundary is an abstraction and
        // gets a nominal height the client is free to grow past the camera.
        boolean solid = mode == ArenaBoundsPayload.MODE_PREPARE;
        double floor = solid ? ArenaRegion.PLATFORM_Y : ArenaRegion.SURFACE_Y;
        double height = solid
                ? ArenaManager.prepareWallTop(region) - ArenaRegion.PLATFORM_Y
                : BOUNDARY_CURTAIN_HEIGHT;
        return new ArenaBoundsPayload(true,
                region.pitMinX(), region.pitMinZ(),
                region.pitMaxX() + 1.0D, region.pitMaxZ() + 1.0D,
                floor, height, mode);
    }

    private void broadcastHud(DuelSession session, boolean active) {
        MatchHudPayload payload = active ? buildHud(session) : MatchHudPayload.hidden();
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                PvPocketNetworking.sendHud(player, payload);
            }
        }
        for (ServerPlayerEntity watcher : spectators.watchersOf(session.id)) {
            PvPocketNetworking.sendHud(watcher, payload);
        }
    }

    /**
     * Flattens the session into the HUD payload.
     *
     * <p>Players are grouped by {@link Participant#teamIndex()} rather than assuming two of them, so
     * a 2v2 or a 4-way match renders without the HUD needing to know how many sides exist — it just
     * draws whatever rows it was handed on the left and on the right.</p>
     */
    private static MatchHudPayload buildHud(DuelSession session) {
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        int leftScore = 0;
        int rightScore = 0;
        for (Participant p : session.participants) {
            boolean leftSide = p.teamIndex() % 2 == 0;
            StringBuilder row = leftSide ? left : right;
            if (row.length() > 0) {
                row.append(',');
            }
            row.append(p.name).append('|').append(p.uuid);
            int wins = session.score.getOrDefault(p.uuid, 0);
            if (leftSide) {
                leftScore += wins;
            } else {
                rightScore += wins;
            }
        }
        return new MatchHudPayload(true, session.round, session.bestOf, winsNeeded(session),
                leftScore, rightScore, left.toString(), right.toString());
    }

    /**
     * Re-sends the HUD to one player, or tells them to hide it.
     *
     * <p>Needed on join: the payload is pushed only when the score changes, so a client that
     * reconnected mid-match would otherwise stare at an empty arena until the next round.</p>
     */
    private void pushHudTo(ServerPlayerEntity player) {
        String id = sessionByPlayer.get(player.getUuid());
        DuelSession session = id == null ? null : sessions.get(id);
        if (session == null || session.stage.isTerminal()) {
            PvPocketNetworking.sendHud(player, MatchHudPayload.hidden());
            PvPocketNetworking.sendBounds(player, ArenaBoundsPayload.hidden());
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
    }

    /**
     * Which colour the curtain should be right now.
     *
     * <p>During the preparation window the answer is neither rule: the wall is a real barrier ring
     * and the client paints it white. Deriving this in one place keeps the three call sites —
     * round start, round activation, reconnect — from drifting apart, which is how a client ends up
     * drawing a red boundary over a white wall.</p>
     */
    private static int boundsMode(DuelSession session) {
        if (session.stage == SessionStage.ROUND_READY) {
            return ArenaBoundsPayload.MODE_PREPARE;
        }
        return DuelRules.decode(session.rules).get("e.boundary");
    }

    /**
     * Gives a freshly joined spectator the same overlay the participants have.
     *
     * <p>Not routed through {@link #pushHudTo}: that method looks the player up as a participant and
     * would answer "no session" for a spectator. Spectating is a different relationship to a match,
     * so it gets its own push rather than a special case inside the participant path.</p>
     */
    void pushSpectatorView(ServerPlayerEntity player, DuelSession session) {
        PvPocketNetworking.sendHud(player, buildHud(session));
        ArenaRegion region = ArenaManager.region(session.id);
        PvPocketNetworking.sendBounds(player, region == null
                ? ArenaBoundsPayload.hidden()
                : boundsOf(region, boundsMode(session)));
    }

    /**
     * Takes back everything {@link #pushSpectatorView} handed out.
     *
     * <p>Both of those payloads are borrowed state: the score bar and the boundary curtain belong to
     * a match the spectator is no longer part of. Nothing else will ever clear them — the match's own
     * broadcasts only reach its participants and its current watchers — so a spectator who leaves
     * mid-series would carry a live scoreboard over their survival world until the match ended.</p>
     */
    void clearSpectatorView(ServerPlayerEntity player) {
        PvPocketNetworking.sendHud(player, MatchHudPayload.hidden());
        PvPocketNetworking.sendBounds(player, ArenaBoundsPayload.hidden());
    }

    /**
     * Puts a player back into the state a fresh round starts from.
     *
     * <p>This is the "items return to their initial state" half of the per-round reset that is not
     * covered by re-granting the loadout: health, hunger, effects and fire all survive a round and
     * would otherwise carry a poisoned or half-dead player into the next one.</p>
     */
    private static void resetRoundState(ServerPlayerEntity player, int maxHealth) {
        player.clearStatusEffects();
        player.extinguish();
        player.setFireTicks(0);
        player.setVelocity(0, 0, 0);
        player.fallDistance = 0;
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5.0F);
        player.getHungerManager().setExhaustion(0.0F);
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
        ServerPlayerEntity player = online(p);
        if (player == null) {
            pendingRestore.put(uuid, session.id);
            journal(session).append("RESTORE_DEFERRED", "player", p.name);
            return false;
        }
        // Cleared above, so this is the unconditional push: an offline-then-online player would
        // otherwise keep a stale legacy flag and a phantom block penalty on their client.
        PvPocketNetworking.sendState(player);
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
            sessionByPlayer.remove(player.getUuid());
            journal(session).append("RESTORE_COMPLETED", "player", p.name, "verified", verified);
            if (!verified) {
                journal(session).append("ANOMALY", "player", p.name,
                        "detail", "state digest mismatch after restore");
                PvPocket.LOGGER.warn("Restore digest mismatch for {} in session {}", p.name, session.id);
            }
            player.sendMessage(Text.literal("决斗结束，生存状态已恢复。").formatted(Formatting.GREEN), false);
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

    ServerPlayerEntity online(Participant p) {
        return server.getPlayerManager().getPlayer(UUID.fromString(p.uuid));
    }

    private static void fail(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.RED), false);
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String scoreLine(DuelSession session) {
        if (session.score.isEmpty()) {
            return "0:0";
        }
        StringBuilder sb = new StringBuilder();
        for (Participant p : session.participants) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(session.score.getOrDefault(p.uuid, 0));
        }
        return sb.toString();
    }
}
