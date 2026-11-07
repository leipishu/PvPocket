package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.loadout.LoadoutScreen;
import com.leipishu.pvpocket.client.gui.match.MatchListScreen;
import com.leipishu.pvpocket.client.gui.rule.RuleScreen;
import com.leipishu.pvpocket.client.hud.MatchHud;
import com.leipishu.pvpocket.client.hud.PossessionHud;
import com.leipishu.pvpocket.client.render.PossessedHandRenderer;
import com.leipishu.pvpocket.client.hud.SpectatorHud;
import com.leipishu.pvpocket.client.render.ArenaBounds;
import com.leipishu.pvpocket.client.render.BoundaryRenderer;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.network.ArenaBoundsPayload;
import com.leipishu.pvpocket.network.CombatStatePayload;
import com.leipishu.pvpocket.network.LoadoutOpenPayload;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.network.MatchListPayload;
import com.leipishu.pvpocket.network.PossessPayload;
import com.leipishu.pvpocket.network.RulesClosePayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.SpectateStatePayload;
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

        ClientPlayNetworking.registerGlobalReceiver(MatchHudPayload.ID, (payload, context) -> {
            // No log line: this arrives at every round boundary for every participant, and it would
            // bury the panel lines that a developer actually needs to see.
            context.client().execute(() -> MatchHud.update(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(ArenaBoundsPayload.ID, (payload, context) -> {
            context.client().execute(() -> ArenaBounds.update(payload));
        });

        // The match list always opens a fresh screen rather than patching an open one. Refresh is
        // implemented by asking the server again, so a payload arriving while the panel is open
        // means the list genuinely changed — and rebuilding it is the only way to get the row
        // buttons to match the new rows.
        ClientPlayNetworking.registerGlobalReceiver(MatchListPayload.ID, (payload, context) -> {
            context.client().execute(() -> context.client().setScreen(new MatchListScreen(payload)));
        });

        ClientPlayNetworking.registerGlobalReceiver(SpectateStatePayload.ID, (payload, context) -> {
            context.client().execute(() -> SpectatorHud.update(payload));
        });

        // The possessed body's hotbar. Separate from the spectator bar because it changes on a
        // different clock: the bar only moves when the camera does, the hotbar whenever the target
        // swaps a slot or takes damage.
        ClientPlayNetworking.registerGlobalReceiver(PossessPayload.ID, (payload, context) -> {
            context.client().execute(() -> PossessionHud.update(payload));
        });

        // A stale mirror after leaving a world would make the next join start with a phantom block
        // penalty and a hidden attack cooldown, so both sides of the mirror are dropped together.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CombatState.clearAll();
            BlockingInput.reset();
            MatchHud.clear();
            ArenaBounds.clear();
            SpectatorHud.clear();
            PossessionHud.clear();
        });

        MatchHud.register();
        SpectatorHud.register();
        PossessionHud.register();
        PossessedHandRenderer.register();
        SpectatorInput.register();
        BoundaryRenderer.register();

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
        // Without this the client predicts a placement the server refuses and keeps a block only it
        // can see; the same mask makes both sides reach the same answer.
        CombatState.setUseGate(client.player.getUuid(), payload.useGate(), payload.bans());
    }
}
