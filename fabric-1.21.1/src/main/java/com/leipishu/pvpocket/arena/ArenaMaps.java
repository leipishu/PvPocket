package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;

import java.util.List;

/**
 * The map list the rule panel cycles through.
 *
 * <p>Index order is part of the wire format: {@code e.template} is stored as an enum value, so
 * reordering this list changes the meaning of every saved rule set. Append, never insert — and if a
 * map has to be withdrawn, leave a hole rather than shifting the ones after it.</p>
 *
 * <p>Every map here is converted from a world save with {@code scripts/world2struct.py}, and every
 * one is third-party artwork under <b>CC BY-SA 4.0</b>. See {@code THIRD-PARTY-NOTICES.md} at the
 * repository root for the attribution and the licence terms that redistribution carries. Do not add
 * a map here whose licence has not been checked: the arena files ship inside the jar, so an
 * unlicensed map is redistribution, not fair use.</p>
 *
 * <p>Sizes are the template bounding boxes as produced by the converter, not hand-written guesses.
 * {@link ArenaMap#paste} re-checks them against the loaded template and logs a warning on a
 * mismatch, but a mismatch here also means the rule panel has been laying out a plot that is the
 * wrong size.</p>
 */
public final class ArenaMaps {

    /**
     * Indoor quartz arena. 38k blocks, enclosed on all four sides, so the boundary curtain sits on
     * real walls rather than in mid-air.
     */
    public static final ArenaMap FUN_HOUSE = new ArenaMap("arena_fun_house", "游乐屋",
            PvPocket.id("arena_fun_house"), 82, 28, 57);

    /**
     * Carved tuff cavern, the densest of the three (114k blocks in a 47x95 footprint). A single
     * winding hall rather than a bowl, so fights happen along it — and the source map spawns its
     * teams at {@code x=-30}, {@code z=-162..-102}, i.e. along Z.
     */
    public static final ArenaMap TRIAL_CHAMBERS = new ArenaMap("arena_trial_chambers", "试炼密室",
            PvPocket.id("arena_trial_chambers"), 47, 29, 95, true);

    /**
     * Two raised bases joined by a bridge over a void. The only map here whose source XML defines
     * its spawns as {@code x=0, z=-82..10}, so the two sides face each other along Z.
     */
    public static final ArenaMap THISTLE = new ArenaMap("arena_thistle", "蓟桥",
            PvPocket.id("arena_thistle"), 102, 44, 99, true);

    public static final List<ArenaMap> ALL =
            List.of(ArenaMap.FLAT, FUN_HOUSE, TRIAL_CHAMBERS, THISTLE);

    private ArenaMaps() {
    }

    /** Index-safe lookup: an out-of-range value (older or newer client) falls back to flat. */
    public static ArenaMap byIndex(int index) {
        if (index < 0 || index >= ALL.size()) {
            return ArenaMap.FLAT;
        }
        return ALL.get(index);
    }

    public static String[] labels() {
        String[] out = new String[ALL.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALL.get(i).label;
        }
        return out;
    }
}
