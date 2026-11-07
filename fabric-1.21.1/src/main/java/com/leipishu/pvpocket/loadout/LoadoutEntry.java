package com.leipishu.pvpocket.loadout;

/**
 * One line of a loadout: a kind, its appearance, and the handful of numbers the player is allowed to
 * set.
 *
 * <p>Flat on purpose. Every field is something the editor draws as one row, and every field is
 * something the codec has to round-trip — a nested structure would add mapping code without adding a
 * single capability. Fields that do not apply to a kind are simply left at their defaults and cost
 * nothing but a few bytes in the encoded string.</p>
 */
public final class LoadoutEntry {

    /** Sentinel for "let the item keep its vanilla durability". */
    public static final int DURABILITY_DEFAULT = 0;
    /** Sentinel for "this item never breaks". */
    public static final int DURABILITY_UNBREAKABLE = -1;

    public ItemKind kind = ItemKind.SWORD;
    public Material material = Material.IRON;
    /** Food item id, splash potion effect id, or block id — depending on {@link #kind}. */
    public String variant = "";
    public int count = 1;
    /** 0-35 inventory, 36-39 armour, 40 offhand. Negative means "assign automatically". */
    public int slot = -1;

    /** Total attack damage x10. 0 keeps the item's vanilla value. */
    public int damageTenths = 0;
    /** Armour points x10. Only meaningful for armour kinds. */
    public int armorTenths = 0;
    /** {@link #DURABILITY_DEFAULT}, {@link #DURABILITY_UNBREAKABLE}, or an exact max damage. */
    public int durability = DURABILITY_DEFAULT;
    /** Knockback enchantment level, 0-3. */
    public int knockback = 0;
    /** Bitmask of {@link WeaponFlag}. */
    public int flags = 0;

    /**
     * Size of the second stack this entry also grants.
     *
     * <p>Arrows for a bow, obsidian for an end crystal, flint and steel for TNT — one field because
     * it is one concept: "how many of the thing this needs beside it". The wire position is
     * unchanged from when it was called {@code companionCount}, so old presets still decode.</p>
     */
    public int companionCount = 0;
    /** Splash potion duration in seconds. */
    public int durationSec = 0;
    /** Splash potion amplifier, 0 = level I. */
    public int amplifier = 0;

    /** Which exclusive trident enchantment this row carries. Only meaningful for {@link ItemKind#TRIDENT}. */
    public TridentStyle tridentStyle = TridentStyle.NONE;
    /** Firework rocket flight duration, 1-3. Only meaningful for {@link ItemKind#FIREWORK_ROCKET}. */
    public int fireworkFlight = 1;

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
            // A companion that defaults to zero would make every freshly added row useless the
            // moment it appears, which reads as "the item is broken" rather than "set the count".
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
     * A detached duplicate, for editors that edit a draft and only commit on confirm.
     *
     * <p>The loadout editor is modal: 取消 has to leave the entry exactly as it was, and the field
     * controls write straight into whatever object they are handed. Handing them a copy and copying
     * back on 确定 is the only way to get "cancel really cancels" without a second set of
     * change-tracking for every field.</p>
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
     * Field order is frozen: it is the wire format between client and server and the on-disk format of
     * a saved preset. Appending a field is safe because {@link #decode} tolerates a short string;
     * reordering is not, so new fields go at the end.
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
                Integer.toString(fireworkFlight));
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
        // A preset saved before Riptide became a TridentStyle still carries its old bit. Reading it
        // once here is what keeps that preset from silently losing its enchantment; the bit itself is
        // cleared so the string re-encodes in the new shape. The kind guard matters because a bit set
        // on the wrong kind used to be a validation error and must not become a silent field write.
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
