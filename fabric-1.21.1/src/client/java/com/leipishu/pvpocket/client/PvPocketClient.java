package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.network.CombatStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * Client entrypoint. PvPocket ships a mandatory client mod, so this is where the custom GUI layer,
 * HUD and combat feedback rendering will be registered.
 *
 * <p>For S2 the only job is keeping the local mirror of the authoritative combat state in sync.
 * The client never decides which rule set applies — it copies what the server says, so there is
 * exactly one place where "is this player legacy right now" can be answered.</p>
 */
public final class PvPocketClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CombatStatePayload.ID, (payload, context) -> {
            context.client().execute(() -> apply(payload));
        });

        // A stale mirror after leaving a world would make the next join start with a phantom block
        // penalty and a hidden attack cooldown, so both sides of the mirror are dropped together.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CombatState.clearAll();
            BlockingInput.reset();
        });

        PvPocket.LOGGER.info("PvPocket client bootstrap complete");
    }

    private static void apply(CombatStatePayload payload) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        CombatState.setMode(client.player.getUuid(),
                payload.legacy() ? CombatMode.LEGACY_1_8 : CombatMode.MODERN);
        CombatState.setBlocking(client.player.getUuid(), payload.blocking());
    }
}
