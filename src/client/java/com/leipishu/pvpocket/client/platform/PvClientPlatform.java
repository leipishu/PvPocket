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
 * Client-side loader surface; the server half is {@code com.leipishu.pvpocket.platform.PvPlatform}.
 *
 * <p>Lives in {@code src/client} because every parameter comes from {@code net.minecraft.client},
 * which a dedicated server classpath must not resolve. Implementations live in
 * {@code src/client-fabric} and {@code src/client-neoforge}.
 *
 * <p>Only behaviour with the same meaning on both loaders belongs here. Implementations forward and
 * do not branch; a decision belongs in {@code PvPocketClient}.
 */
public interface PvClientPlatform {

    /** Loader name, used for logging and diagnostics ({@code fabric} / {@code neoforge}). */
    String name();

    // ---- network

    /**
     * Registers the handler for one payload type sent by the server.
     *
     * <p>The implementation delivers the callback on the client main thread, so the handler must
     * not call {@code client.execute}.
     */
    <T extends CustomPacketPayload> void onServerPacket(
            CustomPacketPayload.Type<T> type, Consumer<T> handler);

    /** Whether the server declared this direction. Decides whether a button is enabled. */
    boolean canSend(CustomPacketPayload.Type<?> type);

    void send(CustomPacketPayload payload);

    // ---- lifecycle

    /** Runs the action at the end of every client tick. Callers fetch {@link Minecraft}. */
    void onClientTick(Runnable action);

    /**
     * Registers a callback for leaving a world.
     *
     * <p>Fires before the level is discarded, from the same cleanup step on both loaders.
     */
    void onDisconnect(Runnable action);

    /**
     * Registers a callback that runs after a {@link Screen} completes {@code init()}.
     *
     * <p>The callback runs on every init, not once per screen: a window resize rebuilds the widget
     * list, and a button added only once disappears on the first resize.
     */
    void onScreenInit(ScreenInit listener);

    // ---- input

    /**
     * Registers a key binding.
     *
     * <p>The return value is ignored: both loaders store the given instance, and the caller
     * polls it with {@code consumeClick()}.
     */
    void registerKeyMapping(KeyMapping mapping);

    // ---- render

    /**
     * Runs after vanilla draws the HUD each frame.
     *
     * <p>{@link DeltaTracker} carries the partial tick together with the paused state, which a
     * plain float would drop.
     */
    void onHudRender(HudLayer layer);

    /**
     * Runs after the translucent pass of world rendering, before particles and weather.
     *
     * <p>NeoForge names this stage {@code RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS} and
     * Fabric names it {@code WorldRenderEvents.AFTER_TRANSLUCENT}; the position is the same.
     */
    void onWorldRenderAfterTranslucent(WorldLayer layer);

    /**
     * Runs at the end of world rendering, before the model view stack is popped.
     *
     * <p>Hand rendering needs the view rotation still on the stack; see the coordinate space notes
     * in {@code PossessedHandRenderer}.
     */
    void onWorldRenderLast(WorldLayer layer);

    // ---- environment

    /** Config directory, the {@code .minecraft/config} level, without {@code pvpocket}. */
    Path configDir();

    /**
     * The mod version string, for the settings page.
     *
     * <p>Each loader reads it from its own metadata: Fabric from {@code ModContainer}, NeoForge
     * from {@code ModList}.
     */
    String modVersion();

    // ---- callback shapes

    /** Runs after a screen initialises. {@code screen} is the instance created by this init. */
    @FunctionalInterface
    interface ScreenInit {
        void init(Minecraft client, Screen screen);
    }

    /** One HUD layer. */
    @FunctionalInterface
    interface HudLayer {
        void render(GuiGraphics graphics, DeltaTracker delta);
    }

    /** One world render pass. */
    @FunctionalInterface
    interface WorldLayer {
        void render(WorldFrame frame);
    }

    /**
     * The values available during one world render pass.
     *
     * <p>A record rather than a loader type: every field exists on both loaders' render events, so
     * renderers never name a loader class.
     */
    record WorldFrame(
            ClientLevel level,
            PoseStack poses,
            MultiBufferSource buffers,
            Camera camera,
            DeltaTracker delta,
            LightTexture lightmap) {
    }

    // ---- global handle

    /**
     * The platform installed by the client entrypoint.
     *
     * <p>Throws when called before {@link #install}.
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
