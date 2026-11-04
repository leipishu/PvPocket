package com.leipishu.pvpocket.loadout;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Every item a loadout can contain, in panel order.
 *
 * <p>The four pages the player sees are {@link Page}; the kinds themselves are flat because the
 * differences between them are almost entirely "which item id do I resolve to" and "which attributes
 * make sense". Keeping them in one enum means the data row, the codec and the editor are all one
 * shape, which matters more here than taxonomic purity.</p>
 *
 * <p>Material handling is uniform by construction: a materialised kind resolves to
 * {@code minecraft:<material>_<kind>}, which is exactly how vanilla names them
 * ({@code wooden_sword}, {@code netherite_helmet}). Anything that does not follow that pattern is
 * declared non-materialised and resolves to {@code minecraft:<kind>}.</p>
 */
public enum ItemKind {

    SWORD(Page.WEAPON, "sword", "剑", true, 7),
    AXE(Page.WEAPON, "axe", "斧", true, 6),
    BOW(Page.WEAPON, "bow", "弓", false, 1),
    CROSSBOW(Page.WEAPON, "crossbow", "弩", false, 1),
    TRIDENT(Page.WEAPON, "trident", "三叉戟", false, 1),
    MACE(Page.WEAPON, "mace", "重锤", false, 1),
    FISHING_ROD(Page.WEAPON, "fishing_rod", "钓鱼竿", false, 1),

    HELMET(Page.ARMOR, "helmet", "头盔", true, 1),
    CHESTPLATE(Page.ARMOR, "chestplate", "胸甲", true, 1),
    LEGGINGS(Page.ARMOR, "leggings", "护腿", true, 1),
    BOOTS(Page.ARMOR, "boots", "靴子", true, 1),
    SHIELD(Page.ARMOR, "shield", "盾牌", false, 1),

    FOOD(Page.CONSUMABLE, "food", "食物", false, 16),
    POTION(Page.CONSUMABLE, "potion", "药水", false, 1),

    /** Pickaxe and shears live on the block page because that is where they are actually needed. */
    PICKAXE(Page.BLOCK, "pickaxe", "镐", true, 1),
    SHEARS(Page.BLOCK, "shears", "剪刀", false, 1),
    BLOCK(Page.BLOCK, "block", "方块", false, 64);

    /** The four pages of the loadout editor. */
    public enum Page {
        WEAPON("武器"),
        ARMOR("装备"),
        CONSUMABLE("消耗品"),
        BLOCK("方块");

        public final String label;

        Page(String label) {
            this.label = label;
        }
    }

    public final Page page;
    public final String id;
    public final String label;
    /** True when the item id is {@code <material>_<kind>} rather than just {@code <kind>}. */
    public final boolean materialised;
    /** Sensible starting stack size when the player adds this kind. */
    public final int defaultCount;

    ItemKind(Page page, String id, String label, boolean materialised, int defaultCount) {
        this.page = page;
        this.id = id;
        this.label = label;
        this.materialised = materialised;
        this.defaultCount = defaultCount;
    }

    public static List<ItemKind> of(Page page) {
        return java.util.Arrays.stream(values()).filter(k -> k.page == page).toList();
    }

    public static ItemKind byId(String id) {
        for (ItemKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Resolves the concrete vanilla item.
     *
     * <p>Returns null rather than throwing: the loadout is player-authored data that arrives over the
     * network, so an unknown combination has to be a rejected entry, not a crashed server.</p>
     */
    public Item resolve(Material material) {
        String path = materialised && material != null
                ? material.itemPrefix() + "_" + id
                : id;
        Identifier identifier = Identifier.of("minecraft", path);
        return Registries.ITEM.containsId(identifier) ? Registries.ITEM.get(identifier) : null;
    }

    /** True for kinds whose attributes (damage / armour / durability) are worth editing. */
    public boolean editableAttributes() {
        return switch (this) {
            case FOOD, BLOCK, SHEARS, SHIELD -> false;
            default -> true;
        };
    }

    public boolean isArmor() {
        return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
    }
}
