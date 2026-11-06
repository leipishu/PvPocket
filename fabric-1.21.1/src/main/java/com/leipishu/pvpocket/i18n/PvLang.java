package com.leipishu.pvpocket.i18n;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * Translation helpers, and the key convention every user-visible string in this mod follows.
 *
 * <p>Text is localised the vanilla way: the server never decides what a sentence looks like, it
 * sends a {@link Text#translatable} with arguments and lets each client resolve it against its own
 * language file. That is why nothing here has a "current language" notion — the only place a
 * language exists is the client that is drawing.</p>
 *
 * <p>Key layout, all under the {@code pvpocket} namespace:</p>
 * <ul>
 *   <li>{@code pvpocket.rule.<id>.label} / {@code .hint} / {@code .c<N>} / {@code .unit} — one
 *       configurable rule, its tooltip, its Nth choice, and the suffix its value carries. The rule
 *       id is already unique and stable (it is the wire format), so it doubles as the key.</li>
 *   <li>{@code pvpocket.item.*}, {@code pvpocket.material.*}, {@code pvpocket.flag.*},
 *       {@code pvpocket.option.*}, {@code pvpocket.map.*} — catalog entries.</li>
 *   <li>{@code pvpocket.gui.<screen>.<element>} — strings the client draws itself.</li>
 *   <li>{@code pvpocket.msg.<area>.<event>} — chat and action-bar lines.</li>
 *   <li>{@code pvpocket.command.*} — {@code /pvpocket} help and diagnostics.</li>
 * </ul>
 *
 * <p>Command syntax is <b>not</b> translated. {@code /pvpocket spectate leave} is a token the server
 * parses, not a sentence, so it appears verbatim in both language files and is passed to
 * {@link #t} as a literal argument rather than as a key.</p>
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
    public static MutableText t(String key, Object... args) {
        return args.length == 0 ? Text.translatable(key) : Text.translatable(key, args);
    }

    /**
     * Resolves a key to the string the current language shows.
     *
     * <p><b>Client only.</b> A dedicated server has no language of its own, so a key resolved there
     * would come back as the key itself; anything the server sends must stay a {@link Text}. This
     * exists for the self-drawn GUI, whose primitives take {@code String} and measure it directly
     * with {@code TextRenderer}.</p>
     */
    public static String str(String key, Object... args) {
        return t(key, args).getString();
    }

    /** Resolves a {@link Text} to a string, for the same self-drawn GUI paths as {@link #str}. */
    public static String str(Text text) {
        return text.getString();
    }
}
