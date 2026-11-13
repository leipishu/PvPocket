package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: the home screen is open, send what it shows.
 *
 * <p>Carries nothing. Every field is derived from the sender's own identity on the server, so a
 * client cannot ask for another player's stats.
 *
 * <p>Sent on open and on an explicit refresh. The server throttles repeats.
 */
public record HomeRequestPayload() implements CustomPacketPayload {

    public static final Type<HomeRequestPayload> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "home_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HomeRequestPayload> CODEC =
            StreamCodec.unit(new HomeRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
