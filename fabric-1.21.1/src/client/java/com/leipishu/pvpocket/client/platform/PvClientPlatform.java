package com.leipishu.pvpocket.client.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 客户端的加载器接触面，服务端那一半见 {@code com.leipishu.pvpocket.platform.PvPlatform}。
 *
 * <p>为什么要有第二个接口而不是给 {@code PvPlatform} 加几个方法：{@code src/main} 会被编译进
 * 一个专用服务端也要加载的 jar，而这个接口的每一个参数都来自 {@code net.minecraft.client}。
 * 把它放进 {@code src/main} 就等于让服务端 classpath 去解析 {@code Minecraft}——那正是
 * {@code splitEnvironmentSourceSets()} 要防的事。所以它待在 {@code src/client}，实现类待在
 * {@code src/client-fabric}。</p>
 *
 * <p>取舍标准与 {@code PvPlatform} 一致：<b>只承诺加载器之间语义一致的东西</b>。这里比服务端那边
 * 更容易写错，因为渲染事件在 Fabric 与 NeoForge 上的时机划分根本不同——见
 * {@link #onWorldRenderAfterTranslucent} 与 {@link #onWorldRenderLast} 的注释。</p>
 *
 * <p>实现类只做转发。如果某个实现里出现了 if 判断，那个判断多半属于 {@code PvPocketClient}。</p>
 */
public interface PvClientPlatform {

    /** 加载器名，只用于日志与诊断（{@code fabric} / {@code neoforge} / {@code forge}）。 */
    String name();

    // ------------------------------------------------------------------ 网络

    /**
     * 收到服务端发来的 payload 时做什么。
     *
     * <p>与 {@code PvPlatform#onServerReceive} 同理：实现类负责把回调放到**客户端主线程**上。
     * Fabric 的 receiver 跑在 netty 线程，NeoForge 的 handler 本来就在主线程——这个差异属于
     * 平台，所以接收方的代码里不该再出现 {@code client.execute}。</p>
     */
    <T extends CustomPacketPayload> void onServerPacket(
            CustomPacketPayload.Type<T> type, Consumer<T> handler);

    /** 服务端是否声明了这个方向。用来决定「这个按钮该不该亮」。 */
    boolean canSend(CustomPacketPayload.Type<?> type);

    void send(CustomPacketPayload payload);

    // ------------------------------------------------------------------ 生命周期

    /** 每个客户端 tick 的末尾。{@link Minecraft} 实例由调用方自己取，三个加载器都只有一份。 */
    void onClientTick(Runnable action);

    /**
     * 退出一个世界时。
     *
     * <p>「退出世界」是加载器之间语义最一致的一个点：Fabric 的
     * {@code ClientPlayConnectionEvents.DISCONNECT} 与 NeoForge 的
     * {@code ClientPlayerNetworkEvent.LoggingOut} 都在同一次清理里触发，都早于世界被丢弃。</p>
     */
    void onDisconnect(Runnable action);

    /**
     * 某个 {@link Screen} 完成 {@code init()} 之后。
     *
     * <p>必须是「每次 init」而不是「一次」：窗口大小一变，界面就会重建自己的控件列表，只加过一次的
     * 按钮会在第一次改变窗口大小时消失。</p>
     */
    void onScreenInit(ScreenInit listener);

    // ------------------------------------------------------------------ 输入

    /**
     * 注册一个按键。
     *
     * <p>返回值忽略：Fabric 与 NeoForge 都是把传进去的实例登记进去，之后按 {@code consumeClick()}
     * 轮询即可，不需要加载器再交还什么。</p>
     */
    void registerKeyMapping(KeyMapping mapping);

    // ------------------------------------------------------------------ 渲染

    /**
     * 每一帧画完原版 HUD 之后。
     *
     * <p>{@link DeltaTracker} 而不是一个 float：1.21 起原版把「部分刻」换成了这个对象，
     * 而它同时带着 {@code getGameTimeDeltaPartialTick(boolean)} 与暂停状态。传 float 会把这个
     * 信息丢掉，动画在暂停时会继续走。</p>
     */
    void onHudRender(HudLayer layer);

    /**
     * 世界渲染的半透明阶段之后，也就是「能看到水、玻璃，但还没开始画粒子与天气」。
     *
     * <p>分开两个方法是因为这个阶段在 NeoForge 上叫
     * {@code RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS}，而 Fabric 只有
     * {@code WorldRenderEvents.AFTER_TRANSLUCENT}——名字不同、位置相同。合成一个方法就得让实现类
     * 去猜调用方要的是哪一个。</p>
     */
    void onWorldRenderAfterTranslucent(WorldLayer layer);

    /**
     * 世界渲染的最后一刻，模型视图栈还没弹掉。
     *
     * <p>「最后」在两边都是最后，但**栈的状态**是这里的全部意义：手部渲染依赖它此刻仍持有视图旋转
     * （详见 {@code PossessedHandRenderer} 的坐标空间注释）。所以这个钩子不能合并进上一个。</p>
     */
    void onWorldRenderLast(WorldLayer layer);

    // ------------------------------------------------------------------ 环境

    /** 配置目录（{@code .minecraft/config} 那一层，不含 {@code pvpocket} 子目录）。 */
    Path configDir();

    /**
     * 本模组的版本字符串，用于设置页显示。
     *
     * <p>加载器各自从自己的元数据里读（Fabric 的 {@code ModContainer}、NeoForge 的
     * {@code ModList}），这是少数几处「语义相同但完全没有公共 API」的地方之一，所以只能放在接口上。</p>
     */
    String modVersion();

    // ------------------------------------------------------------------ 回调形状

    /** 界面初始化后。{@code screen} 已经是本次 init 的新实例。 */
    @FunctionalInterface
    interface ScreenInit {
        void init(Minecraft client, Screen screen);
    }

    /** 一层 HUD。 */
    @FunctionalInterface
    interface HudLayer {
        void render(GuiGraphics graphics, DeltaTracker delta);
    }

    /** 一次世界渲染。 */
    @FunctionalInterface
    interface WorldLayer {
        void render(WorldFrame frame);
    }

    /**
     * 一次世界渲染时能拿到的东西。
     *
     * <p>做成 record 而不是把 {@code WorldRenderContext} 这类加载器类型传进来：那个类型是 Fabric
     * 独有的，而这里六个字段在 NeoForge 的 {@code RenderLevelStageEvent} 上一个不少地存在
     * （{@code getPoseStack} / {@code getLevel} / {@code getCamera} / {@code getPartialTick} /
     * {@code getModelViewMatrix} 之外的两项由事件自带的 {@code MultiBufferSource} 提供）。
     * 字段少一个就得改渲染代码，所以这六个是刻意挑的「两边都有」的交集。</p>
     */
    record WorldFrame(
            ClientLevel level,
            PoseStack poses,
            MultiBufferSource buffers,
            Camera camera,
            DeltaTracker delta,
            LightTexture lightmap) {
    }

    // ------------------------------------------------------------------ 全局句柄

    /**
     * 当前平台的实现。
     *
     * <p>与服务端那份同样是静态句柄：调用点散在二十来个界面里，一层层传参会把接口泄漏进每一个
     * 签名。入口装一次，此后只读。</p>
     */
    static PvClientPlatform get() {
        PvClientPlatform platform = Holder.INSTANCE;
        if (platform == null) {
            throw new IllegalStateException(
                    "PvClientPlatform 还没装：客户端入口必须先调 PvClientPlatform.install(...)");
        }
        return platform;
    }

    static void install(PvClientPlatform platform) {
        Holder.INSTANCE = platform;
    }

    final class Holder {
        private static PvClientPlatform INSTANCE;

        private Holder() {
        }
    }
}
