package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: "draw this scoreboard over the arena".
 *
 * <p>Sent on every round start and every round end rather than every tick. A scoreboard that ticks
 * is a scoreboard that can drift out of sync with the server; a scoreboard that is pushed on change
 * can only ever show something the server actually decided.</p>
 *
 * <p>Sides are sent as two flat strings rather than a list codec, because a match can have one to
 * four of them and the number is not known to the client until it arrives:</p>
 *
 * <ul>
 *   <li>{@code scores} — one integer per side, comma separated: {@code "2,1"}.</li>
 *   <li>{@code teams} — sides separated by {@code ;}, members by {@code ,}, each member
 *       {@code name|uuid}: {@code "Dev1|uuid,Dev2|uuid;Dev3|uuid"}.</li>
 * </ul>
 *
 * <p>{@code maxRounds} is the cap the series runs to, not a best-of: the win count is its own
 * field, so "3 rounds, first to 2" and "3 rounds, first to 3" are both expressible.
 *
 * <p>Player names cannot contain {@code ,}, {@code |} or {@code ;} and uuids cannot contain any of
 * them either, so the format is unambiguous without escaping, and the payload stays four scalars
 * instead of dragging a generic collection codec through {@link PacketCodec#tuple}.</p>
 *
 * <p>{@code active=false} means "hide". It carries no other meaning, and the client treats every
 * other field as garbage in that case — the server sends it on the final round, on surrender, on
 * void, and whenever a player leaves the arena.</p>
 *
 * <p>{@code secondsLeft} is the intermission countdown, or {@code 0} when a round is live. It is
 * pushed every second while it is non-zero, because a countdown is the one value on this payload
 * that changes without the server deciding anything. Sent as whole seconds rather than as a
 * deadline so the client never has to reason about clock skew between the two sides.</p>
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
