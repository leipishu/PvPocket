package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client to server: "save this loadout onto my side of the session".
 *
 * <p>The payload carries a loadout string, never item stacks. Stacks would let a client ask for any
 * item in the game; a loadout string is re-validated against the whitelist and the caps on the server
 * before a single item is constructed, so the worst a tampered client can do is submit a loadout that
 * gets rejected.</p>
 */
public record LoadoutSubmitPayload(String sessionId, String loadout) implements CustomPayload {

    public static final Id<LoadoutSubmitPayload> ID =
            new Id<>(Identifier.of("pvpocket", "loadout_submit"));

    public static final PacketCodec<RegistryByteBuf, LoadoutSubmitPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, LoadoutSubmitPayload::sessionId,
            PacketCodecs.STRING, LoadoutSubmitPayload::loadout,
            LoadoutSubmitPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
