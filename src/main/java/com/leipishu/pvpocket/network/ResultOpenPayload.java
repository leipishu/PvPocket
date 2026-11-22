package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: publish the final shape of the match before it starts.
 *
 * <p>Everything here is the outcome, not an invitation to change anything: {@code items} is the
 * frozen ballot, {@code tallies} says how each item was decided, and {@code roster} is the winning
 * arrangement. The client renders them read-only and counts down.
 *
 * <p>{@code tallies} is one line per item, {@code id \t winner \t counts}, where counts are
 * comma-separated vote counts per option. An item with no line was not voted on at all.
 * {@code names} maps uuid to name so the arrangement can be drawn as names.
 */
public record ResultOpenPayload(String sessionId, String items, String tallies, String roster,
                                String names, int deadlineMsLeft) implements CustomPacketPayload {

    public static final Type<ResultOpenPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "result_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResultOpenPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ResultOpenPayload::sessionId,
                    ByteBufCodecs.STRING_UTF8, ResultOpenPayload::items,
                    ByteBufCodecs.STRING_UTF8, ResultOpenPayload::tallies,
                    ByteBufCodecs.STRING_UTF8, ResultOpenPayload::roster,
                    ByteBufCodecs.STRING_UTF8, ResultOpenPayload::names,
                    ByteBufCodecs.VAR_INT, ResultOpenPayload::deadlineMsLeft,
                    ResultOpenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
