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
        bow.companionCount = 32;
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

    /** How many shield rows this loadout has, for the "legacy dropped it" journal line. */
    public int shieldCount() {
        int count = 0;
        for (LoadoutEntry entry : entries) {
            if (entry.kind == ItemKind.SHIELD) {
                count++;
            }
        }
        return count;
    }

    /**
     * How many rows carry 禁止丢弃.
     *
     * <p>Journalled next to {@code LOADOUT_APPLIED}. The digest alone cannot answer "did my switch
     * reach the round": it changes for any edit, so it says the loadout is a different document
     * without saying whether the lock came with it. A number that goes 6 → 5 the moment the switch is
     * turned off makes that answer readable at a glance.</p>
     */
    public int lockedCount() {
        int count = 0;
        for (LoadoutEntry entry : entries) {
            if (entry.noDrop) {
                count++;
            }
        }
        return count;
    }

    /**
     * The same loadout with every shield taken out.
     *
     * <p>1.8 has no shields, and leaving one in the offhand is not cosmetic: vanilla raises it on
     * right-click, which both blocks damage outright and eats the right-click that the 1.8 sword
     * block needs. Stripping happens at grant time rather than at edit time so one saved loadout can
     * serve a modern round and a legacy one without being rewritten behind the player's back.</p>
     *
     * <p>Returns a copy — the caller's loadout still describes what the player agreed to, which is
     * what the fingerprint in the journal is taken over.</p>
     */
    public Loadout withoutShields() {
        Loadout copy = copy();
        copy.entries.removeIf(entry -> entry.kind == ItemKind.SHIELD);
        // A row that arrived without a slot (a hand-edited string) is skipped by grant(), so the
        // layout is re-derived after the removal rather than assumed complete.
        copy.autoAssignSlots();
        return copy;
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
            // The elytra goes on the chest slot too, so it competes with the chestplate for 38. The
            // loser falls through to the hotbar rather than silently overwriting a chosen item.
            case CHESTPLATE, ELYTRA -> 38;
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
        // Every kind, not only the materialised ones. A mace on 1.20.1 and a wind charge on 1.20.1
        // resolve to an empty stack exactly as quietly as a copper spear on 1.21.1, and the old
        // materialised-only guard let both through: the row validated clean and then granted nothing.
        //
        // Two different sentences, because they are two different mistakes. "This version has no
        // such item" is a preset that came from a newer build and can only be fixed by removing the
        // row; "this material has no such item" is a row on a material this build cannot build and
        // can be fixed by picking another tier.
        if (!entry.kind.resolvesByVariant()) {
            if (!entry.kind.available()) {
                problems.add(PvLang.t("pvpocket.loadout.problem.item_missing", row));
            } else if (entry.kind.resolve(entry.material) == null) {
                problems.add(PvLang.t("pvpocket.loadout.problem.no_such_item", row));
            }
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
        if (entry.companionCount < 0 || entry.companionCount > LoadoutCatalog.COMPANION_MAX) {
            problems.add(PvLang.t("pvpocket.loadout.problem.companion", row,
                    LoadoutCatalog.COMPANION_MAX));
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
            case FIREWORK_ROCKET -> {
                if (entry.fireworkFlight < LoadoutCatalog.FIREWORK_FLIGHT_MIN
                        || entry.fireworkFlight > LoadoutCatalog.FIREWORK_FLIGHT_MAX) {
                    problems.add(PvLang.t("pvpocket.loadout.problem.firework_flight", row,
                            LoadoutCatalog.FIREWORK_FLIGHT_MIN,
                            LoadoutCatalog.FIREWORK_FLIGHT_MAX));
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

    /**
     * Whether any row that produced {@code fingerprint} still asks for 禁止丢弃.
     *
     * <p>Read by {@link LoadoutLock#restamp} after a save. Two rows can grant the same item with
     * different settings; taking the stricter answer means a stack is only unlocked when every row
     * that could have produced it says it may be dropped.</p>
     */
    public boolean locks(String fingerprint) {
        for (LoadoutEntry entry : entries) {
            if (entry.noDrop && entry.fingerprint().equals(fingerprint)) {
                return true;
            }
        }
        return false;
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
