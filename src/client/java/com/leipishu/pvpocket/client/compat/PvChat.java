package com.leipishu.pvpocket.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Lines the client shows on its own account, without a server round trip.
 *
 * <p>26.1 split {@code Player.displayClientMessage} in two: a server player keeps the combined
 * form, while the client-side player only has the chat line, and the action bar moved to
 * {@code Gui}. The call sites name the intent instead of the shape.
 */
public final class PvChat {

    private PvChat() {
    }

    /** Adds one line to the chat box. */
    public static void chat(Component message) {
//? if >=26.1 {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(message);
        }
//?} else {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(message, false);
        }
//?}
    }

    /** Shows one line above the hotbar. */
    public static void actionBar(Component message) {
//? if >=26.1 {
        // The second argument is the fade-in flag, not "overlay"; false matches the old call.
        Minecraft.getInstance().gui.setOverlayMessage(message, false);
//?} else {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(message, true);
        }
//?}
    }
}
