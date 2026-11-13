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
 * NeoForge implementation of {@link PvClientPlatform}.
 *
 * <p>Same shape as {@code FabricClientPlatform}. Key mapping registration timing and world
 * render stage names are the two largest differences; both carry the same meaning under a
 * different API shape, which this class absorbs.
 */
public final class NeoForgeClientPlatform implements PvClientPlatform {

    private final NeoForgePlatform server;
    private final IEventBus modBus;

    /**
     * Key mappings that arrive before {@link RegisterKeyMappingsEvent} fires.
     *
     * <p>The event fires once during mod initialization, while {@code HomeClient.register()} may
     * run before or after it. Buffering is safe either way: the event registers what is present,
     * and later arrivals are registered directly.
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
        // The two stages are subscribed separately: after translucent blocks and after the level.
        // Fabric names them AFTER_TRANSLUCENT and LAST, at the same positions.
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

    // ------------------------------------------------------------------ networking

    @Override
    public <T extends CustomPacketPayload> void onServerPacket(
            CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        // The handler goes into the server-side table because playToClient hands every payload
        // over in one pass inside RegisterPayloadHandlersEvent, shared by both interfaces.
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

    // ------------------------------------------------------------------ lifecycle

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

    // ------------------------------------------------------------------ input

    @Override
    public void registerKeyMapping(KeyMapping mapping) {
        if (keysReady) {
            // The event has already fired, so append to the vanilla key table via NeoForge.
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

    // ------------------------------------------------------------------ rendering

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

    // ------------------------------------------------------------------ environment

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
     * Converts a NeoForge render event into the platform-neutral {@link WorldFrame}.
     *
     * <p>The event carries neither {@code MultiBufferSource} nor {@code LightTexture}, so both
     * come from {@link Minecraft}, where they are global singletons. That makes the six
     * {@code WorldFrame} fields an intersection rather than the Fabric shape.
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
