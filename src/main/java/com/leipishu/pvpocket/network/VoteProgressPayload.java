package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the ballot's progress, pushed after every vote.
 *
 * <p>Only participation travels, never per-option tallies: showing the running counts turns the
 * vote into a bandwagon, and the tally is published in the result line anyway.
 *
 * <p>{@code myVotes} is included so a client that missed a packet can resynchronise its own
 * selections rather than showing them as blank.
 */
public record VoteProgressPayload(String sessionId, String myVotes, int votedPlayers, int totalPlayers)
        implements CustomPacketPayload {

    public static final Type<VoteProgressPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "vote_progress"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VoteProgressPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, VoteProgressPayload::sessionId,
                    ByteBufCodecs.STRING_UTF8, VoteProgressPayload::myVotes,
                    ByteBufCodecs.VAR_INT, VoteProgressPayload::votedPlayers,
                    ByteBufCodecs.VAR_INT, VoteProgressPayload::totalPlayers,
                    VoteProgressPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
