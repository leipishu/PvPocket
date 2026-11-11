package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: "you are watching this session, and the camera is on this player".
 *
 * <p>Drives the spectator bar at the bottom of the screen. It carries the participant list rather
 * than just the current name so the bar can say "1 / 2" and so the client can show what the next
 * press of the cycle key will switch to — a key that changes something invisible until it is pressed
 * is a key nobody presses twice.</p>
 *
 * <p>{@code active=false} is the "you are not watching anything" packet, sent on leave, on session
 * end and on join, so the bar cannot outlive the thing it describes.</p>
 *
 * <p>{@code targets} uses the same {@code name:uuid,name:uuid} encoding as
 * {@link MatchListPayload}, for the same reason: neither field can contain the separators.</p>
 *
 * <p>{@code mapId} and {@code round} are sent instead of a rendered "Colosseum · round 2" label so
 * the bar can say it in the reader's language. The client turns the id into
 * {@code pvpocket.map.<id>}.</p>
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
