package com.leipishu.pvpocket.session;

import java.util.ArrayList;
import java.util.List;

/**
 * One thing the roster votes on: a rule, or one loadout row.
 *
 * <p>Option 0 is always "keep what main has"; every option after it is a value some player
 * proposed. A tie at the top resolves to option 0, so the result is deterministic.
 *
 * <p>Loadout rows are voted on whole, so a merged set can never contain a combination that no
 * proposer validated.
 *
 * <p>Wire form: one line per item, fields separated by tabs, items by newlines.
 */
public final class VoteItem {

    /** A rule id and its candidate integer values. */
    public static final int KIND_RULE = 0;
    /** A row main already has: candidates are whole rows, and an empty value means "remove it". */
    public static final int KIND_ENTRY = 1;
    /** A row no branch of main has: candidates are whole rows, and option 0 means "do not add". */
    public static final int KIND_ADD = 2;
    /** The arrangement: candidates are whole rosters, so a side move cannot be half-applied. */
    public static final int KIND_ROSTER = 3;

    private static final String FIELD = "\t";
    private static final String ITEM = "\n";
    private static final String NAMES = "~";

    public String id;
    /** One of the {@code KIND_*} constants. */
    public int kind;
    /** Rule id for {@link #KIND_RULE}; empty otherwise. */
    public String ruleId = "";
    /** Row number for {@link #KIND_ENTRY}; {@code -1} otherwise. */
    public int entryRef = -1;
    /** Index 0 is the base. Never empty. */
    public List<Option> options = new ArrayList<>();

    /** One value plus whoever proposed it. The base option has no proposers. */
    public static final class Option {
        public String value;
        public List<String> proposers = new ArrayList<>();

        public Option() {
        }

        public Option(String value, List<String> proposers) {
            this.value = value;
            this.proposers.addAll(proposers);
        }

        public static Option base(String value) {
            return new Option(value, List.of());
        }
    }

    public String baseValue() {
        return options.isEmpty() ? "" : options.get(0).value;
    }

    /** Candidate count, excluding the base. */
    public int candidateCount() {
        return Math.max(0, options.size() - 1);
    }

    // ------------------------------------------------------------------ codec

    public static String encodeAll(List<VoteItem> items) {
        StringBuilder sb = new StringBuilder();
        for (VoteItem item : items) {
            if (sb.length() > 0) {
                sb.append(ITEM);
            }
            sb.append(item.id).append(FIELD)
                    .append(item.kind).append(FIELD)
                    .append(item.ruleId).append(FIELD)
                    .append(item.entryRef);
            for (Option option : item.options) {
                sb.append(FIELD).append(option.value);
                if (!option.proposers.isEmpty()) {
                    sb.append(NAMES).append(String.join(",", option.proposers));
                }
            }
        }
        return sb.toString();
    }

    public static List<VoteItem> decodeAll(String encoded) {
        List<VoteItem> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return out;
        }
        for (String line : encoded.split(ITEM)) {
            if (line.isEmpty()) {
                continue;
            }
            // -1 keeps trailing empty fields, and an empty option value is meaningful.
            String[] parts = line.split(FIELD, -1);
            if (parts.length < 5) {
                continue;
            }
            VoteItem item = new VoteItem();
            item.id = parts[0];
            item.kind = parseInt(parts[1], KIND_RULE);
            item.ruleId = parts[2];
            item.entryRef = parseInt(parts[3], -1);
            for (int i = 4; i < parts.length; i++) {
                item.options.add(parseOption(parts[i]));
            }
            if (item.options.isEmpty()) {
                item.options.add(Option.base(""));
            }
            out.add(item);
        }
        return out;
    }

    private static Option parseOption(String raw) {
        int sep = raw.indexOf(NAMES);
        if (sep < 0) {
            return Option.base(raw);
        }
        Option option = new Option();
        option.value = raw.substring(0, sep);
        String names = raw.substring(sep + 1);
        if (!names.isEmpty()) {
            for (String name : names.split(",")) {
                if (!name.isEmpty()) {
                    option.proposers.add(name);
                }
            }
        }
        return option;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
