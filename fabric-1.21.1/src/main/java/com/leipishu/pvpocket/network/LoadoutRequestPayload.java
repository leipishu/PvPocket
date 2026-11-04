package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client to server: "I want to edit my loadout".
 *
 * <p>Opening is a request rather than something the client decides, because the server is the only
 * side that knows whether a session is still in a stage where a loadout may still change. Once the
 * round is running the answer is no, and the client has to be told that rather than quietly editing
 * a copy that will never be applied.</p>
 */
public record LoadoutRequestPayload() implements CustomPayload {

    public static final Id<LoadoutRequestPayload> ID =
            new Id<>(Identifier.of("pvpocket", "loadout_request"));

    public static final PacketCodec<RegistryByteBuf, LoadoutRequestPayload> CODEC =
            PacketCodec.unit(new LoadoutRequestPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
