package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the reader is watching this session, and the camera is on this player.
 *
 * <p>Drives the spectator bar: {@code index} and {@code targets} let it say "1 / 2" and show what
 * the next press of the cycle key will switch to. {@code targets} uses the same
 * {@code name:uuid,name:uuid} encoding as {@link MatchListPayload}.
 *
 * <p>{@code active=false} means nothing is being watched. {@code mapId} and {@code round} are sent
 * instead of a rendered label, so the bar can name the map in the reader's language through
 * {@code pvpocket.map.<id>}.
 */
public record SpectateStatePayload(boolean active, String sessionId, String mapId, int round,
                                   int index, String targets)
        implements CustomPacketPayload {

    public static final Type<SpectateStatePayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "spectate_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpectateStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SpectateStatePayload::active,
            ByteBufCodecs.STRING_UTF8, SpectateStatePayload::sessionId,
            ByteBufCodecs.STRING_UTF8, SpectateStatePayload::mapId,
            ByteBufCodecs.VAR_INT, SpectateStatePayload::round,
            ByteBufCodecs.VAR_INT, SpectateStatePayload::index,
            ByteBufCodecs.STRING_UTF8, SpectateStatePayload::targets,
            SpectateStatePayload::new);

    /** The "stop drawing the bar" packet. Immutable, so one instance is enough. */
    public static SpectateStatePayload hidden() {
        return new SpectateStatePayload(false, "", "", 0, 0, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
