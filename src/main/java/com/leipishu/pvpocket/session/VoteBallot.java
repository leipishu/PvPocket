package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.rules.DuelRules;
import com.leipishu.pvpocket.rules.RuleCatalog;
import com.leipishu.pvpocket.rules.RuleDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns branches into a ballot, counts the votes and merges the winners.
 *
 * <p>Pure data: no server, no packets, no language. The same inputs always produce the same
 * ballot and the same result, which makes a disputed match re-checkable from the journal.
 *
 * <p>Option 0 is the base and wins every tie; a loadout row is one item, so no merge can produce a
 * combination that no branch validated; an item exists only if somebody proposed a change.
 */
public final class VoteBallot {

    /** Key of one player's vote on one item, as stored in {@code DuelSession#votes}. */
    public static String voteKey(String uuid, String itemId) {
        return uuid + "|" + itemId;
    }

    // ------------------------------------------------------------------ build

    /**
     * Builds the ballot from main and the branches that still count.
     *
     * <p>Rows are numbered in list order when a session predates the field, which produces the same
     * numbers the invite would have. The branch list holds players still in the match, in
     * submission order.
     */
    public static List<VoteItem> build(DuelRules main, Loadout mainLoadout, String mainRoster,
                                       List<RuleBranch> branches) {
        List<VoteItem> out = new ArrayList<>();
        if (branches.isEmpty()) {
            return out;
        }
        // A session written before rows were numbered has no refs; numbering here is the same
        // numbering the invite would have produced, since it is list order.
        if (mainLoadout.entries.stream().anyMatch(e -> e.ref < 0)) {
            mainLoadout.assignRefs();
        }
        // The arrangement comes first: it is what the invite showed on its opening page, and it
        // decides who is on which side before any rule is read.
        VoteItem roster = rosterItem(mainRoster, branches);
        if (roster != null) {
            out.add(roster);
        }
        out.addAll(ruleItems(main, branches));
        out.addAll(entryItems(mainLoadout, branches));
        out.addAll(addItems(mainLoadout, branches));
        return out;
    }

    private static List<VoteItem> ruleItems(DuelRules main, List<RuleBranch> branches) {
        List<VoteItem> out = new ArrayList<>();
        for (RuleDefinition def : RuleCatalog.all()) {
            if (RuleCatalog.lockedAfterSend(def.id)) {
                continue;
            }
            int base = main.get(def.id);
            // Value -> who proposed it, in branch order, so the ballot is stable across rebuilds.
            Map<Integer, List<String>> byValue = new LinkedHashMap<>();
            for (RuleBranch branch : branches) {
                int value = branch.decodedRules().get(def.id);
                if (value != base) {
                    byValue.computeIfAbsent(value, k -> new ArrayList<>()).add(branch.name);
                }
            }
            if (byValue.isEmpty()) {
                continue;
            }
            VoteItem item = new VoteItem();
            item.id = "r:" + def.id;
            item.kind = VoteItem.KIND_RULE;
            item.ruleId = def.id;
            item.options.add(VoteItem.Option.base(Integer.toString(base)));
            for (Map.Entry<Integer, List<String>> e : byValue.entrySet()) {
                item.options.add(new VoteItem.Option(Integer.toString(e.getKey()), e.getValue()));
            }
            out.add(item);
        }
        return out;
    }

    /**
     * One item per row main already has, when at least one branch changed or dropped it.
     *
     * <p>Rows are matched by {@code ref}, never by position: a branch that deletes its second row
     * still lines its remaining rows up with main's.
     */
    /** One item for the whole arrangement: candidates are complete rosters, never single moves. */
    private static VoteItem rosterItem(String mainRoster, List<RuleBranch> branches) {
        Map<String, List<String>> byRoster = new LinkedHashMap<>();
        for (RuleBranch branch : branches) {
            String roster = branch.roster;
            if (roster == null || roster.isEmpty() || roster.equals(mainRoster)) {
                continue;
            }
            byRoster.computeIfAbsent(roster, k -> new ArrayList<>()).add(branch.name);
        }
        if (byRoster.isEmpty()) {
            return null;
        }
        VoteItem item = new VoteItem();
        item.id = "t:roster";
        item.kind = VoteItem.KIND_ROSTER;
        item.options.add(VoteItem.Option.base(mainRoster));
        for (Map.Entry<String, List<String>> e : byRoster.entrySet()) {
            item.options.add(new VoteItem.Option(e.getKey(), e.getValue()));
        }
        return item;
    }

    private static List<VoteItem> entryItems(Loadout main, List<RuleBranch> branches) {
        List<VoteItem> out = new ArrayList<>();
        // ref -> (row signature -> proposers), and ref -> who wants it gone.
        Map<Integer, Map<String, List<String>>> changed = new TreeMap<>();
        Map<Integer, Map<String, String>> values = new TreeMap<>();
        Map<Integer, List<String>> removed = new TreeMap<>();

        for (RuleBranch branch : branches) {
            Loadout loadout = branch.decodedLoadout();
            Set<Integer> seen = new HashSet<>();
            for (LoadoutEntry entry : loadout.entries) {
                LoadoutEntry base = main.byRef(entry.ref);
                if (base == null) {
                    continue;   // an addition; see addItems
                }
                seen.add(entry.ref);
                String sig = signature(entry);
                if (sig.equals(signature(base))) {
                    continue;
                }
                changed.computeIfAbsent(entry.ref, k -> new LinkedHashMap<>())
                        .computeIfAbsent(sig, k -> new ArrayList<>()).add(branch.name);
                values.computeIfAbsent(entry.ref, k -> new HashMap<>()).put(sig, entry.encode());
            }
            for (LoadoutEntry base : main.entries) {
                if (!seen.contains(base.ref)) {
                    removed.computeIfAbsent(base.ref, k -> new ArrayList<>()).add(branch.name);
                }
            }
        }

        for (LoadoutEntry base : main.entries) {
            Map<String, List<String>> versions = changed.get(base.ref);
            List<String> gone = removed.get(base.ref);
            if (versions == null && gone == null) {
                continue;
            }
            VoteItem item = new VoteItem();
            item.id = "e:" + base.ref;
            item.kind = VoteItem.KIND_ENTRY;
            item.entryRef = base.ref;
            item.options.add(VoteItem.Option.base(base.encode()));
            if (versions != null) {
                Map<String, String> encoded = values.get(base.ref);
                for (Map.Entry<String, List<String>> e : versions.entrySet()) {
                    item.options.add(new VoteItem.Option(encoded.get(e.getKey()), e.getValue()));
                }
            }
            if (gone != null) {
                // An empty value is "remove this row", and it competes like any other candidate.
                item.options.add(new VoteItem.Option("", gone));
            }
            out.add(item);
        }
        return out;
    }

    /** One item per row nobody had before, grouped by content so two identical additions merge. */
    private static List<VoteItem> addItems(Loadout main, List<RuleBranch> branches) {
        Map<String, List<String>> proposers = new LinkedHashMap<>();
        Map<String, String> encoded = new HashMap<>();
        for (RuleBranch branch : branches) {
            for (LoadoutEntry entry : branch.decodedLoadout().entries) {
                if (main.byRef(entry.ref) != null) {
                    continue;
                }
                String sig = signature(entry);
                proposers.computeIfAbsent(sig, k -> new ArrayList<>()).add(branch.name);
                encoded.putIfAbsent(sig, entry.encode());
            }
        }
        List<VoteItem> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : proposers.entrySet()) {
            VoteItem item = new VoteItem();
            item.id = "e:new:" + shortHash(e.getKey());
            item.kind = VoteItem.KIND_ADD;
            item.options.add(VoteItem.Option.base(""));
            item.options.add(new VoteItem.Option(encoded.get(e.getKey()), e.getValue()));
            out.add(item);
        }
        return out;
    }

    /**
     * What makes two rows "the same row, unchanged".
     *
     * <p>Slot is excluded: hotbar position is not voted on, and the editor re-derives it after a
     * merge. Every other field is included, so a row that differs in any visible way is a
     * candidate.
     */
    private static String signature(LoadoutEntry entry) {
        LoadoutEntry copy = entry.copy();
        copy.slot = -1;
        copy.ref = -1;
        return copy.encode();
    }

    private static String shortHash(String raw) {
        return Integer.toHexString(raw.hashCode());
    }

    // ------------------------------------------------------------------ count

    /** Votes per option index, counting only {@code voters}. */
    public static int[] counts(VoteItem item, List<String> voters, Map<String, String> votes) {
        int[] counts = new int[Math.max(1, item.options.size())];
        for (String uuid : voters) {
            String raw = votes.get(voteKey(uuid, item.id));
            if (raw == null) {
                continue;
            }
            int index = parseInt(raw, -1);
            if (index >= 0 && index < counts.length) {
                counts[index]++;
            }
        }
        return counts;
    }

    /**
     * The winning option index.
     *
     * <p>Zero on a tie and zero when nobody voted: "most votes wins, a tie keeps what main had".
     * There is no tie-break by proposal order, which would let the first submitter win a split.
     */
    public static int winner(int[] counts) {
        int best = 0;
        int bestCount = 0;
        boolean tied = false;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > bestCount) {
                bestCount = counts[i];
                best = i;
                tied = false;
            } else if (counts[i] == bestCount && counts[i] > 0) {
                tied = true;
            }
        }
        return bestCount == 0 || tied ? 0 : best;
    }

    /** True when every voter has cast a vote on every item. */
    public static boolean everyoneVoted(List<VoteItem> items, List<String> voters,
                                        Map<String, String> votes) {
        for (VoteItem item : items) {
            for (String uuid : voters) {
                if (!votes.containsKey(voteKey(uuid, item.id))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Voters who have voted on every item; the footer's "N still to vote" is its complement. */
    public static int finishedVoters(List<VoteItem> items, List<String> voters,
                                     Map<String, String> votes) {
        int done = 0;
        for (String uuid : voters) {
            boolean all = true;
            for (VoteItem item : items) {
                if (!votes.containsKey(voteKey(uuid, item.id))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                done++;
            }
        }
        return done;
    }

    // ------------------------------------------------------------------ merge

    /** The winning arrangement, or {@code mainRoster} when main won or nothing was proposed. */
    public static String resolveRoster(String mainRoster, List<VoteItem> items, List<String> voters,
                                       Map<String, String> votes) {
        for (VoteItem item : items) {
            if (item.kind != VoteItem.KIND_ROSTER) {
                continue;
            }
            int index = winner(counts(item, voters, votes));
            if (index > 0 && index < item.options.size()) {
                return item.options.get(index).value;
            }
        }
        return mainRoster;
    }

    /**
     * Applies the winning rule values onto a copy of main.
     *
     * @param vetoed rule ids whose winning value would make the roster invalid; they keep main's
     *               value. See {@code SessionManager#mergeRules}.
     */
    public static DuelRules resolveRules(DuelRules main, List<VoteItem> items, List<String> voters,
                                         Map<String, String> votes, Set<String> vetoed) {
        DuelRules merged = main.copy();
        for (VoteItem item : items) {
            if (item.kind != VoteItem.KIND_RULE || vetoed.contains(item.ruleId)) {
                continue;
            }
            int index = winner(counts(item, voters, votes));
            if (index <= 0 || index >= item.options.size()) {
                continue;
            }
            merged.set(item.ruleId, parseInt(item.options.get(index).value, main.get(item.ruleId)));
        }
        return merged;
    }

    /**
     * Applies the winning rows onto a copy of main.
     *
     * <p>Order is fixed and matters: replace and remove act on main's rows by {@code ref}, then
     * additions are appended, then every row that changed gets its slot re-derived. That last step
     * is what keeps two winning additions from claiming the same hotbar cell.
     */
    public static Loadout resolveLoadout(Loadout main, List<VoteItem> items, List<String> voters,
                                         Map<String, String> votes) {
        Loadout merged = main.copy();
        for (VoteItem item : items) {
            if (item.kind != VoteItem.KIND_ENTRY) {
                continue;
            }
            int index = winner(counts(item, voters, votes));
            if (index <= 0 || index >= item.options.size()) {
                continue;
            }
            String value = item.options.get(index).value;
            int at = indexOfRef(merged, item.entryRef);
            if (at < 0) {
                continue;
            }
            if (value.isEmpty()) {
                merged.entries.remove(at);
                continue;
            }
            LoadoutEntry replacement = LoadoutEntry.decode(value);
            if (replacement == null) {
                continue;
            }
            replacement.ref = item.entryRef;
            // The slot belongs to the row that lost, not to the row that won: hotbar position is
            // not voted on, and re-deriving it is what stops two winners sharing a cell.
            replacement.slot = -1;
            merged.entries.set(at, replacement);
        }

        int nextRef = merged.nextRef();
        for (VoteItem item : items) {
            if (item.kind != VoteItem.KIND_ADD) {
                continue;
            }
            int index = winner(counts(item, voters, votes));
            if (index <= 0 || index >= item.options.size()) {
                continue;
            }
            LoadoutEntry added = LoadoutEntry.decode(item.options.get(index).value);
            if (added == null) {
                continue;
            }
            added.ref = nextRef++;
            added.slot = -1;
            merged.entries.add(added);
        }

        merged.autoAssignSlots();
        return merged;
    }

    private static int indexOfRef(Loadout loadout, int ref) {
        for (int i = 0; i < loadout.entries.size(); i++) {
            if (loadout.entries.get(i).ref == ref) {
                return i;
            }
        }
        return -1;
    }

    /** One line per item for the journal: the tally and what won. */
    public static String tallyLine(VoteItem item, List<String> voters, Map<String, String> votes) {
        int[] counts = counts(item, voters, votes);
        StringBuilder sb = new StringBuilder(item.id).append('[');
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(counts[i]);
        }
        sb.append("]winner=").append(winner(counts));
        return sb.toString();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
