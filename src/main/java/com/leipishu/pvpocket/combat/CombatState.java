package com.leipishu.pvpocket.combat;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative per-player combat state.
 *
 * <p>This is the single question the combat layer asks: which rule set applies to this player right
 * now. Nothing here knows about sessions, rounds or arenas; the session layer writes into it when a
 * round starts and clears it when the round ends.
 *
 * <p>Reads happen from the render thread and the server thread, so the maps are concurrent.
 */
public final class CombatState {

    private static final Map<UUID, CombatMode> MODES = new ConcurrentHashMap<>();
    private static final Set<UUID> BLOCKING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> USE_GATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> USE_BANS = new ConcurrentHashMap<>();

    /**
     * Client-only: does the round this client is watching allow explosions?
     *
     * <p>Not per player, because an explosion has no owner. The client only renders its own
     * surroundings, so one flag covers it. The server answers from the plot the explosion happened
     * in instead.
     */
    private static volatile boolean clientExplosivesAllowed = true;

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

    /**
     * The item-use gate and the block-filter ban mask for this round.
     *
     * <p>Stored rather than derived because the client asks the same question and cannot see a
     * {@code DuelSession}. {@link #clear} drops both, so "not in a round" and "nothing restricted"
     * are the same state and every lookup falls back to {@link UsePolicy#ALL}.
     */
    public static void setUseGate(UUID player, int gate, int bans) {
        if (gate == UsePolicy.ALL) {
            USE_GATES.remove(player);
        } else {
            USE_GATES.put(player, gate);
        }
        if (bans == 0) {
            USE_BANS.remove(player);
        } else {
            USE_BANS.put(player, bans);
        }
    }

    public static int useGate(UUID player) {
        return USE_GATES.getOrDefault(player, UsePolicy.ALL);
    }

    public static int useBans(UUID player) {
        return USE_BANS.getOrDefault(player, 0);
    }

    public static void setClientExplosivesAllowed(boolean allowed) {
        clientExplosivesAllowed = allowed;
    }

    public static boolean clientExplosivesAllowed() {
        return clientExplosivesAllowed;
    }

    /** Drops every trace of a player. Called on settle, disconnect and shutdown. */
    public static void clear(UUID player) {
        MODES.remove(player);
        BLOCKING.remove(player);
        USE_GATES.remove(player);
        USE_BANS.remove(player);
    }

    public static void clearAll() {
        MODES.clear();
        BLOCKING.clear();
        USE_GATES.clear();
        USE_BANS.clear();
    }
}
