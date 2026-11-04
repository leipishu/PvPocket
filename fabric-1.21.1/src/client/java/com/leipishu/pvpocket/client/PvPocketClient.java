package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.loadout.LoadoutScreen;
import com.leipishu.pvpocket.client.gui.rule.RuleScreen;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.network.CombatStatePayload;
import com.leipishu.pvpocket.network.LoadoutOpenPayload;
import com.leipishu.pvpocket.network.RulesClosePayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * Client entrypoint. PvPocket ships a mandatory client mod, so this is where the custom GUI layer,
 * HUD and combat feedback rendering are registered.
 *
 * <p>Two jobs so far. The combat state mirror keeps the local copy of the authoritative rule set in
 * sync — the client never decides which rules apply, it copies what the server says. The rule panel
 * receiver opens the negotiation screen, which is a pure presentation layer: it edits a local copy
 * and sends it back as an intent, and every permission check happens on the server.</p>
 */
public final class PvPocketClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CombatStatePayload.ID, (payload, context) -> {
            context.client().execute(() -> apply(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(RulesOpenPayload.ID, (payload, context) -> {
            PvPocket.LOGGER.info("Received rules_open for session {} role {}", payload.sessionId(), payload.role());
            context.client().execute(() -> context.client().setScreen(new RuleScreen(payload)));
        });

        ClientPlayNetworking.registerGlobalReceiver(RulesClosePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                boolean showing = context.client().currentScreen instanceof RuleScreen screen
                        && screen.sessionId().equals(payload.sessionId());
                PvPocket.LOGGER.info("Rules close for session {} (panel open: {})",
                        payload.sessionId(), showing);
                if (showing) {
                    ((RuleScreen) context.client().currentScreen).closeSilently();
                    return;
                }
                // The loadout editor is a child of the rule panel, so it has to go too: leaving it
                // open over a finished session would let the player keep editing a loadout that can
                // never be applied.
                if (context.client().currentScreen instanceof LoadoutScreen editor
                        && editor.sessionId().equals(payload.sessionId())) {
                    context.client().setScreen(null);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(LoadoutOpenPayload.ID, (payload, context) -> {
            PvPocket.LOGGER.info("Received loadout_open for session {}", payload.sessionId());
            context.client().execute(() -> {
                // The editor is opened from the rule panel and returns to it, so the panel instance is
                // carried across rather than reconstructed: rebuilding it would throw away any rule
                // edits the player had made but not yet sent.
                RuleScreen parent = context.client().currentScreen instanceof RuleScreen screen ? screen : null;
                context.client().setScreen(new LoadoutScreen(payload, parent));
            });
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
