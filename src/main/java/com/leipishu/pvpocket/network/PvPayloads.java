package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.platform.PvPlatform;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registry of the 22 payloads plus the handler entry points for the client-to-server ones.
 *
 * <p>Registration goes through {@link PvPlatform#registerPlayToServer} and
 * {@link PvPlatform#registerPlayToClient}, inbound packets through
 * {@link PvPlatform#onServerReceive}. Field order lives in the payload classes, thread hops in the
 * platform implementations, and what happens on arrival here.
 *
 * <p>Adding a payload touches the payload class, the registry below, and the client receiver if the
 * client reads it. A missing registration does not fail to compile: the packet does nothing.
 */
public final class PvPayloads {

    private PvPayloads() {
    }

    /** Registers every payload and binds the inbound handlers. Called once by PvBootstrap.wire. */
    public static void register(PvPlatform platform) {
        registerClientToServer(platform);
        registerServerToClient(platform);
        bindServerReceivers(platform);
    }

    /** Client to server: actions, submits and requests the client initiates. */
    private static void registerClientToServer(PvPlatform platform) {
        platform.registerPlayToServer(BlockingPayload.ID, BlockingPayload.CODEC);
        platform.registerPlayToServer(RulesSubmitPayload.ID, RulesSubmitPayload.CODEC);
        platform.registerPlayToServer(LoadoutRequestPayload.ID, LoadoutRequestPayload.CODEC);
        platform.registerPlayToServer(LoadoutSubmitPayload.ID, LoadoutSubmitPayload.CODEC);
        platform.registerPlayToServer(SpectateRequestPayload.ID, SpectateRequestPayload.CODEC);
        platform.registerPlayToServer(PresetSharePayload.ID, PresetSharePayload.CODEC);
        platform.registerPlayToServer(HomeRequestPayload.ID, HomeRequestPayload.CODEC);
        platform.registerPlayToServer(HomeActionPayload.ID, HomeActionPayload.CODEC);
    }

    /** Server to client: state the server pushes, drawn by the client. */
    private static void registerServerToClient(PvPlatform platform) {
        platform.registerPlayToClient(HomeStatePayload.ID, HomeStatePayload.CODEC);
        platform.registerPlayToClient(CombatStatePayload.ID, CombatStatePayload.CODEC);
        platform.registerPlayToClient(BlockingStatePayload.ID, BlockingStatePayload.CODEC);
        platform.registerPlayToClient(RulesOpenPayload.ID, RulesOpenPayload.CODEC);
        platform.registerPlayToClient(RulesClosePayload.ID, RulesClosePayload.CODEC);
        platform.registerPlayToClient(LoadoutOpenPayload.ID, LoadoutOpenPayload.CODEC);
        platform.registerPlayToClient(MatchHudPayload.ID, MatchHudPayload.CODEC);
        platform.registerPlayToClient(ArenaBoundsPayload.ID, ArenaBoundsPayload.CODEC);
        platform.registerPlayToClient(PrepareScreensPayload.ID, PrepareScreensPayload.CODEC);
        platform.registerPlayToClient(MatchListPayload.ID, MatchListPayload.CODEC);
        platform.registerPlayToClient(SpectateStatePayload.ID, SpectateStatePayload.CODEC);
        platform.registerPlayToClient(PossessPayload.ID, PossessPayload.CODEC);
        platform.registerPlayToClient(PresetShareResultPayload.ID, PresetShareResultPayload.CODEC);
        platform.registerPlayToClient(SeriesResultPayload.ID, SeriesResultPayload.CODEC);
    }

    /**
     * Binds the inbound handlers. They run on the server thread, so they read session state
     * directly.
     *
     * <p>Each handler fetches {@link SessionManager#get()} and checks for null: the session is
     * created on server start, and a client may send a packet before that.
     */
    private static void bindServerReceivers(PvPlatform platform) {
        platform.onServerReceive(SpectateRequestPayload.ID, (player, payload) -> {
            SessionManager sessions = SessionManager.get();
            if (sessions != null) {
                sessions.onSpectateRequest(player, payload);
            }
        });

        platform.onServerReceive(BlockingPayload.ID,
                (player, payload) -> PvPocketNetworking.applyBlocking(player, payload.blocking()));

        platform.onServerReceive(RulesSubmitPayload.ID, (player, payload) -> {
            SessionManager sessions = SessionManager.get();
            if (sessions != null) {
                sessions.onRulesSubmit(player, payload.action(), payload.rules(), payload.roster());
            }
        });

        platform.onServerReceive(LoadoutRequestPayload.ID, (player, payload) -> {
            SessionManager sessions = SessionManager.get();
            if (sessions != null) {
                sessions.onLoadoutRequest(player);
            }
        });

        platform.onServerReceive(LoadoutSubmitPayload.ID, (player, payload) -> {
            SessionManager sessions = SessionManager.get();
            if (sessions != null) {
                sessions.onLoadoutSubmit(player, payload.sessionId(), payload.loadout());
            }
        });

        platform.onServerReceive(PresetSharePayload.ID,
                (player, payload) -> PvPocketNetworking.onPresetShare(player, payload));

        // Home snapshot: once per player per second. Opening the screen sends a request, and a
        // double tap on the hotkey would otherwise re-read the history file for nothing.
        platform.onServerReceive(HomeRequestPayload.ID, (player, payload) -> {
            SessionManager sessions = SessionManager.get();
            if (sessions == null || !PvPocketNetworking.allowHomeRequest(player.getUUID())) {
                return;
            }
            sessions.sendHomeState(player);
        });

        platform.onServerReceive(HomeActionPayload.ID, (player, payload) -> {
            SessionManager sessions = SessionManager.get();
            if (sessions != null) {
                sessions.onHomeAction(player, payload);
            }
        });
    }
}
