package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.PvAuditHarness;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.loadout.LoadoutScreen;
import com.leipishu.pvpocket.client.gui.match.MatchListScreen;
import com.leipishu.pvpocket.client.gui.result.MatchResult;
import com.leipishu.pvpocket.client.gui.result.SeriesResultScreen;
import com.leipishu.pvpocket.client.gui.rule.RuleScreen;
import com.leipishu.pvpocket.client.home.HomeClient;
import com.leipishu.pvpocket.client.home.HomeState;
import com.leipishu.pvpocket.client.home.InventoryButton;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.client.preset.PresetShare;
import com.leipishu.pvpocket.client.hud.MatchHud;
import com.leipishu.pvpocket.client.hud.PossessionHud;
import com.leipishu.pvpocket.client.render.PossessedHandRenderer;
import com.leipishu.pvpocket.client.hud.SpectatorHud;
import com.leipishu.pvpocket.client.render.ArenaBounds;
import com.leipishu.pvpocket.client.render.BoundaryRenderer;
import com.leipishu.pvpocket.client.render.PrepareScreens;
import com.leipishu.pvpocket.combat.CombatMode;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.network.ArenaBoundsPayload;
import com.leipishu.pvpocket.network.BlockingStatePayload;
import com.leipishu.pvpocket.network.CombatStatePayload;
import com.leipishu.pvpocket.network.HomeStatePayload;
import com.leipishu.pvpocket.network.LoadoutOpenPayload;
import com.leipishu.pvpocket.network.MatchHudPayload;
import com.leipishu.pvpocket.network.MatchListPayload;
import com.leipishu.pvpocket.network.PossessPayload;
import com.leipishu.pvpocket.network.PrepareScreensPayload;
import com.leipishu.pvpocket.network.PresetShareResultPayload;
import com.leipishu.pvpocket.network.RulesClosePayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.SeriesResultPayload;
import com.leipishu.pvpocket.network.SpectateStatePayload;
import net.minecraft.client.Minecraft;

/**
 * Client wiring. No loader name appears here; entries and platform implementations live in
 * {@code src/client-fabric} and {@code src/client-neoforge}.
 *
 * <p>{@link #wire} installs every handler and render hook. An entrypoint only installs the platform
 * and calls it.
 *
 * <p>Three jobs: mirror the state the server owns, open the screen a payload asks for, and attach
 * the render hooks. The mirror only copies; the client never decides which rule applies.
 */
public final class PvPocketClient {

    private PvPocketClient() {
    }

    public static void wire(PvClientPlatform platform) {
        // Loads the saved theme before the first draw; the palette is read at draw time.
        ClientPrefs.load();
        PvTheme.apply(ClientPrefs.theme());

        platform.onServerPacket(CombatStatePayload.ID, PvPocketClient::apply);

        // Refreshes the home cache only. An open panel repaints on its next frame.
        platform.onServerPacket(HomeStatePayload.ID, HomeState::update);

        // The platform delivers this on the client thread, which the renderer requires.
        platform.onServerPacket(BlockingStatePayload.ID,
                payload -> CombatState.setBlocking(payload.player(), payload.blocking()));

        platform.onServerPacket(RulesOpenPayload.ID, payload -> {
            PvPocket.LOGGER.info("Received rules_open for session {} role {}",
                    payload.sessionId(), payload.role());
            Minecraft.getInstance().setScreen(new RuleScreen(payload));
        });

        platform.onServerPacket(RulesClosePayload.ID, payload -> {
            Minecraft client = Minecraft.getInstance();
            boolean showing = client.screen instanceof RuleScreen screen
                    && screen.sessionId().equals(payload.sessionId());
            PvPocket.LOGGER.info("Rules close for session {} (panel open: {})",
                    payload.sessionId(), showing);
            if (showing) {
                ((RuleScreen) client.screen).closeSilently();
                return;
            }
            // The loadout editor is a child of the rule panel, so it closes with it.
            if (client.screen instanceof LoadoutScreen editor
                    && editor.sessionId().equals(payload.sessionId())) {
                client.setScreen(null);
            }
        });

        platform.onServerPacket(LoadoutOpenPayload.ID, payload -> {
            PvPocket.LOGGER.info("Received loadout_open for session {}", payload.sessionId());
            Minecraft client = Minecraft.getInstance();
            // Carries the panel instance across so unsent rule edits survive.
            RuleScreen parent = client.screen instanceof RuleScreen screen ? screen : null;
            client.setScreen(new LoadoutScreen(payload, parent));
        });

        // No log line for the HUD payload; it arrives at every round boundary for every player.
        platform.onServerPacket(MatchHudPayload.ID, MatchHud::update);
        platform.onServerPacket(ArenaBoundsPayload.ID, ArenaBounds::update);
        platform.onServerPacket(PrepareScreensPayload.ID, PrepareScreens::update);

        // Opens a fresh screen so the row buttons match the new rows.
        platform.onServerPacket(MatchListPayload.ID,
                payload -> Minecraft.getInstance().setScreen(new MatchListScreen(payload)));

        platform.onServerPacket(SpectateStatePayload.ID, SpectatorHud::update);

        // The possessed body's hotbar; it updates on a different clock than the spectator bar.
        platform.onServerPacket(PossessPayload.ID, PossessionHud::update);

        // Share-code answers; the platform delivers these on the client thread.
        platform.onServerPacket(PresetShareResultPayload.ID, PresetShare::receive);

        // The result card honours the local switch; the server sends it to every participant.
        platform.onServerPacket(SeriesResultPayload.ID, payload -> {
            if (!ClientPrefs.resultScreen()) {
                return;
            }
            MatchResult result = MatchResult.parse(payload);
            if (result == null || result.sessionId().equals(lastResultSession)) {
                return;
            }
            lastResultSession = result.sessionId();
            Minecraft.getInstance().setScreen(new SeriesResultScreen(result));
        });

        // Drops every mirror so the next join starts clean.
        platform.onDisconnect(() -> {
            CombatState.clearAll();
            BlockingInput.reset();
            clearPendingState();
            lastResultSession = null;
            MatchHud.clear();
            ArenaBounds.clear();
            PrepareScreens.clear();
            SpectatorHud.clear();
            PossessionHud.clear();
            // Clears the listener so a later answer cannot reach a closed dialog.
            PresetShare.listener(null);
            // Stats and history belong to the server that sent them.
            HomeState.clear();
        });

        MatchHud.register();
        SpectatorHud.register();
        PossessionHud.register();
        PossessedHandRenderer.register();
        SpectatorInput.register();
        HomeClient.register();
        InventoryButton.register();
        BoundaryRenderer.register();
        // No-op unless -Dpvpocket.audit=true. Walks every screen at two canvas sizes. See PvAudit.
        PvAuditHarness.register();

        PvPocket.LOGGER.info("PvPocket client bootstrap complete (platform: {})", platform.name());
    }

    /**
     * A payload that arrived before there was a player to attribute it to.
     *
     * <p>Round start teleports everybody into the arena dimension and then sends the state, so both
     * can land in the same batch. {@link #applyPendingState} retries from the client tick.
     */
    private static CombatStatePayload pending;

    /**
     * The session id of the last series whose card this client showed.
     *
     * <p>Cleared on disconnect. A series can finish twice, and the second payload must not reopen a
     * card the player already dismissed.
     */
    private static String lastResultSession;

    private static void apply(CombatStatePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            pending = payload;
            return;
        }
        pending = null;
        CombatState.setMode(client.player.getUUID(),
                payload.legacy() ? CombatMode.LEGACY_1_8 : CombatMode.MODERN);
        CombatState.setBlocking(client.player.getUUID(), payload.blocking());
        // Same gate as the server, so both sides predict the same placement.
        CombatState.setUseGate(client.player.getUUID(), payload.useGate(), payload.bans());
        // Explosions run on both sides, so the client needs the same allowance.
        CombatState.setClientExplosivesAllowed(payload.explosives());
    }

    /** Applies a state payload that waited for the player. Called from the client tick. */
    public static void applyPendingState() {
        CombatStatePayload waiting = pending;
        if (waiting != null) {
            apply(waiting);
        }
    }

    /** Drops a waiting payload, so the next join cannot inherit a previous world's rule set. */
    public static void clearPendingState() {
        pending = null;
    }
}
