package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.PvAuditHarness;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.loadout.LoadoutScreen;
import com.leipishu.pvpocket.client.gui.match.MatchListScreen;
import com.leipishu.pvpocket.client.gui.prepare.PrepareNotice;
import com.leipishu.pvpocket.client.gui.prepare.PrepareScreen;
import com.leipishu.pvpocket.client.gui.result.MatchResult;
import com.leipishu.pvpocket.client.gui.result.SeriesResultScreen;
import com.leipishu.pvpocket.client.gui.match.ReconnectScreen;
import com.leipishu.pvpocket.client.gui.rule.RuleScreen;
import com.leipishu.pvpocket.client.gui.rule.VoteScreen;
import com.leipishu.pvpocket.client.home.HomeClient;
import com.leipishu.pvpocket.client.home.HomeState;
import com.leipishu.pvpocket.client.home.InventoryButton;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.client.preset.PresetShare;
import com.leipishu.pvpocket.client.hud.MatchHud;
import com.leipishu.pvpocket.client.hud.PossessionHud;
import com.leipishu.pvpocket.client.hud.ResultHud;
import com.leipishu.pvpocket.client.render.PossessedHandRenderer;
import com.leipishu.pvpocket.client.hud.SpectatorHud;
import com.leipishu.pvpocket.client.hud.SurrenderHud;
import com.leipishu.pvpocket.client.surrender.SurrenderInput;
import com.leipishu.pvpocket.client.render.ArenaBounds;
import com.leipishu.pvpocket.client.render.ArenaSky;
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
import com.leipishu.pvpocket.network.PrepareStatusPayload;
import com.leipishu.pvpocket.network.ReconnectOfferPayload;
import com.leipishu.pvpocket.network.ResultOpenPayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.SeriesResultPayload;
import com.leipishu.pvpocket.network.SpectateStatePayload;
import com.leipishu.pvpocket.network.SurrenderStatePayload;
import com.leipishu.pvpocket.network.VoteOpenPayload;
import com.leipishu.pvpocket.network.VoteProgressPayload;
import com.leipishu.pvpocket.client.compat.PvScreens;
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
            PvScreens.open(Minecraft.getInstance(), new RuleScreen(payload));
        });

        platform.onServerPacket(ReconnectOfferPayload.ID, payload -> {
            PvPocket.LOGGER.info("Received reconnect_offer for session {}", payload.sessionId());
            // A screen, not a line: chat reads as an announcement rather than a question. Opened
            // from the tick, since the offer can arrive before the level has finished loading.
            pendingReconnect = payload;
            openReconnectIfReady();
        });

        platform.onServerPacket(VoteOpenPayload.ID, payload -> {
            PvPocket.LOGGER.info("Received vote_open for session {} ({} items)",
                    payload.sessionId(), payload.items().isEmpty()
                            ? 0 : payload.items().split("\n").length);
            PvScreens.open(Minecraft.getInstance(), new VoteScreen(payload));
        });

        platform.onServerPacket(ResultOpenPayload.ID, payload -> {
            PvPocket.LOGGER.info("Received result_open for session {} ({} items)",
                    payload.sessionId(), payload.items().isEmpty()
                            ? 0 : payload.items().split("\n").length);
            // A HUD layer, not a screen: the countdown runs while the player walks around the
            // lobby, and a layer has nothing to lose when the teleport replaces the world.
            ResultHud.update(payload);
        });

        platform.onServerPacket(VoteProgressPayload.ID, payload -> {
            // Only the panel for this ballot listens: a late packet from an older vote must not
            // repaint the one on screen.
            if (PvScreens.current(Minecraft.getInstance()) instanceof VoteScreen screen
                    && screen.sessionId().equals(payload.sessionId())) {
                screen.update(payload);
            }
        });

        platform.onServerPacket(PrepareStatusPayload.ID, payload -> {
            if (payload.active()) {
                // Armed, not opened: a start that finishes inside the delay shows nothing at all.
                PrepareNotice.arm(payload.sessionId());
            } else {
                PrepareNotice.cancel(payload.sessionId());
                PrepareScreen.dismiss(payload.sessionId());
            }
        });

        platform.onServerPacket(RulesClosePayload.ID, payload -> {
            Minecraft client = Minecraft.getInstance();
            // The result page is the HUD layer now, so it is dismissed by name rather than by
            // looking for a screen that may never have opened.
            ResultHud.clear();
            boolean showing = PvScreens.current(client) instanceof RuleScreen screen
                    && screen.sessionId().equals(payload.sessionId());
            PvPocket.LOGGER.info("Rules close for session {} (panel open: {})",
                    payload.sessionId(), showing);
            if (showing) {
                ((RuleScreen) PvScreens.current(client)).closeSilently();
                return;
            }
            if (PvScreens.current(client) instanceof VoteScreen ballot
                    && ballot.sessionId().equals(payload.sessionId())) {
                ballot.closeSilently();
                return;
            }
            // The loadout editor is a child of the rule panel, so it closes with it.
            if (PvScreens.current(client) instanceof LoadoutScreen editor
                    && editor.sessionId().equals(payload.sessionId())) {
                PvScreens.open(client, null);
            }
        });

        platform.onServerPacket(LoadoutOpenPayload.ID, payload -> {
            PvPocket.LOGGER.info("Received loadout_open for session {}", payload.sessionId());
            Minecraft client = Minecraft.getInstance();
            // Carries the panel instance across so unsent rule edits survive.
            RuleScreen parent = PvScreens.current(client) instanceof RuleScreen screen ? screen : null;
            PvScreens.open(client, new LoadoutScreen(payload, parent));
        });

        // No log line for the HUD payload; it arrives at every round boundary for every player.
        platform.onServerPacket(MatchHudPayload.ID, MatchHud::update);
        platform.onServerPacket(ArenaBoundsPayload.ID, ArenaBounds::update);
        platform.onServerPacket(PrepareScreensPayload.ID, PrepareScreens::update);

        // Opens a fresh screen so the row buttons match the new rows.
        platform.onServerPacket(MatchListPayload.ID,
                payload -> PvScreens.open(Minecraft.getInstance(), new MatchListScreen(payload)));

        platform.onServerPacket(SpectateStatePayload.ID, SpectatorHud::update);

        // A surrender request is re-sent once a second while it is open, so it carries the window.
        platform.onServerPacket(SurrenderStatePayload.ID, SurrenderHud::update);

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
            PvScreens.open(Minecraft.getInstance(), new SeriesResultScreen(result));
        });

        // Drops every mirror so the next join starts clean.
        platform.onDisconnect(() -> {
            CombatState.clearAll();
            BlockingInput.reset();
            clearPendingState();
            lastResultSession = null;
            MatchHud.clear();
            ResultHud.clear();
            ArenaBounds.clear();
            PrepareScreens.clear();
            PrepareNotice.clear();
            SpectatorHud.clear();
            PossessionHud.clear();
            SurrenderHud.clear();
            // Clears the listener so a later answer cannot reach a closed dialog.
            PresetShare.listener(null);
            // Stats and history belong to the server that sent them.
            HomeState.clear();
        });

        MatchHud.register();
        ResultHud.register();
        SpectatorHud.register();
        PossessionHud.register();
        SurrenderHud.register();
        PossessedHandRenderer.register();
        SpectatorInput.register();
        SurrenderInput.register();
        HomeClient.register();
        InventoryButton.register();
        BoundaryRenderer.register();
        ArenaSky.register();
        // The preparing panel is armed by a payload and opened from here, see PrepareNotice.
        platform.onClientTick(PrepareNotice::tick);
        platform.onClientTick(PvPocketClient::openReconnectIfReady);
        // No-op unless -Dpvpocket.audit=true. Walks every screen at two canvas sizes. See PvAudit.
        PvAuditHarness.register();

        PvPocket.LOGGER.info("PvPocket client bootstrap complete (platform: {})", platform.name());
    }

    /**
     * Opens the reconnect screen once there is a world and nothing else on screen.
     *
     * <p>The offer is sent on join, which can be before the client has finished receiving the
     * level; a screen set in that window is replaced by the join sequence. Held until the first
     * tick with a player and no other screen.
     */
    private static void openReconnectIfReady() {
        if (pendingReconnect == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || PvScreens.current(client) != null) {
            return;
        }
        ReconnectOfferPayload payload = pendingReconnect;
        pendingReconnect = null;
        PvScreens.open(client, new ReconnectScreen(payload));
    }

    /**
     * A payload that arrived before there was a player to attribute it to.
     *
     * <p>Round start teleports everybody into the arena dimension and then sends the state, so both
     * can land in the same batch. {@link #applyPendingState} retries from the client tick.
     */
    private static CombatStatePayload pending;
    /** A reconnect offer that arrived before the world was up. */
    private static ReconnectOfferPayload pendingReconnect;

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
        CombatState.setUseGate(client.player.getUUID(), payload.useGate());
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
