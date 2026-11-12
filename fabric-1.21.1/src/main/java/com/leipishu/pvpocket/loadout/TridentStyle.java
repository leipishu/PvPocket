package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.MutableComponent;

/**
 * The three mutually exclusive things a trident can be.
 *
 * <p>This is not a {@link WeaponFlag}. A flag is a bit in a mask, and a mask can say "both" — but
 * Loyalty and Riptide are alternatives, not additions: a trident that flies back to your hand and a
 * trident that launches you are two different weapons, and a player who ticked both would have no
 * idea which one they were holding. Three states in one field is the honest shape, and it makes
 * "both on" unrepresentable rather than merely discouraged.</p>
 *
 * <p>Level is fixed at III, the vanilla maximum. A Loyalty I trident comes back so slowly that it
 * reads as a bug, and there is no duel-relevant reason to want a weaker one; exposing a level slider
 * here would be a control that only ever gets dragged to the top.</p>
 *
 * <p>Names are {@code pvpocket.trident.<id>} so the same words serve the editor's cycle control, the
 * loadout list and the diagnostic command.</p>
 */
public enum TridentStyle {

    /** No enchantment. The trident is a melee weapon and a thrown one that stays where it lands. */
    NONE("none", null),
    LOYALTY("loyalty", Enchantments.LOYALTY),
    RIPTIDE("riptide", Enchantments.RIPTIDE);

    /** Level written for both enchantments: the vanilla maximum. */
    public static final int LEVEL = 3;

    public final String id;
    public final String labelKey;
    /** Null for {@link #NONE}, which writes no enchantment at all. */
    public final ResourceKey<Enchantment> enchantment;

    TridentStyle(String id, ResourceKey<Enchantment> enchantment) {
        this.id = id;
        this.labelKey = "pvpocket.trident." + id;
        this.enchantment = enchantment;
    }

    public MutableComponent labelText() {
        return PvLang.t(labelKey);
    }

    /** Display name for the self-drawn GUI and for chat lines composed as strings. */
    public String label() {
        return PvLang.str(labelKey);
    }

    /** Returns {@link #NONE} for anything unrecognised, so a stale string degrades to "plain trident". */
    public static TridentStyle byId(String id) {
        for (TridentStyle style : values()) {
            if (style.id.equals(id)) {
                return style;
            }
        }
        return NONE;
    }
}
