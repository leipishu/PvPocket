package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;

import java.util.List;

/**
 * The map list the rule panel cycles through.
 *
 * <p>Index order is part of the wire format: {@code e.template} is stored as an enum value, so
 * reordering this list changes the meaning of every saved rule set. Append, never insert.</p>
 *
 * <p>The four maps were converted from Litematica projections with
 * {@code scripts/world2struct.py}. Their sizes are the template bounding boxes, which for a
 * colosseum includes the stands — the plot is the whole build, and the boundary curtain is drawn at
 * its outer edge.</p>
 */
public final class ArenaMaps {

    public static final ArenaMap GLADIATOR = new ArenaMap("arena_gladiator", "斗兽场",
            PvPocket.id("arena_gladiator"), 139, 32, 139);
    public static final ArenaMap MUD = new ArenaMap("arena_mud", "泥砖场",
            PvPocket.id("arena_mud"), 57, 25, 42);
    public static final ArenaMap QUARTZ = new ArenaMap("arena_quartz", "石英圆场",
            PvPocket.id("arena_quartz"), 76, 49, 83);
    public static final ArenaMap CASTLE = new ArenaMap("arena_castle", "城堡",
            PvPocket.id("arena_castle"), 45, 18, 49);

    public static final List<ArenaMap> ALL =
            List.of(ArenaMap.FLAT, GLADIATOR, MUD, QUARTZ, CASTLE);

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
