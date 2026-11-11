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
        String path = itemPath(material);
        Identifier identifier = Identifier.of("minecraft", path);
        return Registries.ITEM.containsId(identifier) ? Registries.ITEM.get(identifier) : null;
    }

    /** The vanilla id this kind resolves to for {@code material}, without asking the registry. */
    public String itemPath(Material material) {
        return materialised && material != null ? material.itemPrefix() + "_" + id : id;
    }

    // ------------------------------------------------------------------ availability
    //
    // Which kinds and materials a build can offer is a property of the *game*, not of this mod, and
    // the game keeps changing it: the mace arrived in 1.21, the spear in 1.21.11, copper as a weapon
    // tier later still. Hard-coding a version table would mean every port has to remember to edit it,
    // and getting it wrong shows the player a row that grants nothing.
    //
    // So the registry is the table. The only thing that has to be version-independent is the shape of
    // the question, which is why the predicate is injected: `availableOf(page)` asks the real item
    // registry, while the offline self-check passes a fixed set of ids and asserts what each target
    // version would offer. Without that split this logic could only be tested by launching the game
    // once per version.

    /** Answers "does this item id exist in this build". The only registry-touching part. */
    @FunctionalInterface
    public interface ItemIds {
        boolean has(String path);
    }

    /** True for kinds whose concrete item comes from {@link LoadoutEntry#variant}, not from a material. */
    public boolean resolvesByVariant() {
        return this == FOOD || this == BLOCK || this == POTION;
    }

    /** True when at least one material variant of this kind exists in the running game. */
    public boolean available() {
        return available(ItemKind::registryHas);
    }

    public boolean available(ItemIds ids) {
        // FOOD / BLOCK / POTION carry no item id of their own — the entry's variant names the item,
        // and every variant in the catalog is a vanilla item that predates all of these versions.
        // Asking the registry for "food" would answer no and hide the whole page.
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
     * <p>Per kind rather than global on purpose: copper is a weapon tier in 1.21.11 but not every
     * kind has a copper variant, and a material offered for a kind it cannot build is a cycle that
     * stops on an item that does not exist.</p>
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
        return Registries.ITEM.containsId(Identifier.of("minecraft", path));
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
     * A firework rocket carrying {@code max_damage} would be an item with a durability bar that
     * never moves and a stack that cannot be used up the usual way, which is worse than having no
     * field at all — so the question is asked once, here, rather than as a blacklist in two places.</p>
     */
    public boolean hasDurability() {
        return switch (this) {
            case FOOD, POTION, BLOCK, FIREWORK_ROCKET, WIND_CHARGE, END_CRYSTAL, TNT -> false;
            default -> true;
        };
    }

    /**
     * True for kinds that are useless on their own and need a second stack beside them.
     *
     * <p>A bow without arrows, an end crystal with nothing to put it on, and TNT with nothing to
     * light it are the same shape of problem: the entry is one decision ("I am bringing a bow") but
     * two stacks. The editor shows one extra count for these, and {@code LoadoutBuilder} emits the
     * second stack — the same path arrows have always taken.</p>
     */
    public boolean hasCompanion() {
        return this == BOW || this == END_CRYSTAL || this == TNT;
    }

    public boolean isArmor() {
        return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
    }
}
