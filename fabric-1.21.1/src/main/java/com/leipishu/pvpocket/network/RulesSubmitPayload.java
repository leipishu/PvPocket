package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: the outcome of one panel session.
 *
 * <p>Like every other client message in this mod this is an <em>intent</em>, not a state change.
 * The server re-derives who is allowed to do what from the session record before acting on it: a
 * proposal from someone who is not a participant, or from the side that is currently supposed to
 * be reviewing, is dropped.</p>
 *
 * <p>{@code roster} is the team cards, {@code "uuid,uuid;uuid"}. It only means anything on the
 * first send — the one that turns a draft into an invite — and is empty on every later message,
 * because the roster is fixed once the invite is out. It travels with the rules rather than in a
 * payload of its own so that "these are the sides and these are the terms" arrives as one decision
 * the server can accept or refuse as a whole.</p>
 */
public record RulesSubmitPayload(int action, String rules, String roster) implements CustomPacketPayload {

    /** Send the roster (in draft) or the edited rules (in negotiation) and ask for consent. */
    public static final int ACTION_PROPOSE = 0;
    /** Accept the rule set exactly as it was received. */
    public static final int ACTION_ACCEPT = 1;
    /** Decline the invite. With an explicit roster this drops the player, not the match. */
    public static final int ACTION_REJECT = 2;
    /** Close the panel without deciding anything; the session stays where it is. */
    public static final int ACTION_DISMISS = 3;

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
