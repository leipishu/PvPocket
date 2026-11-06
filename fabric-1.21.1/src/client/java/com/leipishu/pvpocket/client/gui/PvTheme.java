package com.leipishu.pvpocket.client.gui;

/**
 * The visual language, defined once so that every panel in the mod looks like it came from the same
 * product instead of each screen inventing its own skin.
 *
 * <p>Rules of the system, revision 2:</p>
 * <ul>
 *   <li>Near-black surfaces with a five-step elevation ladder, not one shared grey.</li>
 *   <li>One warm accent (amber) for selection and the primary action; red is reserved for
 *       destructive actions and keeps its own hover shade.</li>
 *   <li>4px radius on controls, 6px on panels. Nothing is pill-shaped except the switch track.</li>
 *   <li>An 8px spacing grid; panel padding is 16px.</li>
 *   <li>Two type sizes: 1.3× for titles, 1.0× (9px) for everything else.</li>
 *   <li>Four interaction states everywhere, all of them animated. Disabled lowers contrast; it
 *       never hides.</li>
 * </ul>
 *
 * <p>Colours are ARGB. The panel is 97% opaque: on black, a translucent surface reads as grey haze
 * rather than as a surface, and the point of this palette is that the panel is the darkest thing on
 * screen except the wells inside it.</p>
 */
public final class PvTheme {

    // Scrim — 77% black behind a modal panel. Deep enough that the world cannot compete with the
    // text, shallow enough that a player still knows where they are standing.
    public static final int SCRIM = 0xC4000000;

    // Surfaces, darkest to lightest. The ladder is the whole depth model: there are no gradients
    // and no drop shadows on panels, so "raised" has to be spelled with one of these five values.
    public static final int PANEL = 0xF70A0A0B;
    public static final int SIDEBAR = 0xFF121214;
    public static final int CARD = 0xFF151517;
    public static final int CARD_HOVER = 0xFF1E1E21;
    public static final int CARD_ACTIVE = 0xFF26262B;
    public static final int SUNKEN = 0xFF060607;
    public static final int TOOLTIP = 0xFA101012;

    // Lines
    public static final int BORDER = 0xFF26262A;
    public static final int BORDER_STRONG = 0xFF34343A;
    public static final int DIVIDER = 0xFF1C1C1F;
    /** A 4% white hairline along the top inside edge; the only "light source" in the system. */
    public static final int HIGHLIGHT = 0x0AFFFFFF;

    // Accent — amber. Selection, primary action, switch track, slider fill, focus ring.
    public static final int ACCENT = 0xFFF5A524;
    public static final int ACCENT_HOVER = 0xFFFFB84D;
    public static final int ACCENT_PRESSED = 0xFFD98A12;
    public static final int ACCENT_DIM = 0xFF7A5410;
    public static final int ACCENT_SOFT = 0x24F5A524;
    public static final int ON_ACCENT = 0xFF0A0A0B;

    // Danger — red stays red. Used for reject, cancel and remove, and nothing else.
    public static final int DANGER = 0xFFE5484D;
    public static final int DANGER_HOVER = 0xFFF2666B;
    public static final int DANGER_PRESSED = 0xFFC03338;
    public static final int DANGER_DIM = 0xFF7A2A2E;
    public static final int DANGER_SOFT = 0x24E5484D;

    // Text. Contrast on PANEL: 17:1 / 7.4:1 / 3.4:1. The muted step is for meta text only and is
    // never the sole carrier of a meaning.
    public static final int TEXT = 0xFFEDEDEF;
    public static final int TEXT_DIM = 0xFF9A9AA0;
    public static final int TEXT_MUTED = 0xFF616167;

    // Status — HUD only. A single desaturated green so "live" and "match point" stay tellable apart
    // from the amber accent at a glance, without adding a second brand colour to the panels.
    public static final int LIVE = 0xFF4E9E6A;

    /**
     * The four side colours, as wool.
     *
     * <p>Red, blue, green and yellow, and the values are the wool colours rather than the chat
     * colours: a player who is told "the red team" has to be able to point at a red block on the
     * ground and have it match what the panel showed them. They are dark, so anything that has to be
     * read as text uses {@link #teamText} instead — see {@link #teamInk}.</p>
     */
    public static final int[] TEAM_WOOL = {
            0xFFA12722, // red
            0xFF35399D, // blue
            0xFF546D1B, // green
            0xFFF8C627, // yellow
    };

    public static int team(int index) {
        return TEAM_WOOL[Math.floorMod(index, TEAM_WOOL.length)];
    }

    /**
     * The wool colour lifted until it can carry text on a near-black panel.
     *
     * <p>Wool red is {@code #A12722} — 4.6:1 on {@link #PANEL}, which is not enough for a 9px label.
     * Blending half-way to white keeps the hue recognisable as the same wool and lands around 9:1.
     * The card's colour bar uses the raw wool value, so the two never disagree about which side a
     * card is; only the legibility differs.</p>
     */
    public static int teamInk(int index) {
        return mix(team(index), 0xFFFFFFFF, 0.52F);
    }

    /** The wool colour washed onto a surface, for a card that should read as "this side's". */
    public static int teamWash(int index, int surface, float amount) {
        return mix(surface, team(index), amount);
    }

    // Metrics
    public static final int PAD = 8;
    public static final int PAD_L = 16;
    public static final int RADIUS = 4;
    public static final int RADIUS_PANEL = 6;
    public static final int ROW_H = 28;
    public static final int ROW_H_TALL = 46;
    public static final int BTN_H = 24;
    public static final int TAB_H = 26;
    public static final int SCROLLBAR_W = 6;

    /** One switch for the whole animation layer; false makes every control snap like before. */
    public static final boolean MOTION = true;

    private PvTheme() {
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
