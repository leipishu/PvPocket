package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the scoreboard drawn over the arena.
 *
 * <p>{@code scores} holds one integer per side ({@code "2,1"}); {@code teams} separates sides
 * with {@code ;} and members with {@code ,}, each member {@code name|uuid}. {@code eliminated}
 * lists the uuids of players out of this round so their heads can be drawn grey.
 *
 * <p>{@code active=false} hides it. {@code secondsLeft} is the intermission countdown;
 * {@code clock} is the in-round seconds and {@code clockDown} picks the direction. The server
 * computes the clock, so the client never compares clocks.
 */
public record MatchHudPayload(boolean active,
                              int round, int maxRounds, int winsNeeded,
                              String scores, String teams, String eliminated,
                              boolean showHealth,
                              int secondsLeft,
                              int clock, boolean clockDown)
        implements CustomPacketPayload {

    public static final Type<MatchHudPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "match_hud"));

    /** Hand-written rather than {@code PacketCodec.tuple}: eleven fields, three of them strings. */
    public static final StreamCodec<RegistryFriendlyByteBuf, MatchHudPayload> CODEC = StreamCodec.ofMember(
            (value, buf) -> {
                buf.writeBoolean(value.active);
                buf.writeVarInt(value.round);
                buf.writeVarInt(value.maxRounds);
                buf.writeVarInt(value.winsNeeded);
                buf.writeUtf(value.scores);
                buf.writeUtf(value.teams);
                buf.writeUtf(value.eliminated);
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
                    buf.readUtf(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean()));

    /** The "hide it" packet. One instance is enough; it is immutable. */
    public static MatchHudPayload hidden() {
        return new MatchHudPayload(false, 0, 0, 0, "", "", "", false, 0, -1, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
