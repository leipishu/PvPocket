package com.leipishu.pvpocket.combat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative per-player combat state.
 *
 * <p>This is the single question the combat layer is allowed to ask: "which rule set applies to
 * this player right now". Nothing here knows about sessions, rounds or arenas — the session layer
 * writes into it when a round starts and clears it when the round ends.</p>
 *
 * <p>Reads happen from the render thread (client) and the server thread, so the maps are
 * concurrent and hold no locks on the read path.</p>
 */
public final class CombatState {

    private static final Map<UUID, CombatMode> MODES = new ConcurrentHashMap<>();
    private static final Set<UUID> BLOCKING = ConcurrentHashMap.newKeySet();

    private CombatState() {
    }

    public static void setMode(UUID player, CombatMode mode) {
        if (mode == CombatMode.MODERN) {
            MODES.remove(player);
        } else {
            MODES.put(player, mode);
        }
        if (mode == CombatMode.MODERN) {
            BLOCKING.remove(player);
        }
    }

    public static CombatMode modeOf(UUID player) {
        return MODES.getOrDefault(player, CombatMode.MODERN);
    }

    public static boolean isLegacy(UUID player) {
        return MODES.get(player) == CombatMode.LEGACY_1_8;
    }

    public static void setBlocking(UUID player, boolean blocking) {
        if (blocking) {
            BLOCKING.add(player);
        } else {
            BLOCKING.remove(player);
        }
    }

    public static boolean isBlocking(UUID player) {
        return BLOCKING.contains(player);
    }

    /** Drops every trace of a player. Called on settle, disconnect and shutdown. */
    public static void clear(UUID player) {
        MODES.remove(player);
        BLOCKING.remove(player);
    }

    public static void clearAll() {
        MODES.clear();
        BLOCKING.clear();
    }
}
