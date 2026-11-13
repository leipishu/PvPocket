package com.leipishu.pvpocket.client.gui;

/**
 * Layout arithmetic shared by the panels.
 *
 * <p>The logical canvas varies with GUI scale: a 1280x720 window is 426x240 at scale 3, 640x360 at
 * scale 2 and 480x270 at scale 4.
 *
 * <p>Columns are a share of the panel with a floor and a cap, not a constant. Both bounds are
 * passed at the call site.
 */
public final class PvLayout {

    private PvLayout() {
    }

    public static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /** Returns {@code ratio} of {@code total}, kept inside {@code [lo, hi]}. */
    public static int share(int total, float ratio, int lo, int hi) {
        return clamp(Math.round(total * ratio), lo, hi);
    }

    /**
     * Remaining width after taking {@code used} and {@code gaps} out of {@code total}, never below
     * {@code floor}.
     *
     * <p>A column narrower than its own padding renders as a scissor rect whose right edge is left
     * of its left edge, and children then draw unclipped over the neighbour.
     */
    public static int remainder(int total, int used, int gaps, int floor) {
        return Math.max(floor, total - used - gaps);
    }
}
