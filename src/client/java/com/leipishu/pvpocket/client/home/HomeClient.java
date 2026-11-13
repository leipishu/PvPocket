package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.SpectatorInput;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.network.HomeActionPayload;
import com.leipishu.pvpocket.network.HomeRequestPayload;
import com.leipishu.pvpocket.network.HomeStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Client half of the home screen: its key binding, its requests and the tick that opens it.
 *
 * <p>The hotkey opens the screen only when no other screen is open, so a rule draft is never hidden
 * behind it. The default key is {@code B}; {@code P} stays with vanilla Social Interactions.
 */
public final class HomeClient {

    private static KeyMapping home;

    private HomeClient() {
    }

    public static void register() {
        home = new KeyMapping(
                "key.pvpocket.home",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "key.categories.pvpocket");
        PvClientPlatform.get().registerKeyMapping(home);

        PvClientPlatform.get().onClientTick(() -> {
            Minecraft client = Minecraft.getInstance();
            // Drained in a loop, so two presses in one tick count as two intents.
            while (home.consumeClick()) {
                if (client.screen == null && client.player != null) {
                    client.setScreen(new HomeScreen());
                }
            }
        });
    }

    public static KeyMapping homeKey() {
        return home;
    }

    /** The other keys the settings page can rebind, so one place lists every key this mod adds. */
    public static List<KeyMapping> extraKeys() {
        return SpectatorInput.bindings();
    }

    /**
     * Asks the server for a state snapshot.
     *
     * <p>Best effort: a server without the channel is skipped and the screen keeps its local
     * content.
     */
    public static void request() {
        if (PvClientPlatform.get().canSend(HomeRequestPayload.ID)) {
            PvClientPlatform.get().send(new HomeRequestPayload());
        }
    }

    public static void send(HomeActionPayload payload) {
        if (PvClientPlatform.get().canSend(HomeActionPayload.ID)) {
            PvClientPlatform.get().send(payload);
        }
    }

    /** Sends a spectate request through the channel the match list uses. */
    public static void send(com.leipishu.pvpocket.network.SpectateRequestPayload payload) {
        if (PvClientPlatform.get().canSend(com.leipishu.pvpocket.network.SpectateRequestPayload.ID)) {
            PvClientPlatform.get().send(payload);
        }
    }

    /**
     * Returns true when the server speaks the home protocol; actions that need it depend on this.
     *
     * <p>Checked on the request channel, which travels client to server. The snapshot channel only
     * travels server to client, so {@code canSend} on it answers false on every server.
     */
    public static boolean supported() {
        return PvClientPlatform.get().canSend(HomeRequestPayload.ID);
    }

    /** Opens the screen if it is legal to, for the inventory button and for command aliases. */
    public static void open() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null && client.player != null) {
            client.setScreen(new HomeScreen());
        }
    }
}
