package com.leipishu.pvpocket.i18n;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

/**
 * Translation helpers, and the key convention every user-visible string follows.
 *
 * <p>Text is localised the vanilla way: the server sends a translatable component and each client
 * resolves it against its own language file. No code here holds a current language.
 *
 * <p>Keys live under {@code pvpocket}: {@code rule.<id>.label}, {@code .hint}, {@code .c<N>} and
 * {@code .unit} for rules; {@code item.*}, {@code material.*}, {@code flag.*}, {@code option.*} and
 * {@code map.*} for catalog entries; {@code gui.*}, {@code msg.*} and {@code command.*} for drawn,
 * chat and help strings. Command syntax is a literal: {@code /pvpocket spectate leave} is passed
 * to {@link #t} as an argument rather than as a key.
 */
public final class PvLang {

    private PvLang() {
    }

    /**
     * A translatable text.
     *
     * <p>Arguments are substituted into {@code %s} placeholders. Numbers are passed as {@code %s}
     * too: {@code String.format} renders an {@code Integer} the same way, and mixing {@code %d} in
     * would make the two language files disagree about which arguments are numeric.</p>
     */
    public static MutableComponent t(String key, Object... args) {
        return args.length == 0 ? Component.translatable(key) : Component.translatable(key, args);
    }

    /**
     * Resolves a key to the string the current language shows.
     *
     * <p>Client only. A dedicated server has no language, so a key resolved there comes back as the
     * key itself. This exists for the self-drawn GUI, whose primitives take a string and measure it
     * with {@code TextRenderer}.
     */
    public static String str(String key, Object... args) {
        return t(key, args).getString();
    }

    /** Resolves a {@link Text} to a string, for the same self-drawn GUI paths as {@link #str}. */
    public static String str(Component text) {
        return text.getString();
    }
}
