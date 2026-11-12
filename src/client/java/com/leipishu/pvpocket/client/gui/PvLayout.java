package com.leipishu.pvpocket.client.gui;

/**
 * Layout arithmetic shared by the panels.
 *
 * <p>The panels are drawn at a fixed logical size but the logical canvas is not fixed: 1280x720 at
 * GUI scale 3 is 426x240, the same window at scale 2 is 640x360, and a maximised window at scale 4
 * is 480x270. Every column width used to be a constant chosen against the largest of those, which is
 * how a 200px item list ended up leaving a 66px field column — and, one size further down, a negative
 * one.</p>
 *
 * <p>So columns are a <em>share</em> of the panel with a floor, never a constant. The floor is what
 * stops a share from collapsing to nothing; the cap is what stops it from taking over on a big
 * screen. Both live at the call site so the numbers stay readable next to the panel they belong to.
 * </p>
 */
public final class PvLayout {

    private PvLayout() {
    }

    public static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /** {@code ratio} of {@code total}, kept inside {@code [lo, hi]}. */
    public static int share(int total, float ratio, int lo, int hi) {
        return clamp(Math.round(total * ratio), lo, hi);
    }

    /**
     * Remaining width after taking {@code used} and {@code gaps} out of {@code total}, never below
     * {@code floor}.
     *
     * <p>The floor is not a nicety: a column narrower than its own padding renders as a scissor rect
     * with the right edge left of the left edge, and the renderer does not clamp that — every child
     * draws unclipped over its neighbour. Returning the floor instead means an over-full panel
     * overlaps by a known amount rather than painting outside itself.</p>
     */
    public static int remainder(int total, int used, int gaps, int floor) {
        return Math.max(floor, total - used - gaps);
    }
}
