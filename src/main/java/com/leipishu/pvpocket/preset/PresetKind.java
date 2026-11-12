package com.leipishu.pvpocket.preset;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * The two things a player can save a preset of.
 *
 * <p>They are saved in the same file and shared through the same code path, but they are never
 * interchangeable: a loadout string and a rule string are both "text that encodes a configuration"
 * and nothing else. Keeping the kind on every preset is what stops a rule set from being loaded into
 * the item editor, where it would decode to a pile of defaults and look like a broken save.</p>
 *
 * <p>The id is the wire and disk form, so it must not change with the client's language. The label
 * is {@code pvpocket.preset.kind.<id>}.</p>
 */
public enum PresetKind {

    /** A whole loadout: the item list, without any player or team. */
    LOADOUT("loadout"),
    /**
     * The rule set, without the roster.
     *
     * <p>"Without the roster" is not a limitation to work around — the sides are a decision about
     * <em>this</em> match and the people in it, and a preset that silently re-seated players would be
     * a preset that rearranges somebody else's team.</p>
     */
    RULES("rules");

    public final String id;
    public final String labelKey;

    PresetKind(String id) {
        this.id = id;
        this.labelKey = "pvpocket.preset.kind." + id.toLowerCase(Locale.ROOT);
    }

    public MutableComponent labelText() {
        return PvLang.t(labelKey);
    }

    /** Display name for the self-drawn GUI. */
    public String label() {
        return PvLang.str(labelKey);
    }

    /** Returns null for anything unrecognised — the caller decides whether that is an error. */
    public static PresetKind byId(String id) {
        for (PresetKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        return null;
    }
}
