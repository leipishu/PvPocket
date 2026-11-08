package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

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
public record HomeRequestPayload() implements CustomPayload {

    public static final Id<HomeRequestPayload> ID = new Id<>(Identifier.of("pvpocket", "home_request"));

    public static final PacketCodec<RegistryByteBuf, HomeRequestPayload> CODEC =
            PacketCodec.unit(new HomeRequestPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
