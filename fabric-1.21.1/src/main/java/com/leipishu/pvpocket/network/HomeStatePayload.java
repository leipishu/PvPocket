package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: everything the home screen draws that the client cannot know by itself.
 *
 * <p>One snapshot, three flat strings, same reasoning as {@link MatchListPayload}: the whole payload
 * is display data and a collection codec would drag a record-per-row type through the wire format
 * for no benefit. A row that fails to parse is dropped by the client rather than crashing a screen
 * the player cannot close.</p>
 *
 * <pre>
 *   stats    matches,wins,rounds,roundWins,damage,kills,deaths,streak,bestStreak,lastPlayed
 *   history  id|t|map|mode|teams|myScore|theirScore|won|reason|duration|rounds|opponents|teamMates
 *   matches  same format as MatchListPayload
 * </pre>
 *
 * <p>{@code flags} says what the reader can do right now, so the home screen can grey out the
 * primary action with a reason instead of sending a request the server will refuse:</p>
 * <ul>
 *   <li>bit 0 — the reader is a participant of a live session</li>
 *   <li>bit 1 — the reader is watching a session</li>
 *   <li>bit 2 — the reader has a draft waiting</li>
 *   <li>bit 3 — stats were available (false when the store has never been written)</li>
 * </ul>
 */
public record HomeStatePayload(String stats, String history, String matches, int flags)
        implements CustomPayload {

    public static final int FLAG_IN_DUEL = 1;
    public static final int FLAG_SPECTATING = 1 << 1;
    public static final int FLAG_DRAFT = 1 << 2;
    public static final int FLAG_HAS_STATS = 1 << 3;

    public static final Id<HomeStatePayload> ID = new Id<>(Identifier.of("pvpocket", "home_state"));

    public static final PacketCodec<RegistryByteBuf, HomeStatePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, HomeStatePayload::stats,
            PacketCodecs.STRING, HomeStatePayload::history,
            PacketCodecs.STRING, HomeStatePayload::matches,
            PacketCodecs.VAR_INT, HomeStatePayload::flags,
            HomeStatePayload::new);

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
