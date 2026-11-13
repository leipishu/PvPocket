package com.leipishu.pvpocket.client.platform.fabric;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Fabric implementation of {@link PvClientPlatform}: forwards client events to the shared wiring.
 */
public final class FabricClientPlatform implements PvClientPlatform {

    @Override
    public String name() {
        return "fabric";
    }

    @Override
    public <T extends CustomPacketPayload> void onServerPacket(
            CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        // Fabric delivers on the netty thread, where client state is off limits; the render thread
        // reads the same fields. The hop lives here so handlers never call client.execute.
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                context.client().execute(() -> handler.accept(payload)));
    }

    @Override
    public boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    @Override
    public void send(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public void onClientTick(Runnable action) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> action.run());
    }

    @Override
    public void onDisconnect(Runnable action) {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> action.run());
    }

    @Override
    public void onScreenInit(ScreenInit listener) {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                listener.init(client, screen));
    }

    @Override
    public void registerKeyMapping(KeyMapping mapping) {
        // Fabric requires the instance so vanilla can list the key on the controls screen; the
        // call hands back the same instance, so discarding the result loses nothing.
        KeyBindingHelper.registerKeyBinding(mapping);
    }

    @Override
    public void onHudRender(HudLayer layer) {
        HudRenderCallback.EVENT.register(layer::render);
    }

    @Override
    public void onWorldRenderAfterTranslucent(WorldLayer layer) {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> layer.render(frame(context)));
    }

    @Override
    public void onWorldRenderLast(WorldLayer layer) {
        WorldRenderEvents.LAST.register(context -> layer.render(frame(context)));
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer("pvpocket")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    /**
     * Converts a Fabric render context into the platform-neutral {@link WorldFrame}.
     *
     * <p>All six fields come from {@code WorldRenderContext}, which supplies exactly these.
     * Reading a field here requires the NeoForge side to supply it too, and
     * {@code RenderLevelStageEvent} has no {@code LightTexture}.
     */
    private static WorldFrame frame(WorldRenderContext context) {
        return new WorldFrame(
                context.world(),
                context.matrixStack(),
                context.consumers(),
                context.camera(),
                context.tickCounter(),
                context.lightmapTextureManager());
    }
}
