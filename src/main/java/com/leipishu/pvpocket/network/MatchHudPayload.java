package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the scoreboard drawn over the arena.
 *
 * <p>Sent on every round start and round end rather than every tick. Sides travel as two flat
 * strings: {@code scores} holds one integer per side, comma separated ({@code "2,1"}), and
 * {@code teams} separates sides with {@code ;} and members with {@code ,}, each member
 * {@code name|uuid}.
 *
 * <p>{@code maxRounds} is the cap the series runs to; {@code winsNeeded} is the win count.
 * {@code active=false} means hide. {@code secondsLeft} is the intermission countdown, or {@code 0}
 * while a round is live, pushed every second while it is non-zero.
 */
public record MatchHudPayload(boolean active,
                              int round, int maxRounds, int winsNeeded,
                              String scores, String teams,
                              boolean showHealth,
                              int secondsLeft)
        implements CustomPacketPayload {

    public static final Type<MatchHudPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "match_hud"));

    /** Hand-written rather than {@code PacketCodec.tuple}: eight fields, two of them strings. */
    public static final StreamCodec<RegistryFriendlyByteBuf, MatchHudPayload> CODEC = StreamCodec.ofMember(
            (value, buf) -> {
                buf.writeBoolean(value.active);
                buf.writeVarInt(value.round);
                buf.writeVarInt(value.maxRounds);
                buf.writeVarInt(value.winsNeeded);
                buf.writeUtf(value.scores);
                buf.writeUtf(value.teams);
                buf.writeBoolean(value.showHealth);
                buf.writeVarInt(value.secondsLeft);
            },
            buf -> new MatchHudPayload(
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readBoolean(),
                    buf.readVarInt()));

    /** The "hide it" packet. One instance is enough; it is immutable. */
    public static MatchHudPayload hidden() {
        return new MatchHudPayload(false, 0, 0, 0, "", "", false, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
