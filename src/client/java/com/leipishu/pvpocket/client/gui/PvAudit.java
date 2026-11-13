package com.leipishu.pvpocket.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layout audit: finds the places where two things are drawn on top of each other.
 *
 * <p>Every frame it takes the rectangles that were actually used, from widget boxes and from
 * {@link PvDraw} text runs, and reports the pairs that collide, the boxes that fall outside their
 * panel and the boxes that were given no room. The kinds are {@code degenerate}, {@code escapes},
 * {@code box/box}, {@code text/text} and {@code text/box}.
 *
 * <p>Free of Minecraft imports, so it can run inside the client's render pass. Off unless
 * {@code -Dpvpocket.audit=true}; {@link PvAuditHarness} walks every screen at two canvas sizes.
 */
public final class PvAudit {

    /** Enabled by the audit harness only; every entry point below returns immediately otherwise. */
    public static final boolean ON = Boolean.getBoolean("pvpocket.audit");

    /** Surface of the panel itself: the chrome, the well, and every root widget on them. */
    public static final String CHROME_LAYER = "chrome";
    /** Surface of the modal layer, which is allowed to cover everything below it. */
    public static final String OVERLAY_LAYER = "overlay";
    /** Prefix for a scroll pane's own surface; the pane's identity hash follows. */
    public static final String PANE_LAYER = "pane:";
    /**
     * Surface of a tooltip.
     *
     * <p>A tooltip is drawn at the pointer and may cover anything. Its own surface keeps the audit
     * from reporting "the hint covers the button it describes".
     */
    public static final String TOOLTIP_LAYER = "tooltip";

    /** Height of one line of the 9px face when checking collisions. */
    private static final int TEXT_H = 9;
    /** Text is identified by a prefix of what it says; a long sentence is not a useful key. */
    private static final int TEXT_LABEL = 28;

    private static final List<Box> BOXES = new ArrayList<>();
    private static final Map<String, Integer> KINDS = new LinkedHashMap<>();
    private static final List<String> FINDINGS = new ArrayList<>();
    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();
    /** Shrunk label count, wrapped label count, and the distinct strings that were cut. */
    private static int SHRUNK;
    private static int WRAPPED;
    private static final java.util.Set<String> CUT_LABELS = new java.util.LinkedHashSet<>();

    private static String screen = "";
    private static int canvasW;
    private static int canvasH;
    private static boolean active;
    /**
     * Which surface is being drawn right now.
     *
     * <p>Two boxes are only compared when they are on the same surface, because most apparent
     * overlaps are one surface covering another: a dialog covers the panel, a scrolled row is
     * clipped by its viewport.
     */
    private static String layer = "";

    private PvAudit() {
    }

    /** One measured rectangle. {@code text} boxes are glyph runs, not widgets. */
    private static final class Box {
        final String id;
        final String name;
        final String group;
        final String parent;
        final String layer;
        final int x;
        final int y;
        final int w;
        final int h;
        final int clipX;
        final int clipY;
        final int clipW;
        final int clipH;
        final boolean clipped;
        final boolean text;

        Box(String id, String name, String group, String parent, String layer,
            int x, int y, int w, int h,
            int clipX, int clipY, int clipW, int clipH, boolean clipped, boolean text) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.parent = parent;
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.clipX = clipX;
            this.clipY = clipY;
            this.clipW = clipW;
            this.clipH = clipH;
            this.clipped = clipped;
            this.text = text;
        }

        boolean holds(Box other) {
            return other.x >= x && other.y >= y
                    && other.x + other.w <= x + w && other.y + other.h <= y + h;
        }
    }

    // ------------------------------------------------------------------ recording

    public static void begin(String screenName, int width, int height) {
        if (!ON) {
            return;
        }
        BOXES.clear();
        screen = screenName;
        canvasW = width;
        canvasH = height;
        active = true;
    }

    /** The surface name of a scroll pane's contents. Shared by the walk and by the pane itself. */
    public static String paneLayer(Object pane) {
        return PANE_LAYER + Integer.toHexString(System.identityHashCode(pane));
    }

    /** True while a screen is measuring; text drawn outside that window is not attributed. */
    public static boolean recording() {
        return active;
    }

    /**
     * Sets the surface that subsequent text belongs to, and returns the previous one.
     *
     * <p>Text is recorded where it is drawn rather than where it is laid out, so it has to be told
     * which surface it is on; the caller restores the returned value when it is done.</p>
     */
    public static String layer(String next) {
        String previous = layer;
        layer = next;
        return previous;
    }

    /**
     * A widget box.
     *
     * @param id unique within the frame, so a parent can be recognised as a parent
     *
     * @param parent id of the container that positioned it, or {@code null} for a root
     *
     * @param layer the surface it is drawn on; see {@link #layer}
     *
     * @param clip the rectangle this box is expected to stay inside, or {@code null} for none
     */
    public static void widget(String id, String name, String group, String parent, String layer,
                              int x, int y, int w, int h,
                              int clipX, int clipY, int clipW, int clipH, boolean clipped) {
        if (!active) {
            return;
        }
        BOXES.add(new Box(id, name, group, parent, layer, x, y, w, h,
                clipX, clipY, clipW, clipH, clipped, false));
    }

    /** A text run: {@code x} is its left edge, {@code w} what the font measured. */
    public static void text(String value, int x, int y, int w) {
        text(value, x, y, w, TEXT_H);
    }

    /**
     * Records a text run whose height is not the nominal line height.
     *
     * <p>Only shrunk labels use this: a label drawn at 0.8x is 7px tall, not 9.
     */
    public static void text(String value, int x, int y, int w, int h) {
        if (!active || w <= 0 || value == null || value.isEmpty()) {
            return;
        }
        String label = value.length() > TEXT_LABEL ? value.substring(0, TEXT_LABEL) + "…" : value;
        BOXES.add(new Box("t" + BOXES.size(), '"' + label + '"', "text", null, layer,
                x, y, w, h, 0, 0, 0, 0, false, true));
    }

    // ------------------------------------------------------------------ checking

    /** Runs the checks for the frame that just finished recording. */
    public static void end() {
        if (!active) {
            return;
        }
        active = false;
        for (int i = 0; i < BOXES.size(); i++) {
            Box a = BOXES.get(i);
            if (a.w <= 0 || a.h <= 0) {
                report("degenerate", a, null, 0);
                continue;
            }
            if (a.clipped && (a.x < a.clipX || a.x + a.w > a.clipX + a.clipW
                    || a.y < a.clipY || a.y + a.h > a.clipY + a.clipH)) {
                report("escapes", a, null, 0);
            }
            if (a.text) {
                // A row scrolled past the viewport is clipped by the scissor, so its text may be
                // off the bottom of the canvas, but nothing clips horizontally.
                boolean scrolls = a.layer.startsWith(PANE_LAYER);
                if (a.x < 0 || a.x + a.w > canvasW
                        || (!scrolls && (a.y < 0 || a.y + a.h > canvasH))) {
                    report("off-canvas", a, null, 0);
                }
            }
            for (int j = i + 1; j < BOXES.size(); j++) {
                Box b = BOXES.get(j);
                if (b.w <= 0 || b.h <= 0 || !a.layer.equals(b.layer)) {
                    continue;
                }
                int overlap = overlap(a, b);
                if (overlap <= 0) {
                    continue;
                }
                // A container and the thing it positioned are supposed to overlap. Anything else
                // that shares pixels is a defect, whether or not it is in the same group.
                if (a.id.equals(b.parent) || b.id.equals(a.parent)) {
                    continue;
                }
                if (a.text && b.text) {
                    report("text/text", a, b, overlap);
                } else if (a.text != b.text) {
                    Box t = a.text ? a : b;
                    Box o = a.text ? b : a;
                    if (!o.holds(t)) {
                        report("text/box", t, o, overlap);
                    }
                } else if (!a.holds(b) && !b.holds(a)) {
                    report("box/box", a, b, overlap);
                }
            }
        }
    }

    private static int overlap(Box a, Box b) {
        int x0 = Math.max(a.x, b.x);
        int y0 = Math.max(a.y, b.y);
        int x1 = Math.min(a.x + a.w, b.x + b.w);
        int y1 = Math.min(a.y + a.h, b.y + b.h);
        return x1 > x0 && y1 > y0 ? (x1 - x0) * (y1 - y0) : 0;
    }

    /**
     * Records one finding, once.
     *
     * <p>The audit runs every frame, so a repeated finding is dropped to keep one misplaced button
     * from printing sixty identical lines a second. The key includes the position, so the same
     * defect at a different canvas size is reported again.
     */
    private static void report(String kind, Box a, Box b, int overlap) {
        String signature = screen + '|' + kind + '|' + a.name + '|' + (b == null ? "" : b.name)
                + '|' + a.x + ',' + a.y + ',' + a.w + ',' + a.h
                + '|' + (b == null ? "" : b.x + "," + b.y + "," + b.w + "," + b.h);
        if (!SEEN.add(signature)) {
            return;
        }
        KINDS.merge(kind, 1, Integer::sum);
        StringBuilder line = new StringBuilder("[pvaudit] ").append(screen)
                .append(' ').append(canvasW).append('x').append(canvasH)
                .append(' ').append(kind).append(": ").append(describe(a));
        if (b != null) {
            line.append(" x ").append(describe(b)).append(" (").append(overlap).append("px2)");
        }
        FINDINGS.add(line.toString());
        // Straight to stdout: the client's own log is the file a developer already has open.
        System.out.println(line);
    }

    private static String describe(Box box) {
        return box.name + '@' + box.x + ',' + box.y + ' ' + box.w + 'x' + box.h;
    }

    /** Prints the totals and clears them, so the harness can run several rounds. */
    public static int summary(String label) {
        if (!ON) {
            return 0;
        }
        System.out.println("[pvaudit] == " + label + ": " + FINDINGS.size() + " finding(s) ==");
        for (Map.Entry<String, Integer> entry : KINDS.entrySet()) {
            System.out.println("[pvaudit]    " + entry.getKey() + ": " + entry.getValue());
        }
        if (FINDINGS.isEmpty()) {
            System.out.println("[pvaudit]    clean");
        }
        // Labels are a separate report from overlaps: a shrunk label is the system working, and a
        // cut one means the word did not fit even at the legibility floor.
        System.out.println("[pvaudit]    labels: " + SHRUNK + " shrunk, " + WRAPPED + " wrapped, "
                + CUT_LABELS.size() + " cut");
        for (String cut : CUT_LABELS) {
            System.out.println("[pvaudit]    cut: \"" + cut + '"');
        }
        int total = FINDINGS.size();
        FINDINGS.clear();
        KINDS.clear();
        SEEN.clear();
        CUT_LABELS.clear();
        SHRUNK = 0;
        return total;
    }

    /** One label drawn below 1.0x because its frame was narrower than the word. */
    public static void shrunk() {
        if (ON) {
            SHRUNK++;
        }
    }

    /** One label that needed a second line because shrinking alone would have cut it. */
    public static void wrapped() {
        if (ON) {
            WRAPPED++;
        }
    }

    /**
     * One label that did not fit even at {@link PvDraw#MIN_TEXT_SCALE} and was cut.
     *
     * <p>The frame width is recorded with it: "this string needed 84px and had 46px" says whether
     * the frame is slightly too narrow or the row is out of room.
     */
    public static void cut(String value, int maxWidth, int naturalWidth) {
        if (ON && value != null && !value.isEmpty()) {
            // The screen and the canvas go with it: the same string is cut on a 320x180 canvas and
            // fits at 426x240.
            CUT_LABELS.add(value + "  [" + screen + ' ' + canvasW + 'x' + canvasH
                    + ", frame " + maxWidth + "px, needs "
                    + Math.round(naturalWidth * PvDraw.MIN_TEXT_SCALE) + "px at the floor]");
        }
    }

    /** How many distinct findings have been recorded since the last {@link #summary}. */
    public static int count() {
        return FINDINGS.size();
    }
}
