package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.client.hud.PossessionHud;
import com.leipishu.pvpocket.client.hud.SpectatorHud;
import com.leipishu.pvpocket.network.SpectateRequestPayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.client.compat.PvKeys;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * The two keys a spectator needs: move the camera to the next body, and leave it.
 *
 * <p>{@code V} cycles and {@code G} detaches; {@code G} is unbound in vanilla. Requests are sent
 * only while the spectator bar is up, so a player who is not spectating cannot send them.
 */
public final class SpectatorInput {

    private static KeyMapping cycle;
    private static KeyMapping detach;

    private SpectatorInput() {
    }

    /**
     * Both spectator keys, for the settings page.
     *
     * <p>Rebinding goes through vanilla's controls screen, the same mechanism as the mod's other
     * keys.
     */
    public static java.util.List<KeyMapping> bindings() {
        if (cycle == null || detach == null) {
            return java.util.List.of();
        }
        return java.util.List.of(cycle, detach);
    }

    public static void register() {
        // The category is a translation key, as in vanilla's own controls headings.
        cycle = PvKeys.key("key.pvpocket.cycle_spectator", GLFW.GLFW_KEY_V);
        detach = PvKeys.key("key.pvpocket.detach_spectator", GLFW.GLFW_KEY_G);
        // Registering lists the keys in the controls page; the tick poll below fires them.
        PvClientPlatform.get().registerKeyMapping(cycle);
        PvClientPlatform.get().registerKeyMapping(detach);

        PvClientPlatform.get().onClientTick(() -> {
            // Drained in a loop: two presses inside one tick are two intents.
            while (cycle.consumeClick()) {
                send(SpectateRequestPayload.cycle());
            }
            while (detach.consumeClick()) {
                // Only meaningful while a body is possessed; a free camera ignores it.
                if (PossessionHud.active()) {
                    send(SpectateRequestPayload.detach());
                }
            }
        });
    }

    private static void send(SpectateRequestPayload payload) {
        if (SpectatorHud.active() && PvClientPlatform.get().canSend(SpectateRequestPayload.ID)) {
            PvClientPlatform.get().send(payload);
        }
    }
}
