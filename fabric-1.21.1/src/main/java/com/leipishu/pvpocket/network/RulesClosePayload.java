package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: "this negotiation is over, close the panel".
 *
 * <p>Without it a panel can outlive its session. The obvious case is one side accepting while the
 * other side still has the panel open — the round then starts with a modal dialog in front of a
 * player who is already in the arena. The same applies to a rejection, a dismissal, or a failed
 * start.</p>
 *
 * <p>The client only closes a panel whose session id matches, so a late packet can never shut a
 * newer negotiation's panel.</p>
 */
public record RulesClosePayload(String sessionId) implements CustomPayload {

    public static final Id<RulesClosePayload> ID =
            new Id<>(Identifier.of("pvpocket", "rules_close"));

    public static final PacketCodec<RegistryByteBuf, RulesClosePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, RulesClosePayload::sessionId,
            RulesClosePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
