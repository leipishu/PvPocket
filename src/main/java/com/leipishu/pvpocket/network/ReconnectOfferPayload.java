package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the match is still running and this player's place is still on the roster.
 *
 * <p>Opens a modal screen rather than a chat line. A returning player is already standing in the
 * arena as a spectator, so a line in chat reads as "you were put back in without being asked"; the
 * screen is the question.
 *
 * <p>{@code score} is the same {@code "1:0"} the scoreboard uses, so the screen does not have to
 * know how many sides there are.
 */
public record ReconnectOfferPayload(String sessionId, int round, int maxRounds, String score)
        implements CustomPacketPayload {

    public static final Type<ReconnectOfferPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "reconnect_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReconnectOfferPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ReconnectOfferPayload::sessionId,
                    ByteBufCodecs.VAR_INT, ReconnectOfferPayload::round,
                    ByteBufCodecs.VAR_INT, ReconnectOfferPayload::maxRounds,
                    ByteBufCodecs.STRING_UTF8, ReconnectOfferPayload::score,
                    ReconnectOfferPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
