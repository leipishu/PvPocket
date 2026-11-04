package com.leipishu.pvpocket.loadout;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.registry.RegistryKey;

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
 * <p>{@link #appliesTo} is not decoration. Offering "无限" on a sword would produce an item with an
 * enchantment the game ignores, and a player who set it would believe they had configured something.
 * The editor only shows the flags a kind can actually carry.</p>
 */
public enum WeaponFlag {

    SWEEPING("sweeping", "横扫", Enchantments.SWEEPING_EDGE, 3, ItemKind.SWORD),
    INFINITY("infinity", "无限", Enchantments.INFINITY, 1, ItemKind.BOW),
    FLAME("flame", "火矢", Enchantments.FLAME, 1, ItemKind.BOW),
    PUNCH("punch", "冲击", Enchantments.PUNCH, 2, ItemKind.BOW),
    RIPTIDE("riptide", "激流", Enchantments.RIPTIDE, 3, ItemKind.TRIDENT),
    PIERCING("piercing", "穿透", Enchantments.PIERCING, 4, ItemKind.CROSSBOW);

    public final String id;
    public final String label;
    public final RegistryKey<Enchantment> enchantment;
    /** Highest level the flag will write. Level is fixed at 1 for boolean-style flags. */
    public final int maxLevel;
    private final ItemKind[] kinds;

    WeaponFlag(String id, String label, RegistryKey<Enchantment> enchantment, int maxLevel, ItemKind... kinds) {
        this.id = id;
        this.label = label;
        this.enchantment = enchantment;
        this.maxLevel = maxLevel;
        this.kinds = kinds;
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
        return 1 << ordinal();
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
