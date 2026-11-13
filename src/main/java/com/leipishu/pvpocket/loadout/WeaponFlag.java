package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.MutableComponent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The toggleable special effects a weapon can carry.
 *
 * <p>Each flag maps to a vanilla enchantment, so the effect is the real thing rather than a
 * reimplementation.
 *
 * <p>{@link #appliesTo} restricts the flags the editor offers, because an enchantment the game
 * ignores would let a player configure something that does nothing.
 *
 * <p>Name and tooltip are {@code pvpocket.flag.<id>} and {@code pvpocket.flag.<id>.hint}, used by
 * the editor and by the loadout diagnostic command.
 */
public enum WeaponFlag {

    SWEEPING("sweeping", Enchantments.SWEEPING_EDGE, 3, 0, ItemKind.SWORD),
    INFINITY("infinity", Enchantments.INFINITY, 1, 1, ItemKind.BOW),
    FLAME("flame", Enchantments.FLAME, 1, 2, ItemKind.BOW),
    PUNCH("punch", Enchantments.PUNCH, 2, 3, ItemKind.BOW),
    // Bit 4 is reserved: a preset written when it held RIPTIDE must not gain a different flag.
    PIERCING("piercing", Enchantments.PIERCING, 4, 5, ItemKind.CROSSBOW),
//? if >=1.21.11 {
    /** The spear's own enchantment, added with the spear in 1.21.11. */
    LUNGE("lunge", Enchantments.LUNGE, 3, 6, ItemKind.SPEAR);
//?} else {
    ;
//?}

    /**
     * The bit the retired {@code RIPTIDE} flag occupied.
     *
     * <p>{@link LoadoutEntry#decode} reads it to turn an older preset into a {@link TridentStyle}.
     * No code writes this bit.
     */
    public static final int RETIRED_RIPTIDE_BIT = 1 << 4;

    public final String id;
    public final String labelKey;
    public final String hintKey;
    public final ResourceKey<Enchantment> enchantment;
    /** Highest level the flag will write. Level is fixed at 1 for boolean-style flags. */
    public final int maxLevel;
    /**
     * Bit position in the encoded mask.
     *
     * <p>Explicit rather than {@code ordinal()}, so removing a flag does not shift the flags after
     * it. The mask is written into saved presets, where a shift would turn a Piercing crossbow into
     * another flag.
     */
    private final int bitIndex;
    private final ItemKind[] kinds;

    WeaponFlag(String id, ResourceKey<Enchantment> enchantment, int maxLevel, int bitIndex,
               ItemKind... kinds) {
        this.id = id;
        this.labelKey = "pvpocket.flag." + id;
        this.hintKey = "pvpocket.flag." + id + ".hint";
        this.enchantment = enchantment;
        this.maxLevel = maxLevel;
        this.bitIndex = bitIndex;
        this.kinds = kinds;
    }

    public MutableComponent labelText() {
        return PvLang.t(labelKey);
    }

    public MutableComponent hintText() {
        return PvLang.t(hintKey);
    }

    /** Display name for the self-drawn GUI and for chat lines composed as strings. */
    public String label() {
        return PvLang.str(labelKey);
    }

    public boolean appliesTo(ItemKind kind) {
        for (ItemKind candidate : kinds) {
            if (candidate == kind) {
                return true;
            }
        }
        return false;
    }

    public int bit() {
        return 1 << bitIndex;
    }

    public static List<WeaponFlag> of(ItemKind kind) {
        return java.util.Arrays.stream(values()).filter(f -> f.appliesTo(kind)).toList();
    }

    public static Set<WeaponFlag> decode(int mask) {
        Set<WeaponFlag> out = EnumSet.noneOf(WeaponFlag.class);
        for (WeaponFlag flag : values()) {
            if ((mask & flag.bit()) != 0) {
                out.add(flag);
            }
        }
        return out;
    }
}
