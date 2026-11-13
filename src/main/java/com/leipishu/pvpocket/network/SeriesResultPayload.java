package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the data behind the series result screen and its poster, per participant.
 *
 * <p>{@code mySide} decides which score row is the reader's. {@code winnerSide} is {@code -1} when
 * the series ended without a winner, and a round that timed out with nobody ahead carries -1.
 *
 * <pre>
 *   head    sessionId|map|mode|mySide|winnerSide|endedAt|duration|reason
 *   rounds  one line per settled round: winnerSide,reason,milliseconds
 *   sides   one line per side:          side,score,name,name,...
 * </pre>
 */
public record SeriesResultPayload(String head, String rounds, String sides)
        implements CustomPacketPayload {

    public static final Type<SeriesResultPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "series_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SeriesResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SeriesResultPayload::head,
            ByteBufCodecs.STRING_UTF8, SeriesResultPayload::rounds,
            ByteBufCodecs.STRING_UTF8, SeriesResultPayload::sides,
            SeriesResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
