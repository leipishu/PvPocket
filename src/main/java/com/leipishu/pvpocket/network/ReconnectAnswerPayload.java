package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: the answer to {@link ReconnectOfferPayload}.
 *
 * <p>An intent, like every other client packet: the server re-derives the session from its own
 * record and drops an answer for a session that is not this player's. {@code sessionId} rides along
 * so a stale screen left open across a series cannot answer the next one.
 */
public record ReconnectAnswerPayload(String sessionId, boolean accept)
        implements CustomPacketPayload {

    public static final Type<ReconnectAnswerPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "reconnect_answer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReconnectAnswerPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ReconnectAnswerPayload::sessionId,
                    ByteBufCodecs.BOOL, ReconnectAnswerPayload::accept,
                    ReconnectAnswerPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
