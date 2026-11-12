package com.leipishu.pvpocket.network;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client mirror of the local player's authoritative combat state.
 *
 * <p>Three things go wrong without it, all visible:</p>
 * <ul>
 *   <li>Blocking scales movement input on the server only, so the client predicts full speed and
 *       the player gets yanked back every tick.</li>
 *   <li>The attack cooldown hook is client-visible through the HUD attack indicator, so a legacy
 *       player would still see a cooldown bar that does not actually gate anything.</li>
 *   <li>A refused block placement would be applied locally and never confirmed, leaving a ghost
 *       block only the placer can see — so the client needs the same gate the server enforces.</li>
 * </ul>
 *
 * <p>The client never derives these values itself — it only mirrors what the server decided. The
 * server remains the single source of truth for "which rule set applies right now".</p>
 *
 * <p>{@code useGate} and {@code bans} are the two masks {@link com.leipishu.pvpocket.combat.UsePolicy}
 * takes; see {@code CombatState.setUseGate} for why they are pushed rather than recomputed.</p>
 *
 * <p>{@code explosives} is here for the same reason as the gate: an explosion destroys blocks on
 * whichever side runs it, so a client that has not been told would punch holes the server never
 * confirms — holes only the person who lit the TNT can see.</p>
 */
public record CombatStatePayload(boolean legacy, boolean blocking, int useGate, int bans,
                                 boolean explosives)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CombatStatePayload> ID =
            new CustomPacketPayload.Type<>(PvPocket.id("combat_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CombatStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, CombatStatePayload::legacy,
            ByteBufCodecs.BOOL, CombatStatePayload::blocking,
            ByteBufCodecs.VAR_INT, CombatStatePayload::useGate,
            ByteBufCodecs.VAR_INT, CombatStatePayload::bans,
            ByteBufCodecs.BOOL, CombatStatePayload::explosives,
            CombatStatePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
