package com.leipishu.pvpocket.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.storage.PvPocketStorage;
import net.fabricmc.loader.api.FabricLoader;

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

    /** On-disk shape. Public fields, no adapters, tolerant of missing keys. */
    private static final class File_ {
        int version = 1;
        String theme = "dark";
        boolean motion = true;
    }

    private static boolean loaded;
    private static PvTheme.Theme theme = PvTheme.Theme.DARK;
    private static boolean motion = true;

    private ClientPrefs() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvpocket").resolve("client.json");
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

    private static void save() {
        File_ out = new File_();
        out.theme = theme == PvTheme.Theme.LIGHT ? "light" : "dark";
        out.motion = motion;
        try {
            Files.createDirectories(file().getParent());
            PvPocketStorage.writeAtomically(file(),
                    GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            PvPocket.LOGGER.error("Cannot save client preferences to {}", file(), e);
        }
    }
}
