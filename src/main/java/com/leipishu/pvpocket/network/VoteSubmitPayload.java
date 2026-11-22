package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: one vote, or a change of mind.
 *
 * <p>An intent, not a state change: the server re-checks that the session is voting, that the item
 * is on the frozen ballot and that the player is still in the match before recording anything.
 *
 * <p>{@code option} indexes the item's option list, where 0 is "keep what main has". Sending the
 * same item twice replaces the earlier vote.
 */
public record VoteSubmitPayload(String sessionId, String itemId, int option)
        implements CustomPacketPayload {

    public static final Type<VoteSubmitPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "vote_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VoteSubmitPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, VoteSubmitPayload::sessionId,
                    ByteBufCodecs.STRING_UTF8, VoteSubmitPayload::itemId,
                    ByteBufCodecs.VAR_INT, VoteSubmitPayload::option,
                    VoteSubmitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
