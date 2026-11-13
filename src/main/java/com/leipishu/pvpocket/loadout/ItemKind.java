package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.compat.PvCompat;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;

/**
 * Every item a loadout can contain, in panel order.
 *
 * <p>The four pages the player sees are {@link Page}. A materialised kind resolves to
 * {@code minecraft:<material>_<kind>}, as in {@code wooden_sword}; anything else is declared
 * non-materialised and resolves to {@code minecraft:<kind>}.
 *
 * <p>The display name is {@code pvpocket.item.<id>}, so the constant's id and the translation key
 * share one word.
 */
public enum ItemKind {

    SWORD(Page.WEAPON, "sword", true, 7),
    AXE(Page.WEAPON, "axe", true, 6),
    BOW(Page.WEAPON, "bow", false, 1),
    CROSSBOW(Page.WEAPON, "crossbow", false, 1),
    TRIDENT(Page.WEAPON, "trident", false, 1),
    /** Added in 1.21.11; absent from 1.21.1 and older, so {@link #available} hides it there. */
    SPEAR(Page.WEAPON, "spear", true, 1),
    /** Added in 1.21 (Tricky Trials); absent from 1.20.1. */
    MACE(Page.WEAPON, "mace", false, 1),
    FISHING_ROD(Page.WEAPON, "fishing_rod", false, 1),

    HELMET(Page.ARMOR, "helmet", true, 1),
    CHESTPLATE(Page.ARMOR, "chestplate", true, 1),
    LEGGINGS(Page.ARMOR, "leggings", true, 1),
    BOOTS(Page.ARMOR, "boots", true, 1),
    SHIELD(Page.ARMOR, "shield", false, 1),
    /** Not {@link #isArmor()}: it grants no armour points and wants the chest slot all the same. */
    ELYTRA(Page.ARMOR, "elytra", false, 1),

    FOOD(Page.CONSUMABLE, "food", false, 16),
    POTION(Page.CONSUMABLE, "potion", false, 1),
    FIREWORK_ROCKET(Page.CONSUMABLE, "firework_rocket", false, 16),
    WIND_CHARGE(Page.CONSUMABLE, "wind_charge", false, 16),
    /** Needs obsidian under it, which is granted as its companion stack. */
    END_CRYSTAL(Page.CONSUMABLE, "end_crystal", false, 16),
    /** Needs something to light it, which is granted as its companion stack. */
    TNT(Page.CONSUMABLE, "tnt", false, 16),

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

        public MutableComponent labelText() {
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

    public MutableComponent labelText() {
        return PvLang.t(labelKey);
    }

    /** Display name for the self-drawn GUI and for chat lines composed as strings. */
    public String label() {
        return PvLang.str(labelKey);
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
     * <p>Returns null rather than throwing: the loadout is player-authored data that arrives over
     * the network, so an unknown combination is a rejected entry rather than a crash.
     */
    public Item resolve(Material material) {
        String path = itemPath(material);
        ResourceLocation identifier = ResourceLocation.fromNamespaceAndPath("minecraft", path);
        return BuiltInRegistries.ITEM.containsKey(identifier) ? PvCompat.value(BuiltInRegistries.ITEM, identifier) : null;
    }

    /** The vanilla id this kind resolves to for {@code material}, without asking the registry. */
    public String itemPath(Material material) {
        return materialised && material != null ? material.itemPrefix() + "_" + id : id;
    }

    // ------------------------------------------------------------------ availability
    // The item registry answers which kinds exist; the predicate is injected for the self-check.

    /** Answers "does this item id exist in this build". The only registry-touching part. */
    @FunctionalInterface
    public interface ItemIds {
        boolean has(String path);
    }

    /**
     * True for kinds whose item comes from {@link LoadoutEntry#variant} rather than a material.
     */
    public boolean resolvesByVariant() {
        return this == FOOD || this == BLOCK || this == POTION;
    }

    /** True when at least one material variant of this kind exists in the running game. */
    public boolean available() {
        return available(ItemKind::registryHas);
    }

    public boolean available(ItemIds ids) {
        // FOOD, BLOCK and POTION carry no id of their own; the entry's variant names the item, so
        // asking the registry for "food" would hide the whole page.
        if (resolvesByVariant()) {
            return true;
        }
        if (!materialised) {
            return ids.has(id);
        }
        for (Material material : Material.values()) {
            if (ids.has(itemPath(material))) {
                return true;
            }
        }
        return false;
    }

    /** The kinds on {@code page} this build can actually grant, in declaration order. */
    public static List<ItemKind> availableOf(Page page) {
        return availableOf(page, ItemKind::registryHas);
    }

    public static List<ItemKind> availableOf(Page page, ItemIds ids) {
        return java.util.Arrays.stream(values())
                .filter(kind -> kind.page == page)
                .filter(kind -> kind.available(ids))
                .toList();
    }

    /**
     * The materials that produce a real item for this kind, in tier order.
     *
     * <p>Per kind rather than global, because not every kind has a variant at every tier.
     */
    public List<Material> materials() {
        return materials(ItemKind::registryHas);
    }

    public List<Material> materials(ItemIds ids) {
        if (!materialised) {
            return List.of();
        }
        return java.util.Arrays.stream(Material.values())
                .filter(material -> ids.has(itemPath(material)))
                .toList();
    }

    private static boolean registryHas(String path) {
        return BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath("minecraft", path));
    }

    /** True for kinds whose attributes (damage / armour / durability) are worth editing. */
    public boolean editableAttributes() {
        return switch (this) {
            case FOOD, BLOCK, SHEARS, SHIELD -> false;
            default -> true;
        };
    }

    /**
     * True for kinds that actually wear out.
     *
     * <p>Drives the durability rows in the editor and the {@code MAX_DAMAGE} write in the builder.
     * A firework rocket with {@code max_damage} would draw a durability bar that never moves.
     */
    public boolean hasDurability() {
        return switch (this) {
            case FOOD, POTION, BLOCK, FIREWORK_ROCKET, WIND_CHARGE, END_CRYSTAL, TNT -> false;
            default -> true;
        };
    }

    /**
     * True for kinds that need a second stack beside them.
     *
     * <p>A bow without arrows, an end crystal with nothing to put it on, and TNT with nothing to
     * light it. The editor shows an extra count and {@code LoadoutBuilder} emits the second stack.
     */
    public boolean hasCompanion() {
        return this == BOW || this == END_CRYSTAL || this == TNT;
    }

    public boolean isArmor() {
        return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
    }
}
