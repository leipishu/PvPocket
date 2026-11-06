package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing primitives for the custom GUI layer.
 *
 * <p>Everything is drawn from code — no nine-patch textures, no vanilla widget skins. That means
 * the look survives any GUI scale and any resource pack, and there is no asset to get wrong at
 * 1×/2×/3×/4×.</p>
 *
 * <h2>Why the rounded rectangles are anti-aliased</h2>
 *
 * <p>A rounded rectangle drawn as whole-pixel rows has a staircase at every corner, and on a
 * near-black surface a hard staircase is the strongest "pixel art" cue in the whole panel. Each
 * corner row is therefore drawn in three parts: the column the true edge passes through, painted at
 * partial alpha equal to its exact coverage, and the columns fully inside, painted solid. The edge
 * position comes from the circle equation rather than a lookup table, so it is exact at any
 * radius.</p>
 *
 * <h2>Why the split is by row</h2>
 *
 * <p>A rounded rectangle used to be drawn as a full-width corner band plus a full-height middle
 * band. Two opaque fills over each other are invisible; two 14%-alpha fills are not — they compose
 * to 26%, which put a darker bar along the top and bottom of every tinted chip in the interface.
 * The correction was to split the shape so no pixel is painted twice, but it was applied
 * horizontally (left segment, middle band, right segment) and the side strips of the rows between
 * the corners were never covered. The split has to be vertical: the straight band owns the rows the
 * corners cannot reach, the corner rows own the rest, and the two sets are disjoint by
 * construction.</p>
 */
public final class PvDraw {

    private PvDraw() {
    }

    // ------------------------------------------------------------------ shapes

    public static void rect(DrawContext ctx, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        ctx.fill(x, y, x + w, y + h, color);
    }

    public static void roundRect(DrawContext ctx, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        if (r <= 0) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        // Straight band: the rows the corners never reach, at full width.
        if (h - 2 * r > 0) {
            ctx.fill(x, y + r, x + w, y + h - r, color);
        }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5D;
            double inset = r - Math.sqrt(Math.max(0D, (double) r * r - dy * dy));
            aaRow(ctx, x, w, y + i, inset, color);
            aaRow(ctx, x, w, y + h - i - 1, inset, color);
        }
    }

    /**
     * One corner row: both edge columns at partial alpha, everything between them solid.
     *
     * <p>{@code inset} is how far the true edge sits inside the bounding box on this row. The column
     * it lands in gets alpha equal to the fraction of that column inside the shape; the run between
     * the two edges is fully inside and is painted solid.</p>
     *
     * <p>The split is by row, not by column: corner rows own the top and bottom bands entirely, the
     * straight band owns every row in between entirely. An earlier version split each corner row
     * into a left segment and a right segment and let a full-height middle band cover the rest,
     * which left the side strips of every row between the corners unpainted — a dark notch down
     * both edges of every button, and for shapes narrow enough that {@code w - 2r == 0} (a 2px
     * marker, a 6px scrollbar) the entire body vanished.</p>
     */
    private static void aaRow(DrawContext ctx, int x, int w, int y, double inset, int color) {
        int a = (int) Math.floor(inset);
        float frac = (float) (1D - (inset - a));
        int start = a;
        if (frac < 0.98F) {
            start = a + 1;
            if (frac > 0.02F) {
                ctx.fill(x + a, y, x + a + 1, y + 1, PvTheme.alpha(color, frac));
                ctx.fill(x + w - a - 1, y, x + w - a, y + 1, PvTheme.alpha(color, frac));
            }
        }
        int end = w - start;
        if (end > start) {
            ctx.fill(x + start, y, x + end, y + 1, color);
        }
    }

    /** Rounded rectangle with a 1px border: filled with {@code border}, then inset with {@code fill}. */
    public static void roundPanel(DrawContext ctx, int x, int y, int w, int h, int radius,
                                  int border, int fill) {
        roundRect(ctx, x, y, w, h, radius, border);
        roundRect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), fill);
    }

    /**
     * Border only, {@code thickness} pixels thick, anti-aliased on the outside.
     *
     * <p>Not "outer rounded rect plus transparent inner rect": a transparent fill draws nothing, so
     * that version produced a solid block of the ring colour over whatever it was supposed to
     * outline. The ring is rasterised row by row instead — a top and bottom band across the full
     * width, and two vertical segments per row everywhere else — with the outer edge column painted
     * at its exact coverage.</p>
     */
    public static void ring(DrawContext ctx, int x, int y, int w, int h, int radius, int thickness,
                            int color) {
        if (w <= 0 || h <= 0 || thickness <= 0 || (color >>> 24) == 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        int t = Math.min(thickness, Math.min(w, h) / 2);
        for (int j = 0; j < h; j++) {
            double inset = 0D;
            if (j < r) {
                double dy = r - j - 0.5D;
                inset = r - Math.sqrt(Math.max(0D, (double) r * r - dy * dy));
            } else if (j >= h - r) {
                double dy = r - (h - j - 1) - 0.5D;
                inset = r - Math.sqrt(Math.max(0D, (double) r * r - dy * dy));
            }
            int a = (int) Math.floor(inset);
            float frac = (float) (1D - (inset - a));
            int start = frac >= 0.98F ? a : a + 1;
            if (frac > 0.02F && frac < 0.98F) {
                ctx.fill(x + a, y + j, x + a + 1, y + j + 1, PvTheme.alpha(color, frac));
                ctx.fill(x + w - a - 1, y + j, x + w - a, y + j + 1, PvTheme.alpha(color, frac));
            }
            int end = w - start;
            if (end <= start) {
                continue;
            }
            if (j < t || j >= h - t) {
                ctx.fill(x + start, y + j, x + end, y + j + 1, color);
            } else {
                ctx.fill(x + start, y + j, x + start + t, y + j + 1, color);
                ctx.fill(x + end - t, y + j, x + end, y + j + 1, color);
            }
        }
    }

    public static void hLine(DrawContext ctx, int x, int y, int w, int color) {
        rect(ctx, x, y, w, 1, color);
    }

    public static void vLine(DrawContext ctx, int x, int y, int h, int color) {
        rect(ctx, x, y, 1, h, color);
    }

    /** A filled circle, used for status dots and slider knobs. */
    public static void dot(DrawContext ctx, int cx, int cy, int r, int color) {
        roundRect(ctx, cx - r, cy - r, r * 2, r * 2, r, color);
    }

    /**
     * Two-layer soft edge for surfaces that sit over the world instead of over the scrim.
     *
     * <p>Panels no longer use this: behind a 77% scrim a shadow is invisible. HUD bars still need
     * it, because their background is whatever the player is standing in front of.</p>
     */
    public static void shadow(DrawContext ctx, int x, int y, int w, int h, int radius) {
        roundRect(ctx, x - 2, y - 1, w + 4, h + 5, radius + 2, 0x30000000);
        roundRect(ctx, x - 1, y, w + 2, h + 3, radius + 1, 0x50000000);
    }

    // ------------------------------------------------------------------ glyphs

    /**
     * A chevron pointing along {@code dir} (+1 right, -1 left), {@code height} pixels tall.
     *
     * <p>Two pixels thick, drawn row by row. The stroke is deliberately not anti-aliased: a smoothed
     * 1.5px diagonal at GUI scale 1 turns into grey mush, while two hard pixels stay two hard pixels
     * at every scale.</p>
     */
    public static void chevron(DrawContext ctx, int cx, int cy, int dir, int height, int color) {
        int half = Math.max(1, height / 2);
        int top = cy - half;
        for (int i = 0; i < height; i++) {
            int reach = half - Math.abs(i - half);
            int x = dir > 0 ? cx - half / 2 - 1 + reach : cx + half / 2 - 1 - reach;
            ctx.fill(x, top + i, x + 2, top + i + 1, color);
        }
    }

    public static void plus(DrawContext ctx, int cx, int cy, int arm, int thickness, int color) {
        int h = thickness / 2;
        rect(ctx, cx - arm, cy - h, arm * 2 + 1, thickness, color);
        rect(ctx, cx - h, cy - arm, thickness, arm * 2 + 1, color);
    }

    public static void cross(DrawContext ctx, int cx, int cy, int arm, int color) {
        for (int i = -arm; i <= arm; i++) {
            ctx.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            ctx.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color);
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

    /**
     * Panel title: the body face in bold, at 1.0 scale.
     *
     * <p>It used to be the same face at 1.3×, which is a bitmap font pushed through a GL scale — the
     * glyph pixels stop landing on the screen grid and the result reads as a low-resolution texture
     * stretched up. Bold at native size is the only way to get a heavier title without that blur;
     * hierarchy then comes from weight and colour rather than from size.</p>
     */
    public static void title(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        ctx.drawText(tr, titleText(s), x, y, color, false);
    }

    public static int titleWidth(TextRenderer tr, String s) {
        return tr.getWidth(titleText(s));
    }

    private static Text titleText(String s) {
        return Text.literal(s).formatted(Formatting.BOLD);
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
