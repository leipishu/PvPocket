package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing primitives for the custom GUI layer.
 *
 * <p>Everything is drawn from code — no nine-patch textures, no vanilla widget skins. That means
 * the look survives any GUI scale and any resource pack, and there is no asset to get wrong at
 * 1×/2×/3×/4×.</p>
 *
 * <h2>Why nothing is bold any more</h2>
 *
 * <p>Vanilla's {@code §l} is not a typeface weight, it is a second draw of the same glyph shifted one
 * pixel right. The bundled bitmap font has a hand-made bold sheet, so it looks right; a resource
 * pack's TrueType font does not, and the same code that reads as "heavier" on the default font reads
 * as "smeared" on the font most players install. Since the whole interface is laid out for players
 * who replace the font, hierarchy here comes from colour and from position instead: a title is
 * {@code TEXT} on a band with a divider under it, and the body below is {@code TEXT_DIM}.</p>
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
     * <p>Used by the panel chrome: the content well is square at its top-left, top-right and
     * bottom-left (it abuts the sidebar and the header) and rounded at the bottom-right (it follows
     * the panel's own corner). Expressing that as one shape costs a quadrant test in the distance
     * field; expressing it as a clip costs a scissor state change and a whole extra pass.</p>
     */
    public static void roundRect(GuiGraphics ctx, int x, int y, int w, int h,
                                 int rTL, int rTR, int rBR, int rBL, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        EMITTER.begin(ctx);
        PvRaster.roundedRect(x, y, w, h, rTL, rTR, rBR, rBL, color, EMITTER);
    }

    /** Rounded rectangle with a 1px border: filled with {@code border}, then inset with {@code fill}. */
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
     * <p>Not "outer rounded rect plus transparent inner rect": a transparent fill draws nothing, so
     * that version produced a solid block of the ring colour over whatever it was supposed to
     * outline. {@link PvRaster#ring} carves the inner shape out arithmetically instead.</p>
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
     * <p>Panels no longer use this: behind a 77% scrim a shadow is invisible. HUD bars still need
     * it, because their background is whatever the player is standing in front of.</p>
     */
    public static void shadow(GuiGraphics ctx, int x, int y, int w, int h, int radius) {
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
     * <p>The measurement is already needed for {@code textRight}/{@code textCenter}, and the audit
     * needs the same number for the same string: a label that was not fitted to the space it had is
     * invisible to every other kind of check, because nothing in the code knows it ran over the
     * control beside it. One {@code getWidth} per run is the cheapest place to catch that.</p>
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
     * Smallest scale a label inside a fixed frame may be drawn at before it is truncated instead.
     *
     * <p>0.75 is the floor because the panel's body face is 8px tall: below three quarters the
     * glyphs stop resolving into letters at GUI scale 1, and a label nobody can read is worse than
     * one that ends in an ellipsis. Anything wider than {@code 1 / 0.75 ≈ 1.33×} its frame is not a
     * sizing problem any more — it is a string that belongs in a wrapped paragraph, or a frame that
     * has to give up some space.</p>
     *
     * <p>Why shrink at all: this interface is built for two languages whose words differ in length
     * by up to a factor of two, and every frame in it is measured from the short one. Truncating is
     * what those frames used to do, which is how a button row ended up reading
     * {@code Copy res… / Acc… / Rej…}. Shrinking keeps the word.</p>
     */
    public static final float MIN_TEXT_SCALE = 0.75F;

    /**
     * How far short of the floor a single line may land before it is wrapped or cut instead.
     *
     * <p>Two pixels is the rounding distance, not a second tier: a frame 55px wide against a 75px
     * label needs 0.733, and cutting "Accept & start" over 1.6px would trade a legible word for an
     * ellipsis. Anything genuinely too wide misses by far more than this.</p>
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
     * <p>Three tiers, in this order, because each is worse than the one before it: draw it as
     * written; draw it smaller but still legible; cut it. The third tier only happens when the label
     * is more than a third wider than its frame — at that point shrinking has already lost the size
     * hierarchy the design relies on.</p>
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
     * Draws {@code s} from {@code x}, shrinking it until it fits {@code maxWidth}. Returns the width
     * actually covered, so a caller can lay out what comes after it.
     */
    public static int textFitLeft(GuiGraphics ctx, Font tr, String s, int x, int y,
                                  int maxWidth, int color) {
        Fit fit = measure(tr, s, maxWidth);
        drawScaled(ctx, tr, fit, x, y, color);
        return fit.width;
    }

    /** Right-aligned variant: the run ends at {@code right} and grows leftwards into {@code maxWidth}. */
    public static int textFitRight(GuiGraphics ctx, Font tr, String s, int right, int y,
                                   int maxWidth, int color) {
        Fit fit = measure(tr, s, maxWidth);
        drawScaled(ctx, tr, fit, right - fit.width, y, color);
        return fit.width;
    }

    /** Centred variant: the run is centred on {@code centerX} and may be at most {@code maxWidth} wide. */
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
     * Draws {@code s} inside a {@code maxWidth × boxH} frame, using up to {@code maxLines} lines.
     *
     * <p>The fourth tier, and the one that exists because shrinking is not always enough. A word
     * only shrinks so far before it stops being a word; past that, a frame that is too narrow for one
     * line is not necessarily too narrow for two. "Cycle spectator camera" is 121px on one line and
     * 60px per line as "Cycle spectator / camera", so a 74px label column can hold it at full size
     * instead of holding 75% of it and cutting the last word.</p>
     *
     * <p>Shrinking still comes first, though: a label that fits on one line at 0.85× stays on one
     * line. Wrapping is only reached when a single line would have to be cut, which keeps the
     * one-line rhythm of the panels intact everywhere the frame is merely a little tight.</p>
     *
     * @param top   top of the frame; the block is centred vertically in {@code boxH}
     * @param boxH  height of the frame the label has to live in
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
     * Whether {@code s} would lose text in a {@code maxWidth × maxLines} frame.
     *
     * <p>Asked before drawing so a container can attach the full string as a tooltip. Shrinking and
     * wrapping are how a label stays whole; this is what to do with the ones that cannot — a 55px
     * chip holding "Team wool" is a frame that fits, and a 55px chip holding a whole sentence is a
     * label whose rest belongs somewhere the player can ask for it.</p>
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
            // One line, shrunk if need be. The third test is the rounding slack: a label that lands
            // within two pixels of the floor keeps its one-line rhythm instead of spending a second
            // line on something that only needed a slightly wider frame.
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
     * <p>The audit is handed the <em>drawn</em> box, not the natural one: a shrunk label covers less
     * than its text would at 1.0×, and recording the natural size would invent a collision with
     * whatever sits beside it.</p>
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
        ctx.pose().pushPose();
        ctx.pose().translate(x, y, 0);
        ctx.pose().scale(fit.scale, fit.scale, 1F);
        ctx.drawString(tr, fit.text, 0, 0, color, false);
        ctx.pose().popPose();
    }

    // ------------------------------------------------------------------ strings

    /** Clips text to {@code maxWidth}, appending an ellipsis when it had to be cut. */
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
     * otherwise. CJK text has no spaces, so a word-only wrapper would overflow on every label.
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
