package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.ArenaRegion;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutBook;
import com.leipishu.pvpocket.loadout.LoadoutBuilder;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.network.PvPocketNetworking;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.RulesSubmitPayload;
import com.leipishu.pvpocket.rules.DuelRules;
import com.leipishu.pvpocket.snapshot.PlayerSnapshot;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import com.leipishu.pvpocket.storage.SessionJournal;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * Lower bound on the opening grace window, in milliseconds.
     *
     * <p>Ten seconds is not a balance number: it is roughly the worst case measured for a dev client
     * to finish streaming a dimension it has never loaded. Anything shorter turns a slow disk into a
     * lost round.</p>
     */
    private static final long BOUNDARY_GRACE_FLOOR_MS = 10_000L;

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
    private int sweepTimer;

    private SessionManager(MinecraftServer server) {
        this.server = server;
        this.storage = new PvPocketStorage(server);
        this.loadouts = new LoadoutBook(storage);
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

        // Deliberately no restore attempt here. ServerPlayConnectionEvents.JOIN fires before the
        // player is registered with the PlayerManager, so a lookup by UUID still misses them and the
        // restore would be deferred forever. Deferred restores are drained from tick() instead,
        // which does not depend on event ordering and also covers every other path that can leave a
        // debt behind.
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
            try {
                startRound(session);
            } catch (RuntimeException e) {
                PvPocket.LOGGER.error("Failed to start round {} of {}",
                        session.round + 1, session.id, e);
                abort(session, "ROUND_START_FAILED");
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

    public void tick() {
        if (!deferred.isEmpty()) {
            List<Runnable> copy = new ArrayList<>(deferred);
            deferred.clear();
            copy.forEach(Runnable::run);
        }
        flushPendingRestores();
        startDueRounds();
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
                if (player != null && !ArenaManager.isInside(player, region)) {
                    // Logged with coordinates on purpose: "left the arena" has two very different
                    // causes (a player walked out, or a teleport did not stick) and the position is
                    // the only thing that tells them apart after the fact.
                    journal(session).append("BOUNDARY_EXIT",
                            "player", p.name,
                            "region", region.id,
                            "pos", String.format("%.2f,%.2f,%.2f",
                                    player.getX(), player.getY(), player.getZ()),
                            "world", player.getWorld().getRegistryKey().getValue().toString());
                    // During the opening grace window leaving is never a loss, it is a reset. A
                    // client that is still streaming the new dimension can report a position from
                    // before the teleport for several seconds after the server has already moved
                    // the player; judging that as "walked out" would end the duel before either
                    // side could see the arena. Snapping back is self-correcting and matches what
                    // the countdown rule already promises.
                    if (inOpeningGrace(session) || boundaryIsPushBack(session)) {
                        ArenaManager.teleportToSpawn(player, region, p.spawnIndex);
                        journal(session).append("BOUNDARY_PUSHED_BACK", "player", p.name,
                                "grace", inOpeningGrace(session));
                        continue;
                    }
                    Participant opponent = session.opponentOf(p.uuid);
                    deferred.add(() -> settle(session, "OUT_OF_BOUNDS", opponent == null ? null : opponent.uuid));
                    break;
                }
            }
        }
    }

    /**
     * True while the round is still inside its opening grace window.
     *
     * <p>Length is the countdown rule, floored at {@link #BOUNDARY_GRACE_FLOOR_MS}. The floor exists
     * because the countdown is a design value measured in seconds while the problem it protects
     * against — a client finishing its chunk download for a dimension it has never visited — is
     * measured in whatever the machine happens to take. Three seconds of countdown and no grace is
     * a duel that can be lost during the loading screen.</p>
     */
    private boolean inOpeningGrace(DuelSession session) {
        long startedAt = roundStartedAt.getOrDefault(session.id, 0L);
        if (startedAt == 0L) {
            return false;
        }
        long countdown = DuelRules.decode(session.rules).get("a.countdown") * 1000L;
        return System.currentTimeMillis() - startedAt < Math.max(BOUNDARY_GRACE_FLOOR_MS, countdown);
    }

    private static boolean boundaryIsPushBack(DuelSession session) {
        return DuelRules.decode(session.rules).get("e.boundary") == 0;
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
                otherName(session, player.getUuidAsString()),
                loadoutSummary(session)));
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
        ArenaRegion region = ArenaManager.allocate(server, session.id,
                rules.get("e.sizeX"), rules.get("e.sizeY"), rules.get("e.sizeZ"),
                rules.flag("f.protectTerrain"), rules.flag("f.allowBreakOwn"));
        if (region == null) {
            fail(accepter, "场地已满，暂时无法开始新的决斗。");
            return;
        }
        session.arenaId = region.id;
        journal(session).append("ARENA_LEASED", "region", region.id,
                "size", region.sizeX + "x" + region.sizeY + "x" + region.sizeZ,
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

    public void surrender(ServerPlayerEntity player) {
        String sessionId = sessionByPlayer.get(player.getUuid());
        if (sessionId == null) {
            fail(player, "你不在决斗中。");
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.ROUND_ACTIVE) {
            fail(player, "当前没有进行中的回合。");
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
        settle(session, "SURRENDER", opponent == null ? null : opponent.uuid);
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
            player.sendMessage(Text.literal("第 " + session.round + " / " + session.bestOf + " 局开始（"
                            + modeLabel(mode) + "，" + rules.summary() + "）。"
                            + (session.bestOf > 1 ? "先赢 " + winsNeeded(session) + " 局者胜。" : "")
                            + "输入 /pvpocket surrender 认输。")
                    .formatted(Formatting.GREEN), false);
        }
        journal(session).append("ROUND_STARTED", "round", session.round,
                "digest", session.rulesDigest,
                "region", region.id,
                "spawns", region.spawnX(0) + "," + ArenaRegion.SURFACE_Y + "," + region.spawnZ()
                        + " | " + region.spawnX(1) + "," + ArenaRegion.SURFACE_Y + "," + region.spawnZ());
        roundStartedAt.put(session.id, System.currentTimeMillis());
        transition(session, SessionStage.ROUND_ACTIVE);
        broadcastHud(session, true);
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
        // Cleared here rather than at the end: the opening grace belongs to the round that just
        // ended, and leaving it in place would let the intermission look like a grace window.
        roundStartedAt.remove(session.id);

        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);

        String seriesWinner = seriesWinner(session);
        if (seriesWinner == null && session.participants.stream().anyMatch(p -> online(p) == null)) {
            // Somebody left. Starting a round they cannot join would only reach abort() after the
            // intermission, so the series is cut here instead, with the current leader taking it.
            // Recorded as its own event so "why did this series end" has an answer in the journal.
            journal(session).append("SERIES_CUT", "reason", "participant_offline");
            seriesWinner = leaderOf(session);
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
        String line = "决斗结束：" + (winner == null ? "无人获胜" : winner.name + " 获胜")
                + "（" + scoreLine(session) + "）。";
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                player.sendMessage(Text.literal(line)
                        .formatted(p.uuid.equals(winnerUuid) ? Formatting.GREEN : Formatting.GRAY), false);
            }
        }
    }

    // ------------------------------------------------------------------ scoreboard HUD

    private void broadcastHud(DuelSession session, boolean active) {
        MatchHudPayload payload = active ? buildHud(session) : MatchHudPayload.hidden();
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player != null) {
                PvPocketNetworking.sendHud(player, payload);
            }
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
            return;
        }
        PvPocketNetworking.sendHud(player,
                session.stage == SessionStage.DRAFT || session.stage == SessionStage.NEGOTIATING
                        || session.stage == SessionStage.FROZEN
                        ? MatchHudPayload.hidden()
                        : buildHud(session));
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
        broadcastHud(session, false);
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

    private SessionJournal journal(DuelSession session) {
        return journals.computeIfAbsent(session.id, id -> new SessionJournal(storage.journalFile(id)));
    }

    private ServerPlayerEntity online(Participant p) {
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
