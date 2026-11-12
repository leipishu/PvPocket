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
    private static final Map<UUID, Integer> USE_GATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> USE_BANS = new ConcurrentHashMap<>();

    /**
     * Client-only: does the round this client is watching allow explosions?
     *
     * <p>Not per player, unlike everything else here, because an explosion has no owner — a creeper
     * is nobody's. The client only ever renders its own surroundings, so one flag is the whole
     * question it can ask. The server does not read this; it answers from the plot the explosion
     * happened in, which is the only place the answer actually varies.</p>
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
     * <p>Stored rather than derived because the client has to ask the same question and cannot see
     * a {@code DuelSession}. The session layer writes both when a round starts and
     * {@link #clear} drops them, which makes "not in a round" and "nothing restricted" the same
     * state — the maps stay empty and every lookup falls back to {@link UsePolicy#ALL}.</p>
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
