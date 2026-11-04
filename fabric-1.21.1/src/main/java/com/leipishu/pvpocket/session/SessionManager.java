package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.ArenaRegion;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutBuilder;
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
    private final List<Runnable> deferred = new ArrayList<>();
    /** Session id -> wall-clock start of the current round, for the opening grace window. */
    private final Map<String, Long> roundStartedAt = new HashMap<>();
    private int sweepTimer;

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

        // Both sides start from the same editable preset rather than an empty loadout. An empty one
        // would make the first duel an unarmed fistfight, which reads as a bug rather than a choice.
        Loadout preset = Loadout.defaultDuel();
        for (Participant p : session.participants) {
            p.loadout = preset.encode();
            p.loadoutDigest = preset.digest();
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
                otherName(session, player.getUuidAsString())));
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
            startAccepted(session, player);
        }
    }

    /** Freeze, snapshot, teleport, start. Shared by the panel path and the /accept shortcut. */
    private void startAccepted(DuelSession session, ServerPlayerEntity accepter) {
        if (!ArenaManager.isReady(server)) {
            fail(accepter, "决斗维度未加载。");
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
        session.bestOf = switch (rules.get("a.series")) {
            case 0 -> 1;
            case 2 -> 5;
            case 3 -> 7;
            default -> 3;
        };
        session.rules = rules.encode();
        session.rulesDigest = rules.digest();
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
     * The loadout this player would enter with right now.
     *
     * <p>Falls back to the default preset rather than returning null so that the diagnostic command
     * and a first duel show the same thing — a null here would make "no loadout yet" look like
     * "loadout support is broken".</p>
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
        return Loadout.defaultDuel();
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
            if (!ArenaManager.teleportToSpawn(player, region, p.spawnIndex)) {
                throw new IllegalStateException("arena world unavailable");
            }
            player.changeGameMode(GameMode.SURVIVAL);
            // The snapshot restores attributes wholesale, so a modified base value here is rolled
            // back by the same mechanism that restores inventory — no separate undo bookkeeping.
            var health = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(maxHealth);
                player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
            }
            CombatState.setMode(player.getUuid(), mode);
            // Loadout is granted here, not at freeze(): the snapshot must contain the survival
            // inventory, so anything written before it would be captured as if the player owned it.
            Loadout loadout = p.loadout == null ? Loadout.defaultDuel() : Loadout.decode(p.loadout);
            int granted = LoadoutBuilder.grant(player, loadout, p.spawnIndex);
            p.loadoutApplied = true;
            journal(session).append("LOADOUT_APPLIED", "player", p.name,
                    "digest", p.loadoutDigest, "items", granted);
            // The client mirrors the rule set to predict movement and to hide the attack cooldown
            // indicator, so it has to be told which mode this round runs before the first hit.
            PvPocketNetworking.sendState(player);
            player.sendMessage(Text.literal("决斗开始（" + modeLabel(mode)
                            + "，" + rules.summary() + "）。输入 /pvpocket surrender 认输。")
                    .formatted(Formatting.GREEN), false);
        }
        journal(session).append("ROUND_STARTED", "round", session.round,
                "digest", session.rulesDigest,
                "region", region.id,
                "spawns", region.spawnX(0) + "," + ArenaRegion.SURFACE_Y + "," + region.spawnZ()
                        + " | " + region.spawnX(1) + "," + ArenaRegion.SURFACE_Y + "," + region.spawnZ());
        roundStartedAt.put(session.id, System.currentTimeMillis());
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

        ArenaRegion region = ArenaManager.region(session.id);
        var arenaWorld = ArenaManager.world(server);
        int cleared = ArenaManager.clearDroppedItems(arenaWorld, region);
        journal(session).append("ARENA_CLEARED", "removed", cleared);

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
        roundStartedAt.remove(session.id);

        transition(session, SessionStage.ARCHIVED);
        inviteByTarget.values().removeIf(session.id::equals);
    }

    /** Rolls a session back after a failed start. Restores anyone already frozen, then voids it. */
    private void abort(DuelSession session, String reason) {
        session.voidReason = reason;
        closePanels(session);
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
