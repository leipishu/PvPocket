package com.leipishu.pvpocket.rules;

import com.leipishu.pvpocket.arena.ArenaMap;
import com.leipishu.pvpocket.arena.ArenaMaps;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A concrete rule set: one integer per rule id.
 *
 * <p>Wire and disk format is {@code id=value} pairs joined by {@code ;}. That is deliberately dull:
 * it diffs cleanly in a journal, survives a rule being added or removed without a format bump, and
 * lets a client built from a different jar round-trip rules it does not know about instead of
 * corrupting them.</p>
 *
 * <p>The set is mutable while a proposal is being edited and immutable in practice once the round
 * starts — the session stores a copy plus a digest, and the journal records both, so "I agreed to
 * v1 but v3 ran" is answerable after the fact.</p>
 */
public final class DuelRules {

    private final Map<String, Integer> values = new LinkedHashMap<>();
    /** Ids whose stored value the clamp had to move, as {@code raw->stored}; see {@link #decode}. */
    private final Map<String, String> clamped = new LinkedHashMap<>();

    private DuelRules() {
    }

    public static DuelRules defaults() {
        DuelRules rules = new DuelRules();
        for (RuleDefinition def : RuleCatalog.all()) {
            rules.values.put(def.id, normalise(def.id, def.defaultValue));
        }
        return rules;
    }

    public static DuelRules decode(String encoded) {
        DuelRules rules = defaults();
        if (encoded == null || encoded.isEmpty()) {
            return rules;
        }
        for (String part : encoded.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String id = part.substring(0, eq).trim();
            try {
                int raw = Integer.parseInt(part.substring(eq + 1).trim());
                RuleDefinition def = RuleCatalog.byId(id);
                // Unknown ids are kept verbatim: a rule added on the server but missing from an
                // older client must survive the round trip rather than be reset to its default.
                int stored = def == null ? raw : normalise(id, raw);
                // A value the normalisation had to move is the one case where the rule set a player
                // agreed to is not the rule set that will run, and it is silent by construction —
                // the digest in the journal hashes what was stored, not what was asked for.
                // Recorded here and journalled at accept time; the case that motivated it is a map
                // index left over from a build that had more maps than this one.
                if (stored != raw) {
                    rules.clamped.put(id, raw + "->" + stored);
                }
                rules.values.put(id, stored);
            } catch (NumberFormatException ignored) {
                // A malformed pair is dropped; the default for that id stays in place.
            }
        }
        return rules;
    }

    public DuelRules copy() {
        DuelRules copy = new DuelRules();
        copy.values.putAll(values);
        copy.clamped.putAll(clamped);
        return copy;
    }

    /** Ids whose value was moved by {@link RuleDefinition#clamp} on the way in, as {@code raw->stored}. */
    public Map<String, String> clampedValues() {
        return java.util.Collections.unmodifiableMap(clamped);
    }

    public int get(String id) {
        Integer v = values.get(id);
        if (v != null) {
            return v;
        }
        RuleDefinition def = RuleCatalog.byId(id);
        return def == null ? 0 : def.defaultValue;
    }

    /**
     * Everything a stored value has to satisfy before any code reads it.
     *
     * <p>Three steps, in this order: the definition's own range, the bits the panel will not let you
     * clear, and the bits that exclude the rest. Applied on both the {@code set} path and the
     * {@code decode} path, because the second one is how a session reloaded from disk, a preset file
     * or a hand-built packet gets in — and a rule set that can only be trusted when it arrived
     * through the panel is not a rule set at all.</p>
     */
    private static int normalise(String id, int value) {
        RuleDefinition def = RuleCatalog.byId(id);
        int out = def == null ? value : def.clamp(value);
        out |= RuleCatalog.lockedBits(id);
        int exclusive = RuleCatalog.exclusiveBits(id);
        if (exclusive != 0) {
            if ((out & exclusive) != 0) {
                out &= exclusive;
            } else if ((out & ~exclusive) == 0) {
                // "none of the ordinary bits" and "the exclusive bit" are the same state; storing
                // both spellings would make two rule sets that behave identically hash differently.
                out = exclusive;
            }
        }
        return out;
    }

    public void set(String id, int value) {
        values.put(id, normalise(id, value));
    }

    public boolean flag(String id) {
        return get(id) != 0;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : values.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Short stable fingerprint, recorded in the journal and shown in the panel footer. */
    public String digest() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(encode().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(encode().hashCode());
        }
    }

    /** Rule ids whose value differs from {@code other}, in catalog order. */
    public List<String> differences(DuelRules other) {
        List<String> out = new ArrayList<>();
        for (RuleDefinition def : RuleCatalog.all()) {
            if (get(def.id) != other.get(def.id)) {
                out.add(def.id);
            }
        }
        return out;
    }

    /**
     * One-line summary for chat: only the rules that shape the match, not all thirty-nine.
     *
     * <p>Assembled out of translatable pieces rather than formatted into a string. Every caller is
     * either the server (which has no language and must hand the client a {@link Text}) or a client
     * chat line, so a pre-formatted string would freeze one language into a message read by
     * somebody speaking the other.</p>
     */
    public MutableComponent summaryText() {
        RuleDefinition mode = RuleCatalog.byId("c.mode");
        RuleDefinition sizeX = RuleCatalog.byId("e.sizeX");
        RuleDefinition sizeZ = RuleCatalog.byId("e.sizeZ");
        // Clamped here as well as in the panel: "win 4 of at most 3" is a state the sliders can be
        // left in for a frame, and a summary line that promises an unreachable target is worse than
        // no summary at all.
        int maxRounds = get("a.maxRounds");
        int winsNeeded = Math.min(get("a.winsNeeded"), maxRounds);
        ArenaMap map = ArenaMaps.byIndex(get("e.template"));
        // The map is named, not just its size. It was the one field of this line that the reader
        // could not recover from anywhere else, and it is the most visible thing about a match — two
        // players who agreed on "斗兽场" had no way to tell from the summary that they were about to
        // be dropped into the fun house. The size is still printed because it is what the flat map
        // actually uses, but for any other map the name is the fact that matters.
        return Component.empty()
                .append(PvLang.t("pvpocket.rule.series.short", maxRounds, winsNeeded))
                .append(Component.literal(" · "))
                .append(mode.formatText(get("c.mode")))
                .append(Component.literal(" · "))
                .append(PvLang.t("pvpocket.rule.b.teamSlot", get("b.teamSize")))
                .append(Component.literal(" · "))
                .append(map.labelText())
                .append(Component.literal(" "))
                .append(PvLang.t("pvpocket.rule.e.size", sizeX.formatText(get("e.sizeX")),
                        sizeZ.formatText(get("e.sizeZ"))))
                .append(Component.literal(" · "))
                .append(PvLang.t("pvpocket.rule.c.maxHealthShort", get("c.maxHealth")));
    }

    /** The same summary as a string, for the self-drawn GUI. */
    public String summary() {
        return PvLang.str(summaryText());
    }
}
