package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: everything the home screen draws that the client cannot know by itself.
 *
 * <pre>
 *   stats    matches,wins,rounds,roundWins,damage,kills,deaths,streak,bestStreak,lastPlayed
 *   history  id|t|map|mode|teams|myScore|theirScore|won|reason|duration|rounds|opponents|teamMates
 *   arena    guard,stamped,slots,leased,building  then one line per map: id,copies,leased
 * </pre>
 *
 * <p>{@code flags} bits: 0 participant, 1 watching, 2 draft, 3 stats, 4 operator. {@code arena}
 * is empty without bit 4, and {@code matches} uses the {@link MatchListPayload} row format.
 */
public record HomeStatePayload(String stats, String history, String matches, int flags, String arena)
        implements CustomPacketPayload {

    public static final int FLAG_IN_DUEL = 1;
    public static final int FLAG_SPECTATING = 1 << 1;
    public static final int FLAG_DRAFT = 1 << 2;
    public static final int FLAG_HAS_STATS = 1 << 3;
    /** The reader may reset the dimension, add copies and see the plot layout. */
    public static final int FLAG_OPERATOR = 1 << 4;

    public static final Type<HomeStatePayload> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "home_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HomeStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, HomeStatePayload::stats,
            ByteBufCodecs.STRING_UTF8, HomeStatePayload::history,
            ByteBufCodecs.STRING_UTF8, HomeStatePayload::matches,
            ByteBufCodecs.VAR_INT, HomeStatePayload::flags,
            ByteBufCodecs.STRING_UTF8, HomeStatePayload::arena,
            HomeStatePayload::new);

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
