package com.leipishu.pvpocket.session;

import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.rules.DuelRules;

/**
 * One participant's alternative to the match's rule set: a branch off main.
 *
 * <p>Plain data so it round-trips through Gson. The encoded strings are the same formats the
 * session already stores ({@code DuelRules#encode}, {@code Loadout#encode}), which keeps a branch
 * readable in the journal and diffable by hand.
 *
 * <p>At most one branch per player, replaced on every re-submission: the model is "amend your
 * branch", not "stack of patches". A branch equal to main is dropped rather than stored, so a
 * player who opened the editor and saved without touching anything does not appear as a modifier.
 */
public final class RuleBranch {

    public String uuid;
    public String name;
    /** This player's proposed rule set, encoded. */
    public String rules;
    /** This player's proposed loadout, encoded, rows carrying their {@code ref}. */
    public String loadout;
    /** This player's proposed arrangement, {@code uuid,uuid;uuid}; empty means main's. */
    public String roster;
    /**
     * {@code rulesDigest + ":" + loadoutDigest}, so "changed nothing" is one string comparison.
     *
     * <p>Recomputed on every submission; never trusted from the client.
     */
    public String digest;
    public long submittedAt;

    public RuleBranch() {
    }

    public RuleBranch(String uuid, String name, String rules, String loadout, String roster,
                      String digest) {
        this.uuid = uuid;
        this.name = name;
        this.rules = rules;
        this.loadout = loadout;
        this.roster = roster;
        this.digest = digest;
        this.submittedAt = System.currentTimeMillis();
    }

    public DuelRules decodedRules() {
        return DuelRules.decode(rules);
    }

    public Loadout decodedLoadout() {
        return Loadout.decode(loadout);
    }
}
