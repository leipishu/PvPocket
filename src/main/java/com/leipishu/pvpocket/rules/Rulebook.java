package com.leipishu.pvpocket.rules;

/**
 * The rule set read by name, with the semantics of each rule in one place.
 *
 * <p>{@link DuelRules} is the wire format: a map of id to integer. A question such as "does a fall
 * hurt" is answered here instead, so callers use no ids and no bit arithmetic.
 *
 * <p>Not cached. Instances are cheap wrappers over a session's decoded rules, which the session
 * caches in {@code DuelSession#rulebook()}.
 */
public final class Rulebook {

    /** Bits of {@code a.winConditions}, in choice order, which is also the wire format. */
    public enum WinCondition {
        /** A side is wiped out. Always on: see {@link RuleCatalog#lockedBits(String)}. */
        KILL,
        OUT_OF_BOUNDS,
        SURRENDER,
        TIMEOUT
    }

    /** Values of {@code g.chatScope}, in choice order. */
    public enum ChatScope {
        /** Everyone on the server reads it. */
        SERVER,
        /** Only the sender's own side reads it. In a 1v1 that is the sender alone. */
        TEAM,
        /** Nobody reads it, including the sender's side. */
        MUTED
    }

    /** Choices of {@code a.timeoutVerdict}, in choice order. */
    public enum TimeoutVerdict {
        HIGHEST_HEALTH,
        DRAW,
        DOUBLE_LOSS
    }

    /**
     * Minutes per {@code a.roundLimit} choice, in choice order; index 0 is "no limit".
     *
     * <p>The stored value is the choice index, so reordering the labels without reordering this
     * table changes what every stored session means.
     */
    private static final int[] ROUND_LIMIT_MINUTES = {0, 1, 3, 5, 10, 15};

    /** Bit of {@code e.hazard} meaning "no hazards at all". Wins over the bits below. */
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

    // ------------------------------------------------------------------ A. match structure

    /** Whether a round may end for this reason. */
    public boolean winsBy(WinCondition condition) {
        return (rules.get("a.winConditions") & (1 << condition.ordinal())) != 0;
    }

    /**
     * Whether wiping the other side out ends the round.
     *
     * <p>Reads the kill bit of {@code a.winConditions}, the only condition that is locked on. The
     * round-end path calls this, so the lock guards a branch that is read.
     */
    public boolean wipeEndsRound() {
        return winsBy(WinCondition.KILL);
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

    // ------------------------------------------------------------------ B. participants

    /** Whether a side can damage itself ({@code b.friendlyFire}). */
    public boolean friendlyFire() {
        return rules.flag("b.friendlyFire");
    }

    // ------------------------------------------------------------------ C. combat

    /** Hunger-driven health regeneration ({@code c.naturalRegen}). */
    public boolean naturalRegen() {
        return rules.flag("c.naturalRegen");
    }

    /** Hunger setting ({@code c.hunger}): 0 is normal, 1 is off. */
    public boolean hunger() {
        return rules.get("c.hunger") == 0;
    }

    /**
     * Fall damage, which {@code e.hazard} owns outright.
     *
     * <p>Equivalent to the fall bit of {@code e.hazard}. A second toggle would leave one of the two
     * switches without effect whenever the other was cleared.
     */
    public boolean fallDamage() {
        return hazard(HAZARD_FALL);
    }

    /** Lava bit of {@code e.hazard}. */
    public boolean lavaHazard() {
        return hazard(HAZARD_LAVA);
    }

    /** Void bit of {@code e.hazard}. */
    public boolean voidHazard() {
        return hazard(HAZARD_VOID);
    }

    /**
     * {@code c.knockbackResistance} as the attribute wants it, 0..1.
     *
     * <p>The rule is stored as a percentage because that is what the panel shows, while the
     * attribute is a fraction. Converting here keeps the divisor out of every caller.
     */
    public double knockbackResistance() {
        return rules.get("c.knockbackResistance") / 100.0D;
    }

    // ------------------------------------------------------------------ D. loadout

    /** Whether potions, food and golden apples may be used ({@code d.allowConsumables}). */
    public boolean allowConsumables() {
        return rules.flag("d.allowConsumables");
    }

    /** Whether dropped items can be collected ({@code d.allowPickup}). */
    public boolean allowPickup() {
        return rules.flag("d.allowPickup");
    }

    /** Whether a crafting result can leave the grid ({@code d.allowCrafting}). */
    public boolean allowCrafting() {
        return rules.flag("d.allowCrafting");
    }

    /** Whether a loadout may be re-proposed mid-series ({@code d.allowSwapBetweenRounds}). */
    public boolean allowSwapBetweenRounds() {
        return rules.flag("d.allowSwapBetweenRounds");
    }

    // ------------------------------------------------------------------ F. blocks

    /**
     * {@code f.allowPlace}: whether a player may put a block down inside the arena.
     *
     * <p>Placing and breaking are separate questions. Placement is recorded in the plot's deviation
     * set and rolled back at settle, so this switch only changes what a round plays like.
     */
    public boolean allowPlace() {
        return rules.flag("f.allowPlace");
    }

    /** Raw ban mask of {@code f.blockFilter}; {@code UsePolicy} knows what the bits mean. */
    public int blockBans() {
        return rules.get("f.blockFilter");
    }

    /** Whether TNT, creepers and crystals may detonate ({@code f.allowExplosives}). */
    public boolean explosives() {
        return rules.flag("f.allowExplosives");
    }

    // ------------------------------------------------------------------ G. other

    /** Whether ender pearls can be thrown ({@code g.enderPearl}). */
    public boolean allowEnderPearl() {
        return rules.flag("g.enderPearl");
    }

    /** Whether the surrender command is available at all ({@code g.allowSurrender}). */
    public boolean allowSurrender() {
        return rules.flag("g.allowSurrender");
    }

    /**
     * {@code g.showOpponentHealth}: draw the other side's health in the match bar.
     *
     * <p>Only the switch travels to the client. Both sides share the arena, so the client already
     * holds every participant's health in its own world.
     */
    public boolean showOpponentHealth() {
        return rules.flag("g.showOpponentHealth");
    }

    /** Chat scope ({@code g.chatScope}): 0 server-wide, 1 team only, 2 muted. */
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
