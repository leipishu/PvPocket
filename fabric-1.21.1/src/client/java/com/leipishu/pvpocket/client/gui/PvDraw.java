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
 * near-black surface a hard staircase is the strongest "pixel art" cue in the whole panel. Every
 * shape here therefore goes through {@link PvRaster}, which takes the coverage of each boundary
 * pixel from a signed distance field rather than from a one-dimensional lookup — see that class for
 * why one axis is not enough on an arc.</p>
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

    /**
     * Whether item icons may be drawn this pass. Cleared while a modal is being drawn over the panel.
     *
     * <p>Item icons are not GUI pixels: {@code DrawContext.drawItem} translates to <b>z = 150</b> and
     * renders through a depth-tested item layer, while every panel fill, scrim and glyph goes into
     * the GUI layer at z = 0 with depth testing off. Depth testing means the icon wins on geometry,
     * not on draw order, so no scrim drawn afterwards can cover it — the scrim is simply further
     * away. Flushing the buffer first does not help either: the item layer is emitted after the GUI
     * layer regardless of when it was submitted.</p>
     *
     * <p>So a dialog cannot dim what is behind it while the things behind it are items. The only
     * reliable answer is to not submit them: {@code PvScreen} clears this for the roots pass when an
     * overlay is up, and widgets that draw items check it.</p>
     */
    public static boolean itemsVisible = true;

    /**
     * The one span sink, reused across calls.
     *
     * <p>A fresh sink per shape would allocate several dozen objects per frame, and the visual
     * specification forbids the animation layer from allocating. The GUI is single-threaded, so one
     * instance with a mutable target is enough.</p>
     */
    private static final SpanEmitter EMITTER = new SpanEmitter();

    private static final class SpanEmitter implements PvRaster.Sink {
        private DrawContext ctx;

        void begin(DrawContext target) {
            this.ctx = target;
        }

        @Override
        public void span(int x0, int y, int x1, int argb) {
            if (x1 > x0 && (argb >>> 24) != 0) {
                ctx.fill(x0, y, x1, y + 1, argb);
            }
        }
    }

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
        roundRect(ctx, x, y, w, h, radius, radius, radius, radius, color);
    }

    /**
     * A rounded rectangle whose four corners carry their own radius.
     *
     * <p>Used by the panel chrome: the content well is square at its top-left, top-right and
     * bottom-left (it abuts the sidebar and the header) and rounded at the bottom-right (it follows
     * the panel's own corner). Expressing that as one shape costs a quadrant test in the distance
     * field; expressing it as a clip costs a scissor state change and a whole extra pass.</p>
     */
    public static void roundRect(DrawContext ctx, int x, int y, int w, int h,
                                 int rTL, int rTR, int rBR, int rBL, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        EMITTER.begin(ctx);
        PvRaster.roundedRect(x, y, w, h, rTL, rTR, rBR, rBL, color, EMITTER);
    }

    /** Rounded rectangle with a 1px border: filled with {@code border}, then inset with {@code fill}. */
    public static void roundPanel(DrawContext ctx, int x, int y, int w, int h, int radius,
                                  int border, int fill) {
        if (w <= 0 || h <= 0) {
            return;
        }
        EMITTER.begin(ctx);
        PvRaster.roundedPanel(x, y, w, h, radius, 1, border, fill, EMITTER);
    }

    /**
     * Border only, {@code thickness} pixels thick, inset from the shape's own edge.
     *
     * <p>Not "outer rounded rect plus transparent inner rect": a transparent fill draws nothing, so
     * that version produced a solid block of the ring colour over whatever it was supposed to
     * outline. {@link PvRaster#ring} carves the inner shape out arithmetically instead.</p>
     */
    public static void ring(DrawContext ctx, int x, int y, int w, int h, int radius, int thickness,
                            int color) {
        if (w <= 0 || h <= 0 || thickness <= 0 || (color >>> 24) == 0) {
            return;
        }
        EMITTER.begin(ctx);
        PvRaster.ring(x, y, w, h, radius, thickness, color, EMITTER);
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

    /**
     * Records the run for the layout audit, then draws it.
     *
     * <p>The measurement is already needed for {@code textRight}/{@code textCenter}, and the audit
     * needs the same number for the same string: a label that was not fitted to the space it had is
     * invisible to every other kind of check, because nothing in the code knows it ran over the
     * control beside it. One {@code getWidth} per run is the cheapest place to catch that.</p>
     */
    public static void text(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        if (PvAudit.ON) {
            PvAudit.text(s, x, y, tr.getWidth(s));
        }
        ctx.drawText(tr, s, x, y, color, false);
    }

    public static void textShadow(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        if (PvAudit.ON) {
            PvAudit.text(s, x, y, tr.getWidth(s));
        }
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
        if (PvAudit.ON) {
            PvAudit.text(s, x, y, titleWidth(tr, s));
        }
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
