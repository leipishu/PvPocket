package com.leipishu.pvpocket.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layout audit: finds the places where two things are drawn on top of each other.
 *
 * <p>The panel is drawn from code, so "does this overlap" is not a question the compiler or the
 * renderer can answer — a widget painted 6px too low simply paints over its neighbour, and the only
 * witness is whoever happens to open that screen at that window size. This class is that witness,
 * written down: every frame it takes the rectangles that were actually used (widget boxes from the
 * tree, text runs from {@link PvDraw}) and reports the pairs that collide, the boxes that fall
 * outside their panel, and the boxes that were given no room at all.</p>
 *
 * <p>Deliberately free of Minecraft imports. It is fed rectangles by {@link PvScreen} and
 * {@link PvDraw} and knows nothing about drawing, which is what lets it run inside the client's own
 * render pass — measuring the real layout instead of a model of it — and still be read as a plain
 * geometry checker.</p>
 *
 * <h2>What counts as a defect</h2>
 *
 * <ul>
 *   <li><b>degenerate</b> — a visible box with no width or no height. Either a layout branch
 *       computed a negative size, or a widget was never given one; both draw nothing while still
 *       taking clicks.</li>
 *   <li><b>escapes</b> — a box outside the panel (roots) or the viewport (overlay). The scissor
 *       does not clamp a reversed rectangle, so a root that runs past the panel edge is drawn over
 *       whatever is beside it.</li>
 *   <li><b>box/box</b> — two widget boxes overlapping, neither of them inside the other. Siblings
 *       in one container, a root over another root, a root over a row: whoever is drawn last steals
 *       the clicks.</li>
 *   <li><b>text/text</b> — two text runs overlapping. On a 9px face that is unreadable mush, and it
 *       is the defect a screenshot shows first.</li>
 *   <li><b>text/box</b> — a text run crossing a widget it does not belong to (a label that was not
 *       fitted to the space it had). Text <em>inside</em> a widget is normal and is not reported.</li>
 * </ul>
 *
 * <p>Off unless {@code -Dpvpocket.audit=true}. {@link PvAuditHarness} turns it on and walks every
 * screen at two canvas sizes; {@code scripts/audit-ui.sh} runs that and fails on any finding.</p>
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
     * <p>A tooltip is drawn at the pointer and is allowed to cover anything — that is what it is
     * for. It gets its own surface so the audit does not read "the hint covers the button it
     * describes" as a defect sixty times a run.</p>
     */
    public static final String TOOLTIP_LAYER = "tooltip";

    /** Height of one line of the 9px face, for collision purposes. */
    private static final int TEXT_H = 9;
    /** Text is identified by a prefix of what it says; a long sentence is not a useful key. */
    private static final int TEXT_LABEL = 28;

    private static final List<Box> BOXES = new ArrayList<>();
    private static final Map<String, Integer> KINDS = new LinkedHashMap<>();
    private static final List<String> FINDINGS = new ArrayList<>();
    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();

    private static String screen = "";
    private static int canvasW;
    private static int canvasH;
    private static boolean active;
    /**
     * Which surface is being drawn right now.
     *
     * <p>Two boxes are only compared when they are on the same surface, because most of what looks
     * like an overlap is one surface legitimately covering another: a dialog covers the panel it
     * opened over, and a scrolled row is clipped by its viewport rather than colliding with the
     * status line under it. Without this the audit reports the modal layer sixty times and the real
     * defects drown.</p>
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
     * @param id     unique within the frame, so a parent can be recognised as a parent
     * @param parent id of the container that positioned it, or {@code null} for a root
     * @param layer  the surface it is drawn on; see {@link #layer}
     * @param clip   the rectangle this box is expected to stay inside, or {@code null} for none
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
        if (!active || w <= 0 || value == null || value.isEmpty()) {
            return;
        }
        String label = value.length() > TEXT_LABEL ? value.substring(0, TEXT_LABEL) + "…" : value;
        BOXES.add(new Box("t" + BOXES.size(), '"' + label + '"', "text", null, layer,
                x, y, w, TEXT_H, 0, 0, 0, 0, false, true));
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
                // A row scrolled past the viewport is clipped by the scissor, so its text is allowed
                // to be off the bottom of the canvas; it is not allowed to be off the side of it,
                // because nothing clips horizontally.
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
                // A container and the thing it positioned are supposed to overlap: the pane is
                // larger than its rows, and a row is larger than nothing. Everything else that
                // shares pixels is a defect, whether or not the pair is in the same group.
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
     * <p>The audit runs every frame, so without this a single misplaced button would print sixty
     * identical lines a second and bury everything else. The key is the pair of names plus where
     * they were, which also means the same defect at a different canvas size is reported again —
     * that is the interesting case, because it is how a size-dependent layout bug shows itself.</p>
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
        // Straight to stdout: the client's own log is the one file a developer already has open,
        // and a finding that only exists in memory is a finding nobody reads.
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
        int total = FINDINGS.size();
        FINDINGS.clear();
        KINDS.clear();
        SEEN.clear();
        return total;
    }

    /** How many distinct findings have been recorded since the last {@link #summary}. */
    public static int count() {
        return FINDINGS.size();
    }
}
