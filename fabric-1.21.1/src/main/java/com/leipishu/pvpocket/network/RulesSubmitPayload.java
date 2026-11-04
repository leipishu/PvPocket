package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client to server: the outcome of one panel session.
 *
 * <p>Like every other client message in this mod this is an <em>intent</em>, not a state change.
 * The server re-derives who is allowed to do what from the session record before acting on it: a
 * proposal from someone who is not a participant, or from the side that is currently supposed to
 * be reviewing, is dropped.</p>
 */
public record RulesSubmitPayload(int action, String rules) implements CustomPayload {

    /** Send the edited rules to the other side and ask for consent. */
    public static final int ACTION_PROPOSE = 0;
    /** Accept the rule set exactly as it was received. */
    public static final int ACTION_ACCEPT = 1;
    /** Decline the duel. */
    public static final int ACTION_REJECT = 2;
    /** Close the panel without deciding anything; the session stays where it is. */
    public static final int ACTION_DISMISS = 3;

    public static final Id<RulesSubmitPayload> ID =
            new Id<>(Identifier.of("pvpocket", "rules_submit"));

    public static final PacketCodec<RegistryByteBuf, RulesSubmitPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, RulesSubmitPayload::action,
            PacketCodecs.STRING, RulesSubmitPayload::rules,
            RulesSubmitPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
