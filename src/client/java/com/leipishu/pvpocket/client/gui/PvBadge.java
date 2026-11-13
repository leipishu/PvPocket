package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A small status pill: one short word in a tinted capsule.
 *
 * <p>14px tall with a 3px radius, a tinted fill and the text colour passed to the constructor.
 *
 * <p>The width is measured in the constructor and stored in {@code width}, so the layout audit can
 * measure the widget without rendering it.
 */
public class PvBadge extends PvWidget {

    /** Fixed height, so a row can centre it against a toggle without measuring text. */
    public static final int H = 14;
    /** Horizontal padding inside the capsule. */
    private static final int PAD = 6;

    private final String label;
    private final int textColor;
    private final int fillColor;

    public PvBadge(String label) {
        this(label, PvTheme.ACCENT, PvTheme.ACCENT_SOFT);
    }

    public PvBadge(String label, int textColor, int fillColor) {
        this.label = label;
        this.textColor = textColor;
        this.fillColor = fillColor;
        this.height = H;
        this.width = widthOf(label);
    }

    /**
     * Returns the width a pill with this label would take.
     *
     * <p>For callers that reserve room without creating a widget.
     */
    public static int widthOf(String label) {
        return Minecraft.getInstance().font.width(label) + PAD * 2;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        Font tr = Minecraft.getInstance().font;
        PvDraw.roundRect(ctx, x, y, width, H, 3, fillColor);
        // The pill width comes from this label, so it normally draws at 1.0x. The shrink applies
        // when a caller pinned the width narrower than the text.
        PvDraw.textFitCenter(ctx, tr, label, x + width / 2, y + (H - 8) / 2 + 1, width - PAD * 2,
                textColor);
    }
}
