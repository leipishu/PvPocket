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
 * <p>These are the things that cannot be folded into "damage / durability / knockback": they change
 * how the weapon behaves rather than how hard it hits. Each one maps to a vanilla enchantment, so the
 * effect is the real thing rather than a re-implementation — a swept attack really is a sweep.</p>
 *
 * <p>{@link #appliesTo} is not decoration. Offering Infinity on a sword would produce an item with an
 * enchantment the game ignores, and a player who set it would believe they had configured something.
 * The editor only shows the flags a kind can actually carry.</p>
 *
 * <p>Name and tooltip are {@code pvpocket.flag.<id>} and {@code pvpocket.flag.<id>.hint}. The
 * tooltip lives beside the name rather than in the editor because the editor is not the only place a
 * flag is named — the loadout diagnostic command prints them too.</p>
 */
public enum WeaponFlag {

    SWEEPING("sweeping", Enchantments.SWEEPING_EDGE, 3, 0, ItemKind.SWORD),
    INFINITY("infinity", Enchantments.INFINITY, 1, 1, ItemKind.BOW),
    FLAME("flame", Enchantments.FLAME, 1, 2, ItemKind.BOW),
    PUNCH("punch", Enchantments.PUNCH, 2, 3, ItemKind.BOW),
    // Bit 4 is retired: it was RIPTIDE, which is now one of the three TridentStyle values.
    // Do not reuse it — a saved preset written before the move would silently gain a different flag.
    PIERCING("piercing", Enchantments.PIERCING, 4, 5, ItemKind.CROSSBOW);

    /**
     * The bit the old {@code RIPTIDE} flag occupied.
     *
     * <p>Kept so that {@link LoadoutEntry#decode} can recognise a preset saved before the move and
     * turn it into a {@link TridentStyle} instead of quietly dropping the enchantment. Nothing writes
     * this bit any more.</p>
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
     * <p>Explicit rather than {@code ordinal()} so that removing a flag does not shift every flag
     * after it. The mask is written into saved presets, so a shift is not a cosmetic change: it
     * turns somebody's Piercing crossbow into whatever moved into that bit.</p>
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
