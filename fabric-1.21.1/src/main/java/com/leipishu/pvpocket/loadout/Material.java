package com.leipishu.pvpocket.loadout;

/**
 * The material a materialised item is built from.
 *
 * <p><b>Material is display only.</b> This is the one place where the design deliberately departs
 * from vanilla: an iron sword and a netherite sword differ in colour and in nothing else, because the
 * player sets the damage, the durability and the knockback explicitly. That is why
 * {@link LoadoutBuilder} <em>replaces</em> the {@code attribute_modifiers} component instead of adding
 * to it — appending would leave the vanilla material bonus in place and quietly make the material
 * matter again.</p>
 */
public enum Material {

    WOOD("wood", "木", "wooden", 0),
    STONE("stone", "石", "stone", 1),
    IRON("iron", "铁", "iron", 2),
    GOLD("gold", "金", "golden", 3),
    DIAMOND("diamond", "钻石", "diamond", 4),
    NETHERITE("netherite", "下界合金", "netherite", 5);

    public final String id;
    public final String label;
    private final String itemPrefix;
    /** Display order only. It carries no stat meaning — see the class note. */
    public final int tier;

    Material(String id, String label, String itemPrefix, int tier) {
        this.id = id;
        this.label = label;
        this.itemPrefix = itemPrefix;
        this.tier = tier;
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
