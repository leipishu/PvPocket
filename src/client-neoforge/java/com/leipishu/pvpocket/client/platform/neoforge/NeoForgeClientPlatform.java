package com.leipishu.pvpocket.client.platform.neoforge;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.platform.neoforge.NeoForgePlatform;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.fml.ModList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * NeoForge 侧的 {@link PvClientPlatform}。
 *
 * <p>与 {@code FabricClientPlatform} 同形，四处差异都在下面标了。最大的两处是**按键注册的时机**
 * 与**世界渲染的阶段名**——两者都是「语义相同但 API 形状不同」，属于平台该吸收的东西。</p>
 */
public final class NeoForgeClientPlatform implements PvClientPlatform {

    private final NeoForgePlatform server;
    private final IEventBus modBus;

    /**
     * 还没到注册时机的按键。
     *
     * <p>{@link RegisterKeyMappingsEvent} 在 mod 总线上、初始化期触发一次，而
     * {@code HomeClient.register()} 可能在它之前也可能在它之后跑到。攒起来两边都安全：事件来时
     * 把已有的注册掉，之后再来新的就直接注册。</p>
     */
    private final List<KeyMapping> pendingKeys = new ArrayList<>();
    private boolean keysReady;

    public NeoForgeClientPlatform(NeoForgePlatform server, IEventBus modBus, IEventBus gameBus) {
        this.server = server;
        this.modBus = modBus;

        modBus.addListener(this::registerKeys);

        gameBus.addListener(ClientTickEvent.Post.class, event -> tickActions.forEach(Runnable::run));
        gameBus.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
                event -> disconnectActions.forEach(Runnable::run));
        gameBus.addListener(ScreenEvent.Init.Post.class,
                event -> screenActions.forEach(action -> action.init(
                        Minecraft.getInstance(), event.getScreen())));
        gameBus.addListener(RenderGuiEvent.Post.class,
                event -> hudLayers.forEach(layer -> layer.render(
                        event.getGuiGraphics(), event.getPartialTick())));
        // 两个阶段分开订阅，因为它们对应的是两个不同的钩子：半透明之后 vs 最后一刻。
        // Fabric 那边是 WorldRenderEvents.AFTER_TRANSLUCENT 与 LAST，名字不同、位置相同。
        gameBus.addListener(RenderLevelStageEvent.class, event -> {
            List<WorldLayer> layers;
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                layers = translucentLayers;
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                layers = lastLayers;
            } else {
                return;
            }
            for (WorldLayer layer : layers) {
                layer.render(frame(event));
            }
        });
    }

    private final List<Runnable> tickActions = new ArrayList<>();
    private final List<Runnable> disconnectActions = new ArrayList<>();
    private final List<ScreenInit> screenActions = new ArrayList<>();
    private final List<HudLayer> hudLayers = new ArrayList<>();
    private final List<WorldLayer> translucentLayers = new ArrayList<>();
    private final List<WorldLayer> lastLayers = new ArrayList<>();

    @Override
    public String name() {
        return "neoforge";
    }

    // ------------------------------------------------------------------ 网络

    @Override
    public <T extends CustomPacketPayload> void onServerPacket(
            CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        // 处理器写进服务端那份表，因为 playToClient 是在 RegisterPayloadHandlersEvent 里
        // 一次性交出去的——两个接口在 NeoForge 上共享同一次注册。
        server.onClientReceive(type, handler);
    }

    @Override
    public boolean canSend(CustomPacketPayload.Type<?> type) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(type);
    }

    @Override
    public void send(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onClientTick(Runnable action) {
        tickActions.add(action);
    }

    @Override
    public void onDisconnect(Runnable action) {
        disconnectActions.add(action);
    }

    @Override
    public void onScreenInit(ScreenInit listener) {
        screenActions.add(listener);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public void registerKeyMapping(KeyMapping mapping) {
        if (keysReady) {
            // 事件已经过去，只能直接往原版的按键表里塞——这是 NeoForge 提供的入口，不是绕过。
            Minecraft.getInstance().options.keyMappings =
                    append(Minecraft.getInstance().options.keyMappings, mapping);
            return;
        }
        pendingKeys.add(mapping);
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        keysReady = true;
        for (KeyMapping mapping : pendingKeys) {
            event.register(mapping);
        }
        pendingKeys.clear();
    }

    private static KeyMapping[] append(KeyMapping[] original, KeyMapping extra) {
        KeyMapping[] out = new KeyMapping[original.length + 1];
        System.arraycopy(original, 0, out, 0, original.length);
        out[original.length] = extra;
        return out;
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void onHudRender(HudLayer layer) {
        hudLayers.add(layer);
    }

    @Override
    public void onWorldRenderAfterTranslucent(WorldLayer layer) {
        translucentLayers.add(layer);
    }

    @Override
    public void onWorldRenderLast(WorldLayer layer) {
        lastLayers.add(layer);
    }

    // ------------------------------------------------------------------ 环境

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String modVersion() {
        return ModList.get().getModContainerById("pvpocket")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("?");
    }

    /**
     * NeoForge 的渲染事件 → 平台的中立 record。
     *
     * <p>事件本身不带 {@code MultiBufferSource} 与 {@code LightTexture}，所以这两项从
     * {@link Minecraft} 上取——它们本来就是全局单例，Fabric 那边只是碰巧由上下文递了过来。
     * 这也说明 {@code WorldFrame} 那六个字段是交集而不是 Fabric 的形状。</p>
     */
    private static WorldFrame frame(RenderLevelStageEvent event) {
        Minecraft client = Minecraft.getInstance();
        PoseStack poses = event.getPoseStack();
        MultiBufferSource buffers = client.renderBuffers().bufferSource();
        Camera camera = event.getCamera();
        DeltaTracker delta = event.getPartialTick();
        LightTexture lightmap = client.gameRenderer.lightTexture();
        ClientLevel level = client.level;
        return new WorldFrame(level, poses, buffers, camera, delta, lightmap);
    }
}
