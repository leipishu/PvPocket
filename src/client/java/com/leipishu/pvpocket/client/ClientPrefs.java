package com.leipishu.pvpocket.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Presentation preferences that belong to this installation, not to a world or a server.
 *
 * <p>Separate from {@code presets.json} because the two have different lifetimes and different
 * failure modes: losing a preset loses something the player made, losing a theme preference costs a
 * click. Keeping them in one file would mean a corrupt preset row could take the theme with it.</p>
 *
 * <p>Key bindings are deliberately <b>not</b> here. They live in vanilla's {@code options.txt} through
 * {@code KeyBinding#setBoundKey}, so that the mod's keys appear in the vanilla controls screen and
 * there is exactly one place a binding can be wrong.</p>
 */
public final class ClientPrefs {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /**
     * Bounds of the history page size, and the value a file that predates the setting gets.
     *
     * <p>Ten is the default because it is the largest round number that still shows more than one
     * page on a typical list without making the pager decorative. Five is the floor: below that the
     * footer is taller than the list it paginates.</p>
     */
    public static final int HISTORY_PAGE_MIN = 5;
    public static final int HISTORY_PAGE_MAX = 30;
    public static final int HISTORY_PAGE_STEP = 5;
    public static final int DEFAULT_HISTORY_PAGE = 10;

    /**
     * Four sizes for the match scoreboard, from smallest to largest.
     *
     * <p>The third step is what the HUD was drawn at before the setting existed, so the default
     * changes nothing for anyone who never opens the settings page. The steps are not evenly spaced
     * on purpose: a player who moves one notch down wants the bar out of the way, not a 5% nudge,
     * while the largest step is bounded by the fact that the bar sits over the middle of the screen
     * — at 1.5× a two-side bar covers a fifth of a 2× GUI view, and the thing it covers is the fight.
     * </p>
     *
     * <p>Nothing smaller than 0.75×: that is the panel's own legibility floor ({@code
     * PvDraw.MIN_TEXT_SCALE}), and a scoreboard whose numbers cannot be read has no reason to exist.
     * </p>
     */
    public static final int HUD_SCALE_MIN = 1;
    public static final int HUD_SCALE_MAX = 4;
    public static final int DEFAULT_HUD_SCALE = 3;
    private static final float[] HUD_SCALE_FACTORS = {0.75F, 0.9F, 1.0F, 1.2F};

    /** On-disk shape. Public fields, no adapters, tolerant of missing keys. */
    private static final class File_ {
        int version = 1;
        String theme = "dark";
        boolean motion = true;
        /** Rows per page on the history list. Zero in a file written before the field existed. */
        int historyPageSize = DEFAULT_HISTORY_PAGE;
        /** Scoreboard size step, 1..4. Zero in a file written before the field existed. */
        int hudScale = DEFAULT_HUD_SCALE;
        /**
         * Whether a finished series opens the result card.
         *
         * <p>On by default, including for a file written before the field existed: Gson leaves an
         * absent field at its initialiser, which is the same answer the settings page shows, and a
         * feature that has to be discovered in a settings page before it does anything is a feature
         * nobody finds. It is one Esc away from being dismissed, and one switch away from never
         * appearing again.</p>
         */
        boolean resultScreen = true;
    }

    private static boolean loaded;
    private static PvTheme.Theme theme = PvTheme.Theme.DARK;
    private static boolean motion = true;
    private static int historyPageSize = DEFAULT_HISTORY_PAGE;
    private static int hudScale = DEFAULT_HUD_SCALE;
    private static boolean resultScreen = true;

    private ClientPrefs() {
    }

    private static Path file() {
        return PvClientPlatform.get().configDir().resolve("pvpocket").resolve("client.json");
    }

    /** Reads the file once and installs what it says. Safe to call more than once. */
    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (Files.isRegularFile(path)) {
            try {
                File_ parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), File_.class);
                if (parsed != null) {
                    theme = "light".equalsIgnoreCase(parsed.theme)
                            ? PvTheme.Theme.LIGHT : PvTheme.Theme.DARK;
                    motion = parsed.motion;
                    historyPageSize = clampPage(parsed.historyPageSize);
                    hudScale = clampHudScale(parsed.hudScale);
                    resultScreen = parsed.resultScreen;
                }
            } catch (Exception e) {
                // A bad preference file costs a theme, not a launch.
                PvPocket.LOGGER.warn("Discarding unreadable client preferences {}", path, e);
            }
        }
        PvTheme.apply(theme);
        PvTheme.MOTION = motion;
    }

    public static PvTheme.Theme theme() {
        return theme;
    }

    public static void setTheme(PvTheme.Theme value) {
        theme = value == null ? PvTheme.Theme.DARK : value;
        PvTheme.apply(theme);
        save();
    }

    public static boolean motion() {
        return motion;
    }

    public static void setMotion(boolean value) {
        motion = value;
        PvTheme.MOTION = value;
        save();
    }

    /** Rows per page on the history list, always within {@link #HISTORY_PAGE_MIN}..{@link #HISTORY_PAGE_MAX}. */
    public static int historyPageSize() {
        return historyPageSize;
    }

    public static void setHistoryPageSize(int value) {
        historyPageSize = clampPage(value);
        save();
    }

    /**
     * Snaps a value onto the allowed range, with zero meaning "not in the file".
     *
     * <p>Gson leaves a field it cannot find at its initialiser, so a missing key is already handled;
     * this covers the other two cases — a file hand-edited to something absurd, and a value written
     * by a future build with a wider range — and both have to survive a downgrade without leaving
     * the list paginating in fifths of a row.</p>
     */
    private static int clampPage(int raw) {
        if (raw <= 0) {
            return DEFAULT_HISTORY_PAGE;
        }
        return Math.max(HISTORY_PAGE_MIN, Math.min(HISTORY_PAGE_MAX, raw));
    }

    /** Scoreboard size step, always within {@link #HUD_SCALE_MIN}..{@link #HUD_SCALE_MAX}. */
    public static int hudScale() {
        return hudScale;
    }

    public static void setHudScale(int value) {
        hudScale = clampHudScale(value);
        save();
    }

    /** The multiplier the HUD draws itself at — what {@link #hudScale()} means on screen. */
    public static float hudScaleFactor() {
        return HUD_SCALE_FACTORS[clampHudScale(hudScale) - HUD_SCALE_MIN];
    }

    /** The same step as a percentage, for the readout. 100% is the default and the old size. */
    public static int hudScalePercent(int step) {
        return Math.round(HUD_SCALE_FACTORS[clampHudScale(step) - HUD_SCALE_MIN] * 100F);
    }

    /** Same contract as {@link #clampPage}: zero means the field was not in the file. */
    private static int clampHudScale(int raw) {
        if (raw <= 0) {
            return DEFAULT_HUD_SCALE;
        }
        return Math.max(HUD_SCALE_MIN, Math.min(HUD_SCALE_MAX, raw));
    }

    /** Whether a finished series opens the result card. See {@link File_#resultScreen}. */
    public static boolean resultScreen() {
        return resultScreen;
    }

    public static void setResultScreen(boolean value) {
        resultScreen = value;
        save();
    }

    private static void save() {
        File_ out = new File_();
        out.theme = theme == PvTheme.Theme.LIGHT ? "light" : "dark";
        out.motion = motion;
        out.historyPageSize = historyPageSize;
        out.hudScale = hudScale;
        out.resultScreen = resultScreen;
        try {
            Files.createDirectories(file().getParent());
            PvPocketStorage.writeAtomically(file(),
                    GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot save client preferences to {}", file(), e);
        }
    }
}
