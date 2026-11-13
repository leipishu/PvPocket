package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: every duel currently on this server, behind {@code /pvpocket list}.
 *
 * <p>One flat string, one row per line, with {@code players} as {@code name:uuid,name:uuid}:
 *
 * <pre>
 *   sessionId | mapLabel | stageLabel | round | maxRounds | leftScore:rightScore | flags |
 *   spectators | players
 * </pre>
 *
 * <p>{@code flags}: bit 0 spectating ({@code b.spectator}), bit 1 participant, bit 2 watching.
 */
public record MatchListPayload(String entries) implements CustomPacketPayload {

    public static final int FLAG_SPECTATABLE = 1;
    public static final int FLAG_PARTICIPANT = 1 << 1;
    public static final int FLAG_WATCHING = 1 << 2;

    public static final Type<MatchListPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "match_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MatchListPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MatchListPayload::entries,
            MatchListPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
