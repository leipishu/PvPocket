package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.loadout.LoadoutFields;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.LoadoutCatalog;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.rules.RuleCatalog;
import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.session.VoteItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Names and diffs for a loadout row on the ballot.
 *
 * <p>The server sends whole rows and nothing else, because it has no language. Everything a player
 * reads here is built on the client from the same {@link LoadoutFields} table the editor uses, so a
 * row that is one slider apart reads as exactly that one slider rather than as "different".
 */
public final class VoteText {

    /** How many changed fields a chip names before it gives up and says "...". */
    private static final int MAX_PARTS = 3;

    private VoteText() {
    }

    /** The row's own name, such as "iron sword" or "golden apple". */
    public static String title(LoadoutEntry entry) {
        StringBuilder sb = new StringBuilder(entry.kind.label());
        if (entry.kind.materialised) {
            sb.append(' ').append(entry.material.label());
        }
        if (!entry.variant.isEmpty()) {
            sb.append(' ').append(variant(entry.variant));
        }
        return sb.toString();
    }

    /**
     * The row as it stands, for the "original" side of the line: {@code damage 7.0}.
     *
     * <p>Names the one number that identifies the row, in the editor's own words: damage for a
     * weapon, armour for a piece of armour, the companion count for a bow, otherwise the stack
     * size. A row that is only a count still reads better as "count 32" than as a bare number.
     */
    public static String value(LoadoutEntry entry) {
        List<LoadoutFields.Field> fields = LoadoutFields.of(entry.copy());
        for (String wanted : new String[]{"lo.damage", "lo.armor", "lo.arrows", "lo.companion.crystal",
                "lo.companion.tnt", "lo.count"}) {
            LoadoutFields.Field field = find(fields, wanted);
            if (field == null) {
                continue;
            }
            int value = field.get().getAsInt();
            if (wanted.equals("lo.count") || value != 0) {
                return field.def().label() + " " + field.def().format(value);
            }
        }
        return title(entry);
    }

    /**
     * What this candidate changes, one field per part: {@code damage 7.0->8.0, knockback 0->2}.
     *
     * <p>Falls back to the whole row when the field table finds nothing, which can only happen if a
     * kind's fields are version-dependent.
     */
    public static String diff(LoadoutEntry base, LoadoutEntry candidate) {
        List<LoadoutFields.Field> before = LoadoutFields.of(base.copy());
        List<LoadoutFields.Field> after = LoadoutFields.of(candidate.copy());
        List<String> parts = new ArrayList<>();
        for (LoadoutFields.Field field : before) {
            LoadoutFields.Field other = find(after, field.def().id);
            if (other == null) {
                continue;
            }
            int a = field.get().getAsInt();
            int b = other.get().getAsInt();
            if (a == b) {
                continue;
            }
            parts.add(field.def().label() + " " + field.def().format(a)
                    + "→" + other.def().format(b));
        }
        if (parts.isEmpty()) {
            return title(candidate);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size() && i < MAX_PARTS; i++) {
            if (i > 0) {
                sb.append(" · ");
            }
            sb.append(parts.get(i));
        }
        if (parts.size() > MAX_PARTS) {
            sb.append(" …");
        }
        return sb.toString();
    }

    /**
     * The heading an item is listed under: its rule label, or the row it carries.
     *
     * <p>An addition has no row in main, so the first candidate names it. Shared by the ballot and
     * the result HUD so the two cannot call the same item by two names.
     */
    public static String itemTitle(VoteItem item) {
        if (item.kind == VoteItem.KIND_RULE) {
            RuleDefinition def = RuleCatalog.byId(item.ruleId);
            return def == null ? item.ruleId : def.label();
        }
        LoadoutEntry entry = entryOf(item.baseValue());
        if (entry == null) {
            for (int i = 1; i < item.options.size(); i++) {
                entry = entryOf(item.options.get(i).value);
                if (entry != null) {
                    break;
                }
            }
        }
        return entry == null ? item.id : title(entry);
    }

    /**
     * What one item was decided on, for the read-only list after the vote.
     *
     * <p>Unlike a ballot chip this carries no proposer and no "keep" wording: the list answers what
     * the match will run on, so the winning option is spelled out as the value it holds, and an
     * option that removes or skips a row says so.
     */
    public static String decidedValue(VoteItem item, int winner) {
        int index = winner < 0 || winner >= item.options.size() ? 0 : winner;
        String raw = item.options.get(index).value;
        if (item.kind == VoteItem.KIND_RULE) {
            RuleDefinition def = RuleCatalog.byId(item.ruleId);
            return def == null ? raw : def.format(parse(raw));
        }
        LoadoutEntry chosen = entryOf(raw);
        if (index == 0) {
            if (item.kind == VoteItem.KIND_ADD) {
                return PvLang.str("pvpocket.gui.vote.skip");
            }
            return chosen == null ? "" : value(chosen);
        }
        if (chosen == null) {
            return PvLang.str("pvpocket.gui.vote.remove");
        }
        if (item.kind == VoteItem.KIND_ADD) {
            return title(chosen);
        }
        LoadoutEntry before = entryOf(item.baseValue());
        return before == null ? title(chosen) : diff(before, chosen);
    }

    private static LoadoutEntry entryOf(String encoded) {
        return encoded == null || encoded.isEmpty() ? null : LoadoutEntry.decode(encoded);
    }

    private static int parse(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses the payload's {@code uuid|name,uuid|name} map; a malformed pair is skipped. */
    public static Map<String, String> parseNames(String encoded) {
        Map<String, String> out = new java.util.HashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String pair : encoded.split(",")) {
            int bar = pair.indexOf('|');
            if (bar <= 0) {
                continue;
            }
            out.put(pair.substring(0, bar), pair.substring(bar + 1));
        }
        return out;
    }

    private static LoadoutFields.Field find(List<LoadoutFields.Field> fields, String id) {
        for (LoadoutFields.Field field : fields) {
            if (field.def().id.equals(id)) {
                return field;
            }
        }
        return null;
    }

    /**
     * A variant id as the player's language names it.
     *
     * <p>Looked up in the same catalog tables the editor builds its cycles from, so the ballot and
     * the editor cannot disagree about what a row is called. An id this build does not offer is
     * shown raw rather than as an empty chip.
     */
    private static String variant(String id) {
        for (LoadoutCatalog.Option option : LoadoutCatalog.foods()) {
            if (option.id().equals(id)) {
                return PvLang.str(option.labelKey());
            }
        }
        for (LoadoutCatalog.Option option : LoadoutCatalog.blocks()) {
            if (option.id().equals(id)) {
                return PvLang.str(option.labelKey());
            }
        }
        for (LoadoutCatalog.EffectOption option : LoadoutCatalog.effects()) {
            if (option.id().equals(id)) {
                return PvLang.str(option.pickKey());
            }
        }
        return id;
    }
}
