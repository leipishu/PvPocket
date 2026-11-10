package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client to server: one action taken on the home screen.
 *
 * <p>An action number plus one string, rather than three payload types. Every action here ends in a
 * call the server already exposes — {@code openRoster} and its preset variants, {@code resetArena},
 * {@code setGuardEnabled} — so the payload's only job is to name which one, and a numeric action
 * keeps an older server from having to know about a newer button. Unknown actions are logged and
 * ignored.</p>
 *
 * <p>{@code arg} is the encoded preset for the two preset actions, a map id for the copy and remove
 * actions, and {@code "1"} / {@code "0"} for the guard. It is re-validated on arrival exactly like a
 * hand-submitted rule set: the client is allowed to express intent, never to declare what the intent
 * decodes to. The four operator actions are checked against the caller's permission level on the
 * server — the panel not offering them is a convenience, not the lock.</p>
 */
public record HomeActionPayload(int action, String arg) implements CustomPayload {

    /** Open a fresh duel draft (or re-open the player's existing one). */
    public static final int ACTION_OPEN_DUEL = 0;
    /** Open a duel draft with a saved rule set applied. */
    public static final int ACTION_DUEL_WITH_RULES = 1;
    /** Open a duel draft with the caller's own loadout taken from a saved preset. */
    public static final int ACTION_DUEL_WITH_LOADOUT = 2;
    /** Operator: give the map named in {@code arg} one more plot. */
    public static final int ACTION_ARENA_COPY = 3;
    /** Operator: end every session and rebuild the dimension. */
    public static final int ACTION_ARENA_RESET = 4;
    /** Operator: turn the stray-player guard on or off for the caller ({@code arg} is 1 or 0). */
    public static final int ACTION_GUARD = 5;
    /** Operator: take one plot away from the map named in {@code arg}. */
    public static final int ACTION_ARENA_REMOVE = 6;

    public static final Id<HomeActionPayload> ID = new Id<>(Identifier.of("pvpocket", "home_action"));

    public static final PacketCodec<RegistryByteBuf, HomeActionPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, HomeActionPayload::action,
            PacketCodecs.STRING, HomeActionPayload::arg,
            HomeActionPayload::new);

    public static HomeActionPayload duel() {
        return new HomeActionPayload(ACTION_OPEN_DUEL, "");
    }

    public static HomeActionPayload withRules(String encoded) {
        return new HomeActionPayload(ACTION_DUEL_WITH_RULES, encoded == null ? "" : encoded);
    }

    public static HomeActionPayload withLoadout(String encoded) {
        return new HomeActionPayload(ACTION_DUEL_WITH_LOADOUT, encoded == null ? "" : encoded);
    }

    public static HomeActionPayload arenaCopy(String mapId) {
        return new HomeActionPayload(ACTION_ARENA_COPY, mapId == null ? "" : mapId);
    }

    public static HomeActionPayload arenaRemove(String mapId) {
        return new HomeActionPayload(ACTION_ARENA_REMOVE, mapId == null ? "" : mapId);
    }

    public static HomeActionPayload arenaReset() {
        return new HomeActionPayload(ACTION_ARENA_RESET, "");
    }

    public static HomeActionPayload guard(boolean enabled) {
        return new HomeActionPayload(ACTION_GUARD, enabled ? "1" : "0");
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
