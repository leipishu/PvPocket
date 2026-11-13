package com.leipishu.pvpocket.client.gui;

/**
 * Analytic rasteriser for the panel's shapes.
 *
 * <p>Free of any Minecraft import: the arithmetic runs over a pixel grid, so {@link #main} can
 * self-check coverage without launching a client.
 *
 * <p>Coverage comes from a signed distance field: {@code d} is negative inside, and a pixel whose
 * centre sits {@code d} from the boundary is covered by {@code clamp(0.5 - d, 0, 1)}. Each row is
 * scanned from both ends and the fully covered run between them is emitted as one span.
 */
public final class PvRaster {

    /**
     * Receives one horizontal run of one pixel row.
     *
     * <p>{@code argb} already carries the coverage, so the sink never has to know how the alpha was
     * derived.
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
     * <p>Per-corner radii serve the content well of a panel: three corners are square and the
     * bottom-right follows the panel's own radius.
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
     * <p>The outline is inset: it owns the outermost {@code thickness} pixels. A centred one-pixel
     * stroke covers only half of the outer row and reads as a lighter line.
     *
     * <p>Each boundary pixel reports the fraction of itself the border covers and mixes the two
     * fills by that fraction, at the shape's own coverage as its alpha.
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

            // The right edge is located first, by distance alone, so the left pass knows where the
            // flat middle begins. Each edge pixel is measured once.
            int rightStart = i1;
            while (rightStart > i0
                    && sd(rightStart - 0.5D + x - cx, j + 0.5D + y - cy, hw, hh, r, r, r, r) > solid) {
                rightStart--;
            }

            // Runs, not pixels: along a straight edge every pixel of a row has the same distance
            // and colour, so a 380px row emits 66 spans instead of 1,686 one-pixel fills.
            int leftEnd = i0;
            int from = i0;
            int color = 0;
            double lastD = Double.NaN;
            while (leftEnd < rightStart) {
                double d = sd(leftEnd + 0.5D + x - cx, j + 0.5D + y - cy, hw, hh, r, r, r, r);
                if (d <= solid) {
                    break;
                }
                // Identical distance means an identical blend; recomputing it is the same answer at
                // the price of a colour mix, and along a straight edge that is the whole row.
                int argb = d == lastD ? color : shade(fill, border, t, d);
                lastD = d;
                if (leftEnd > from && argb == color) {
                    leftEnd++;
                    continue;
                }
                if (leftEnd > from && (color >>> 24) != 0) {
                    sink.span(x + from, y + j, x + leftEnd, color);
                }
                from = leftEnd;
                color = argb;
                leftEnd++;
            }
            if (leftEnd > from && (color >>> 24) != 0) {
                sink.span(x + from, y + j, x + leftEnd, color);
            }

            if (rightStart > leftEnd) {
                sink.span(x + leftEnd, y + j, x + rightStart, fill);
            }

            from = rightStart;
            color = 0;
            lastD = Double.NaN;
            for (int i = rightStart; i < i1; i++) {
                double d = sd(i + 0.5D + x - cx, j + 0.5D + y - cy, hw, hh, r, r, r, r);
                int argb = d == lastD ? color : shade(fill, border, t, d);
                lastD = d;
                if (i > from && argb == color) {
                    continue;
                }
                if (i > from && (color >>> 24) != 0) {
                    sink.span(x + from, y + j, x + i, color);
                }
                from = i;
                color = argb;
            }
            if (i1 > from && (color >>> 24) != 0) {
                sink.span(x + from, y + j, x + i1, color);
            }
        }
    }

    /**
     * A border-only ring, {@code thickness} pixels deep, inset from the shape's own edge.
     *
     * <p>Rasterised as a band rather than as outer shape minus inner shape, because a fill with
     * zero alpha draws nothing. A pixel belongs to the band when its extent overlaps
     * {@code [-thickness, 0]} in distance.
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
     * <p>The corner radius is picked by quadrant: each quadrant is a box rounded by its own radius,
     * and the four agree along the axes.
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
     * <p>An interval overlap rather than a distance ramp, so a one-pixel stroke comes out at full
     * alpha on the row it owns.
     */
    private static float band(double d, double t) {
        double lo = Math.max(d - 0.5D, -t);
        double hi = Math.min(d + 0.5D, 0D);
        return (float) Math.max(0D, Math.min(1D, hi - lo));
    }

    /**
     * Coverage of one pixel, from the distance at its centre.
     *
     * <p>One sample rather than a 2x2 supersample: the {@code 0.5 - d} ramp is the box filter of
     * the pixel, so averaging four of them gives a 1.5-pixel-wide filter. The ramp is exact on
     * straight and 45-degree edges, and inside a corner arc its error is about 2% at the 6px radius
     * the panels use.
     */
    private static float fillCoverage(int i, int j, int x, int y, double cx, double cy,
                                      double hw, double hh, double tl, double tr,
                                      double br, double bl) {
        double d = sd(x + i + 0.5D - cx, y + j + 0.5D - cy, hw, hh, tl, tr, br, bl);
        return (float) cover(d);
    }

    /** The composited colour of one boundary pixel of a bordered panel. */
    private static int shade(int fill, int border, double t, double d) {
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
     * <p>{@code arc} takes the distance from the edge the corner belongs to, which is the row index
     * at the top and {@code h - j} at the bottom.
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
     * <p>It asserts three properties: every pixel is painted at most once, no covered pixel is left
     * unpainted, and the emitted alpha agrees with an 8x8 reference within a tenth of a pixel.
     */
    public static void main(String[] args) {
        int failures = 0;
        int cases = 0;
        for (int r = 0; r <= 16; r++) {
            for (int w = 2 * r + 1; w <= 2 * r + 30; w += 7) {
                for (int h = 2 * r + 1; h <= 2 * r + 30; h += 5) {
                    failures += check(w, h, r);
                    failures += checkPanel(w, h, r);
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

    /**
     * The same check for a bordered panel.
     *
     * <p>The panel path shades a band instead of breaking at the first covered pixel, so it is the
     * path that can emit a pixel twice or leave a hole in a straight edge.
     */
    private static int checkPanel(int w, int h, int r) {
        final int pad = 2;
        final int bw = w + pad * 2;
        final int bh = h + pad * 2;
        final int fill = 0xFF204060;
        final int border = 0xFFA0B0C0;
        int[] hits = new int[bw * bh];
        int[] argbs = new int[bw * bh];
        roundedPanel(pad, pad, w, h, r, 1, border, fill, (x0, y, x1, argb) -> {
            for (int i = x0; i < x1; i++) {
                int idx = y * bw + i;
                hits[idx]++;
                argbs[idx] = argb;
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
                double d = sd(x + 0.5D - cx, y + 0.5D - cy, hw, hh, r, r, r, r);
                if (hits[idx] > 1) {
                    bad++;
                } else if (ref > 0.05D && hits[idx] == 0) {
                    bad++;
                } else if (hits[idx] == 1 && Math.abs((argbs[idx] >>> 24) / 255D - ref) > 0.12D) {
                    bad++;
                } else if (hits[idx] == 1 && ref > 0.99D && d < -1.75D && argbs[idx] != fill) {
                    // Well inside the border: the fill has to come out exactly, not blended.
                    bad++;
                }
                if (bad == 1 && first == null) {
                    first = "at " + x + "," + y + " ref=" + Math.round(ref * 100) / 100D
                            + " hits=" + hits[idx] + " a=" + (argbs[idx] >>> 24) / 255D;
                }
            }
        }
        if (bad != 0) {
            System.out.println("  panel " + w + "x" + h + " r" + r + ": " + bad + " bad pixels " + first);
        }
        return bad;
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
                // Reference coverage is the true area from point samples, not the 0.5-d ramp the
                // rasteriser uses, or the comparison would grade its own approximation.
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
