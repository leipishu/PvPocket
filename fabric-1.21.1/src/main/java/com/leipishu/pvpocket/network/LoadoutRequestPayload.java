package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: "I want to edit my loadout".
 *
 * <p>Opening is a request rather than something the client decides, because the server is the only
 * side that knows whether a session is still in a stage where a loadout may still change. Once the
 * round is running the answer is no, and the client has to be told that rather than quietly editing
 * a copy that will never be applied.</p>
 */
public record LoadoutRequestPayload() implements CustomPacketPayload {

    public static final Type<LoadoutRequestPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "loadout_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LoadoutRequestPayload> CODEC =
            StreamCodec.unit(new LoadoutRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
