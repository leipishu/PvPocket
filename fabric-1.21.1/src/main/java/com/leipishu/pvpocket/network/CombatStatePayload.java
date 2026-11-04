package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * Server -> client mirror of the local player's authoritative combat state.
 *
 * <p>Two things go wrong without it, both visible:</p>
 * <ul>
 *   <li>Blocking scales movement input on the server only, so the client predicts full speed and
 *       the player gets yanked back every tick.</li>
 *   <li>The attack cooldown hook is client-visible through the HUD attack indicator, so a legacy
 *       player would still see a cooldown bar that does not actually gate anything.</li>
 * </ul>
 *
 * <p>The client never derives these values itself — it only mirrors what the server decided. The
 * server remains the single source of truth for "which rule set applies right now".</p>
 */
public record CombatStatePayload(boolean legacy, boolean blocking) implements CustomPayload {

    public static final CustomPayload.Id<CombatStatePayload> ID =
            new CustomPayload.Id<>(PvPocket.id("combat_state"));

    public static final PacketCodec<RegistryByteBuf, CombatStatePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOL, CombatStatePayload::legacy,
            PacketCodecs.BOOL, CombatStatePayload::blocking,
            CombatStatePayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
