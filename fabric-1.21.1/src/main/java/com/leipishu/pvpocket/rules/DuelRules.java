package com.leipishu.pvpocket.rules;

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

    private DuelRules() {
    }

    public static DuelRules defaults() {
        DuelRules rules = new DuelRules();
        for (RuleDefinition def : RuleCatalog.all()) {
            rules.values.put(def.id, def.defaultValue);
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
                rules.values.put(id, def == null ? raw : def.clamp(raw));
            } catch (NumberFormatException ignored) {
                // A malformed pair is dropped; the default for that id stays in place.
            }
        }
        return rules;
    }

    public DuelRules copy() {
        DuelRules copy = new DuelRules();
        copy.values.putAll(values);
        return copy;
    }

    public int get(String id) {
        Integer v = values.get(id);
        if (v != null) {
            return v;
        }
        RuleDefinition def = RuleCatalog.byId(id);
        return def == null ? 0 : def.defaultValue;
    }

    public void set(String id, int value) {
        RuleDefinition def = RuleCatalog.byId(id);
        values.put(id, def == null ? value : def.clamp(value));
    }

    public boolean flag(String id) {
        return get(id) != 0;
    }

    public boolean has(String id, int bit) {
        return (get(id) & (1 << bit)) != 0;
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

    /** One-line summary for chat: only the rules that shape the match, not all thirty-six. */
    public String summary() {
        RuleDefinition series = RuleCatalog.byId("a.series");
        RuleDefinition mode = RuleCatalog.byId("c.mode");
        RuleDefinition sizeX = RuleCatalog.byId("e.sizeX");
        RuleDefinition sizeZ = RuleCatalog.byId("e.sizeZ");
        String team = get("b.teamCount") + " 队 × " + get("b.teamSize") + " 人";
        return series.format(get("a.series"))
                + " · " + mode.format(get("c.mode"))
                + " · " + team
                + " · " + sizeX.format(get("e.sizeX")).trim() + "×" + sizeZ.format(get("e.sizeZ")).trim()
                + " · 生命 " + get("c.maxHealth");
    }
}
