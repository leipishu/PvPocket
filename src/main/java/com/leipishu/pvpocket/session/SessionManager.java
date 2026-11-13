package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.compat.PvCompat;

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
 * <p>Invariants: the journal is written before the state change it describes, and player state is
 * only touched after the snapshot is on disk.
 */
public final class SessionManager {

    private static SessionManager instance;

    /** How often stray mobs are swept out of leased plots, in server ticks. */
    private static final int SWEEP_INTERVAL = 60;

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
            }
        });
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
     * <p>Runs from {@code tick()}. A round that cannot start because a participant logged out voids
     * the series instead of throwing out of the tick loop.
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
                // startRound bumps the round counter before it can fail; a cut counts no round.
                session.round = before;
                if (session.participants.stream().anyMatch(p -> online(p) == null)) {
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
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        if (me == null) {
            return;
        }
        // Leaving is an elimination, not a verdict on the round: the last member of a side ends it.
        deferred.add(() -> eliminate(session, me, "DISCONNECT"));
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
     * {@code f.allowExplosives}, asked from {@code Explosion#collectBlocksAndDamageEntities} and
     * {@code Explosion#affectWorld}.
     *
     * <p>The server looks the plot up by position, since an explosion has no owner. The client has
     * no sessions and uses the flag its own payload carried.
     *
     * <p>{@code affectWorld} destroys blocks on whichever side runs it, so a client that was not
     * told would carve holes the server never confirms.
     *
     * @return false when the explosion must not run
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
     * Last tick each player was told why their click did nothing. Server thread only.
     */
    private static final Map<UUID, Integer> useWarnAt = new HashMap<>();

    /**
     * Tells a player why their click did nothing.
     *
     * <p>Only the server speaks, and the message is throttled because a held button retries every
     * few ticks.
     */
    public static void reportUseDenial(Player player, UsePolicy.Denial denial, ItemStack stack) {
        if (denial == UsePolicy.Denial.NONE || !(player instanceof ServerPlayer server)) {
            return;
        }
        MutableComponent message = switch (denial) {
            case PLACE -> PvLang.t("pvpocket.msg.use.place");
            // Named, so the player knows which of the five filter switches was tripped.
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
        CombatState.setUseGate(player.getUUID(), gate, book.blockBans());
    }

    /**
     * The round clock for {@code a.roundLimit}.
     *
     * <p>Starts when the round starts, not when the preparation countdown does. Runs only when
     * {@code a.winConditions} also accepts a timeout win.
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
        // Marked here and settled below, so a crash between the two still leaves a journalled
        // outcome.
        me.eliminated = true;
        recordDeath(session, me, player);
        int winner = lastTeamStanding(session);
        session.pendingWinner = me.uuid;
        session.pendingReason = "DEATH";
        session.pendingWinnerTeam = winner;
        journal(session).append("ROUND_DECIDED", "reason", "DEATH",
                "player", me.name,
                "winnerTeam", winner,
                "alive", survivorsOf(session));
        persist(session);

        // A mid-series round settles immediately, without waiting for the respawn. Only the
        // deciding round waits, because finishSeries restores the snapshot onto a live entity.
        if (winner >= 0 && !decidesSeries(session, winner)) {
            deferred.add(() -> settle(session, "DEATH", winner));
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
        // A respawn replaces the server-side entity, so re-send the HUD state; nothing else will
        // push it before the next boundary.
        pushHudTo(player);
        String sessionId = sessionByPlayer.get(player.getUUID());
        if (sessionId == null) {
            return;
        }
        DuelSession session = sessions.get(sessionId);
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
            deferred.add(() -> settle(session, reason, team));
            return;
        }
        deferred.add(() -> watchRestOfRound(session, player));
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
        ArenaRegion region = ArenaManager.region(session.id);
        if (me == null || region == null || !me.eliminated) {
            return;
        }
        Sandbox.changeGameMode(player, GameType.SPECTATOR);
        ArenaManager.teleportToSpawn(player, region, me.teamIndex(), me.member,
                Math.max(1, session.teamMembers(me.teamIndex()).size()));
        // The round is still live, so the "decided but unapplied" record is stale.
        session.pendingWinner = null;
        session.pendingReason = null;
        persist(session);
        player.displayClientMessage(PvLang.t("pvpocket.msg.round.eliminated")
                .withStyle(ChatFormatting.GRAY), false);
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
        if (region == null) {
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

    public void tick() {
        if (!deferred.isEmpty()) {
            List<Runnable> copy = new ArrayList<>(deferred);
            deferred.clear();
            copy.forEach(Runnable::run);
        }
        flushPendingRestores();
        startDueRounds();
        // After startDueRounds: a round that just started has dropped its roundReadyAt entry.
        tickIntermissionCountdown();
        tickPrepare();
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
                    // Through eliminate(): leaving the plot removes one player, not the side.
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

        sessions.put(session.id, session);
        journal(session).append("SESSION_DRAFTED", "proposer", me.name);
        persist(session);
        openPanel(proposer, session, RulesOpenPayload.ROLE_DRAFT);
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
                    p.pendingLoadout = previous.pendingLoadout;
                    p.pendingLoadoutDigest = previous.pendingLoadoutDigest;
                    p.acceptedLoadoutRevision = previous.acceptedLoadoutRevision;
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
        // The tally is "how many have pressed Accept", not "how many agreed to the loadout".
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
                        // Recomputed on every open: a map frees up when the other duel ends.
                        ArenaManager.busyMapMask(session.id),
                        // Only the draft edits the roster, so only it gets the candidate list.
                        role == RulesOpenPayload.ROLE_DRAFT
                                ? candidates(player)
                                : "")));
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
     * <p>Silent about players who have not answered yet; that is {@link #allResponded}'s job.
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
     * <p>A change drops the session back to {@link SessionStage#DRAFT} and gives the pen back to
     * whoever changed it: their panel reopens as proposer, everyone else's as reviewer.
     *
     * <p>{@code before} is the combined fingerprint from just before the edit. A save that produced
     * the same fingerprint changed nothing, so the session is left alone.
     */
    private void noteLoadoutChanged(DuelSession session, Participant changed, String before) {
        String after = combinedLoadoutDigest(session);
        if (before != null && before.equals(after)) {
            persist(session);
            return;
        }
        if (session.stage == SessionStage.DRAFT) {
            // Nothing has been sent yet, so there is no consent to invalidate.
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
        // A draft belongs to one player and has no reviewer. Past the draft the "proposer" is
        // whoever authored the revision on the table, not whoever is being waited on.
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
     * <p>Every branch re-derives permission from the session record: only the player the session is
     * waiting on may act, and only the author of the current revision may replace it.
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
            // Past the draft, walking away leaves everybody waiting, so it is recorded as a
            // refusal. The author of the revision on the table is left alone.
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
                // A draft rebuilds the participant list on every send, so an old refusal is gone.
                fail(player, "pvpocket.msg.rules.none_pending");
                return;
            }
            DuelRules proposed = DuelRules.decode(encoded);
            List<String> changed = DuelRules.decode(session.rules).differences(proposed);
            // In DRAFT this is "send it"; in NEGOTIATING the roster is frozen and this is "ask
            // again with the new terms".
            if (session.stage == SessionStage.DRAFT) {
                // Validated against the rules just sent, not against the defaults the draft
                // opened with.
                if (!submitRoster(session, player, encodedRoster, proposed)) {
                    // No reopen: the client keeps its draft panel up, so the cards stay put.
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
            // Proposing is also agreeing: the proposer is looking at this exact loadout set.
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
            // A re-proposal invalidates every earlier yes, so the proposer's panel is re-sent.
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
            // Accepting agrees to the rules and to everybody's loadout as they stand. The rules an
            // ACCEPT carries are not applied, so a client with unsent edits has to send PROPOSE.
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
            // Consent is checked before the loadout, so an unanswered invite is not reported as a
            // loadout disagreement.
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
                // Reachable only if a loadout changed after everybody had already said yes.
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
     * Everybody has answered: accepted the current revision, or declined.
     *
     * <p>Not "everybody accepted". A decline is an answer, and a negotiation cannot wait for a
     * player who has already said no.
     */
    private static boolean allResponded(DuelSession session) {
        for (Participant p : session.participants) {
            if (!p.responded(session.rulesRevision)) {
                return false;
            }
        }
        return true;
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
        // A reset re-stamps one map per tick, and a lease taken mid-rebuild would race the stamp.
        if (ArenaManager.warmUpPending()) {
            fail(accepter, "pvpocket.msg.rules.arena_rebuilding");
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
        // Checked here as well as in the panel, which may have been open for a minute while
        // somebody else took the map.
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
        if (session.stage != SessionStage.NEGOTIATING) {
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
     * Forfeits the whole series, not the round.
     *
     * <p>Surrender is the way out of a match, not a way to drop one round. The score is left
     * untouched: nobody won this round, and the series ends with the opponent as its winner.
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
        if (!session.rulebook().allowSurrender()
                || !session.rulebook().winsBy(Rulebook.WinCondition.SURRENDER)) {
            fail(player, "pvpocket.msg.duel.surrender_disabled");
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        // A surrender gives up for the whole side, so the series goes to the best other side.
        forfeit(session, player, leaderTeam(session, me == null ? -1 : me.teamIndex()));
    }

    /**
     * Ends the series by forfeit: the surrendering side loses, {@code winnerTeam} takes the series.
     *
     * <p>Works from {@code ROUND_READY} as well as {@code ROUND_ACTIVE}: a player who wants out
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
            if (session.stage == SessionStage.DRAFT || session.stage == SessionStage.NEGOTIATING) {
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
            // d.allowSwapBetweenRounds: the result goes to a pending slot, not the live loadout.
            DuelSession series = seriesSessionOf(player.getUUID());
            if (series != null) {
                if (series.rulebook().allowSwapBetweenRounds()) {
                    openSeriesEditor(player, series);
                    return;
                }
                // The rule is off, so the personal loadout opens and the refusal is said aloud.
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
        // The panel draws the match's one set, whoever opened it.
        String encoded = sharedLoadout(session).encode();
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
            // d.allowSwapBetweenRounds: the loadout can change but cannot land on its own.
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
        // One set per match: whoever saves writes the set everybody fights with.
        adoptSharedLoadout(session, loadout);
        // The book is written too, so the saved set means the same thing wherever the panel opened.
        loadouts.put(player.getUUID(), loadout);
        journal(session).append("LOADOUT_SAVED",
                "player", p.name,
                "digest", p.loadoutDigest,
                "items", loadout.size(),
                "summary", loadout.name,
                "scope", "match");
        persist(session);
        noteLoadoutChanged(session, p, before);
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

    /**
     * A loadout change made after the series has started, held until everybody agrees to it.
     *
     * <p>The loadout is part of what the sides agreed to before the first round, so a change is
     * re-agreed. {@code d.allowSwapBetweenRounds} decides whether a change is offered at all.
     *
     * <p>Stored as {@code pendingLoadout} rather than written over {@code loadout}, so the old set
     * survives a refusal. It lands in an inventory at the next round start.
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
            // Nothing changed, so the revision is not bumped.
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

    /** Command {@code /pvpocket loadout accept}: one player's yes to the change on the table. */
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

    /** Command {@code /pvpocket loadout decline}: the change is dropped and the old set stands. */
    public void declineLoadoutSwap(ServerPlayer player) {
        DuelSession session = swapSessionOf(player);
        if (session == null) {
            fail(player, "pvpocket.msg.loadout.swap_none");
            return;
        }
        Participant me = session.participant(player.getStringUUID());
        // The table is reset rather than the revision rewound: marking everyone as agreed to the
        // current revision is the same state as "no change pending".
        clearLoadoutSwaps(session);
        journal(session).append("LOADOUT_SWAP_DECLINED", "player", me.name,
                "revision", session.loadoutRevision);
        persist(session);
        broadcastParticipants(session, PvLang.t("pvpocket.msg.loadout.swap_declined", me.name));
    }

    /**
     * The series this player is fighting, in the two stages where a loadout change means anything.
     *
     * <p>{@code ROUND_READY} is the intermission and {@code ROUND_ACTIVE} the round itself. A
     * change is accepted in either, since it only reaches an inventory at a round start.
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
        // The pending change if there is one, otherwise the match's set.
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
     * <p>Not {@code findOpenSession}, which only knows about drafts and negotiations.
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
            // The change is to the match's set, not one copy, so both sides keep the same kit.
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
        // Says "from the next round": the inventory is re-granted at every round start.
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
                String before = combinedLoadoutDigest(session);
                // The same one-set-per-match write as onLoadoutSubmit.
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
            // A running series keeps its set, so this save cannot reach the held items.
            mirrored = PvLang.t("pvpocket.msg.loadout.series_locked");
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
        String before = combinedLoadoutDigest(session);
        // Everyone, for the same reason a save writes everyone: the match has one set.
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
        // Elimination is per round: every side starts each round with everybody standing.
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
            ServerPlayer player = online(p);
            if (player == null) {
                // Somebody left during the preparation window; they never fought, so cut it.
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
                // The boundary rule in force, spelled out; the digest cannot be read back.
                "boundary", rules.get("e.boundary") == 0 ? "push_back" : "countdown",
                // The health cap, the one rule whose effect is a number on the HUD.
                "maxHealth", rules.get("c.maxHealth"),
                // The round clock only runs when a timeout is also an accepted win condition.
                "roundLimit", session.rulebook().roundLimitSeconds() == 0
                        || !session.rulebook().winsBy(Rulebook.WinCondition.TIMEOUT)
                        ? "off" : session.rulebook().roundLimitSeconds() + "s",
                "region", region == null ? "-" : region.id,
                "teams", session.teamCount,
                "shape", shapeOf(session),
                // Every pad the match used, so the journal can say where each player spawned.
                "spawns", region == null ? "-" : spawnsOf(region));
        roundStartedAt.put(session.id, System.currentTimeMillis());
        transition(session, SessionStage.ROUND_ACTIVE);
        broadcastHud(session, true);
        // Re-pushed, so the client drops the white preparation curtain for the open arena.
        broadcastBounds(session, true);
        for (Participant p : session.participants) {
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
        world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
        world.setWeatherParameters(12_000, 0, false, false);
    }

    /**
     * Takes one player out of the round.
     *
     * <p>The round ends when the last member of a side is gone, not when the first player dies. A
     * 1v1 ends on the first death and a 3v3 continues while two team-mates stand.
     *
     * <p>Asks {@code a.winConditions} for the kill condition rather than assuming it. If the bit
     * arrives cleared the round still ends, and the anomaly is journalled.
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
        prepareDeadlines.remove(session.id);
        countdownPushed.remove(session.id);

        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);

        int seriesWinner = seriesWinnerTeam(session);
        boolean decided = seriesDecided(session);
        if (!decided && session.participants.stream()
                .anyMatch(p -> !p.rejected && online(p) == null)) {
            // Somebody left, so the series is cut now rather than failing in the intermission.
            // Cut always finishes, even with no leader: a cut tied series has no winner.
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
     * <p>Separate from {@link #settle} because shutdown needs it without the round bookkeeping, and
     * the snapshots have to be applied while the players are still connected.
     */
    private void finishSeries(DuelSession session, int winnerTeam) {
        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);

        // Defensive: a series can end during the preparation window, and a leftover barrier ring
        // would be an invisible cage.
        ArenaManager.removePrepareWall(arenaWorld, region);
        // Ejected before the snapshots are restored, because restoring teleports the participants.
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

        // Written while the region is still leased, since the map id is read off the plot.
        recordSeries(session, winnerTeam);

        transition(session, SessionStage.RESTORING);

        for (Participant p : session.participants) {
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
     * {@code -1} when two or more sides are absent, since there is no forfeit to attribute.
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
     * <p>{@code exclude} exists for surrender: a side that quits cannot be its own winner, so the
     * tie is judged among the sides that stayed.
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
        countdownPushed.remove(session.id);
        transition(session, SessionStage.ROUND_READY);
        // Nobody walks during the intermission: spectator mode, parked above the spawn. Dead
        // players are skipped and parked by onRespawn once they are alive.
        for (Participant p : session.participants) {
            ServerPlayer player = online(p);
            if (player == null || p.rejected || !player.isAlive()) {
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
     * <p>Two flat strings rather than one field per side, since the payload is hand-written around
     * {@code PacketCodec.tuple}'s six-component limit. {@code scores} is {@code "2,1"} and
     * {@code teams} is {@code "Dev1|uuid,Dev2|uuid;Dev3|uuid"}, with {@code ,} between members of a
     * side, {@code ;} between sides and {@code |} between a name and its uuid.
     */
    private MatchHudPayload buildHud(DuelSession session) {
        int teams = Math.max(1, session.teamCount);
        StringBuilder scores = new StringBuilder();
        StringBuilder roster = new StringBuilder();
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
        return new MatchHudPayload(true, session.round, session.maxRounds, session.winsNeeded,
                scores.toString(), roster.toString(), session.rulebook().showOpponentHealth(),
                intermissionSecondsLeft(session));
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
     * Re-sends the HUD to one player, or tells them to hide it.
     *
     * <p>Needed on join: the payload is pushed only when the score changes.
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
        countdownPushed.remove(session.id);
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
            player.displayClientMessage(PvLang.t("pvpocket.msg.series.restored")
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
