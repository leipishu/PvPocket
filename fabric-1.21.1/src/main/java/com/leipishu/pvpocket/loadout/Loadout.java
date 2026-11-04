package com.leipishu.pvpocket.loadout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * A complete set of items one side enters the round with.
 *
 * <p>The loadout is part of what both players agree to, so it is encoded to a single string and
 * fingerprinted exactly like the rule set: a disputed match can be replayed against the loadout that
 * actually ran, not the one somebody remembers proposing.</p>
 *
 * <p>Nothing here touches the world. Validation is pure data checking, which is what makes it cheap
 * enough to run on every keystroke in the editor and again on the server when the loadout arrives.</p>
 */
public final class Loadout {

    private static final String FIELD_SEPARATOR = "|";
    private static final String ENTRY_SEPARATOR = ";";
    private static final String NAME_SEPARATOR = "~";

    public String name = "默认配装";
    public final List<LoadoutEntry> entries = new ArrayList<>();

    public Loadout() {
    }

    public static Loadout of(LoadoutEntry... entries) {
        Loadout loadout = new Loadout();
        for (LoadoutEntry entry : entries) {
            loadout.entries.add(entry);
        }
        return loadout;
    }

    /**
     * The preset a player who never opens the editor gets.
     *
     * <p>Deliberately plain: an iron sword, a shield, food, and a bow with arrows. It is meant to be
     * playable and obviously editable, not optimal — a preset that already looks tuned makes players
     * assume the numbers are fixed.</p>
     */
    public static Loadout defaultDuel() {
        Loadout loadout = new Loadout();
        loadout.name = "默认决斗配装";

        LoadoutEntry sword = LoadoutEntry.of(ItemKind.SWORD);
        sword.material = Material.IRON;
        sword.damageTenths = 70;
        sword.durability = 800;
        sword.knockback = 1;
        sword.set(WeaponFlag.SWEEPING, true);
        loadout.entries.add(sword);

        LoadoutEntry shield = LoadoutEntry.of(ItemKind.SHIELD);
        shield.durability = 400;
        loadout.entries.add(shield);

        LoadoutEntry bow = LoadoutEntry.of(ItemKind.BOW);
        bow.damageTenths = 0;
        bow.durability = 500;
        bow.set(WeaponFlag.INFINITY, false);
        bow.arrowCount = 32;
        loadout.entries.add(bow);

        LoadoutEntry carrot = LoadoutEntry.of(ItemKind.FOOD);
        carrot.variant = "minecraft:golden_carrot";
        carrot.count = 16;
        loadout.entries.add(carrot);

        LoadoutEntry steak = LoadoutEntry.of(ItemKind.FOOD);
        steak.variant = "minecraft:cooked_beef";
        steak.count = 8;
        loadout.entries.add(steak);

        LoadoutEntry wool = LoadoutEntry.of(ItemKind.BLOCK);
        wool.variant = "minecraft:white_wool";
        wool.count = 32;
        loadout.entries.add(wool);

        LoadoutEntry pickaxe = LoadoutEntry.of(ItemKind.PICKAXE);
        pickaxe.material = Material.IRON;
        pickaxe.durability = 400;
        loadout.entries.add(pickaxe);

        loadout.autoAssignSlots();
        return loadout;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Loadout copy() {
        return decode(encode());
    }

    // ------------------------------------------------------------------ slots

    /**
     * Fills in a slot for every entry that does not have one.
     *
     * <p>Armour goes to the armour slots, a shield to the offhand, everything else to the hotbar and
     * then the main inventory. Entries that already carry a slot keep it — the player's arrangement is
     * part of the loadout, and re-deriving it on every edit would move items under their cursor.</p>
     */
    public void autoAssignSlots() {
        boolean[] taken = new boolean[41];
        for (LoadoutEntry entry : entries) {
            if (entry.slot >= 0 && entry.slot < taken.length) {
                taken[entry.slot] = true;
            }
        }
        for (LoadoutEntry entry : entries) {
            if (entry.slot >= 0) {
                continue;
            }
            int preferred = preferredSlot(entry.kind);
            if (preferred >= 0 && !taken[preferred]) {
                entry.slot = preferred;
                taken[preferred] = true;
                continue;
            }
            for (int slot = 0; slot < 36; slot++) {
                if (!taken[slot]) {
                    entry.slot = slot;
                    taken[slot] = true;
                    break;
                }
            }
        }
    }

    private static int preferredSlot(ItemKind kind) {
        return switch (kind) {
            // Vanilla armour slot order is boots(36) leggings(37) chest(38) helmet(39) offhand(40).
            case BOOTS -> 36;
            case LEGGINGS -> 37;
            case CHESTPLATE -> 38;
            case HELMET -> 39;
            case SHIELD -> 40;
            default -> -1;
        };
    }

    // ------------------------------------------------------------------ validation

    /**
     * Returns one human-readable problem per offending row, in row order.
     *
     * <p>Empty means "this loadout can be granted". The server runs this again on arrival: the client
     * runs it to keep the save button honest, but a client is not a source of truth.</p>
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (entries.isEmpty()) {
            problems.add("配装不能为空。");
        }
        if (entries.size() > LoadoutCatalog.MAX_ENTRIES) {
            problems.add("配装最多 " + LoadoutCatalog.MAX_ENTRIES + " 项，当前 " + entries.size() + " 项。");
        }
        boolean[] used = new boolean[41];
        for (int i = 0; i < entries.size(); i++) {
            LoadoutEntry entry = entries.get(i);
            String row = "第 " + (i + 1) + " 行（" + entry.kind.label + "）：";
            if (entry.slot >= 0 && entry.slot < used.length) {
                if (used[entry.slot]) {
                    problems.add(row + "与前面的物品占用了同一个格子。");
                }
                used[entry.slot] = true;
            }
            problems.addAll(validateEntry(entry, row));
        }
        return problems;
    }

    private static List<String> validateEntry(LoadoutEntry entry, String row) {
        List<String> problems = new ArrayList<>();
        // Only materialised kinds are named <material>_<kind>. FOOD and BLOCK resolve through their
        // variant instead, so asking "does iron_food exist" would reject every valid food row — and
        // did, which made the default preset unsaveable until the editor exposed it.
        if (entry.kind.materialised && entry.kind.resolve(entry.material) == null) {
            problems.add(row + "该材质没有对应的物品。");
        }
        if (entry.count < 1 || entry.count > 64) {
            problems.add(row + "数量必须在 1 到 64 之间。");
        }
        if (entry.damageTenths != 0
                && (entry.damageTenths < LoadoutCatalog.DAMAGE_MIN_TENTHS
                    || entry.damageTenths > LoadoutCatalog.DAMAGE_MAX_TENTHS)) {
            problems.add(row + "伤害必须在 " + (LoadoutCatalog.DAMAGE_MIN_TENTHS / 10.0)
                    + " 到 " + (LoadoutCatalog.DAMAGE_MAX_TENTHS / 10.0) + " 之间。");
        }
        if (entry.armorTenths < 0 || entry.armorTenths > LoadoutCatalog.ARMOR_MAX_TENTHS) {
            problems.add(row + "护甲值超出范围。");
        }
        if (entry.durability != LoadoutEntry.DURABILITY_UNBREAKABLE
                && (entry.durability < 0 || entry.durability > LoadoutCatalog.DURABILITY_MAX)) {
            problems.add(row + "耐久必须在 0 到 " + LoadoutCatalog.DURABILITY_MAX + " 之间（-1 为不可破坏）。");
        }
        if (entry.knockback < 0 || entry.knockback > LoadoutCatalog.KNOCKBACK_MAX) {
            problems.add(row + "击退等级必须在 0 到 " + LoadoutCatalog.KNOCKBACK_MAX + " 之间。");
        }
        if (entry.arrowCount < 0 || entry.arrowCount > LoadoutCatalog.ARROW_MAX) {
            problems.add(row + "箭矢数量必须在 0 到 " + LoadoutCatalog.ARROW_MAX + " 之间。");
        }
        switch (entry.kind) {
            case POTION -> {
                if (!LoadoutCatalog.isEffect(entry.variant)) {
                    problems.add(row + "药水效果不在允许列表内。");
                }
                if (entry.durationSec < LoadoutCatalog.DURATION_MIN_SEC
                        || entry.durationSec > LoadoutCatalog.DURATION_MAX_SEC) {
                    problems.add(row + "药水时长必须在 " + LoadoutCatalog.DURATION_MIN_SEC
                            + " 到 " + LoadoutCatalog.DURATION_MAX_SEC + " 秒之间。");
                }
                if (entry.amplifier < 0 || entry.amplifier > LoadoutCatalog.AMPLIFIER_MAX) {
                    problems.add(row + "药水等级超出范围。");
                }
            }
            case FOOD -> {
                if (!LoadoutCatalog.isFood(entry.variant)) {
                    problems.add(row + "食物不在允许列表内。");
                }
            }
            case BLOCK -> {
                if (!LoadoutCatalog.isBlock(entry.variant)) {
                    problems.add(row + "方块不在允许列表内。");
                }
            }
            default -> {
            }
        }
        for (WeaponFlag flag : WeaponFlag.decode(entry.flags)) {
            if (!flag.appliesTo(entry.kind)) {
                problems.add(row + "「" + flag.label + "」不适用于" + entry.kind.label + "。");
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------ codec

    public String encode() {
        StringBuilder sb = new StringBuilder(escape(name)).append(NAME_SEPARATOR);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(ENTRY_SEPARATOR);
            }
            sb.append(entries.get(i).encode());
        }
        return sb.toString();
    }

    public static Loadout decode(String encoded) {
        Loadout loadout = new Loadout();
        if (encoded == null || encoded.isEmpty()) {
            return loadout;
        }
        int split = encoded.indexOf(NAME_SEPARATOR);
        String body = encoded;
        if (split >= 0) {
            loadout.name = unescape(encoded.substring(0, split));
            body = encoded.substring(split + 1);
        }
        if (body.isEmpty()) {
            return loadout;
        }
        for (String chunk : body.split(ENTRY_SEPARATOR)) {
            if (chunk.isEmpty()) {
                continue;
            }
            LoadoutEntry entry = LoadoutEntry.decode(chunk);
            if (entry != null) {
                loadout.entries.add(entry);
            }
        }
        return loadout;
    }

    /** The three separators are the only characters that need escaping, so a tiny map is enough. */
    private static String escape(String raw) {
        return raw.replace("%", "%25")
                .replace(NAME_SEPARATOR, "%7E")
                .replace(ENTRY_SEPARATOR, "%3B")
                .replace(FIELD_SEPARATOR, "%7C");
    }

    private static String unescape(String raw) {
        return raw.replace("%7E", NAME_SEPARATOR)
                .replace("%3B", ENTRY_SEPARATOR)
                .replace("%7C", FIELD_SEPARATOR)
                .replace("%25", "%");
    }

    /** Same construction as the rule digest, so the two fingerprints are comparable in logs. */
    public String digest() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(encode().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "--------";
        }
    }

    @Override
    public String toString() {
        return name + "(" + entries.size() + " 项, " + digest() + ")";
    }
}
