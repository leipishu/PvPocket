package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: open the ballot.
 *
 * <p>{@code items} is {@code VoteItem#encodeAll}: one line per item, values only. Labels and diffs
 * are rendered by the client from its own catalog, because the server has no language.
 *
 * <p>{@code myVotes} restores this player's selections, {@code itemId=option;itemId=option}.
 * {@code names} maps uuid to name so a roster item can be drawn as names. {@code votedPlayers}
 * counts players who have voted on every item.
 */
public record VoteOpenPayload(String sessionId, Body body) implements CustomPacketPayload {

    /** The payload body, nested because {@code StreamCodec.composite} stops at six components. */
    public record Body(String items, String myVotes, String names, int deadlineMsLeft,
                       int votedPlayers, int totalPlayers) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Body> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Body::items,
                ByteBufCodecs.STRING_UTF8, Body::myVotes,
                ByteBufCodecs.STRING_UTF8, Body::names,
                ByteBufCodecs.VAR_INT, Body::deadlineMsLeft,
                ByteBufCodecs.VAR_INT, Body::votedPlayers,
                ByteBufCodecs.VAR_INT, Body::totalPlayers,
                Body::new);
    }

    public static final Type<VoteOpenPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "vote_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VoteOpenPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, VoteOpenPayload::sessionId,
                    Body.CODEC, VoteOpenPayload::body,
                    VoteOpenPayload::new);

    public String items() {
        return body.items();
    }

    public String myVotes() {
        return body.myVotes();
    }

    public String names() {
        return body.names();
    }

    public int deadlineMsLeft() {
        return body.deadlineMsLeft();
    }

    public int votedPlayers() {
        return body.votedPlayers();
    }

    public int totalPlayers() {
        return body.totalPlayers();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
