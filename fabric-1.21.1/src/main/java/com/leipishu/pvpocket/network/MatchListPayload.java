package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: "here is every duel currently on this server" — the data behind
 * {@code /pvpocket list}.
 *
 * <p>One flat string rather than a list codec, for the same reason {@link MatchHudPayload} uses
 * flat strings: the whole payload is display data, the client re-derives everything it needs from
 * it, and a generic collection codec would drag a record-per-row type through the wire format for
 * no benefit. A server with more than a handful of concurrent duels is not a case this panel is
 * built for, and the format degrades by truncation rather than by failing to parse.</p>
 *
 * <p>Field layout, one row per line:</p>
 * <pre>
 *   sessionId | mapLabel | stageLabel | round | maxRounds | leftScore:rightScore | flags | spectators | players
 *   players   = name:uuid,name:uuid
 * </pre>
 *
 * <p>Neither player names nor the map labels the mod ships can contain {@code |}, {@code :},
 * {@code ,} or a newline, so the format is unambiguous without escaping. A row that does not parse
 * is dropped by the client rather than crashing the panel — a malformed row is a version skew, and
 * losing one row is a better answer to that than losing the screen.</p>
 *
 * <p>{@code flags} is a bit field so the row can grow a capability without a format bump:</p>
 * <ul>
 *   <li>bit 0 — spectating is enabled for this session ({@code b.spectator})</li>
 *   <li>bit 1 — the reader is a participant of this session</li>
 *   <li>bit 2 — the reader is already watching this session</li>
 * </ul>
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
