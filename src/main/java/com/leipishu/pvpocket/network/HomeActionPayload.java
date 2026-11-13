package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: one action taken on the home screen.
 *
 * <p>An action number plus one string, so the payload only names which server call to make and an
 * older server ignores a newer button. {@code arg} is the encoded preset for the two preset
 * actions, a map id for the copy and remove actions, and {@code "1"} / {@code "0"} for the guard.
 *
 * <p>The server re-validates {@code arg} and checks the four operator actions against the caller's
 * permission level. Unknown actions are logged and ignored.
 */
public record HomeActionPayload(int action, String arg) implements CustomPacketPayload {

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

    public static final Type<HomeActionPayload> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "home_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HomeActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HomeActionPayload::action,
            ByteBufCodecs.STRING_UTF8, HomeActionPayload::arg,
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
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
