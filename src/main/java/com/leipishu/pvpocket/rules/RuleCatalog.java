package com.leipishu.pvpocket.rules;

import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.arena.ArenaMaps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every configurable rule, in panel order.
 *
 * <p>Defaults are the recommended configuration, so an untouched proposal runs the settings the
 * design was validated against. Rule text is not stored here: labels, hints, choices and unit
 * suffixes are looked up from {@code assets/pvpocket/lang/} under keys derived from the rule id,
 * see {@link RuleDefinition}.
 *
 * <p>{@code c.modeConflict} is {@link RuleKind#FIXED}, because agreement between both sides is a
 * property of the negotiation. {@code e.template} takes its choices from {@link ArenaMaps} so the
 * panel and the paste path agree on what each index means.
 */
public final class RuleCatalog {

    private static final List<RuleDefinition> ORDERED = new ArrayList<>();
    private static final Map<String, RuleDefinition> BY_ID = new LinkedHashMap<>();

    static {
        // Rounds to play and rounds to win. The cap of 15 keeps a series under an hour.
        add(RuleDefinition.intRange("a.maxRounds", RuleGroup.A, 1, 15, 1, 3));
        add(RuleDefinition.intRange("a.winsNeeded", RuleGroup.A, 1, 15, 1, 2));
        add(RuleDefinition.enumOf("a.roundLimit", RuleGroup.A, 5, choices("a.roundLimit", 6)));
        add(RuleDefinition.enumOf("a.timeoutVerdict", RuleGroup.A, 0, choices("a.timeoutVerdict", 3)));
        add(RuleDefinition.intRange("a.countdown", RuleGroup.A, 0, 30, 1, 5));
        add(RuleDefinition.intRange("a.intermission", RuleGroup.A, 1, 10, 1, 3));
        add(RuleDefinition.multi("a.winConditions", RuleGroup.A, 0b1111, choices("a.winConditions", 4)));

        // b.teamSize counts slots per player card, so it also bounds players per side: 4 x 5 = 20.
        add(RuleDefinition.intRange("b.teamSize", RuleGroup.B, 1, 5, 1, 5));
        add(RuleDefinition.intRange("b.maxPlayers", RuleGroup.B, 2, 20, 1, 20));
        add(RuleDefinition.toggle("b.friendlyFire", RuleGroup.B, false));
        // On by default. A spectator cannot collide, change blocks or affect the boundary check.
        add(RuleDefinition.toggle("b.spectator", RuleGroup.B, true));

        // C. combat
        add(RuleDefinition.enumOf("c.mode", RuleGroup.C, 0, choices("c.mode", 2)));
        add(RuleDefinition.fixed("c.modeConflict", RuleGroup.C, "pvpocket.rule.c.modeConflict.c0"));
        add(RuleDefinition.intRange("c.maxHealth", RuleGroup.C, 1, 40, 1, 20));
        add(RuleDefinition.toggle("c.naturalRegen", RuleGroup.C, true));
        add(RuleDefinition.enumOf("c.hunger", RuleGroup.C, 0, choices("c.hunger", 2)));
        add(RuleDefinition.percent("c.knockbackResistance", RuleGroup.C, 0));

        // D. loadout
        add(RuleDefinition.toggle("d.allowSwapBetweenRounds", RuleGroup.D, false));
        add(RuleDefinition.toggle("d.allowPickup", RuleGroup.D, false));
        add(RuleDefinition.toggle("d.allowCrafting", RuleGroup.D, false));
        add(RuleDefinition.toggle("d.allowConsumables", RuleGroup.D, true));

        // E. arena
        // Upper bound is ArenaManager.MAX_SIZE_XZ: 224 = 256 centre spacing - 32. Largest map: 147.
        add(RuleDefinition.intRange("e.sizeX", RuleGroup.E, 16, ArenaManager.MAX_SIZE_XZ, 8, 48));
        add(RuleDefinition.intRange("e.sizeZ", RuleGroup.E, 16, ArenaManager.MAX_SIZE_XZ, 8, 48));
        add(RuleDefinition.intRange("e.sizeY", RuleGroup.E, 8, ArenaManager.MAX_SIZE_Y, 4, 32));
        add(RuleDefinition.enumOf("e.template", RuleGroup.E, 0, ArenaMaps.labelKeys()));
        add(RuleDefinition.enumOf("e.boundary", RuleGroup.E, 1, choices("e.boundary", 2)));
        add(RuleDefinition.enumOf("e.timeOfDay", RuleGroup.E, 1, choices("e.timeOfDay", 5)));
        add(RuleDefinition.enumOf("e.weather", RuleGroup.E, 0, choices("e.weather", 3)));
        // All three bits on by default: the arena is a void dimension and the boundary check
        // depends on falling out.
        add(RuleDefinition.multi("e.hazard", RuleGroup.E, 0b1110, choices("e.hazard", 4)));

        // F. blocks
        // On by default: the default loadout carries 32 wool and placement rolls back at settle.
        add(RuleDefinition.toggle("f.allowPlace", RuleGroup.F, true));
        add(RuleDefinition.toggle("f.protectTerrain", RuleGroup.F, true));
        add(RuleDefinition.toggle("f.allowBreakOwn", RuleGroup.F, true));
        add(RuleDefinition.multi("f.blockFilter", RuleGroup.F, 0b1111, choices("f.blockFilter", 5)));
        add(RuleDefinition.toggle("f.allowExplosives", RuleGroup.F, false));

        // G. other
        add(RuleDefinition.toggle("g.enderPearl", RuleGroup.G, false));
        add(RuleDefinition.toggle("g.allowSurrender", RuleGroup.G, true));
        add(RuleDefinition.toggle("g.showOpponentHealth", RuleGroup.G, true));
        // Server-wide by default: a 1v1 has no team, so team-only chat would be silent.
        add(RuleDefinition.enumOf("g.chatScope", RuleGroup.G, 0, choices("g.chatScope", 3)));
    }

    private RuleCatalog() {
    }

    private static String[] choices(String id, int count) {
        return RuleDefinition.choices(id, count);
    }

    /**
     * Bits of a multi-select rule that the panel will not let a caller clear.
     *
     * <p>{@code a.winConditions} keeps the kill bit on, because respawning the wiped side and
     * playing on is a different game mode rather than a switch. {@link DuelRules#set} forces the
     * bit back on and the chip widget draws it as locked. Returns 0 for rules without a lock.
     */
    public static int lockedBits(String id) {
        return "a.winConditions".equals(id) ? 1 : 0;
    }

    /**
     * Bits of a multi-select rule that exclude every other bit.
     *
     * <p>{@code e.hazard} has four chips: none, fall, lava and void. Checking none clears the rest
     * and checking anything else clears none, so the empty state has a single spelling. Returns 0
     * for rules without an exclusive bit.
     */
    public static int exclusiveBits(String id) {
        return "e.hazard".equals(id) ? 1 : 0;
    }

    private static void add(RuleDefinition def) {
        ORDERED.add(def);
        BY_ID.put(def.id, def);
    }

    public static List<RuleDefinition> all() {
        return Collections.unmodifiableList(ORDERED);
    }

    public static List<RuleDefinition> of(RuleGroup group) {
        List<RuleDefinition> out = new ArrayList<>();
        for (RuleDefinition def : ORDERED) {
            if (def.group == group) {
                out.add(def);
            }
        }
        return out;
    }

    public static RuleDefinition byId(String id) {
        return BY_ID.get(id);
    }

    public static int size() {
        return ORDERED.size();
    }
}
