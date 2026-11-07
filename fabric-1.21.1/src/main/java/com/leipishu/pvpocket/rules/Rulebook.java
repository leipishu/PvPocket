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

    /** {@code a.winConditions} bits, in choice order — the order is the wire format. */
    public enum WinCondition {
        /** A side is wiped out. Always on: see {@link RuleCatalog#lockedBits(String)}. */
        KILL,
        OUT_OF_BOUNDS,
        SURRENDER,
        TIMEOUT
    }

    /** {@code g.chatScope} values, in choice order. */
    public enum ChatScope {
        /** Everyone on the server reads it. */
        SERVER,
        /** Only the sender's own side reads it. In a 1v1 that is the sender alone. */
        TEAM,
        /** Nobody reads it, including the sender's side. */
        MUTED
    }

    /** {@code a.timeoutVerdict} choices, in choice order. */
    public enum TimeoutVerdict {
        HIGHEST_HEALTH,
        DRAW,
        DOUBLE_LOSS
    }

    /**
     * Minutes per {@code a.roundLimit} choice, in choice order; index 0 is "no limit".
     *
     * <p>Positional because that is what the wire format is — {@code c0..c5} are the panel labels and
     * the stored value is the index. Reordering the labels without reordering this table would
     * silently change what every existing session meant.</p>
     */
    private static final int[] ROUND_LIMIT_MINUTES = {0, 1, 3, 5, 10, 15};

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

    // ------------------------------------------------------------------ A. 对局结构

    /** Whether a round may end for this reason. */
    public boolean winsBy(WinCondition condition) {
        return (rules.get("a.winConditions") & (1 << condition.ordinal())) != 0;
    }

    /** The round clock, in seconds; 0 means no limit. */
    public int roundLimitSeconds() {
        int choice = rules.get("a.roundLimit");
        int minutes = choice >= 0 && choice < ROUND_LIMIT_MINUTES.length
                ? ROUND_LIMIT_MINUTES[choice]
                : 0;
        return minutes * 60;
    }

    /** How a round is settled when its clock runs out. */
    public TimeoutVerdict timeoutVerdict() {
        return switch (rules.get("a.timeoutVerdict")) {
            case 1 -> TimeoutVerdict.DRAW;
            case 2 -> TimeoutVerdict.DOUBLE_LOSS;
            default -> TimeoutVerdict.HIGHEST_HEALTH;
        };
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

    // ------------------------------------------------------------------ D. 配装

    /** {@code d.allowConsumables}: potions, food and golden apples. */
    public boolean allowConsumables() {
        return rules.flag("d.allowConsumables");
    }

    /** {@code d.allowPickup}: whether dropped items can be collected. */
    public boolean allowPickup() {
        return rules.flag("d.allowPickup");
    }

    /** {@code d.allowCrafting}: whether the crafting result can be taken out of a grid. */
    public boolean allowCrafting() {
        return rules.flag("d.allowCrafting");
    }

    // ------------------------------------------------------------------ F. 方块

    /**
     * {@code f.allowPlace}: whether a player may put a block down inside the arena.
     *
     * <p>Placing is not the same question as breaking. {@code f.protectTerrain} guards the map, and
     * the placement itself is recorded in the plot's deviation set and rolled back at settle — so
     * this switch is about what a round plays like, not about leaving a mess behind.</p>
     */
    public boolean allowPlace() {
        return rules.flag("f.allowPlace");
    }

    /** {@code f.blockFilter} as a raw ban mask; {@code UsePolicy} knows what the bits mean. */
    public int blockBans() {
        return rules.get("f.blockFilter");
    }

    /** {@code f.allowExplosives}: whether TNT, creepers and crystals are allowed to detonate. */
    public boolean explosives() {
        return rules.flag("f.allowExplosives");
    }

    // ------------------------------------------------------------------ G. 其他

    /** {@code g.enderPearl}: whether pearls can be thrown. */
    public boolean allowEnderPearl() {
        return rules.flag("g.enderPearl");
    }

    /** {@code g.allowSurrender}: whether the surrender command is available at all. */
    public boolean allowSurrender() {
        return rules.flag("g.allowSurrender");
    }

    /**
     * {@code g.showOpponentHealth}: draw the other side's health in the match bar.
     *
     * <p>Only the switch travels to the client; the numbers do not. Both sides are standing in the
     * same arena, so the client already has every participant's health in its own world — shipping
     * twenty health values per tick to say what the entity already says would be the most expensive
     * thing in the payload.</p>
     */
    public boolean showOpponentHealth() {
        return rules.flag("g.showOpponentHealth");
    }

    /** {@code g.chatScope}: 0 server-wide, 1 team only, 2 muted. */
    public ChatScope chatScope() {
        return switch (rules.get("g.chatScope")) {
            case 1 -> ChatScope.TEAM;
            case 2 -> ChatScope.MUTED;
            default -> ChatScope.SERVER;
        };
    }

    private boolean hazard(int bit) {
        int mask = rules.get("e.hazard");
        if ((mask & HAZARD_NONE) != 0) {
            return false;
        }
        return (mask & bit) != 0;
    }
}
