package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

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

    /**
     * A stable identifier, not a display name.
     *
     * <p>It is encoded into the loadout string and therefore into its fingerprint, so it must not
     * change when the client's language does — a loadout saved in Chinese and re-read by an English
     * client would otherwise fingerprint differently for no reason at all. The presets are named by
     * id here and translated at the point of display by {@link #displayName()}.</p>
     */
    public String name = "default";
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
        loadout.name = "default_duel";

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

    /**
     * The name to show a player.
     *
     * <p>The two presets are stored as ids and translated here; anything else is a name that came
     * from disk, which is data rather than a string this mod owns, so it is shown as-is.</p>
     */
    public Text displayName() {
        return switch (name) {
            case "default" -> PvLang.t("pvpocket.loadout.name.default");
            case "default_duel" -> PvLang.t("pvpocket.loadout.name.default_duel");
            default -> Text.literal(name);
        };
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
     *
     * <p>Returns {@link Text} rather than strings because the same list is produced on both sides:
     * the client draws it in the footer, and the server quotes the first entry back in a chat line.
     * A string built on the server would be in the server's language, which is nobody's.</p>
     */
    public List<Text> validate() {
        List<Text> problems = new ArrayList<>();
        if (entries.isEmpty()) {
            problems.add(PvLang.t("pvpocket.loadout.problem.empty"));
        }
        if (entries.size() > LoadoutCatalog.MAX_ENTRIES) {
            problems.add(PvLang.t("pvpocket.loadout.problem.too_many",
                    LoadoutCatalog.MAX_ENTRIES, entries.size()));
        }
        boolean[] used = new boolean[41];
        for (int i = 0; i < entries.size(); i++) {
            LoadoutEntry entry = entries.get(i);
            MutableText row = PvLang.t("pvpocket.loadout.problem.row",
                    i + 1, entry.kind.labelText());
            if (entry.slot >= 0 && entry.slot < used.length) {
                if (used[entry.slot]) {
                    problems.add(PvLang.t("pvpocket.loadout.problem.slot_taken", row));
                }
                used[entry.slot] = true;
            }
            problems.addAll(validateEntry(entry, row));
        }
        return problems;
    }

    private static List<Text> validateEntry(LoadoutEntry entry, MutableText row) {
        List<Text> problems = new ArrayList<>();
        // Only materialised kinds are named <material>_<kind>. FOOD and BLOCK resolve through their
        // variant instead, so asking "does iron_food exist" would reject every valid food row — and
        // did, which made the default preset unsaveable until the editor exposed it.
        if (entry.kind.materialised && entry.kind.resolve(entry.material) == null) {
            problems.add(PvLang.t("pvpocket.loadout.problem.no_such_item", row));
        }
        if (entry.count < 1 || entry.count > 64) {
            problems.add(PvLang.t("pvpocket.loadout.problem.count", row));
        }
        if (entry.damageTenths != 0
                && (entry.damageTenths < LoadoutCatalog.DAMAGE_MIN_TENTHS
                    || entry.damageTenths > LoadoutCatalog.DAMAGE_MAX_TENTHS)) {
            problems.add(PvLang.t("pvpocket.loadout.problem.damage", row,
                    LoadoutCatalog.DAMAGE_MIN_TENTHS / 10.0,
                    LoadoutCatalog.DAMAGE_MAX_TENTHS / 10.0));
        }
        if (entry.armorTenths < 0 || entry.armorTenths > LoadoutCatalog.ARMOR_MAX_TENTHS) {
            problems.add(PvLang.t("pvpocket.loadout.problem.armor", row));
        }
        if (entry.durability != LoadoutEntry.DURABILITY_UNBREAKABLE
                && (entry.durability < 0 || entry.durability > LoadoutCatalog.DURABILITY_MAX)) {
            problems.add(PvLang.t("pvpocket.loadout.problem.durability", row,
                    LoadoutCatalog.DURABILITY_MAX));
        }
        if (entry.knockback < 0 || entry.knockback > LoadoutCatalog.KNOCKBACK_MAX) {
            problems.add(PvLang.t("pvpocket.loadout.problem.knockback", row,
                    LoadoutCatalog.KNOCKBACK_MAX));
        }
        if (entry.arrowCount < 0 || entry.arrowCount > LoadoutCatalog.ARROW_MAX) {
            problems.add(PvLang.t("pvpocket.loadout.problem.arrows", row,
                    LoadoutCatalog.ARROW_MAX));
        }
        switch (entry.kind) {
            case POTION -> {
                if (!LoadoutCatalog.isEffect(entry.variant)) {
                    problems.add(PvLang.t("pvpocket.loadout.problem.effect_unknown", row));
                }
                if (entry.durationSec < LoadoutCatalog.DURATION_MIN_SEC
                        || entry.durationSec > LoadoutCatalog.DURATION_MAX_SEC) {
                    problems.add(PvLang.t("pvpocket.loadout.problem.duration", row,
                            LoadoutCatalog.DURATION_MIN_SEC, LoadoutCatalog.DURATION_MAX_SEC));
                }
                if (entry.amplifier < 0 || entry.amplifier > LoadoutCatalog.AMPLIFIER_MAX) {
                    problems.add(PvLang.t("pvpocket.loadout.problem.amplifier", row));
                }
            }
            case FOOD -> {
                if (!LoadoutCatalog.isFood(entry.variant)) {
                    problems.add(PvLang.t("pvpocket.loadout.problem.food_unknown", row));
                }
            }
            case BLOCK -> {
                if (!LoadoutCatalog.isBlock(entry.variant)) {
                    problems.add(PvLang.t("pvpocket.loadout.problem.block_unknown", row));
                }
            }
            default -> {
            }
        }
        for (WeaponFlag flag : WeaponFlag.decode(entry.flags)) {
            if (!flag.appliesTo(entry.kind)) {
                problems.add(PvLang.t("pvpocket.loadout.problem.flag_mismatch", row,
                        flag.labelText(), entry.kind.labelText()));
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
        return name + "(" + entries.size() + " items, " + digest() + ")";
    }
}
