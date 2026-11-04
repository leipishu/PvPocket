package com.leipishu.pvpocket.client.gui;

/**
 * The visual language, defined once so that every panel in the mod looks like it came from the same
 * product instead of each screen inventing its own skin.
 *
 * <p>Rules of the system, from the locked spec §9.1:</p>
 * <ul>
 *   <li>One dark translucent surface, one 1px border, 4px corner radius.</li>
 *   <li>A single accent colour, used for selection and the primary action. Nothing else.</li>
 *   <li>An 8px spacing grid; panel padding is 16px.</li>
 *   <li>Two type sizes: 1.3× for titles, 1.0× (9px) for everything else.</li>
 *   <li>Four interaction states everywhere. Disabled lowers contrast; it never hides.</li>
 * </ul>
 *
 * <p>Colours are ARGB. Every surface is opaque enough to stay readable over bright terrain — a
 * 90% panel over sand still holds a 4.5:1 contrast ratio for body text.</p>
 */
public final class PvTheme {

    // Surfaces
    public static final int PANEL_BG = 0xF0141721;
    public static final int PANEL_BORDER = 0xFF2E3648;
    public static final int SURFACE = 0xFF1B2030;
    public static final int SURFACE_HOVER = 0xFF232A3C;
    public static final int SURFACE_ACTIVE = 0xFF2B3450;
    public static final int SURFACE_SUNKEN = 0xFF12151F;
    public static final int DIVIDER = 0xFF262D3D;
    public static final int SHADOW = 0x66000000;

    // Accent
    public static final int ACCENT = 0xFF5B9CFF;
    public static final int ACCENT_DIM = 0xFF2F4E80;
    public static final int ACCENT_SOFT = 0x335B9CFF;
    public static final int ON_ACCENT = 0xFF0B0F17;

    // Text
    public static final int TEXT = 0xFFE7EBF4;
    public static final int TEXT_DIM = 0xFF9AA3B8;
    public static final int TEXT_MUTED = 0xFF5F6880;

    // Semantic
    public static final int OK = 0xFF56CE8A;
    public static final int WARN = 0xFFE7B44C;
    public static final int DANGER = 0xFFE26D6D;

    // Metrics
    public static final int PAD = 8;
    public static final int PAD_L = 16;
    public static final int RADIUS = 4;
    public static final int ROW_H = 22;
    public static final int ROW_H_TALL = 40;
    public static final int BTN_H = 20;
    public static final int TAB_H = 22;
    public static final int SCROLLBAR_W = 4;
    public static final float TITLE_SCALE = 1.3F;

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

    /** Scales the alpha of a colour, used for fade-in and for disabled surfaces. */
    public static int alpha(int color, float factor) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0F, Math.min(1F, factor)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
