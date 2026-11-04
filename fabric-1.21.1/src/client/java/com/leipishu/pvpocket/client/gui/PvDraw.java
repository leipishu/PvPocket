package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing primitives for the custom GUI layer.
 *
 * <p>Everything is drawn from code — no nine-patch textures, no vanilla widget skins. That means
 * the look survives any GUI scale and any resource pack, and there is no asset to get wrong at
 * 1×/2×/3×/4×.</p>
 *
 * <p>Rounded rectangles are built by filling a cross plus per-row corner strips, so the result is
 * exact at the pixel grid rather than an approximation from overlapping shapes.</p>
 */
public final class PvDraw {

    private PvDraw() {
    }

    // ------------------------------------------------------------------ shapes

    public static void rect(DrawContext ctx, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        ctx.fill(x, y, x + w, y + h, color);
    }

    public static void roundRect(DrawContext ctx, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        if (r <= 0) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        // Middle columns full height, side columns between the corners, then the corner strips.
        ctx.fill(x + r, y, x + w - r, y + h, color);
        ctx.fill(x, y + r, x + r, y + h - r, color);
        ctx.fill(x + w - r, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5D;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0D, (double) r * r - dy * dy)));
            ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            ctx.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    /** Rounded rectangle with a 1px border: filled with {@code border}, then inset with {@code fill}. */
    public static void roundPanel(DrawContext ctx, int x, int y, int w, int h, int radius,
                                  int border, int fill) {
        roundRect(ctx, x, y, w, h, radius, border);
        roundRect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
    }

    public static void hLine(DrawContext ctx, int x, int y, int w, int color) {
        rect(ctx, x, y, w, 1, color);
    }

    public static void vLine(DrawContext ctx, int x, int y, int h, int color) {
        rect(ctx, x, y, 1, h, color);
    }

    /** Soft drop shadow: a few progressively wider translucent rounded rects behind the panel. */
    public static void shadow(DrawContext ctx, int x, int y, int w, int h, int radius, int spread) {
        for (int i = spread; i >= 1; i--) {
            int a = (int) (0x12 * (1F - (float) (i - 1) / spread));
            roundRect(ctx, x - i, y - i + 1, w + i * 2, h + i * 2, radius + i, (a << 24));
        }
    }

    // ------------------------------------------------------------------ text

    public static void text(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        ctx.drawText(tr, s, x, y, color, false);
    }

    public static void textShadow(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        ctx.drawText(tr, s, x, y, color, true);
    }

    public static void textRight(DrawContext ctx, TextRenderer tr, String s, int right, int y, int color) {
        text(ctx, tr, s, right - tr.getWidth(s), y, color);
    }

    public static void textCenter(DrawContext ctx, TextRenderer tr, String s, int centerX, int y, int color) {
        text(ctx, tr, s, centerX - tr.getWidth(s) / 2, y, color);
    }

    /** Draws text at an arbitrary scale, anchored top-left. Used for titles. */
    public static void textScaled(DrawContext ctx, TextRenderer tr, String s, float x, float y,
                                  float scale, int color) {
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0F);
        m.scale(scale, scale, 1F);
        ctx.drawText(tr, s, 0, 0, color, false);
        m.pop();
    }

    public static int scaledWidth(TextRenderer tr, String s, float scale) {
        return Math.round(tr.getWidth(s) * scale);
    }

    /** Clips text to {@code maxWidth}, appending an ellipsis when it had to be cut. */
    public static String fit(TextRenderer tr, String s, int maxWidth) {
        if (tr.getWidth(s) <= maxWidth) {
            return s;
        }
        String ellipsis = "…";
        int budget = maxWidth - tr.getWidth(ellipsis);
        if (budget <= 0) {
            return "";
        }
        return tr.trimToWidth(s, budget) + ellipsis;
    }

    /**
     * Wraps a string to {@code maxWidth}, breaking on spaces where possible and on any character
     * otherwise. CJK text has no spaces, so a word-only wrapper would overflow on every label.
     */
    public static List<String> wrap(TextRenderer tr, String s, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                lines.add(line.toString());
                line.setLength(0);
                continue;
            }
            line.append(c);
            if (tr.getWidth(line.toString()) > maxWidth && line.length() > 1) {
                int breakAt = line.length() - 1;
                if (c != ' ') {
                    int space = line.lastIndexOf(" ");
                    if (space > 0 && space > breakAt - 12) {
                        breakAt = space;
                    }
                }
                String head = line.substring(0, breakAt);
                lines.add(head);
                String tail = line.substring(Math.min(line.length(), breakAt + 1)).trim();
                line.setLength(0);
                line.append(tail);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }
}
