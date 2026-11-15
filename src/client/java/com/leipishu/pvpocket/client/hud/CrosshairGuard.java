package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the four corner brackets of the 1.8 block indicator around the crosshair.
 *
 * <p>Pure drawing, so the {@code Gui} hook and the {@code Hud} hook share it while the injection
 * point follows the version. Drawn in the mod's warm accent, dimmed against the crosshair.
 */
public final class CrosshairGuard {

    /** Length of each bracket arm, in GUI pixels. */
    private static final int ARM = 4;

    /** Half the box around the 15x15 crosshair. */
    private static final int SPAN = 11;

    /** The accent {@code #F5A524} at 72% alpha. */
    private static final int GUARD_COLOR = 0xB8F5A524;

    private CrosshairGuard() {
    }

    /** Draws the brackets around the current crosshair when the local player is blocking. */
    public static void draw(GuiGraphics context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !CombatState.isBlocking(client.player.getUUID())) {
            return;
        }
        int centerX = context.guiWidth() / 2;
        int centerY = context.guiHeight() / 2;
        int left = centerX - SPAN;
        int top = centerY - SPAN;
        int right = centerX + SPAN;
        int bottom = centerY + SPAN;
        // Eight 1px bars, one horizontal and one vertical arm per corner. fill() is exact at 1px.
        context.fill(left, top, left + ARM, top + 1, GUARD_COLOR);
        context.fill(left, top, left + 1, top + ARM, GUARD_COLOR);
        context.fill(right - ARM, top, right, top + 1, GUARD_COLOR);
        context.fill(right - 1, top, right, top + ARM, GUARD_COLOR);
        context.fill(left, bottom - 1, left + ARM, bottom, GUARD_COLOR);
        context.fill(left, bottom - ARM, left + 1, bottom, GUARD_COLOR);
        context.fill(right - ARM, bottom - 1, right, bottom, GUARD_COLOR);
        context.fill(right - 1, bottom - ARM, right, bottom, GUARD_COLOR);
    }
}
