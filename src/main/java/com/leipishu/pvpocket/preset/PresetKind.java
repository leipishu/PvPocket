package com.leipishu.pvpocket.preset;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * The two things a player can save a preset of.
 *
 * <p>Both are saved in the same file and shared through the same code path, but a loadout string
 * and a rule string are never interchangeable. The kind on every preset stops a rule set from
 * being loaded into the item editor.
 *
 * <p>The id is the wire and disk form, so it must not change with the client's language. The label
 * is {@code pvpocket.preset.kind.<id>}.
 */
public enum PresetKind {

    /** A whole loadout: the item list, without any player or team. */
    LOADOUT("loadout"),
    /**
     * The rule set, without the roster.
     *
     * <p>The sides belong to one match and its players, so a preset never re-seats anyone.
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

    /** Returns null for anything unrecognised; the caller decides whether that is an error. */
    public static PresetKind byId(String id) {
        for (PresetKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        return null;
    }
}
