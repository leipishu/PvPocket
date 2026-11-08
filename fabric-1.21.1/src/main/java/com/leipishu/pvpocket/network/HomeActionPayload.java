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
 * call the server already exposes ({@code openRoster} and its preset variants), so the payload's
 * only job is to name which one — and a numeric action keeps an older server from having to know
 * about a newer button. Unknown actions are logged and ignored.</p>
 *
 * <p>{@code arg} is the encoded preset for the two preset actions and empty otherwise. It is
 * re-validated on arrival exactly like a hand-submitted rule set: the client is allowed to express
 * intent, never to declare what the intent decodes to.</p>
 */
public record HomeActionPayload(int action, String arg) implements CustomPayload {

    /** Open a fresh duel draft (or re-open the player's existing one). */
    public static final int ACTION_OPEN_DUEL = 0;
    /** Open a duel draft with a saved rule set applied. */
    public static final int ACTION_DUEL_WITH_RULES = 1;
    /** Open a duel draft with the caller's own loadout taken from a saved preset. */
    public static final int ACTION_DUEL_WITH_LOADOUT = 2;

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

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
