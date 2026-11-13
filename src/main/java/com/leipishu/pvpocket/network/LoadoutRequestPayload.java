package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: request to open the loadout editor.
 *
 * <p>Opening is a request because only the server knows whether the session is still in a stage
 * where a loadout may change. Once the round is running the answer is no, and the client is told
 * that instead of editing a copy that will never be applied.
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
