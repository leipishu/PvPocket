package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: this negotiation is over, close the panel.
 *
 * <p>A panel can otherwise outlive its session: one side accepts while the other still has the
 * panel open, and the round starts with a modal dialog in front of a player in the arena. The same
 * applies to a rejection, a dismissal, or a failed start.
 *
 * <p>The client only closes a panel whose session id matches, so a late packet cannot shut a newer
 * negotiation's panel.
 */
public record RulesClosePayload(String sessionId) implements CustomPacketPayload {

    public static final Type<RulesClosePayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "rules_close"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RulesClosePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RulesClosePayload::sessionId,
            RulesClosePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
