package com.leipishu.pvpocket.loadout;

/**
 * One line of a loadout: a kind, its appearance, and the numbers the player may set.
 *
 * <p>Every field is one editor row and one codec field. Fields that do not apply to a kind stay at
 * their defaults.
 */
public final class LoadoutEntry {

    /** Sentinel for "let the item keep its vanilla durability". */
    public static final int DURABILITY_DEFAULT = 0;
    /** Sentinel for "this item never breaks". */
    public static final int DURABILITY_UNBREAKABLE = -1;

    public ItemKind kind = ItemKind.SWORD;
    public Material material = Material.IRON;
    /** Food item id, splash potion effect id, or block id, depending on {@link #kind}. */
    public String variant = "";
    public int count = 1;
    /** Slot index: 0-35 inventory, 36-39 armour, 40 offhand. Negative assigns automatically. */
    public int slot = -1;

    /** Total attack damage x10. 0 keeps the item's vanilla value. */
    public int damageTenths = 0;
    /** Armour points x10. Only meaningful for armour kinds. */
    public int armorTenths = 0;
    /** One of {@link #DURABILITY_DEFAULT}, {@link #DURABILITY_UNBREAKABLE}, or an exact max. */
    public int durability = DURABILITY_DEFAULT;
    /** Knockback enchantment level, 0-3. */
    public int knockback = 0;
    /** Bitmask of {@link WeaponFlag}. */
    public int flags = 0;

    /**
     * Size of the second stack this entry also grants.
     *
     * <p>Arrows for a bow, obsidian for an end crystal, flint and steel for TNT.
     */
    public int companionCount = 0;
    /** Splash potion duration in seconds. */
    public int durationSec = 0;
    /** Splash potion amplifier, 0 is level 1. */
    public int amplifier = 0;

    /**
     * Which exclusive trident enchantment this row carries. Only meaningful for
     * {@link ItemKind#TRIDENT}.
     */
    public TridentStyle tridentStyle = TridentStyle.NONE;
    /**
     * Firework rocket flight duration, 1-3. Only meaningful for {@link ItemKind#FIREWORK_ROCKET}.
     */
    public int fireworkFlight = 1;

    /**
     * Whether the Q key refuses to throw the stacks this entry grants away.
     *
     * <p>On by default, because a sword thrown away mid-round cannot be recovered. The lock travels
     * on the stack itself (see {@code LoadoutLock}), so it survives moving the item.
     */
    public boolean noDrop = true;

    public static LoadoutEntry of(ItemKind kind) {
        LoadoutEntry entry = new LoadoutEntry();
        entry.kind = kind;
        entry.count = kind.defaultCount;
        switch (kind) {
            case POTION -> {
                entry.variant = "minecraft:speed";
                entry.durationSec = 8;
                entry.count = 1;
            }
            case FOOD -> {
                entry.variant = "minecraft:golden_carrot";
                entry.count = 16;
            }
            case BLOCK -> {
                entry.variant = "minecraft:white_wool";
                entry.count = 32;
            }
            // A zero companion would leave a freshly added row unusable until the count is set.
            case BOW -> entry.companionCount = 32;
            case END_CRYSTAL -> entry.companionCount = 16;
            case TNT -> entry.companionCount = 1;
            default -> {
            }
        }
        return entry;
    }

    public int flagMask() {
        return flags;
    }

    /**
     * A stable name for what this row grants, for finding the row again from a granted stack.
     *
     * <p>The lock travels on the stack (see {@code LoadoutLock}), which outlives the row that
     * produced it, so this string is written into the mark and read back. The numbers are excluded,
     * so a damage edit does not orphan the stack.
     */
    public String fingerprint() {
        return kind.id + '|' + material.id + '|' + variant + '|' + tridentStyle.id;
    }

    /**
     * A detached duplicate, for editors that edit a draft and commit on confirm.
     *
     * <p>The field controls write into whatever object they are handed, so the editor edits a copy
     * and copies back on confirm.
     */
    public LoadoutEntry copy() {
        LoadoutEntry e = new LoadoutEntry();
        e.copyFrom(this);
        return e;
    }

    /** Overwrites every editable field from {@code other}, keeping this object's identity. */
    public void copyFrom(LoadoutEntry other) {
        kind = other.kind;
        material = other.material;
        variant = other.variant;
        count = other.count;
        slot = other.slot;
        damageTenths = other.damageTenths;
        armorTenths = other.armorTenths;
        durability = other.durability;
        knockback = other.knockback;
        flags = other.flags;
        companionCount = other.companionCount;
        durationSec = other.durationSec;
        amplifier = other.amplifier;
        tridentStyle = other.tridentStyle;
        fireworkFlight = other.fireworkFlight;
        noDrop = other.noDrop;
    }

    public boolean has(WeaponFlag flag) {
        return (flags & flag.bit()) != 0;
    }

    public void set(WeaponFlag flag, boolean on) {
        if (on) {
            flags |= flag.bit();
        } else {
            flags &= ~flag.bit();
        }
    }

    public float damage() {
        return damageTenths / 10.0F;
    }

    public float armor() {
        return armorTenths / 10.0F;
    }

    // ------------------------------------------------------------------ codec

    /**
     * Field order is frozen: it is the wire format between client and server and the on-disk format
     * of a saved preset. Appending a field is safe because {@link #decode} tolerates a short
     * string; reordering is not, so new fields go at the end.
     */
    public String encode() {
        return String.join("|",
                kind.id,
                material.id,
                variant,
                Integer.toString(count),
                Integer.toString(slot),
                Integer.toString(damageTenths),
                Integer.toString(armorTenths),
                Integer.toString(durability),
                Integer.toString(knockback),
                Integer.toString(flags),
                Integer.toString(companionCount),
                Integer.toString(durationSec),
                Integer.toString(amplifier),
                tridentStyle.id,
                Integer.toString(fireworkFlight),
                Integer.toString(noDrop ? 1 : 0));
    }

    public static LoadoutEntry decode(String encoded) {
        String[] parts = encoded.split("\\|", -1);
        LoadoutEntry entry = new LoadoutEntry();
        entry.kind = ItemKind.byId(part(parts, 0, "sword"));
        if (entry.kind == null) {
            return null;
        }
        entry.material = Material.byId(part(parts, 1, "iron"));
        entry.variant = part(parts, 2, "");
        entry.count = Math.max(1, num(parts, 3, entry.kind.defaultCount));
        entry.slot = num(parts, 4, -1);
        entry.damageTenths = num(parts, 5, 0);
        entry.armorTenths = num(parts, 6, 0);
        entry.durability = num(parts, 7, DURABILITY_DEFAULT);
        entry.knockback = num(parts, 8, 0);
        entry.flags = num(parts, 9, 0);
        entry.companionCount = num(parts, 10, 0);
        entry.durationSec = num(parts, 11, 0);
        entry.amplifier = num(parts, 12, 0);
        entry.tridentStyle = TridentStyle.byId(part(parts, 13, "none"));
        entry.fireworkFlight = Math.max(LoadoutCatalog.FIREWORK_FLIGHT_MIN,
                Math.min(LoadoutCatalog.FIREWORK_FLIGHT_MAX, num(parts, 14, 1)));
        // Defaults to 1 (locked), so a preset without this field still protects its stacks.
        entry.noDrop = num(parts, 15, 1) != 0;
        // A preset that still carries the retired riptide bit is read as RIPTIDE and the bit is
        // cleared, so the string re-encodes in the current shape. Trident rows only.
        if (entry.kind == ItemKind.TRIDENT
                && entry.tridentStyle == TridentStyle.NONE
                && (entry.flags & WeaponFlag.RETIRED_RIPTIDE_BIT) != 0) {
            entry.tridentStyle = TridentStyle.RIPTIDE;
            entry.flags &= ~WeaponFlag.RETIRED_RIPTIDE_BIT;
        }
        return entry;
    }

    private static String part(String[] parts, int index, String fallback) {
        return index < parts.length ? parts[index] : fallback;
    }

    private static int num(String[] parts, int index, int fallback) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return kind.id + "(" + material.id + ")" + (variant.isEmpty() ? "" : "[" + variant + "]");
    }
}
