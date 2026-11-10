package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: "this series is over, and here is what happened" — the data behind the result
 * screen and its poster.
 *
 * <p>Sent once per participant at the end of a series, and <b>per participant rather than per
 * session</b>: {@code mySide} decides which row of the score block is "yours", which round marks are
 * wins rather than losses, and whether the headline says victory or not. One shared payload would
 * force every reader to be told which side it is anyway, so the field is here and the perspective is
 * baked in.</p>
 *
 * <p>Three flat strings rather than three nested codecs, for the same reason {@link HomeStatePayload}
 * is flat: {@code PacketCodec.tuple} stops at six components, everything here is display data the
 * client re-derives from, and a malformed line has to cost one row rather than the screen. The
 * separators are safe because none of the values can contain them — a map id, a mode name and a
 * reason code are identifiers, and a Minecraft name is {@code [A-Za-z0-9_]}.</p>
 *
 * <pre>
 *   head    sessionId|map|mode|mySide|winnerSide|endedAt|duration|reason
 *   rounds  one line per settled round: winnerSide,reason,milliseconds
 *   sides   one line per side:          side,score,name,name,...
 * </pre>
 *
 * <p>{@code winnerSide} is {@code -1} for a series that ended without one, which is two different
 * situations the reason tells apart: {@code DRAW} is a cap reached with the lead shared, an empty
 * reason with no winner is a series that was cut. A round's {@code winnerSide} is {@code -1} too, for
 * a round that timed out with nobody ahead.</p>
 *
 * <p>Nothing here is authoritative. The record of the match is {@code MatchRecord}, written on the
 * server before this is sent; this payload exists only so the client can draw a card, and dropping
 * every one of them loses no state.</p>
 */
public record SeriesResultPayload(String head, String rounds, String sides)
        implements CustomPayload {

    public static final Id<SeriesResultPayload> ID =
            new Id<>(Identifier.of("pvpocket", "series_result"));

    public static final PacketCodec<RegistryByteBuf, SeriesResultPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SeriesResultPayload::head,
            PacketCodecs.STRING, SeriesResultPayload::rounds,
            PacketCodecs.STRING, SeriesResultPayload::sides,
            SeriesResultPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
