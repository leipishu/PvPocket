package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: the outcome of one rule panel session.
 *
 * <p>An intent, not a state change. The server re-derives who may do what from the session record
 * before acting: a proposal from someone who is not a participant is dropped.
 *
 * <p>{@code roster} is the team cards, {@code "uuid,uuid;uuid"}. It only means anything on the
 * first send, the one that turns a draft into an invite, and is empty afterwards.
 *
 * <p>Past the draft: {@link #ACTION_ACCEPT} means no change and starts nothing,
 * {@link #ACTION_PROPOSE} files or amends a branch, {@link #ACTION_WITHDRAW} voids the invite.
 */
public record RulesSubmitPayload(int action, String rules, String roster) implements CustomPacketPayload {

    /** Send the roster (in draft) or file/amend this player's branch. */
    public static final int ACTION_PROPOSE = 0;
    /** Change nothing: the player has reviewed main and has no alternative to file. */
    public static final int ACTION_ACCEPT = 1;
    /** Decline the invite. With an explicit roster this drops the player, not the match. */
    public static final int ACTION_REJECT = 2;
    /** Close the panel without deciding anything; the window keeps running. */
    public static final int ACTION_DISMISS = 3;
    /**
     * The proposer takes the whole invite back during the branch window.
     *
     * <p>Distinct from {@link #ACTION_REJECT}, which drops one player from a roster the others may
     * still play. Only the player who sent the invite may withdraw, and only while the window is
     * open; a ballot already under way is not theirs to cancel.
     */
    public static final int ACTION_WITHDRAW = 4;

    public static final Type<RulesSubmitPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "rules_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RulesSubmitPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RulesSubmitPayload::action,
            ByteBufCodecs.STRING_UTF8, RulesSubmitPayload::rules,
            ByteBufCodecs.STRING_UTF8, RulesSubmitPayload::roster,
            RulesSubmitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
