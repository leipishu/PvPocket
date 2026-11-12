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
 * 客户端接线。**这里不该出现任何加载器名字**——入口与平台实现都在
 * {@code src/client-fabric}，与 {@code src/fabric} 那一半对称。
 *
 * <p>形状照抄服务端的 {@code PvBootstrap}：一个 {@code wire(platform)} 装完所有东西，入口类只剩
 * 「装平台、调 wire」两行。这样 NeoForge 那一格要写的是一个新的平台实现，而不是第二份接线。</p>
 *
 * <p>三件事：把服务端说了算的状态镜像下来、按 payload 决定开哪个界面、把渲染钩子挂上去。镜像的
 * 那一半永远只是拷贝——客户端从不决定哪条规则生效。</p>
 */
public final class PvPocketClient {

    private PvPocketClient() {
    }

    public static void wire(PvClientPlatform platform) {
        // Before anything can draw: the palette is read at draw time now that its tokens are no
        // longer compile-time constants, so loading the saved theme here is what decides whether the
        // first panel the player opens is light or dark.
        ClientPrefs.load();
        PvTheme.apply(ClientPrefs.theme());

        platform.onServerPacket(CombatStatePayload.ID, PvPocketClient::apply);

        // The home panel's snapshot. Not a reason to open a screen — the player may have closed the
        // panel before the answer arrived — so it only refreshes the cache and lets an open panel
        // repaint on its next frame.
        platform.onServerPacket(HomeStatePayload.ID, HomeState::update);

        // Somebody else's block, so it can be drawn. The platform hops to the client thread, because
        // the render thread reads the same set every frame.
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
            // The loadout editor is a child of the rule panel, so it has to go too: leaving it
            // open over a finished session would let the player keep editing a loadout that can
            // never be applied.
            if (client.screen instanceof LoadoutScreen editor
                    && editor.sessionId().equals(payload.sessionId())) {
                client.setScreen(null);
            }
        });

        platform.onServerPacket(LoadoutOpenPayload.ID, payload -> {
            PvPocket.LOGGER.info("Received loadout_open for session {}", payload.sessionId());
            Minecraft client = Minecraft.getInstance();
            // The editor is opened from the rule panel and returns to it, so the panel instance is
            // carried across rather than reconstructed: rebuilding it would throw away any rule
            // edits the player had made but not yet sent.
            RuleScreen parent = client.screen instanceof RuleScreen screen ? screen : null;
            client.setScreen(new LoadoutScreen(payload, parent));
        });

        // No log line for the HUD payload: it arrives at every round boundary for every participant,
        // and it would bury the panel lines that a developer actually needs to see.
        platform.onServerPacket(MatchHudPayload.ID, MatchHud::update);
        platform.onServerPacket(ArenaBoundsPayload.ID, ArenaBounds::update);
        platform.onServerPacket(PrepareScreensPayload.ID, PrepareScreens::update);

        // The match list always opens a fresh screen rather than patching an open one. Refresh is
        // implemented by asking the server again, so a payload arriving while the panel is open
        // means the list genuinely changed — and rebuilding it is the only way to get the row
        // buttons to match the new rows.
        platform.onServerPacket(MatchListPayload.ID,
                payload -> Minecraft.getInstance().setScreen(new MatchListScreen(payload)));

        platform.onServerPacket(SpectateStatePayload.ID, SpectatorHud::update);

        // The possessed body's hotbar. Separate from the spectator bar because it changes on a
        // different clock: the bar only moves when the camera does, the hotbar whenever the target
        // swaps a slot or takes damage.
        platform.onServerPacket(PossessPayload.ID, PossessionHud::update);

        // Share-code answers. Hopped onto the client thread by the platform, because the dialog that
        // consumes them lives on the render thread and mutates its own widgets.
        platform.onServerPacket(PresetShareResultPayload.ID, PresetShare::receive);

        // The result card. Unlike the panel openers above, this one can decline to open at all: the
        // settings page has a switch, and the server does not know about it — it sends the card to
        // every participant and lets each client decide. That is the right split, because the
        // preference belongs to the installation, not to the account.
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

        // A stale mirror after leaving a world would make the next join start with a phantom block
        // penalty and a hidden attack cooldown, so both sides of the mirror are dropped together.
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
            // The dialog is gone with the screen, and a listener left behind would take the next
            // server's answer and write it into a store the player is not looking at.
            PresetShare.listener(null);
            // Stats and history belong to the server that sent them; keeping them across a server
            // change would show one server's win rate on another server's panel.
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
        // No-op unless -Dpvpocket.audit=true: walks every screen at two canvas sizes and reports
        // the rectangles that collide. See PvAudit.
        PvAuditHarness.register();

        PvPocket.LOGGER.info("PvPocket client bootstrap complete (platform: {})", platform.name());
    }

    /**
     * A payload that arrived before there was a player to attribute it to.
     *
     * <p>Round start teleports everybody into the arena dimension and then sends the state, so the
     * two can land in the same batch. Dropping the payload in that window would leave the mirror on
     * the previous rule set for the whole round — no blocking intent, no predicted slowdown — and
     * the symptom is not "the mirror is stale" but "right-click does nothing".
     */
    private static CombatStatePayload pending;

    /**
     * The last series whose card this client has already shown.
     *
     * <p>A series can be finished twice — a clean end, then a shutdown that runs the same teardown —
     * and the second payload would otherwise reopen a card the player has already dismissed. Keyed
     * on the session id rather than on a timestamp because it is the thing that is actually unique,
     * and cleared on disconnect so a new world starts with a clean slate.</p>
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
        // Without this the client predicts a placement the server refuses and keeps a block only it
        // can see; the same mask makes both sides reach the same answer.
        CombatState.setUseGate(client.player.getUUID(), payload.useGate(), payload.bans());
        // An explosion destroys blocks on whichever side runs it, so this has to reach the client or
        // it leaves holes the server never confirms.
        CombatState.setClientExplosivesAllowed(payload.explosives());
    }

    /** Applies a state payload that had to wait for the player to exist. Called from the client tick. */
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
