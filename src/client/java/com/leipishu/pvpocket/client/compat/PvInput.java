package com.leipishu.pvpocket.client.compat;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Mouse button numbers as the game reports them.
 *
 * <p>26.3 moved the window layer from GLFW to SDL, and SDL numbers the buttons from one: left is
 * 1, middle 2, right 3. A handler comparing against a literal keeps working on one side and
 * silently stops matching on the other, so every comparison goes through these constants.
 */
public final class PvInput {

    /** Left mouse button. */
    public static final int LEFT = InputConstants.MOUSE_BUTTON_LEFT;

    /** Right mouse button. */
    public static final int RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;

    /** Middle mouse button. */
    public static final int MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;

    private PvInput() {
    }
}
