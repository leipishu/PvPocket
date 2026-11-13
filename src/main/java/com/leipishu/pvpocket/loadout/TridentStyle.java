package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.MutableComponent;

/**
 * The three mutually exclusive things a trident can be.
 *
 * <p>This is not a {@link WeaponFlag}. A mask can say "both", but Loyalty and Riptide are
 * alternatives: a trident that flies back and a trident that launches the thrower are different
 * weapons. Three states in one field make "both on" unrepresentable.
 *
 * <p>Level is fixed at III, the vanilla maximum, because a weaker enchantment has no duel use.
 *
 * <p>Names are {@code pvpocket.trident.<id>}, shared by the editor, the loadout list and the
 * diagnostic command.
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

    /** Returns {@link #NONE} for anything unrecognised, so a stale string degrades safely. */
    public static TridentStyle byId(String id) {
        for (TridentStyle style : values()) {
            if (style.id.equals(id)) {
                return style;
            }
        }
        return NONE;
    }
}
