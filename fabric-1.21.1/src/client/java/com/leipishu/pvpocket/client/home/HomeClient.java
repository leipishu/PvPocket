package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.SpectatorInput;
import com.leipishu.pvpocket.network.HomeActionPayload;
import com.leipishu.pvpocket.network.HomeRequestPayload;
import com.leipishu.pvpocket.network.HomeStatePayload;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The client half of the home screen: its key, its request, and the tick that opens it.
 *
 * <p><b>The hotkey only fires with no screen open.</b> That is not politeness — opening the home
 * screen over a rule draft would leave the draft panel's unsent edits behind a screen that has no way
 * back to them, and the player would have to close the home screen to discover the draft is still
 * there. One condition prevents a whole class of "where did my edits go".</p>
 *
 * <p>Default key is {@code B}. Not {@code P}: vanilla binds that to Social Interactions, and a mod
 * that silently owns a vanilla key is a mod that breaks a screen the player did not know they had.</p>
 */
public final class HomeClient {

    private static KeyMapping home;

    private HomeClient() {
    }

    public static void register() {
        home = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.pvpocket.home",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "key.categories.pvpocket"));

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    // Drained in a loop for the same reason the spectator keys are: two presses in
                    // one tick are two intents.
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
     * Asks the server for a snapshot.
     *
     * <p>Best effort: a server without the channel gets nothing and the screen keeps its local
     * content. The alternative — waiting for an answer that will never come — would leave a permanent
     * "loading" line on a single-player world with an older build.</p>
     */
    public static void request() {
        if (ClientPlayNetworking.canSend(HomeRequestPayload.ID)) {
            ClientPlayNetworking.send(new HomeRequestPayload());
        }
    }

    public static void send(HomeActionPayload payload) {
        if (ClientPlayNetworking.canSend(HomeActionPayload.ID)) {
            ClientPlayNetworking.send(payload);
        }
    }

    /** Spectating from the home screen goes through the channel the match list already uses. */
    public static void send(com.leipishu.pvpocket.network.SpectateRequestPayload payload) {
        if (ClientPlayNetworking.canSend(com.leipishu.pvpocket.network.SpectateRequestPayload.ID)) {
            ClientPlayNetworking.send(payload);
        }
    }

    /**
     * True when the server speaks the home protocol, used to disable actions that need it.
     *
     * <p>Asked of the <b>request</b> channel, not the snapshot: {@code canSend} answers "is this
     * channel declared in the direction I would be sending", and the snapshot only ever travels
     * server-to-client — so asking about it returns false on every server, including a perfectly
     * current one. That mistake showed up as a permanently greyed-out "start a duel" button on a
     * server that answered the request in the same tick.</p>
     */
    public static boolean supported() {
        return ClientPlayNetworking.canSend(HomeRequestPayload.ID);
    }

    /** Opens the screen if it is legal to, for the inventory button and for command aliases. */
    public static void open() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null && client.player != null) {
            client.setScreen(new HomeScreen());
        }
    }
}
