package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.combat.CombatState;
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
        PayloadTypeRegistry.playS2C().register(CombatStatePayload.ID, CombatStatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(BlockingPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> applyBlocking(player, payload.blocking()));
        });
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
        ServerPlayNetworking.send(player, new CombatStatePayload(
                CombatState.isLegacy(player.getUuid()),
                CombatState.isBlocking(player.getUuid())));
    }
}
