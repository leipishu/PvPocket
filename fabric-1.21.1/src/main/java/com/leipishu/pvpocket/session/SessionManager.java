package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.network.PvPocketNetworking;
import com.leipishu.pvpocket.snapshot.PlayerSnapshot;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import com.leipishu.pvpocket.storage.SessionJournal;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private final MinecraftServer server;
    private final PvPocketStorage storage;
    private final Map<String, DuelSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, String> sessionByPlayer = new HashMap<>();
    private final Map<UUID, String> inviteByTarget = new HashMap<>();
    /** Player uuid -> session id whose snapshot still has to be restored. Survives restarts. */
    private final Map<UUID, String> pendingRestore = new LinkedHashMap<>();
    private final Map<String, SessionJournal> journals = new HashMap<>();
    private final List<Runnable> deferred = new ArrayList<>();

    private SessionManager(MinecraftServer server) {
        this.server = server;
        this.storage = new PvPocketStorage(server);
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
                settle(session, "SERVER_STOP", null);
            }
        }
        CombatState.clearAll();
        journals.values().forEach(SessionJournal::close);
        journals.clear();
    }

    // ------------------------------------------------------------------ player events

    public void onJoin(ServerPlayerEntity player) {
        // Unconditional, because the client mirror is a correctness requirement rather than a hint:
        // a stale legacy flag would leave the player with a permanent block penalty. One tiny packet
        // per join is a cheap price for never having to reason about how the state got there.
        PvPocketNetworking.sendState(player);

        // Deliberately no restore attempt here. ServerPlayConnectionEvents.JOIN fires before the
        // player is registered with the PlayerManager, so a lookup by UUID still misses them and the
        // restore would be deferred forever. Deferred restores are drained from tick() instead,
        // which does not depend on event ordering and also covers every other path that can leave a
        // debt behind.
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
        for (DuelSession session : sessions.values()) {
            if (session.stage != SessionStage.ROUND_ACTIVE) {
                continue;
            }
            for (Participant p : session.participants) {
                ServerPlayerEntity player = online(p);
                if (player != null && !ArenaManager.isInside(player)) {
                    Participant opponent = session.opponentOf(p.uuid);
                    deferred.add(() -> settle(session, "OUT_OF_BOUNDS", opponent == null ? null : opponent.uuid));
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ commands

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
        if (inviteByTarget.containsKey(target.getUuid())) {
            fail(challenger, target.getName().getString() + " 有待处理的邀请。");
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
        session.mode = mode.id();
        session.createdAt = System.currentTimeMillis();
        session.updatedAt = session.createdAt;
        session.participants.add(new Participant(challenger.getUuidAsString(), challenger.getName().getString(), 0));
        session.participants.add(new Participant(target.getUuidAsString(), target.getName().getString(), 1));
        sessions.put(session.id, session);
        inviteByTarget.put(target.getUuid(), session.id);

        journal(session).append("SESSION_CREATED",
                "challenger", challenger.getName().getString(),
                "target", target.getName().getString());
        transition(session, SessionStage.NEGOTIATING);

        challenger.sendMessage(Text.literal("已向 " + target.getName().getString()
                        + " 发起决斗（BO1，" + modeLabel(mode) + "）。")
                .formatted(Formatting.GRAY), false);
        target.sendMessage(Text.literal(challenger.getName().getString()
                        + " 向你发起决斗（" + modeLabel(mode) + "）。输入 /pvpocket accept 接受，/pvpocket deny 拒绝。")
                .formatted(Formatting.GOLD), false);
    }

    private static String modeLabel(CombatMode mode) {
        return mode == CombatMode.LEGACY_1_8 ? "1.8 战斗" : "现代战斗";
    }

    public void accept(ServerPlayerEntity player) {
        String sessionId = inviteByTarget.remove(player.getUuid());
        if (sessionId == null) {
            fail(player, "没有待接受的邀请。");
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null || session.stage != SessionStage.NEGOTIATING) {
            fail(player, "邀请已失效。");
            return;
        }
        if (!ArenaManager.isReady(server)) {
            fail(player, "决斗维度未加载。");
            return;
        }

        try {
            freeze(session);
            transition(session, SessionStage.ROUND_READY);
            startRound(session);
        } catch (RuntimeException e) {
            PvPocket.LOGGER.error("Failed to start session {}", session.id, e);
            abort(session, "START_FAILED");
        }
    }

    public void deny(ServerPlayerEntity player) {
        String sessionId = inviteByTarget.remove(player.getUuid());
        if (sessionId == null) {
            fail(player, "没有待处理的邀请。");
            return;
        }
        DuelSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        journal(session).append("SESSION_DENIED", "player", player.getName().getString());
        session.voidReason = "DENIED";
        transition(session, SessionStage.VOIDED);
        ServerPlayerEntity challenger = online(session.participants.get(0));
        if (challenger != null) {
            challenger.sendMessage(Text.literal("对方拒绝了决斗邀请。").formatted(Formatting.GRAY), false);
        }
        player.sendMessage(Text.literal("已拒绝决斗邀请。").formatted(Formatting.GRAY), false);
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
        Participant opponent = session.opponentOf(player.getUuidAsString());
        settle(session, "SURRENDER", opponent == null ? null : opponent.uuid);
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
                + " | 回合 " + session.round
                + " | 比分 " + scoreLine(session)
                + (opponent == null ? "" : " | 对手 " + opponent.name))
                .formatted(Formatting.AQUA), false);
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
        for (Participant p : session.participants) {
            ServerPlayerEntity player = online(p);
            if (player == null) {
                throw new IllegalStateException("participant offline: " + p.name);
            }
            if (!ArenaManager.teleportToSpawn(player, p.spawnIndex)) {
                throw new IllegalStateException("arena world unavailable");
            }
            player.changeGameMode(GameMode.SURVIVAL);
            CombatState.setMode(player.getUuid(), mode);
            // The client mirrors the rule set to predict movement and to hide the attack cooldown
            // indicator, so it has to be told which mode this round runs before the first hit.
            PvPocketNetworking.sendState(player);
            player.sendMessage(Text.literal("决斗开始（" + modeLabel(mode)
                            + "）。输入 /pvpocket surrender 认输。")
                    .formatted(Formatting.GREEN), false);
        }
        journal(session).append("ROUND_STARTED", "round", session.round);
        transition(session, SessionStage.ROUND_ACTIVE);
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

        int cleared = ArenaManager.clearDroppedItems(server);
        journal(session).append("ARENA_CLEARED", "removed", cleared);

        transition(session, SessionStage.RESTORING);

        for (Participant p : session.participants) {
            restoreParticipant(session, p);
        }
        transition(session, SessionStage.ARCHIVED);
        inviteByTarget.values().removeIf(session.id::equals);
    }

    /** Rolls a session back after a failed start. Restores anyone already frozen, then voids it. */
    private void abort(DuelSession session, String reason) {
        session.voidReason = reason;
        for (Participant p : session.participants) {
            if (p.snapshotFile != null) {
                restoreParticipant(session, p);
            }
        }
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
