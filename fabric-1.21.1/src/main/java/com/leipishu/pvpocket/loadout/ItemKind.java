package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

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
 *
 * <p>The display name is {@code pvpocket.item.<id>}, so the enum constant's id and the translation
 * key share one word — there is no third place where a kind's name can drift.</p>
 */
public enum ItemKind {

    SWORD(Page.WEAPON, "sword", true, 7),
    AXE(Page.WEAPON, "axe", true, 6),
    BOW(Page.WEAPON, "bow", false, 1),
    CROSSBOW(Page.WEAPON, "crossbow", false, 1),
    TRIDENT(Page.WEAPON, "trident", false, 1),
    MACE(Page.WEAPON, "mace", false, 1),
    FISHING_ROD(Page.WEAPON, "fishing_rod", false, 1),

    HELMET(Page.ARMOR, "helmet", true, 1),
    CHESTPLATE(Page.ARMOR, "chestplate", true, 1),
    LEGGINGS(Page.ARMOR, "leggings", true, 1),
    BOOTS(Page.ARMOR, "boots", true, 1),
    SHIELD(Page.ARMOR, "shield", false, 1),

    FOOD(Page.CONSUMABLE, "food", false, 16),
    POTION(Page.CONSUMABLE, "potion", false, 1),

    /** Pickaxe and shears live on the block page because that is where they are actually needed. */
    PICKAXE(Page.BLOCK, "pickaxe", true, 1),
    SHEARS(Page.BLOCK, "shears", false, 1),
    BLOCK(Page.BLOCK, "block", false, 64);

    /** The four pages of the loadout editor. */
    public enum Page {
        WEAPON,
        ARMOR,
        CONSUMABLE,
        BLOCK;

        public final String labelKey = "pvpocket.item.page." + name().toLowerCase(Locale.ROOT);

        public MutableText labelText() {
            return PvLang.t(labelKey);
        }

        /** Tab label for the self-drawn GUI. */
        public String label() {
            return PvLang.str(labelKey);
        }
    }

    public final Page page;
    public final String id;
    public final String labelKey;
    /** True when the item id is {@code <material>_<kind>} rather than just {@code <kind>}. */
    public final boolean materialised;
    /** Sensible starting stack size when the player adds this kind. */
    public final int defaultCount;

    ItemKind(Page page, String id, boolean materialised, int defaultCount) {
        this.page = page;
        this.id = id;
        this.labelKey = "pvpocket.item." + id;
        this.materialised = materialised;
        this.defaultCount = defaultCount;
    }

    public MutableText labelText() {
        return PvLang.t(labelKey);
    }

    /** Display name for the self-drawn GUI and for chat lines composed as strings. */
    public String label() {
        return PvLang.str(labelKey);
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
