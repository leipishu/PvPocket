package com.leipishu.pvpocket.client.platform.fabric;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//?}
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?} else {
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//?}
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if >=26.1 {
import net.minecraft.resources.ResourceLocation;
//?}

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Fabric implementation of {@link PvClientPlatform}: forwards client events to the shared wiring.
 */
public final class FabricClientPlatform implements PvClientPlatform {

//? if >=26.1 {
    /**
     * Suffix for the next HUD element id.
     *
     * <p>The 26.1 HUD registry refuses a duplicate id, and every HUD layer registers through the
     * same method, so the counter is what keeps three layers apart.
     */
    private int hudIds;
//?}

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
//? if >=26.1 {
        KeyMappingHelper.registerKeyMapping(mapping);
//?} else {
        KeyBindingHelper.registerKeyBinding(mapping);
//?}
    }

    @Override
    public void onHudRender(HudLayer layer) {
//? if >=26.1 {
        // 26.1 replaced the single HUD callback with an ordered element registry, so each layer
        // needs its own id, and the callback method is named after the extract phase.
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("pvpocket", "hud" + hudIds++);
        HudElementRegistry.addLast(id, (graphics, delta) -> layer.draw(graphics, delta));
//?} else {
        HudRenderCallback.EVENT.register(layer::draw);
//?}
    }

    @Override
    public void onWorldRenderAfterTranslucent(WorldLayer layer) {
//? if >=26.1 {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> layer.render(frame(context)));
//?} elif >=1.21.11 {
        WorldRenderEvents.END_MAIN.register(context -> layer.render(frame(context)));
//?} else {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> layer.render(frame(context)));
//?}
    }

    @Override
    public void onWorldRenderLast(WorldLayer layer) {
//? if >=26.1 {
        // 26.1 renamed the stage list and split the translucent pass into terrain and features.
        // END_MAIN is still the last hook, and both layers land on it in registration order.
        LevelRenderEvents.END_MAIN.register(context -> layer.render(frame(context)));
//?} elif >=1.21.11 {
        // 1.21.11 has no separate after-translucent stage: END_MAIN is the last world hook, and
        // both layers land on it in registration order.
        WorldRenderEvents.END_MAIN.register(context -> layer.render(frame(context)));
//?} else {
        WorldRenderEvents.LAST.register(context -> layer.render(frame(context)));
//?}
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
     * <p>All six fields come from the loader's render context or the client singletons.
     * Reading a field here requires the NeoForge side to supply it too, and
     * {@code RenderLevelStageEvent} has no {@code LightTexture}.
     */
//? if >=26.1 {
    private static WorldFrame frame(LevelRenderContext context) {
        // The context kept the stack, the buffers and the submit queue; level, camera, tick and
        // lightmap come from the client singletons, and the lightmap is a bare texture view now.
        Minecraft client = Minecraft.getInstance();
        return new WorldFrame(
                client.level,
                context.poseStack(),
                context.bufferSource(),
                client.gameRenderer.getMainCamera(),
                client.getDeltaTracker(),
                client.gameRenderer.lightmap(),
                context.submitNodeCollector());
    }
//?} elif >=1.21.11 {
    private static WorldFrame frame(WorldRenderContext context) {
        // The context carries the stack and the buffers; level, camera, tick and lightmap are
        // client singletons, and the submit queue is what turns immediate draws into real output.
        Minecraft client = Minecraft.getInstance();
        return new WorldFrame(
                client.level,
                context.matrices(),
                context.consumers(),
                client.gameRenderer.getMainCamera(),
                client.getDeltaTracker(),
                client.gameRenderer.lightTexture(),
                context.commandQueue());
    }
//?} else {
    private static WorldFrame frame(WorldRenderContext context) {
        return new WorldFrame(
                context.world(),
                context.matrixStack(),
                context.consumers(),
                context.camera(),
                context.tickCounter(),
                context.lightmapTextureManager(),
                null);
    }
//?}
}
