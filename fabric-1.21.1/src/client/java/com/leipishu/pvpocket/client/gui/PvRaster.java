package com.leipishu.pvpocket.client.gui;

/**
 * Analytic rasteriser for the panel's shapes.
 *
 * <p>Deliberately free of any Minecraft import: the whole thing is arithmetic over a pixel grid,
 * which is what lets {@link #main} self-check the coverage without launching a client.</p>
 *
 * <h2>Why a signed distance field</h2>
 *
 * <p>A rounded rectangle used to be anti-aliased by computing, for each corner row, how far the true
 * edge sits inside the bounding box and painting the column it lands in at partial alpha. That is a
 * one-dimensional measure: it knows where the edge crosses the row, and nothing about how much of
 * the pixel the curve actually covers vertically. At 45 degrees along the arc the two differ by up
 * to a quarter of a pixel of alpha, and at GUI scale 3 a quarter of a logical pixel is a visible
 * step in a 3x3 block — which is exactly the "pixel art" edge this class exists to remove.</p>
 *
 * <p>So coverage comes from the signed distance instead: {@code d} is negative inside, and a pixel
 * whose centre sits {@code d} away from the boundary is covered by {@code clamp(0.5 - d, 0, 1)}.
 * The 0.5 is the box filter of a one-pixel-wide sample: a pixel exactly on the edge is half in. This
 * is correct on a straight edge (where it reduces to the old horizontal fraction), on a diagonal,
 * and on an arc, with one formula.</p>
 *
 * <h2>Why the rows are still split</h2>
 *
 * <p>Coverage per pixel would be {@code w*h} evaluations and {@code w*h} draw calls. Only the
 * boundary needs that treatment, so each row is scanned from both ends until it reaches a pixel that
 * is fully inside, and the run between the two is emitted as a single span. The draw-call count is
 * therefore the same as the row-split version it replaces — roughly three per row — while the alpha
 * on the boundary pixels comes from the distance field rather than from a lookup on one axis.</p>
 *
 * <p>Interior pixels are never painted twice: the spans of a row are disjoint by construction, and
 * the boundary scan hands every pixel to exactly one of the three emitters.</p>
 */
public final class PvRaster {

    /**
     * Receives one horizontal run of one pixel row.
     *
     * <p>{@code argb} already carries the coverage, so the sink never has to know how the alpha was
     * derived — which is what lets a two-colour panel edge be composited in the same pass as a flat
     * fill.</p>
     */
    public interface Sink {
        void span(int x0, int y, int x1, int argb);
    }

    /** Coverage below this is dropped: it would round to alpha 0 anyway. */
    private static final float EMPTY = 1F / 256F;

    private PvRaster() {
    }

    /**
     * A rectangle with independent corner radii, filled with one colour.
     *
     * <p>Per-corner radii exist for one caller: the content well of a panel, whose top-left,
     * top-right and bottom-left corners are square (they sit against the chrome) and whose
     * bottom-right corner has to follow the panel's own radius. Expressing that as a shape is a
     * good deal simpler than clipping one rectangle with another.</p>
     */
    public static void roundedRect(int x, int y, int w, int h,
                                   int rTL, int rTR, int rBR, int rBL,
                                   int color, Sink sink) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        double hw = w / 2D;
        double hh = h / 2D;
        double cx = x + hw;
        double cy = y + hh;
        double tl = fit(rTL, w, h);
        double tr = fit(rTR, w, h);
        double br = fit(rBR, w, h);
        double bl = fit(rBL, w, h);

        for (int j = 0; j < h; j++) {
            double insetL = arcInset(j, h, tl, bl);
            double insetR = arcInset(j, h, tr, br);
            if (w - insetL - insetR < 0.02D) {
                continue;
            }
            int i0 = Math.max(0, (int) Math.floor(insetL) - 1);
            int i1 = Math.min(w, (int) Math.ceil(w - insetR) + 1);

            int i = i0;
            while (i < i1) {
                float c = fillCoverage(i, j, x, y, cx, cy, hw, hh, tl, tr, br, bl);
                if (c >= 1F) {
                    break;
                }
                if (c > EMPTY) {
                    sink.span(x + i, y + j, x + i + 1, PvTheme.alpha(color, c));
                }
                i++;
            }
            int runStart = i;
            int k = i1 - 1;
            while (k >= runStart) {
                float c = fillCoverage(k, j, x, y, cx, cy, hw, hh, tl, tr, br, bl);
                if (c >= 1F) {
                    break;
                }
                if (c > EMPTY) {
                    sink.span(x + k, y + j, x + k + 1, PvTheme.alpha(color, c));
                }
                k--;
            }
            if (k + 1 > runStart) {
                sink.span(x + runStart, y + j, x + k + 1, color);
            }
        }
    }

    /**
     * A rounded rectangle filled with {@code fill} and outlined with {@code border}, in one pass.
     *
     * <p>The outline is inset — it owns the outermost {@code thickness} pixels, exactly as the
     * "draw the border, then draw the fill one pixel in" pair it replaces did. It is not centred on
     * the boundary, because a centred one-pixel stroke only covers half of the outer row and reads
     * as a lighter line than the panel next to it.</p>
     *
     * <p>Compositing both colours per boundary pixel, instead of painting two shapes over each
     * other, is what keeps the seam clean: the pixel reports the fraction of itself the border
     * covers and mixes the two fills by that fraction, at the shape's own coverage as its alpha. Two
     * stacked shapes would instead blend a feathered border edge into a feathered fill edge and put
     * a 50%-alpha ring of the wrong colour along every straight edge.</p>
     */
    public static void roundedPanel(int x, int y, int w, int h, int radius, int thickness,
                                    int border, int fill, Sink sink) {
        if (w <= 0 || h <= 0) {
            return;
        }
        double hw = w / 2D;
        double hh = h / 2D;
        double cx = x + hw;
        double cy = y + hh;
        double r = fit(radius, w, h);
        double t = Math.max(0D, Math.min(thickness, Math.min(w, h) / 2D));

        for (int j = 0; j < h; j++) {
            double inset = arcInset(j, h, r, r);
            if (w - inset * 2D < 0.02D) {
                continue;
            }
            int i0 = Math.max(0, (int) Math.floor(inset) - 1);
            int i1 = Math.min(w, (int) Math.ceil(w - inset) + 1);
            // A pixel is pure fill once its whole extent is more than the border's depth inside.
            double solid = -(t + 0.75D);

            int i = i0;
            while (i < i1) {
                double d = sd(i + 0.5D + x - cx, j + 0.5D + y - cy, hw, hh, r, r, r, r);
                if (d <= solid) {
                    break;
                }
                int argb = shade(fill, border, t, i, j, x, y, cx, cy, hw, hh, r);
                if ((argb >>> 24) != 0) {
                    sink.span(x + i, y + j, x + i + 1, argb);
                }
                i++;
            }
            int runStart = i;
            int k = i1 - 1;
            while (k >= runStart) {
                double d = sd(k + 0.5D + x - cx, j + 0.5D + y - cy, hw, hh, r, r, r, r);
                if (d <= solid) {
                    break;
                }
                int argb = shade(fill, border, t, k, j, x, y, cx, cy, hw, hh, r);
                if ((argb >>> 24) != 0) {
                    sink.span(x + k, y + j, x + k + 1, argb);
                }
                k--;
            }
            if (k + 1 > runStart) {
                sink.span(x + runStart, y + j, x + k + 1, fill);
            }
        }
    }

    /**
     * A border-only ring, {@code thickness} pixels deep, inset from the shape's own edge.
     *
     * <p>Rasterised as a band rather than as "outer shape minus inner shape" because a fill with
     * zero alpha draws nothing: the subtraction never happens, and the inner shape has to be carved
     * out of the band arithmetically. Here that is just the distance test — a pixel belongs to the
     * band when its extent overlaps {@code [-thickness, 0]} in distance.</p>
     */
    public static void ring(int x, int y, int w, int h, int radius, int thickness,
                            int color, Sink sink) {
        if (w <= 0 || h <= 0 || thickness <= 0 || (color >>> 24) == 0) {
            return;
        }
        double hw = w / 2D;
        double hh = h / 2D;
        double cx = x + hw;
        double cy = y + hh;
        double r = fit(radius, w, h);
        double t = Math.min(thickness, Math.min(w, h) / 2D);

        for (int j = 0; j < h; j++) {
            double inset = arcInset(j, h, r, r);
            if (w - inset * 2D < 0.02D) {
                continue;
            }
            int leftFrom = Math.max(0, (int) Math.floor(inset - t) - 1);
            int leftTo = Math.min(w, (int) Math.ceil(inset + t) + 1);
            for (int i = leftFrom; i < leftTo; i++) {
                double d = sd(i + 0.5D + x - cx, j + 0.5D + y - cy, hw, hh, r, r, r, r);
                float c = band(d, t);
                if (c > EMPTY) {
                    sink.span(x + i, y + j, x + i + 1, PvTheme.alpha(color, c));
                }
            }
            int rightFrom = Math.max(0, (int) Math.floor(w - inset - t) - 1);
            int rightTo = Math.min(w, (int) Math.ceil(w - inset + t) + 1);
            for (int i = rightFrom; i < rightTo; i++) {
                double d = sd(i + 0.5D + x - cx, j + 0.5D + y - cy, hw, hh, r, r, r, r);
                float c = band(d, t);
                if (c > EMPTY) {
                    sink.span(x + i, y + j, x + i + 1, PvTheme.alpha(color, c));
                }
            }
            // Rows inside the top or bottom band are covered straight across; the horizontal band
            // scans above have already taken the corners, so the run between them is one span.
            float across = Math.max(band(-(j + 0.5D), t), band(-(h - j - 0.5D), t));
            if (across > EMPTY && rightFrom > leftTo) {
                sink.span(x + leftTo, y + j, x + rightFrom, PvTheme.alpha(color, across));
            }
        }
    }

    // ------------------------------------------------------------------ maths

    /**
     * Signed distance to a box with per-corner radii, measured from the box's centre.
     *
     * <p>The corner radius is picked by quadrant, which is the standard trick: each quadrant is a
     * box rounded by its own radius, and the four agree along the axes, so the field stays
     * continuous.</p>
     */
    private static double sd(double px, double py, double hw, double hh,
                             double tl, double tr, double br, double bl) {
        double r = px > 0D ? (py > 0D ? br : tr) : (py > 0D ? bl : tl);
        double qx = Math.abs(px) - hw + r;
        double qy = Math.abs(py) - hh + r;
        double ax = qx > 0D ? qx : 0D;
        double ay = qy > 0D ? qy : 0D;
        return Math.sqrt(ax * ax + ay * ay) + Math.min(Math.max(qx, qy), 0D) - r;
    }

    /** Box-filter coverage of a pixel whose centre sits {@code d} from the boundary. */
    private static double cover(double d) {
        double c = 0.5D - d;
        return c <= 0D ? 0D : Math.min(c, 1D);
    }

    /**
     * Coverage of the inset band {@code [-t, 0]} by a pixel spanning {@code [d-0.5, d+0.5]}.
     *
     * <p>Written as an interval overlap rather than as a distance ramp: a one-pixel stroke has to
     * come out at full alpha on the row it owns, and {@code 0.5 - (|d| - t/2)} would put it at 50%
     * instead.</p>
     */
    private static float band(double d, double t) {
        double lo = Math.max(d - 0.5D, -t);
        double hi = Math.min(d + 0.5D, 0D);
        return (float) Math.max(0D, Math.min(1D, hi - lo));
    }

    /**
     * Coverage of one pixel, from the distance at its centre.
     *
     * <p>One sample rather than a 2x2 supersample, and the difference is not laziness: the
     * {@code 0.5 - d} ramp <em>is</em> the box filter of the pixel, so averaging four of them over
     * the same pixel averages four one-pixel-wide filters and comes out as a 1.5-pixel-wide one.
     * On a straight edge that reported 0.75 where the truth is 1.0. The ramp is exact on straight
     * and 45-degree edges, and inside a corner arc its error is second order in the radius — about
     * 2% at the 6px radius the panels use.</p>
     */
    private static float fillCoverage(int i, int j, int x, int y, double cx, double cy,
                                      double hw, double hh, double tl, double tr,
                                      double br, double bl) {
        double d = sd(x + i + 0.5D - cx, y + j + 0.5D - cy, hw, hh, tl, tr, br, bl);
        return (float) cover(d);
    }

    /** The composited colour of one boundary pixel of a bordered panel. */
    private static int shade(int fill, int border, double t, int i, int j, int x, int y,
                             double cx, double cy, double hw, double hh, double r) {
        double d = sd(x + i + 0.5D - cx, y + j + 0.5D - cy, hw, hh, r, r, r, r);
        double fs = cover(d);
        if (fs <= EMPTY) {
            return 0;
        }
        double f = Math.min(1D, band(d, t) / fs);
        int alpha = (int) Math.round(Math.min(1D, fs) * 255D);
        return (PvTheme.blend(fill, border, (float) f) & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * How far the true edge sits inside the bounding box on row {@code j}.
     *
     * <p>{@code arc} takes the distance <em>from the edge the corner belongs to</em>, which is the
     * row index at the top and {@code h - j} at the bottom. Passing the distance from the corner's
     * centre instead is the same number at r=1 and wrong everywhere else — it puts the widest part
     * of the arc against the row that should be the narrowest.</p>
     */
    private static double arcInset(int j, int h, double top, double bottom) {
        double jc = j + 0.5D;
        if (top > 0.01D && jc < top) {
            return arc(jc, top);
        }
        if (bottom > 0.01D && jc > h - bottom) {
            return arc(h - jc, bottom);
        }
        return 0D;
    }

    private static double arc(double fromEdge, double r) {
        double t = r - fromEdge;
        return r - Math.sqrt(Math.max(0D, r * r - t * t));
    }

    private static double fit(double radius, int w, int h) {
        return Math.max(0D, Math.min(radius, Math.min(w, h) / 2D));
    }

    // ------------------------------------------------------------------ self-check

    /**
     * Coverage self-check, runnable without a client: {@code java -cp build/classes/java/client
     * com.leipishu.pvpocket.client.gui.PvRaster}.
     *
     * <p>It asserts the three properties the drawing code relies on and that no screenshot can
     * prove: every pixel is painted at most once (no translucent fill stacking into a darker bar),
     * no pixel that the shape covers is left unpainted (no black notch along an edge), and the
     * emitted alpha agrees with a 8x8 reference within a tenth of a pixel.</p>
     */
    public static void main(String[] args) {
        int failures = 0;
        int cases = 0;
        for (int r = 0; r <= 16; r++) {
            for (int w = 2 * r + 1; w <= 2 * r + 30; w += 7) {
                for (int h = 2 * r + 1; h <= 2 * r + 30; h += 5) {
                    failures += check(w, h, r);
                    cases++;
                }
            }
        }
        System.out.println(failures == 0
                ? "sdf: " + cases + " shapes ok"
                : "sdf: " + failures + " failures in " + cases + " shapes");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static int check(int w, int h, int r) {
        final int pad = 2;
        final int bw = w + pad * 2;
        final int bh = h + pad * 2;
        int[] hits = new int[bw * bh];
        int[] alpha = new int[bw * bh];
        roundedRect(pad, pad, w, h, r, r, r, r, 0xFF000000, (x0, y, x1, argb) -> {
            for (int i = x0; i < x1; i++) {
                int idx = y * bw + i;
                hits[idx]++;
                alpha[idx] = argb >>> 24;
            }
        });

        double hw = w / 2D;
        double hh = h / 2D;
        double cx = pad + hw;
        double cy = pad + hh;
        int bad = 0;
        String first = null;
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                // Reference coverage is the true area: point samples, not the 0.5-d ramp the
                // rasteriser uses, or the comparison would just be the approximation marking its
                // own homework.
                double ref = 0D;
                for (int sy = 0; sy < 8; sy++) {
                    for (int sx = 0; sx < 8; sx++) {
                        if (sd(x + (sx + 0.5D) / 8D - cx, y + (sy + 0.5D) / 8D - cy,
                                hw, hh, r, r, r, r) < 0D) {
                            ref += 1D;
                        }
                    }
                }
                ref /= 64D;
                int idx = y * bw + x;
                if (hits[idx] > 1) {
                    bad++;
                } else if (ref > 0.05D && hits[idx] == 0) {
                    bad++;
                } else if (hits[idx] == 1 && Math.abs(alpha[idx] / 255D - ref) > 0.12D) {
                    bad++;
                }
                if (bad == 1 && first == null) {
                    first = "at " + x + "," + y + " ref=" + Math.round(ref * 100) / 100D
                            + " hits=" + hits[idx] + " a=" + alpha[idx] / 255D;
                }
            }
        }
        if (bad != 0) {
            System.out.println("  " + w + "x" + h + " r" + r + ": " + bad + " bad pixels " + first);
        }
        return bad;
    }
}
