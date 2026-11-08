package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * A small status pill: one short word in a tinted capsule.
 *
 * <p>The same shape the rule panel's title row uses for its revision ({@code v0}, {@code v1}) — 14px
 * tall, 3px radius, a tinted fill and the accent as text. It was inlined there first and the home
 * panel needed the same thing, so it is a widget now rather than a second copy of six lines.</p>
 *
 * <p>A pill rather than loose coloured text because at 12px of amber a bare word reads as part of
 * whatever sentence it sits next to. The capsule gives it an edge and says "this is a separate
 * fact".</p>
 *
 * <p>The width is measured in the constructor and written to the ordinary {@code width} field, not
 * computed on demand: the audit reads that field to decide whether a control overlaps its row, and a
 * widget that only knows its size while drawing is a widget the audit measures as zero.</p>
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
     * The width a pill with this label would take.
     *
     * <p>For callers that only need to reserve room — the rule panel's revision chip is positioned
     * right-anchored against a button and drawn inline, so it never becomes a widget, but it still
     * has to agree with this one on how wide "v0" is.</p>
     */
    public static int widthOf(String label) {
        return MinecraftClient.getInstance().textRenderer.getWidth(label) + PAD * 2;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        PvDraw.roundRect(ctx, x, y, width, H, 3, fillColor);
        // The pill's width comes from this same label, so it normally draws at 1.0×; the shrink is
        // there for a caller that pinned the width to something narrower.
        PvDraw.textFitCenter(ctx, tr, label, x + width / 2, y + (H - 8) / 2 + 1, width - PAD * 2,
                textColor);
    }
}
