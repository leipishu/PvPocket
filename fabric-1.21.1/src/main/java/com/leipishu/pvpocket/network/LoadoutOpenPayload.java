package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: "open the loadout editor for this session".
 *
 * <p>Only the values cross the wire. The catalog of kinds, materials, effects, blocks and limits is
 * compiled into the jar on both sides, so the editor is the same screen for the proposer and the
 * reviewer and a version mismatch cannot produce a half-populated list.</p>
 */
public record LoadoutOpenPayload(String sessionId, String loadout, int role, String otherName)
        implements CustomPayload {

    public static final Id<LoadoutOpenPayload> ID =
            new Id<>(Identifier.of("pvpocket", "loadout_open"));

    public static final PacketCodec<RegistryByteBuf, LoadoutOpenPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, LoadoutOpenPayload::sessionId,
            PacketCodecs.STRING, LoadoutOpenPayload::loadout,
            PacketCodecs.VAR_INT, LoadoutOpenPayload::role,
            PacketCodecs.STRING, LoadoutOpenPayload::otherName,
            LoadoutOpenPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
