package com.leipishu.pvpocket.client.hud;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The small rounded plate the HUD puts in a screen corner.
 *
 * <p>Shared by the match bar and the result banner so the two read as one surface: same height,
 * same padding, same radius. A plate is as wide as its own text, measured from the string rather
 * than from a fixed width, because a label narrower than its text draws the overflow past its own
 * edge and the corner labels change with the language.
 */
final class HudPlate {

    /** Plate height; the text is centred inside it. */
    static final int H = 12;
    /** Inner padding either side of the text. */
    static final int PAD_X = 5;
    /** Gap between a plate and the screen edge, measured from the plate's own edge. */
    static final int MARGIN = 4;
    /** Gap the top edge keeps, so the plates and a centred bar share one baseline. */
    static final int TOP = 3;

    private HudPlate() {
    }

    /** Width of a plate holding {@code text}: the string plus its own padding. */
    static int width(Font tr, String text) {
        return tr.width(text) + PAD_X * 2;
    }

    /**
     * Draws one plate, left-anchored, sized to its own text.
     *
     * @param alpha 0..1 multiplier on the plate's fill, for the entrance fade
     */
    static void draw(GuiGraphics ctx, Font tr, String text, int x, int y, int colour, float alpha) {
        PvDraw.roundRect(ctx, x, y, width(tr, text), H, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.CARD, 0.72F * alpha));
        PvDraw.text(ctx, tr, text, x + PAD_X, y + (H - 8) / 2 + 1, PvTheme.alpha(colour, alpha));
    }
}
