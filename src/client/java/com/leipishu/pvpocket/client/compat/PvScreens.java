package com.leipishu.pvpocket.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The current screen, and the call that replaces it.
 *
 * <p>26.2 moved the whole screen stack off {@code Minecraft} and onto {@code Gui}: the field
 * {@code screen} became {@code gui.screen()}, and {@code setScreen} became {@code gui.setScreen},
 * with the same body it had before (the null case still falls back to the title screen when no
 * level is loaded). Call sites name the intent so the move stays in one file.
 */
public final class PvScreens {

    private PvScreens() {
    }

    /** The screen on show, or null when the player is looking at the world. */
    public static Screen current(Minecraft client) {
//? if >=26.2 {
        return client.gui.screen();
//?} else {
        return client.screen;
//?}
    }

    /**
     * Replaces the current screen.
     *
     * <p>A null argument returns to the world, or to the title screen when no level is loaded.
     */
    public static void open(Minecraft client, Screen screen) {
//? if >=26.2 {
        client.gui.setScreen(screen);
//?} else {
        client.setScreen(screen);
//?}
    }
}
