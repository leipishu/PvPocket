package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client mirror of the local player's authoritative combat state.
 *
 * <p>The client never derives these values; it mirrors what the server decided, so movement
 * scaling, the attack indicator, block placement and explosions agree with the server.
 *
 * <p>{@code useGate} is the mask {@link com.leipishu.pvpocket.combat.UsePolicy} takes.
 */
public record CombatStatePayload(boolean legacy, boolean blocking, int useGate)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CombatStatePayload> ID =
            new CustomPacketPayload.Type<>(PvPocket.id("combat_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CombatStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, CombatStatePayload::legacy,
            ByteBufCodecs.BOOL, CombatStatePayload::blocking,
            ByteBufCodecs.VAR_INT, CombatStatePayload::useGate,
            CombatStatePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
