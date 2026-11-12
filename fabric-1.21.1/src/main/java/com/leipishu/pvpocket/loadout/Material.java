package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.network.chat.MutableComponent;

/**
 * The material a materialised item is built from.
 *
 * <p><b>Material is display only.</b> This is the one place where the design deliberately departs
 * from vanilla: an iron sword and a netherite sword differ in colour and in nothing else, because the
 * player sets the damage, the durability and the knockback explicitly. That is why
 * {@link LoadoutBuilder} <em>replaces</em> the {@code attribute_modifiers} component instead of adding
 * to it — appending would leave the vanilla material bonus in place and quietly make the material
 * matter again.</p>
 *
 * <p>"Display only" is also why the name is a translation key rather than a literal: the one thing
 * this enum contributes to the item is the word in front of it, and that word is language-specific
 * by definition.</p>
 */
public enum Material {

    WOOD("wood", "wooden", 0),
    STONE("stone", "stone", 1),
    /**
     * A weapon tier only from 1.21.11 onwards, and not for every kind — {@link ItemKind#materials()}
     * asks the registry per kind, so copper shows up exactly where a copper variant exists.
     */
    COPPER("copper", "copper", 2),
    IRON("iron", "iron", 3),
    GOLD("gold", "golden", 4),
    DIAMOND("diamond", "diamond", 5),
    NETHERITE("netherite", "netherite", 6);

    public final String id;
    public final String labelKey;
    private final String itemPrefix;
    /** Display order only. It carries no stat meaning — see the class note. */
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
