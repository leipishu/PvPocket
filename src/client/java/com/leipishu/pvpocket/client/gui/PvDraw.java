package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing primitives for the custom GUI layer.
 *
 * <p>Everything is drawn from code: no nine-patch textures and no vanilla widget skins, so the look
 * survives any GUI scale and any resource pack.
 *
 * <p>No bold text. Vanilla's bold flag is a second draw of the glyph shifted one pixel, which a
 * resource pack's font renders as a smear, so hierarchy comes from colour and position.
 *
 * <p>Rounded shapes go through {@link PvRaster}, which takes boundary coverage from a signed
 * distance field. The shape is split by row so no pixel is painted twice.
 */
public final class PvDraw {

    /**
     * Whether item icons may be drawn this pass. Cleared while a modal is drawn over the panel.
     *
     * <p>Item icons are not GUI pixels: they render through a depth-tested item layer at z = 150,
     * while panel fills, scrims and glyphs go into the GUI layer at z = 0 with depth testing off. A
     * scrim drawn afterwards cannot cover an icon.
     *
     * <p>{@code PvScreen} clears this for the roots pass while an overlay is up, and widgets that
     * draw items check it.
     */
    public static boolean itemsVisible = true;

    /**
     * The one span sink, reused across calls.
     *
     * <p>A fresh sink per shape would allocate several dozen objects per frame. The GUI is
     * single-threaded, so one instance with a mutable target is enough.
     */
    private static final SpanEmitter EMITTER = new SpanEmitter();

    private static final class SpanEmitter implements PvRaster.Sink {
        private GuiGraphics ctx;

        void begin(GuiGraphics target) {
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

    public static void rect(GuiGraphics ctx, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        ctx.fill(x, y, x + w, y + h, color);
    }

    public static void roundRect(GuiGraphics ctx, int x, int y, int w, int h, int radius, int color) {
        roundRect(ctx, x, y, w, h, radius, radius, radius, radius, color);
    }

    /**
     * A rounded rectangle whose four corners carry their own radius.
     *
     * <p>Used by the panel chrome: the content well is square at three corners and rounded at the
     * bottom-right, where it follows the panel's own corner.
     */
    public static void roundRect(GuiGraphics ctx, int x, int y, int w, int h,
                                 int rTL, int rTR, int rBR, int rBL, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        EMITTER.begin(ctx);
        PvRaster.roundedRect(x, y, w, h, rTL, rTR, rBR, rBL, color, EMITTER);
    }

    /** Rounded rectangle with a 1px border: filled with {@code border}, inset with {@code fill}. */
    public static void roundPanel(GuiGraphics ctx, int x, int y, int w, int h, int radius,
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
     * <p>A transparent inner fill draws nothing, so the ring is carved arithmetically in
     * {@link PvRaster#ring}.
     */
    public static void ring(GuiGraphics ctx, int x, int y, int w, int h, int radius, int thickness,
                            int color) {
        if (w <= 0 || h <= 0 || thickness <= 0 || (color >>> 24) == 0) {
            return;
        }
        EMITTER.begin(ctx);
        PvRaster.ring(x, y, w, h, radius, thickness, color, EMITTER);
    }

    public static void hLine(GuiGraphics ctx, int x, int y, int w, int color) {
        rect(ctx, x, y, w, 1, color);
    }

    public static void vLine(GuiGraphics ctx, int x, int y, int h, int color) {
        rect(ctx, x, y, 1, h, color);
    }

    /** A filled circle, used for status dots and slider knobs. */
    public static void dot(GuiGraphics ctx, int cx, int cy, int r, int color) {
        roundRect(ctx, cx - r, cy - r, r * 2, r * 2, r, color);
    }

    /**
     * Two-layer soft edge for surfaces that sit over the world instead of over the scrim.
     *
     * <p>Panels do not use it, since a shadow is invisible behind a 77% scrim. HUD bars still need
     * it, because their background is whatever the player stands in front of.
     */
    public static void shadow(GuiGraphics ctx, int x, int y, int w, int h, int radius) {
        roundRect(ctx, x - 2, y - 1, w + 4, h + 5, radius + 2, 0x30000000);
        roundRect(ctx, x - 1, y, w + 2, h + 3, radius + 1, 0x50000000);
    }

    // ------------------------------------------------------------------ glyphs

    /**
     * A chevron pointing along {@code dir} (+1 right, -1 left), {@code height} pixels tall.
     *
     * <p>Two pixels thick, drawn row by row, and not anti-aliased: a smoothed 1.5px diagonal at GUI
     * scale 1 turns into grey mush.
     */
    public static void chevron(GuiGraphics ctx, int cx, int cy, int dir, int height, int color) {
        int half = Math.max(1, height / 2);
        int top = cy - half;
        for (int i = 0; i < height; i++) {
            int reach = half - Math.abs(i - half);
            int x = dir > 0 ? cx - half / 2 - 1 + reach : cx + half / 2 - 1 - reach;
            ctx.fill(x, top + i, x + 2, top + i + 1, color);
        }
    }

    public static void plus(GuiGraphics ctx, int cx, int cy, int arm, int thickness, int color) {
        int h = thickness / 2;
        rect(ctx, cx - arm, cy - h, arm * 2 + 1, thickness, color);
        rect(ctx, cx - h, cy - arm, thickness, arm * 2 + 1, color);
    }

    public static void cross(GuiGraphics ctx, int cx, int cy, int arm, int color) {
        for (int i = -arm; i <= arm; i++) {
            ctx.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            ctx.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color);
        }
    }

    // ------------------------------------------------------------------ text

    /**
     * Records the run for the layout audit, then draws it.
     *
     * <p>The audit needs the same width the drawing needs, and a label that ran over its neighbour
     * is invisible to every other check. One {@code getWidth} per run catches it.
     */
    public static void text(GuiGraphics ctx, Font tr, String s, int x, int y, int color) {
        if (PvAudit.ON) {
            PvAudit.text(s, x, y, tr.width(s));
        }
        ctx.drawString(tr, s, x, y, color, false);
    }

    public static void textShadow(GuiGraphics ctx, Font tr, String s, int x, int y, int color) {
        if (PvAudit.ON) {
            PvAudit.text(s, x, y, tr.width(s));
        }
        ctx.drawString(tr, s, x, y, color, true);
    }

    public static void textRight(GuiGraphics ctx, Font tr, String s, int right, int y, int color) {
        text(ctx, tr, s, right - tr.width(s), y, color);
    }

    public static void textCenter(GuiGraphics ctx, Font tr, String s, int centerX, int y, int color) {
        text(ctx, tr, s, centerX - tr.width(s) / 2, y, color);
    }

    // -------------------------------------------------------------- shrink to fit

    /**
     * Smallest scale a label inside a fixed frame may be drawn at before it is truncated.
     *
     * <p>0.75 is the floor because the body face is 8px tall: below three quarters the glyphs stop
     * resolving into letters at GUI scale 1. A label wider than {@code 1 / 0.75 = 1.33x} its frame
     * belongs in a wrapped paragraph.
     *
     * <p>Frames are measured from the shorter of two languages, whose words differ in length by up
     * to a factor of two. Shrinking keeps the word.
     */
    public static final float MIN_TEXT_SCALE = 0.75F;

    /**
     * How far short of the floor a single line may land before it is wrapped or cut.
     *
     * <p>Two pixels is the rounding distance: a 55px frame against a 75px label needs 0.733, and
     * cutting "Accept & start" over 1.6px would trade a legible word for an ellipsis.
     */
    private static final int FLOOR_SLACK = 2;

    /** Height of the body face, and the leading between two lines of it. */
    private static final int LINE_H = 8;
    private static final int WRAP_PITCH = 10;

    /** Scales tried for a multi-line label, widest first: full size beats a smaller two-liner. */
    private static final float[] WRAP_SCALES = {1F, 0.9F, 0.8F, MIN_TEXT_SCALE};

    /**
     * A label measured against the space it has: the string to draw, at what scale, and how wide.
     *
     * <p>Three tiers, each worse than the last: draw it as written, draw it smaller but
     * legible, cut it. Cutting happens only when the label is more than a third wider than its
     * frame.
     */
    private record Fit(String text, float scale, int width) {
    }

    /** A label laid out over one or more lines, all at the same scale. */
    private record Wrapped(List<String> lines, float scale, boolean cut) {
    }

    private static Fit measure(Font tr, String s, int maxWidth) {
        int natural = tr.width(s);
        if (maxWidth <= 0 || s.isEmpty()) {
            return new Fit("", 1F, 0);
        }
        if (natural <= maxWidth) {
            return new Fit(s, 1F, natural);
        }
        float scale = (float) maxWidth / natural;
        if (scale >= MIN_TEXT_SCALE) {
            return new Fit(s, scale, maxWidth);
        }
        if (natural * MIN_TEXT_SCALE <= maxWidth + FLOOR_SLACK) {
            // Within rounding distance of the floor: draw it at the exact scale, which lands on the
            // frame edge, rather than turn the last word into an ellipsis over two pixels.
            return new Fit(s, scale, maxWidth);
        }
        // At the floor and still too wide: cut, so that what is left still fits at the floor.
        PvAudit.cut(s, maxWidth, natural);
        String cut = fit(tr, s, (int) Math.floor(maxWidth / MIN_TEXT_SCALE));
        return new Fit(cut, MIN_TEXT_SCALE, Math.max(1, Math.round(tr.width(cut) * MIN_TEXT_SCALE)));
    }

    /**
     * Draws {@code s} from {@code x}, shrinking it until it fits {@code maxWidth}.
     *
     * <p>Returns the width actually covered, so a caller can lay out what comes after it.
     */
    public static int textFitLeft(GuiGraphics ctx, Font tr, String s, int x, int y,
                                  int maxWidth, int color) {
        Fit fit = measure(tr, s, maxWidth);
        drawScaled(ctx, tr, fit, x, y, color);
        return fit.width;
    }

    /** Right-aligned variant: the run ends at {@code right} and grows left into the space. */
    public static int textFitRight(GuiGraphics ctx, Font tr, String s, int right, int y,
                                   int maxWidth, int color) {
        Fit fit = measure(tr, s, maxWidth);
        drawScaled(ctx, tr, fit, right - fit.width, y, color);
        return fit.width;
    }

    /** Centred variant: the run is centred on {@code centerX} and at most {@code maxWidth} wide. */
    public static int textFitCenter(GuiGraphics ctx, Font tr, String s, int centerX, int y,
                                    int maxWidth, int color) {
        Fit fit = measure(tr, s, maxWidth);
        drawScaled(ctx, tr, fit, centerX - fit.width / 2, y, color);
        return fit.width;
    }

    /** Width {@link #textFitLeft} would cover, without drawing anything. */
    public static int fittedWidth(Font tr, String s, int maxWidth) {
        return measure(tr, s, maxWidth).width;
    }

    // -------------------------------------------------------------- wrap when shrinking fails

    /**
     * Draws {@code s} inside a {@code maxWidth x boxH} frame, using up to {@code maxLines} lines.
     *
     * <p>Shrinking comes first; wrapping is reached only when one line would be cut. "Cycle
     * spectator camera" is 121px on one line and 60px per line as two lines.
     *
     * @param top top of the frame; the block is centred vertically in {@code boxH}
     *
     * @param boxH height of the frame the label has to live in
     *
     * @return the width the block covered, i.e. its widest line, so a caller can anchor to it
     */
    public static int textWrapLeft(GuiGraphics ctx, Font tr, String s, int x, int top,
                                   int boxH, int maxWidth, int maxLines, int color) {
        return drawWrapped(ctx, tr, layout(tr, s, maxWidth, maxLines), x, top, boxH, false, color);
    }

    /** Centred variant of {@link #textWrapLeft}: the block is centred on {@code centerX}. */
    public static int textWrapCenter(GuiGraphics ctx, Font tr, String s, int centerX,
                                     int top, int boxH, int maxWidth, int maxLines, int color) {
        return drawWrapped(ctx, tr, layout(tr, s, maxWidth, maxLines), centerX, top, boxH, true,
                color);
    }

    /** Lines {@link #textWrapLeft} would draw, without drawing anything. */
    public static int wrappedLines(Font tr, String s, int maxWidth, int maxLines) {
        return layout(tr, s, maxWidth, maxLines).lines().size();
    }
    /** Height a wrapped block occupies: {@code n} lines of 8px with 2px leading between them. */
    public static int wrappedHeight(int lines) {
        return lines <= 0 ? 0 : lines * LINE_H + (lines - 1) * (WRAP_PITCH - LINE_H);
    }

    /**
     * Whether {@code s} would lose text in a {@code maxWidth x maxLines} frame.
     *
     * <p>Asked before drawing so a container can attach the full string as a tooltip.
     */
    public static boolean willCut(Font tr, String s, int maxWidth, int maxLines) {
        return layout(tr, s, maxWidth, maxLines).cut();
    }

    private static Wrapped layout(Font tr, String s, int maxWidth, int maxLines) {
        if (s == null || s.isEmpty() || maxWidth <= 0) {
            return new Wrapped(List.of(""), 1F, false);
        }
        int natural = tr.width(s);
        if (maxLines <= 1 || natural <= maxWidth
                || natural * MIN_TEXT_SCALE <= maxWidth + FLOOR_SLACK) {
            // One line, shrunk if need be. The third test is the rounding slack, so a label within
            // two pixels of the floor keeps its one-line rhythm.
            Fit one = measure(tr, s, maxWidth);
            boolean cut = natural > maxWidth && natural * MIN_TEXT_SCALE > maxWidth + FLOOR_SLACK;
            return new Wrapped(List.of(one.text()), one.scale(), cut);
        }
        for (float scale : WRAP_SCALES) {
            List<String> lines = wrap(tr, s, Math.max(1, (int) Math.floor(maxWidth / scale)));
            if (lines.size() <= maxLines) {
                return new Wrapped(lines, scale, false);
            }
        }
        // More text than the frame holds even wrapped at the floor: keep what fits and say so.
        int lineWidth = Math.max(1, (int) Math.floor(maxWidth / MIN_TEXT_SCALE));
        List<String> all = wrap(tr, s, lineWidth);
        PvAudit.cut(s, maxWidth * maxLines, natural);
        List<String> kept = new ArrayList<>(all.subList(0, maxLines));
        kept.set(maxLines - 1, fit(tr, kept.get(maxLines - 1) + "…", lineWidth));
        return new Wrapped(kept, MIN_TEXT_SCALE, true);
    }

    private static int drawWrapped(GuiGraphics ctx, Font tr, Wrapped w, int origin,
                                   int top, int boxH, boolean centred, int color) {
        List<String> lines = w.lines();
        int n = lines.size();
        if (n > 1) {
            PvAudit.wrapped();
        }
        // One line keeps the baseline every single-line label in the panel uses; two or more are
        // centred as a block, which is what makes a wrapped pair read as one label.
        int y = top + (boxH - wrappedHeight(n)) / 2 + (n == 1 ? 1 : 0);
        int widest = 0;
        for (String line : lines) {
            Fit fit = new Fit(line, w.scale(), Math.max(1, Math.round(tr.width(line) * w.scale())));
            drawScaled(ctx, tr, fit, centred ? origin - fit.width() / 2 : origin, y, color);
            widest = Math.max(widest, fit.width());
            y += WRAP_PITCH;
        }
        return widest;
    }

    /**
     * Draws one measured run, scaling the matrix when it had to shrink.
     *
     * <p>The audit is handed the drawn box, not the natural one, so a shrunk label does not
     * report a collision with whatever sits beside it.
     */
    private static void drawScaled(GuiGraphics ctx, Font tr, Fit fit, int x, int y, int color) {
        if (fit.text.isEmpty()) {
            return;
        }
        if (fit.scale >= 0.999F) {
            text(ctx, tr, fit.text, x, y, color);
            return;
        }
        PvAudit.shrunk();
        if (PvAudit.ON) {
            PvAudit.text(fit.text, x, y, fit.width,
                    Math.max(1, Math.round(tr.lineHeight * fit.scale)));
        }
        PvMatrix.push(ctx);
        PvMatrix.translate(ctx, x, y);
        PvMatrix.scale(ctx, fit.scale, fit.scale);
        ctx.drawString(tr, fit.text, 0, 0, color, false);
        PvMatrix.pop(ctx);
    }

    // ------------------------------------------------------------------ strings

    /** Clips text to {@code maxWidth}, appending an ellipsis when it is cut. */
    public static String fit(Font tr, String s, int maxWidth) {
        if (tr.width(s) <= maxWidth) {
            return s;
        }
        String ellipsis = "…";
        int budget = maxWidth - tr.width(ellipsis);
        if (budget <= 0) {
            return "";
        }
        return tr.plainSubstrByWidth(s, budget) + ellipsis;
    }

    /**
     * Wraps a string to {@code maxWidth}, breaking on spaces where possible and on any character
     * otherwise. CJK text has no spaces, so a word-only wrapper would overflow.
     */
    public static List<String> wrap(Font tr, String s, int maxWidth) {
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
            if (tr.width(line.toString()) > maxWidth && line.length() > 1) {
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
