package com.leipishu.pvpocket.rules;

/**
 * The rule set read by name, with the semantics of each rule in one place.
 *
 * <p>{@link DuelRules} is the wire format: a map of id to integer, and it should stay that way. The
 * question "does a fall hurt right now" is not a map lookup — it is the answer to two switches
 * ({@code c.fallDamage} and the fall bit of {@code e.hazard}) plus the "no hazards at all" bit. That
 * reasoning used to live nowhere, which is why nineteen rules were recorded and never read: reading
 * one meant remembering which ids overlap and how the bits are packed.</p>
 *
 * <p>One method per question, no ids in the callers, no bit arithmetic outside this file.</p>
 *
 * <p><b>Not cached.</b> Instances are cheap wrappers and are built from a session's decoded rules;
 * the session caches the decode (see {@code DuelSession#rulebook()}).</p>
 */
public final class Rulebook {

    /** {@code e.hazard} bit: "no hazards at all". Wins over the three bits below. */
    private static final int HAZARD_NONE = 1 << 0;
    private static final int HAZARD_FALL = 1 << 1;
    private static final int HAZARD_LAVA = 1 << 2;
    private static final int HAZARD_VOID = 1 << 3;

    private final DuelRules rules;

    private Rulebook(DuelRules rules) {
        this.rules = rules;
    }

    public static Rulebook of(DuelRules rules) {
        return new Rulebook(rules == null ? DuelRules.defaults() : rules);
    }

    // ------------------------------------------------------------------ B. 参与者

    /** {@code b.friendlyFire}: whether a side can damage itself. */
    public boolean friendlyFire() {
        return rules.flag("b.friendlyFire");
    }

    // ------------------------------------------------------------------ C. 战斗

    /** {@code c.naturalRegen}: hunger-driven health regeneration. */
    public boolean naturalRegen() {
        return rules.flag("c.naturalRegen");
    }

    /** {@code c.hunger}: {@code 正常} is 0, {@code 关闭} is 1. */
    public boolean hunger() {
        return rules.get("c.hunger") == 0;
    }

    /**
     * Fall damage, which two rules can turn off.
     *
     * <p>{@code c.fallDamage} is the general switch and {@code e.hazard} has a fall bit of its own.
     * They overlap, so the stricter of the two wins: a player who clears the hazard bit and sees
     * their landing still hurt would be right to call it a bug, and so would one who turns off
     * {@code c.fallDamage} and still dies to a three-block drop.</p>
     */
    public boolean fallDamage() {
        return rules.flag("c.fallDamage") && hazard(HAZARD_FALL);
    }

    /** {@code e.hazard} · lava. */
    public boolean lavaHazard() {
        return hazard(HAZARD_LAVA);
    }

    /** {@code e.hazard} · void. */
    public boolean voidHazard() {
        return hazard(HAZARD_VOID);
    }

    /**
     * {@code c.knockbackResistance} as the attribute wants it, 0..1.
     *
     * <p>The rule is stored as a percentage because that is what the panel shows; the attribute is a
     * fraction. Converting here keeps every caller from having to remember the divisor.</p>
     */
    public double knockbackResistance() {
        return rules.get("c.knockbackResistance") / 100.0D;
    }

    private boolean hazard(int bit) {
        int mask = rules.get("e.hazard");
        if ((mask & HAZARD_NONE) != 0) {
            return false;
        }
        return (mask & bit) != 0;
    }
}
