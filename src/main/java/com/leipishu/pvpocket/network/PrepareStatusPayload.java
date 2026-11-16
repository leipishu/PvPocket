package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: show or dismiss the "preparing duel" screen.
 *
 * <p>Once every side has accepted, the panels close and the server waits for a dead or absent
 * participant before it can freeze and teleport. Without a screen that wait looks like the accept
 * click was lost. The client also opens the screen locally on click for immediate feedback; this
 * packet is the authoritative dismiss, including on abort.
 */
public record PrepareStatusPayload(String sessionId, boolean active)
        implements CustomPacketPayload {

    public static final Type<PrepareStatusPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "prepare_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PrepareStatusPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, PrepareStatusPayload::sessionId,
                    ByteBufCodecs.BOOL, PrepareStatusPayload::active,
                    PrepareStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
