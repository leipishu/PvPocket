package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: "draw this scoreboard over the arena".
 *
 * <p>Sent on every round start and every round end rather than every tick. A scoreboard that ticks
 * is a scoreboard that can drift out of sync with the server; a scoreboard that is pushed on change
 * can only ever show something the server actually decided.</p>
 *
 * <p>The two sides are sent as flat strings — {@code "Dev1|uuid1,Dev2|uuid2"} — rather than as a
 * list codec. Player names cannot contain {@code ,} or {@code |} and uuids cannot contain either, so
 * the format is unambiguous, and it keeps the payload to six scalars instead of dragging a generic
 * collection codec through {@link PacketCodec#tuple}. The client parses it into rows; a malformed
 * row is dropped rather than crashing the HUD.</p>
 *
 * <p>{@code active=false} means "hide". It carries no other meaning, and the client treats every
 * other field as garbage in that case — the server sends it on the final round, on surrender, on
 * void, and whenever a player leaves the arena.</p>
 */
public record MatchHudPayload(boolean active,
                              int round, int bestOf, int winsNeeded,
                              int leftScore, int rightScore,
                              String leftPlayers, String rightPlayers)
        implements CustomPayload {

    public static final Id<MatchHudPayload> ID =
            new Id<>(Identifier.of("pvpocket", "match_hud"));

    /**
     * Hand-written rather than {@code PacketCodec.tuple}.
     *
     * <p>{@code tuple} only goes up to six fields and this payload has eight. Splitting it into two
     * tuples would mean an intermediate record whose only job is to satisfy an arity limit; writing
     * the eight lines out is shorter and says exactly what crosses the wire, in order.</p>
     */
    public static final PacketCodec<RegistryByteBuf, MatchHudPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.active);
                buf.writeVarInt(value.round);
                buf.writeVarInt(value.bestOf);
                buf.writeVarInt(value.winsNeeded);
                buf.writeVarInt(value.leftScore);
                buf.writeVarInt(value.rightScore);
                buf.writeString(value.leftPlayers);
                buf.writeString(value.rightPlayers);
            },
            buf -> new MatchHudPayload(
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readString(),
                    buf.readString()));

    /** The "hide it" packet. One instance is enough; it is immutable. */
    public static MatchHudPayload hidden() {
        return new MatchHudPayload(false, 0, 0, 0, 0, 0, "", "");
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
