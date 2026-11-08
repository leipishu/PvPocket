package com.leipishu.pvpocket.client.gui;

/**
 * The visual language, defined once so that every panel in the mod looks like it came from the same
 * product instead of each screen inventing its own skin.
 *
 * <p>Rules of the system, revision 3:</p>
 * <ul>
 *   <li>Near-black surfaces with a five-step elevation ladder, not one shared grey. The light theme
 *       inverts the ladder rather than tinting it.</li>
 *   <li>One warm accent (amber) for selection and the primary action; red is reserved for
 *       destructive actions and keeps its own hover shade.</li>
 *   <li>4px radius on controls, 6px on panels. Nothing is pill-shaped except the switch track.</li>
 *   <li>An 8px spacing grid; panel padding is 16px.</li>
 *   <li>One type size: 1.0× (9px). Hierarchy comes from weight and colour.</li>
 *   <li>Four interaction states everywhere, all of them animated. Disabled lowers contrast; it
 *       never hides.</li>
 * </ul>
 *
 * <h2>Why these are not constants</h2>
 *
 * <p>They were {@code static final int}, which javac folds into every call site as a literal. That
 * made a runtime theme switch impossible in a way that is very hard to diagnose: assigning a new
 * value to the field compiles, runs, and changes nothing, because the 282 places that read it do not
 * read the field at all. Dropping {@code final} is the whole fix — no call site had to change.</p>
 *
 * <p>Colours are ARGB. The panel is 97% opaque: on black, a translucent surface reads as grey haze
 * rather than as a surface, and the point of this palette is that the panel is the darkest thing on
 * screen except the wells inside it.</p>
 */
public final class PvTheme {

    /** Which palette is installed. */
    public enum Theme {
        DARK,
        LIGHT
    }

    // Scrim — 77% black behind a modal panel. Deep enough that the world cannot compete with the
    // text, shallow enough that a player still knows where they are standing.
    public static int SCRIM;

    // Surfaces, darkest to lightest. The ladder is the whole depth model: there are no gradients
    // and no drop shadows on panels, so "raised" has to be spelled with one of these five values.
    public static int PANEL;
    /**
     * The chrome: the title band and the sidebar column, drawn as one continuous surface.
     *
     * <p>It has to be a full step away from {@link #PANEL}, not a nudge: the chrome and the content
     * well meet along two straight edges with no line between them, and a 3% luminance step reads as
     * a smudge on the panel rather than as a change of region.</p>
     */
    public static int SIDEBAR;
    public static int CARD;
    public static int CARD_HOVER;
    public static int CARD_ACTIVE;
    public static int SUNKEN;
    public static int TOOLTIP;

    // Lines
    public static int BORDER;
    public static int BORDER_STRONG;
    public static int DIVIDER;
    /**
     * A hairline along the top inside edge; the only "light source" in the system.
     *
     * <p>White at 4% in the dark theme, black at 5% in the light one — the point is that the top edge
     * catches light, and on a white surface light is what the surface already is.</p>
     */
    public static int HIGHLIGHT;

    // Accent — amber. Selection, primary action, switch track, slider fill, focus ring.
    public static int ACCENT;
    public static int ACCENT_HOVER;
    public static int ACCENT_PRESSED;
    public static int ACCENT_DIM;
    public static int ACCENT_SOFT;
    public static int ON_ACCENT;

    // Danger — red stays red. Used for reject, cancel and remove, and nothing else.
    public static int DANGER;
    public static int DANGER_HOVER;
    public static int DANGER_PRESSED;
    public static int DANGER_DIM;
    public static int DANGER_SOFT;

    // Text. Contrast on PANEL: 17:1 / 7.4:1 / 3.4:1 in the dark theme. The muted step is for meta
    // text only and is never the sole carrier of a meaning.
    public static int TEXT;
    public static int TEXT_DIM;
    public static int TEXT_MUTED;

    // Status — HUD only. A single desaturated green so "live" and "match point" stay tellable apart
    // from the amber accent at a glance, without adding a second brand colour to the panels.
    public static int LIVE;

    /**
     * The four side colours, as wool.
     *
     * <p>Red, blue, green and yellow, and the values are the wool colours rather than the chat
     * colours: a player who is told "the red team" has to be able to point at a red block on the
     * ground and have it match what the panel showed them. They are dark, so anything that has to be
     * read as text uses {@link #teamText} instead — see {@link #teamInk}.</p>
     *
     * <p>These do not change with the theme: they are the colour of a block in the world.</p>
     */
    public static final int[] TEAM_WOOL = {
            0xFFA12722, // red
            0xFF35399D, // blue
            0xFF546D1B, // green
            0xFFF8C627, // yellow
    };

    /** Metrics are theme-independent. */
    public static final int PAD = 8;
    public static final int PAD_L = 16;
    public static final int RADIUS = 4;
    public static final int RADIUS_PANEL = 6;
    public static final int ROW_H = 24;
    public static final int ROW_H_TALL = 40;
    public static final int BTN_H = 20;
    public static final int TAB_H = 24;
    public static final int SCROLLBAR_W = 6;

    /** One switch for the whole animation layer; false makes every control snap like before. */
    public static boolean MOTION = true;

    private static Theme current = Theme.DARK;

    static {
        apply(Theme.DARK);
    }

    private PvTheme() {
    }

    public static Theme current() {
        return current;
    }

    /**
     * Installs a palette.
     *
     * <p>Every token is assigned, including the ones the two themes share. A partial switch is the
     * one bug this design invites — a surface that stays dark under a light theme is not a subtle
     * mistake, it is a black rectangle in the middle of the screen — so the two blocks below are kept
     * as full parallel lists.</p>
     */
    public static void apply(Theme theme) {
        current = theme == null ? Theme.DARK : theme;
        if (current == Theme.LIGHT) {
            SCRIM = 0xC8F2F2F4;
            PANEL = 0xF7F7F7F8;
            SIDEBAR = 0xFFFFFFFF;
            CARD = 0xFFFFFFFF;
            CARD_HOVER = 0xFFF2F2F4;
            CARD_ACTIVE = 0xFFE7E7EB;
            SUNKEN = 0xFFEDEDEF;
            TOOLTIP = 0xFAFFFFFF;

            BORDER = 0xFFDCDCE0;
            BORDER_STRONG = 0xFFC2C2C8;
            DIVIDER = 0xFFE6E6EA;
            HIGHLIGHT = 0x0A000000;

            // Darkened rather than kept: #F5A524 is 1.9:1 on white, which is unusable as text, and
            // the accent is used as text in 38 places. One darker value fixes all of them at once
            // without touching a call site.
            ACCENT = 0xFFB26A00;
            ACCENT_HOVER = 0xFFCC7A00;
            ACCENT_PRESSED = 0xFF8F5500;
            ACCENT_DIM = 0xFFD8C39E;
            ACCENT_SOFT = 0x24B26A00;
            ON_ACCENT = 0xFFFFFFFF;

            DANGER = 0xFFC0332F;
            DANGER_HOVER = 0xFFD9433F;
            DANGER_PRESSED = 0xFF9E2825;
            DANGER_DIM = 0xFFE7B6B3;
            DANGER_SOFT = 0x24C0332F;

            TEXT = 0xFF1A1A1C;
            TEXT_DIM = 0xFF5C5C63;
            TEXT_MUTED = 0xFF8B8B93;

            LIVE = 0xFF2F7D4E;
        } else {
            SCRIM = 0xC4000000;
            PANEL = 0xF70A0A0B;
            SIDEBAR = 0xFF151519;
            CARD = 0xFF151517;
            CARD_HOVER = 0xFF1E1E21;
            CARD_ACTIVE = 0xFF26262B;
            SUNKEN = 0xFF060607;
            TOOLTIP = 0xFA101012;

            BORDER = 0xFF26262A;
            BORDER_STRONG = 0xFF34343A;
            DIVIDER = 0xFF1C1C1F;
            HIGHLIGHT = 0x0AFFFFFF;

            ACCENT = 0xFFF5A524;
            ACCENT_HOVER = 0xFFFFB84D;
            ACCENT_PRESSED = 0xFFD98A12;
            ACCENT_DIM = 0xFF7A5410;
            ACCENT_SOFT = 0x24F5A524;
            ON_ACCENT = 0xFF0A0A0B;

            DANGER = 0xFFE5484D;
            DANGER_HOVER = 0xFFF2666B;
            DANGER_PRESSED = 0xFFC03338;
            DANGER_DIM = 0xFF7A2A2E;
            DANGER_SOFT = 0x24E5484D;

            TEXT = 0xFFEDEDEF;
            TEXT_DIM = 0xFF9A9AA0;
            TEXT_MUTED = 0xFF616167;

            LIVE = 0xFF4E9E6A;
        }
    }

    public static int team(int index) {
        return TEAM_WOOL[Math.floorMod(index, TEAM_WOOL.length)];
    }

    /**
     * The wool colour lifted (or dropped) until it can carry text on the panel.
     *
     * <p>Wool red is {@code #A12722} — 4.6:1 on the dark panel, which is not enough for a 9px label.
     * Blending half-way towards the theme's ink keeps the hue recognisable as the same wool and lands
     * around 9:1. The card's colour bar uses the raw wool value, so the two never disagree about
     * which side a card is; only the legibility differs.</p>
     */
    public static int teamInk(int index) {
        return mix(team(index), current == Theme.LIGHT ? 0xFF000000 : 0xFFFFFFFF, 0.52F);
    }

    /** The wool colour washed onto a surface, for a card that should read as "this side's". */
    public static int teamWash(int index, int surface, float amount) {
        return mix(surface, team(index), amount);
    }

    /** Blends towards {@code to} by {@code t} in [0,1], preserving alpha of {@code from}. */
    public static int mix(int from, int to, float t) {
        t = Math.max(0F, Math.min(1F, t));
        int a = (from >>> 24) & 0xFF;
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Blends every channel including alpha.
     *
     * <p>{@link #mix} deliberately keeps the alpha of its source, which is what surfaces want when
     * they fade between two opaque fills. Crossfading a translucent fill over an opaque one is the
     * other case, and needs the alpha to move too — without this the accent wash would pop in at
     * full strength halfway through the fade.</p>
     */
    public static int blend(int from, int to, float t) {
        t = Math.max(0F, Math.min(1F, t));
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Scales the alpha of a colour, used for fades, disabled surfaces and soft fills. */
    public static int alpha(int color, float factor) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0F, Math.min(1F, factor)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
