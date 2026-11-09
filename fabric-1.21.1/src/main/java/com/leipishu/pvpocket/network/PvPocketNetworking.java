package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.preset.Preset;
import com.leipishu.pvpocket.preset.PresetKind;
import com.leipishu.pvpocket.preset.PresetShareRegistry;
import com.leipishu.pvpocket.session.SessionManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.SwordItem;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Payload registration plus the server-side gate for client combat input.
 *
 * <p>The client is allowed to express intent ("I am holding right-click with a sword"), never
 * state. Every payload that arrives here is re-validated against the authoritative session data
 * before it is allowed to change anything.</p>
 */
public final class PvPocketNetworking {

    private PvPocketNetworking() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(BlockingPayload.ID, BlockingPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RulesSubmitPayload.ID, RulesSubmitPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(LoadoutRequestPayload.ID, LoadoutRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(LoadoutSubmitPayload.ID, LoadoutSubmitPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SpectateRequestPayload.ID, SpectateRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PresetSharePayload.ID, PresetSharePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HomeRequestPayload.ID, HomeRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HomeActionPayload.ID, HomeActionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HomeStatePayload.ID, HomeStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CombatStatePayload.ID, CombatStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BlockingStatePayload.ID, BlockingStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RulesOpenPayload.ID, RulesOpenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RulesClosePayload.ID, RulesClosePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LoadoutOpenPayload.ID, LoadoutOpenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MatchHudPayload.ID, MatchHudPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ArenaBoundsPayload.ID, ArenaBoundsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PrepareScreensPayload.ID, PrepareScreensPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MatchListPayload.ID, MatchListPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SpectateStatePayload.ID, SpectateStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PossessPayload.ID, PossessPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PresetShareResultPayload.ID, PresetShareResultPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SpectateRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                SessionManager sessions = SessionManager.get();
                if (sessions != null) {
                    sessions.onSpectateRequest(player, payload);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(BlockingPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> applyBlocking(player, payload.blocking()));
        });

        ServerPlayNetworking.registerGlobalReceiver(RulesSubmitPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                SessionManager sessions = SessionManager.get();
                if (sessions != null) {
                    sessions.onRulesSubmit(player, payload.action(), payload.rules(),
                            payload.roster());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LoadoutRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                SessionManager sessions = SessionManager.get();
                if (sessions != null) {
                    sessions.onLoadoutRequest(player);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(LoadoutSubmitPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                SessionManager sessions = SessionManager.get();
                if (sessions != null) {
                    sessions.onLoadoutSubmit(player, payload.sessionId(), payload.loadout());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(PresetSharePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> onPresetShare(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(HomeRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                SessionManager sessions = SessionManager.get();
                if (sessions == null || !allowHomeRequest(player.getUuid())) {
                    return;
                }
                sessions.sendHomeState(player);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(HomeActionPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                SessionManager sessions = SessionManager.get();
                if (sessions != null) {
                    sessions.onHomeAction(player, payload);
                }
            });
        });
    }

    /**
     * One snapshot per player per second.
     *
     * <p>Not a rate limit for its own sake: the home screen sends a request on open, and opening it
     * by tapping a hotkey twice is a thing players do. Each answer walks the player's history file,
     * so the second request in the same second would do the same disk read to produce a payload the
     * client is about to overwrite.</p>
     */
    private static final java.util.Map<java.util.UUID, Long> HOME_REQUESTS = new java.util.HashMap<>();

    private static boolean allowHomeRequest(java.util.UUID uuid) {
        long now = System.currentTimeMillis();
        Long last = HOME_REQUESTS.get(uuid);
        if (last != null && now - last < 1000L) {
            return false;
        }
        HOME_REQUESTS.put(uuid, now);
        // Bounded: one entry per player who has ever opened the screen on this server.
        if (HOME_REQUESTS.size() > 512) {
            HOME_REQUESTS.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
        }
        return true;
    }

    /**
     * Publishes or fetches a preset share code.
     *
     * <p>Validation lives here rather than in the registry because this is the boundary where
     * untrusted input arrives: an unknown kind, a blank name and an oversized string are all
     * rejected before anything is stored. The registry stays pure storage.</p>
     */
    private static void onPresetShare(ServerPlayerEntity player, PresetSharePayload payload) {
        if (!ServerPlayNetworking.canSend(player, PresetShareResultPayload.ID)) {
            // A client without the channel would never see the answer, so there is nothing to do
            // that would not just be a log line. The mod is mandatory in practice; this is the
            // protocol-level guard, not a policy one.
            return;
        }
        if (payload.action() == PresetSharePayload.ACTION_FETCH) {
            PresetShareRegistry.Shared shared = PresetShareRegistry.fetch(payload.code());
            ServerPlayNetworking.send(player, shared == null
                    ? PresetShareResultPayload.failure(PresetShareResultPayload.STATUS_UNKNOWN_CODE)
                    : PresetShareResultPayload.fetched(shared.kind().id, shared.name(),
                            shared.encoded(), PresetShareRegistry.normalise(payload.code())));
            return;
        }
        PresetKind kind = PresetKind.byId(payload.kind());
        String name = Preset.cleanName(payload.name());
        if (kind == null
                || !Preset.isValidName(name)
                || payload.encoded().isEmpty()
                || payload.encoded().length() > PresetSharePayload.MAX_ENCODED) {
            ServerPlayNetworking.send(player,
                    PresetShareResultPayload.failure(PresetShareResultPayload.STATUS_REJECTED));
            return;
        }
        String code = PresetShareRegistry.publish(player.getUuid(), kind, name, payload.encoded());
        if (code == null) {
            ServerPlayNetworking.send(player,
                    PresetShareResultPayload.failure(PresetShareResultPayload.STATUS_FULL));
            return;
        }
        // Logged because a share code is the one thing here with no server-side record: if a player
        // says "the code does not work", this line is the only evidence that it ever existed.
        PvPocket.LOGGER.info("Preset code {} for {} ({} / {})", code,
                player.getName().getString(), kind.id, name);
        ServerPlayNetworking.send(player, PresetShareResultPayload.published(kind.id, name,
                payload.encoded(), code, (int) (PresetShareRegistry.TTL_MS / 1000L)));
    }

    /**
     * Pushes the scoreboard to one client.
     *
     * <p>Silent on purpose — no log line. Unlike a panel opening (which a developer wants to see in
     * the log when it does not appear), the HUD is sent on every round boundary to every participant,
     * and logging it would bury the interesting lines.</p>
     */
    public static void sendHud(ServerPlayerEntity player, MatchHudPayload payload) {
        if (ServerPlayNetworking.canSend(player, MatchHudPayload.ID)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Pushes the plot outline to one client, or clears it.
     *
     * <p>Silent like {@link #sendHud}: this fires at every round boundary for every participant, and
     * a log line per player per round would drown the state transitions it sits next to.</p>
     */
    public static void sendBounds(ServerPlayerEntity player, ArenaBoundsPayload payload) {
        if (ServerPlayNetworking.canSend(player, ArenaBoundsPayload.ID)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Pushes the preparation screens to one client, or clears them.
     *
     * <p>Silent for the same reason as {@link #sendBounds}: it is sent next to the bounds on every
     * round boundary, so a log line here would be noise around the transitions it accompanies.</p>
     */
    public static void sendScreens(ServerPlayerEntity player, PrepareScreensPayload payload) {
        if (ServerPlayNetworking.canSend(player, PrepareScreensPayload.ID)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Opens the rule panel on the client.
     *
     * <p>Guarded by {@code canSend}: Fabric throws if a payload is sent to a client that never
     * declared the channel. The mod is mandatory, but "mandatory" is enforced by policy, not by the
     * protocol — a vanilla client can still connect — and a rule proposal must degrade to "nothing
     * happens" rather than to a server-side exception.</p>
     */
    public static void openRules(ServerPlayerEntity player, RulesOpenPayload payload) {
        boolean canSend = ServerPlayNetworking.canSend(player, RulesOpenPayload.ID);
        PvPocket.LOGGER.info("Rules panel -> {} (canSend={}, revision={})",
                player.getName().getString(), canSend, payload.revision());
        if (canSend) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Tells a client to drop its rule panel because the negotiation it belongs to is finished.
     *
     * <p>Sent at every terminal transition of a negotiation: accepted, rejected, dismissed or
     * aborted. Without it a panel can outlive its session — the visible case is one side accepting
     * while the other side still has the panel open, which starts the round with a modal dialog in
     * front of a player who is already in the arena.</p>
     *
     * <p>The client only closes a panel whose session id matches, so a late packet can never shut a
     * newer negotiation's panel and over-sending is harmless.</p>
     */
    public static void closeRules(ServerPlayerEntity player, String sessionId) {
        if (ServerPlayNetworking.canSend(player, RulesClosePayload.ID)) {
            ServerPlayNetworking.send(player, new RulesClosePayload(sessionId));
        }
    }

    /**
     * Pushes the home screen's snapshot to one client.
     *
     * <p>Silent, unlike the panel openers: this is an answer to a request the player just made, and
     * the interesting log line for "the screen is empty" is on the client side (did the request
     * leave). A line per hotkey press would be noise.</p>
     */
    public static void sendHomeState(ServerPlayerEntity player, HomeStatePayload payload) {
        if (ServerPlayNetworking.canSend(player, HomeStatePayload.ID)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** Opens the loadout editor. Same {@code canSend} reasoning as {@link #openRules}. */    public static void openLoadout(ServerPlayerEntity player, LoadoutOpenPayload payload) {
        boolean canSend = ServerPlayNetworking.canSend(player, LoadoutOpenPayload.ID);
        PvPocket.LOGGER.info("Loadout editor -> {} (canSend={}, session={})",
                player.getName().getString(), canSend, payload.sessionId());
        if (canSend) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Opens (or refreshes) the match list panel on the client.
     *
     * <p>Logged like the other panel openers: "the panel did not appear" is the one failure mode
     * that cannot be diagnosed from the client, and the answer is always one of two things —
     * {@code canSend} was false, or the payload never left the server.</p>
     */
    public static void openMatchList(ServerPlayerEntity player, MatchListPayload payload) {
        boolean canSend = ServerPlayNetworking.canSend(player, MatchListPayload.ID);
        PvPocket.LOGGER.info("Match list -> {} (canSend={}, {} rows)",
                player.getName().getString(), canSend,
                payload.entries().isEmpty() ? 0 : payload.entries().split("\n").length);
        if (canSend) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Pushes the spectator bar state to one client, or clears it.
     *
     * <p>Silent, like the HUD: it is sent on join, on every camera switch and on every leave, and a
     * log line per press of the cycle key would be noise.</p>
     */
    public static void sendSpectate(ServerPlayerEntity player, SpectateStatePayload payload) {
        if (ServerPlayNetworking.canSend(player, SpectateStatePayload.ID)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Pushes the possessed body's hotbar and vitals to one spectator.
     *
     * <p>Only sent while the camera is attached, and only when the content actually changed — the
     * caller compares signatures first. A spectator watching someone stand still costs one string
     * compare per tick and no packets at all.</p>
     */
    public static void sendPossess(ServerPlayerEntity player, PossessPayload payload) {
        if (ServerPlayNetworking.canSend(player, PossessPayload.ID)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * Re-validates a blocking request and pushes the resulting authoritative state back down.
     *
     * <p>Only the client knows whether a mouse button is held, so the request has to be taken at
     * face value — but every reason to refuse it is checked here: wrong rule set, no sword in hand,
     * dead, spectator. A refusal is still echoed back so the client mirror cannot drift.</p>
     */
    private static void applyBlocking(ServerPlayerEntity player, boolean requested) {
        boolean accepted = requested
                && CombatState.isLegacy(player.getUuid())
                && player.getMainHandStack().getItem() instanceof SwordItem
                && !player.isDead()
                && !player.isSpectator();
        setBlocking(player, accepted);
    }

    /** Writes the blocking flag and mirrors it to the client only when it actually changed. */
    public static void setBlocking(ServerPlayerEntity player, boolean blocking) {
        boolean changed = CombatState.isBlocking(player.getUuid()) != blocking;
        CombatState.setBlocking(player.getUuid(), blocking);
        if (changed) {
            // Blocking is the one piece of combat state that exists only because the client said
            // so, so "the request never arrived" and "the request was refused" have to be
            // distinguishable without a debugger. One line per transition is cheap; a duel is not
            // a thousand blocks.
            PvPocket.LOGGER.info("Blocking {} for {}", blocking ? "on" : "off",
                    player.getName().getString());
            sendState(player);
            broadcastBlocking(player, blocking);
        }
    }

    /**
     * Tells every other client that {@code player} started or stopped blocking.
     *
     * <p>The blocker is skipped: their own client learns the flag from {@link #sendState}, and
     * sending both would only give two packets the chance to arrive in the wrong order.</p>
     *
     * <p>Silent, unlike {@link #setBlocking} — the transition itself is already logged there, and
     * this is a fan-out of the same event rather than a second event.</p>
     */
    public static void broadcastBlocking(ServerPlayerEntity player, boolean blocking) {
        BlockingStatePayload payload = new BlockingStatePayload(player.getUuid(), blocking);
        for (ServerPlayerEntity target : player.server.getPlayerManager().getPlayerList()) {
            if (target != player && ServerPlayNetworking.canSend(target, BlockingStatePayload.ID)) {
                ServerPlayNetworking.send(target, payload);
            }
        }
    }

    /**
     * Clears every remote blocking pose.
     *
     * <p>Called where the server drops combat state wholesale rather than player by player — arena
     * reset and shutdown. Without it the last frame a client saw is kept forever, and a player who
     * was blocking when the round ended goes on standing in a block pose for everyone else.</p>
     */
    public static void clearBlocking(ServerPlayerEntity player) {
        broadcastBlocking(player, false);
    }

    /**
     * Sends the current authoritative state unconditionally. Used at the points where the session
     * layer flips the rule set (round start, restore, join), where the client's mirror is known to
     * be stale and a change-gated send could be skipped.
     */
    public static void sendState(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, CombatStatePayload.ID)) {
            return;
        }
        ServerPlayNetworking.send(player, new CombatStatePayload(
                CombatState.isLegacy(player.getUuid()),
                CombatState.isBlocking(player.getUuid()),
                CombatState.useGate(player.getUuid()),
                CombatState.useBans(player.getUuid()),
                SessionManager.explosivesAllowedFor(player)));
    }
}
