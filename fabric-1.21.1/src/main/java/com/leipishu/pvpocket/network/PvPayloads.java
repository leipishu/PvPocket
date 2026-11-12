package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.platform.PvPlatform;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * 22 个 payload 的注册表，以及 8 个上行包各自的处理入口。
 *
 * <p>这里没有加载器的名字：注册走 {@link PvPlatform#registerPlayToServer} /
 * {@link PvPlatform#registerPlayToClient}，收包走 {@link PvPlatform#onServerReceive}。
 * 三件事因此各归其位——</p>
 *
 * <ul>
 *   <li><b>字段顺序</b>在 payload 类里，一份。</li>
 *   <li><b>注册与线程切换</b>在平台实现里（Fabric 的 receiver 跑在 netty 线程，必须跳回服务端
 *       线程；NeoForge 的本来就在服务端线程）。所以下面的接收器里没有 {@code server.execute}。</li>
 *   <li><b>收到之后做什么</b>在这里，一份。</li>
 * </ul>
 *
 * <p>新增一个 payload 要改三处：payload 类、本文件的注册表、以及（如果客户端要收）客户端那侧的
 * 接收器。少改一处不会编译失败——上行包漏注册的表现是「点了没反应」，下行包漏注册的表现是
 * 「面板不出现」。所以注册表按方向分成两段，方便一眼看出漏了哪边。</p>
 */
public final class PvPayloads {

    private PvPayloads() {
    }

    /** 注册全部 payload 并绑定上行处理。由 {@code PvBootstrap.wire} 在挂事件之前调用一次。 */
    public static void register(PvPlatform platform) {
        registerClientToServer(platform);
        registerServerToClient(platform);
        bindServerReceivers(platform);
    }

    /** 客户端 → 服务端。全部由客户端发起：动作、提交、请求。 */
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

    /** 服务端 → 客户端。全部是服务端推的，客户端只负责画。 */
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
     * 上行包的处理。已经是服务端线程，所以直接读会话状态。
     *
     * <p>每个分支都先取一次 {@link SessionManager#get()} 再判空：会话在服务端启动事件里才建立，
     * 而一个手快的客户端可能在那之前就把包发过来了。</p>
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

        // 主页快照：每玩家每秒一次。不是为限流而限流——主页打开时就会请求，而连按两下热键是
        // 玩家真会做的事；每个回答都要走一遍历史文件，同一秒里的第二次是白读一次盘。
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
