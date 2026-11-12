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
 * Fabric 的客户端平台实现。只做转发，例外只有两处，都在注释里说明了为什么。
 */
public final class FabricClientPlatform implements PvClientPlatform {

    @Override
    public String name() {
        return "fabric";
    }

    @Override
    public <T extends CustomPacketPayload> void onServerPacket(
            CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        // 第一处例外：跳线程。Fabric 的 receiver 跑在 netty 线程上，碰任何客户端状态都是未定义
        // 行为（界面会在渲染线程读同一批字段）。这个跳转属于平台，所以接收方的代码里看不到
        // `client.execute`。
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
        // 第二处例外：Fabric 要求把实例交给它登记，之后原版才会在控制页里列出这个键。返回值就是
        // 传进去的那个实例，所以这里丢掉它不损失信息。
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
     * Fabric 的上下文 → 平台的中立 record。
     *
     * <p>六个字段逐一对应，没有遗漏也没有编造：{@code WorldRenderContext} 在 1.21.1 上正好提供
     * 这六项。多取一个字段就等于要求 NeoForge 那边也提供它，而那边的
     * {@code RenderLevelStageEvent} 并不带 {@code LightTexture}——到时候这里就是编译不过的地方，
     * 也是唯一需要改的地方。</p>
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
