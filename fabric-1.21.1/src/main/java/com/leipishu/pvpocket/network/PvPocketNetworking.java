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
        PayloadTypeRegistry.playS2C().register(CombatStatePayload.ID, CombatStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RulesOpenPayload.ID, RulesOpenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RulesClosePayload.ID, RulesClosePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LoadoutOpenPayload.ID, LoadoutOpenPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(BlockingPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> applyBlocking(player, payload.blocking()));
        });

        ServerPlayNetworking.registerGlobalReceiver(RulesSubmitPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                SessionManager sessions = SessionManager.get();
                if (sessions != null) {
                    sessions.onRulesSubmit(player, payload.action(), payload.rules());
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
     * through {@code /pvpocket accept} while the other side still has the panel open, which starts
     * the round with a modal dialog in front of a player who is already in the arena.</p>
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
                CombatState.isBlocking(player.getUuid())));
    }
}
