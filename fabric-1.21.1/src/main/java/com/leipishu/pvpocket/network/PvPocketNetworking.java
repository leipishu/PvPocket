package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.combat.CombatState;
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
        PayloadTypeRegistry.playS2C().register(CombatStatePayload.ID, CombatStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RulesOpenPayload.ID, RulesOpenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RulesClosePayload.ID, RulesClosePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LoadoutOpenPayload.ID, LoadoutOpenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MatchHudPayload.ID, MatchHudPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ArenaBoundsPayload.ID, ArenaBoundsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MatchListPayload.ID, MatchListPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SpectateStatePayload.ID, SpectateStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PossessPayload.ID, PossessPayload.CODEC);

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

    /** Opens the loadout editor. Same {@code canSend} reasoning as {@link #openRules}. */
    public static void openLoadout(ServerPlayerEntity player, LoadoutOpenPayload payload) {
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
            sendState(player);
        }
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
                CombatState.useBans(player.getUuid())));
    }
}
