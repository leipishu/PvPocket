package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the scoreboard drawn over the arena.
 *
 * <p>Sent on round boundaries and once a second while a countdown runs. Sides travel as two flat
 * strings: {@code scores} holds one integer per side ({@code "2,1"}), and {@code teams} separates
 * sides with {@code ;} and members with {@code ,}, each member {@code name|uuid}.
 *
 * <p>{@code active=false} hides it. {@code secondsLeft} is the intermission countdown, {@code 0}
 * during a live round. {@code clock} is the in-round seconds, counted down under a round limit and
 * up without one; {@code clockDown} picks the direction, {@code -1} hides it. The server computes
 * it, so the client never compares clocks.
 */
public record MatchHudPayload(boolean active,
                              int round, int maxRounds, int winsNeeded,
                              String scores, String teams,
                              boolean showHealth,
                              int secondsLeft,
                              int clock, boolean clockDown)
        implements CustomPacketPayload {

    public static final Type<MatchHudPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "match_hud"));

    /** Hand-written rather than {@code PacketCodec.tuple}: ten fields, two of them strings. */
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
                buf.writeVarInt(value.clock);
                buf.writeBoolean(value.clockDown);
            },
            buf -> new MatchHudPayload(
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean()));

    /** The "hide it" packet. One instance is enough; it is immutable. */
    public static MatchHudPayload hidden() {
        return new MatchHudPayload(false, 0, 0, 0, "", "", false, 0, -1, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
