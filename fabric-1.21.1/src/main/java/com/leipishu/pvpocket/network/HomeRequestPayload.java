package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: "the home screen is open, send me what it shows".
 *
 * <p>Carries nothing. The screen is the same screen for everyone, and every field it needs is
 * derived from the sender's own identity on the server — a client that could ask for somebody else's
 * stats would be a client that could ask for somebody else's stats.</p>
 *
 * <p>Sent on open and on an explicit refresh. The server throttles repeats, because a player
 * tapping the hotkey twice must not queue two full snapshots.</p>
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
