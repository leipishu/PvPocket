package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.client.hud.PossessionHud;
import com.leipishu.pvpocket.client.hud.SpectatorHud;
import com.leipishu.pvpocket.network.SpectateRequestPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * The two keys a spectator needs: move the camera on, and get out of it.
 *
 * <p>Vanilla spectator mode already lets you attach to whatever you are looking at, which is fine
 * when you are hunting for a player in a world and useless when there are exactly two of them and
 * you want to see the other side of the exchange. A cycle key answers the actual question — "what
 * did that look like from where he was standing" — in one press.</p>
 *
 * <p><b>Why a second key for detaching.</b> Attaching the camera replaces the spectator's own view
 * entirely: their body is still in the arena but they cannot see it, cannot fly it, and cannot get
 * back to it. Vanilla's escape hatch for this is buried in controls most players never open, so a
 * dedicated key is the difference between "I am watching Dev1" and "I am stuck in Dev1". {@code G}
 * is unbound in vanilla and sits next to {@code V} on the same hand.</p>
 *
 * <p>Requests are only sent while the spectator bar is up, so a player who is not spectating cannot
 * spam the server with actions it would have to refuse.</p>
 */
@Environment(EnvType.CLIENT)
public final class SpectatorInput {

    private static KeyBinding cycle;
    private static KeyBinding detach;

    private SpectatorInput() {
    }

    public static void register() {
        cycle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pvpocket.cycle_spectator",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                // A key, not a name: the controls screen translates the category with
                // Text.translatable, which is how vanilla's own "Movement" heading is produced.
                "key.categories.pvpocket"));
        detach = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pvpocket.detach_spectator",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "key.categories.pvpocket"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Drained in a loop rather than checked once: two presses inside one tick are two
            // intents, and dropping one makes the key feel unreliable at high frame rates.
            while (cycle.wasPressed()) {
                send(SpectateRequestPayload.cycle());
            }
            while (detach.wasPressed()) {
                // Only meaningful while a body is being ridden. Sending it from the detached state
                // would be a no-op round trip, and the bar already says "free camera" there.
                if (PossessionHud.active()) {
                    send(SpectateRequestPayload.detach());
                }
            }
        });
    }

    private static void send(SpectateRequestPayload payload) {
        if (SpectatorHud.active() && ClientPlayNetworking.canSend(SpectateRequestPayload.ID)) {
            ClientPlayNetworking.send(payload);
        }
    }
}
