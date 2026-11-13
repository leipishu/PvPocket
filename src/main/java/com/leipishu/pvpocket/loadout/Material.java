package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.network.chat.MutableComponent;

/**
 * The material a materialised item is built from.
 *
 * <p>Material is display only: an iron sword and a netherite sword differ in colour and in nothing
 * else, because the player sets damage, durability and knockback explicitly.
 * {@link LoadoutBuilder} replaces the {@code attribute_modifiers} component rather than appending
 * to it.
 *
 * <p>The name is a translation key rather than a literal, because the word in front of the item is
 * language-specific.
 */
public enum Material {

    WOOD("wood", "wooden", 0),
    STONE("stone", "stone", 1),
    /**
     * A weapon tier only from 1.21.11 onwards, and not for every kind. {@link ItemKind#materials()}
     * asks the registry per kind, so copper appears only where a copper variant exists.
     */
    COPPER("copper", "copper", 2),
    IRON("iron", "iron", 3),
    GOLD("gold", "golden", 4),
    DIAMOND("diamond", "diamond", 5),
    NETHERITE("netherite", "netherite", 6);

    public final String id;
    public final String labelKey;
    private final String itemPrefix;
    /** Display order only; it carries no stat meaning. */
    public final int tier;

    Material(String id, String itemPrefix, int tier) {
        this.id = id;
        this.labelKey = "pvpocket.material." + id;
        this.itemPrefix = itemPrefix;
        this.tier = tier;
    }

    public MutableComponent labelText() {
        return PvLang.t(labelKey);
    }

    /** Display name for the self-drawn GUI and for chat lines composed as strings. */
    public String label() {
        return PvLang.str(labelKey);
    }

    /** Vanilla calls the wooden tier {@code wooden_}, not {@code wood_}. */
    public String itemPrefix() {
        return itemPrefix;
    }

    public static Material byId(String id) {
        for (Material material : values()) {
            if (material.id.equals(id)) {
                return material;
            }
        }
        return IRON;
    }
}
