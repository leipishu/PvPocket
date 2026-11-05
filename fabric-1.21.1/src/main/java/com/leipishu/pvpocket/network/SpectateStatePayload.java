package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

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
 */
public record SpectateStatePayload(boolean active, String sessionId, String label,
                                   int index, String targets)
        implements CustomPayload {

    public static final Id<SpectateStatePayload> ID =
            new Id<>(Identifier.of("pvpocket", "spectate_state"));

    public static final PacketCodec<RegistryByteBuf, SpectateStatePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOL, SpectateStatePayload::active,
            PacketCodecs.STRING, SpectateStatePayload::sessionId,
            PacketCodecs.STRING, SpectateStatePayload::label,
            PacketCodecs.VAR_INT, SpectateStatePayload::index,
            PacketCodecs.STRING, SpectateStatePayload::targets,
            SpectateStatePayload::new);

    /** The "stop drawing the bar" packet. Immutable, so one instance is enough. */
    public static SpectateStatePayload hidden() {
        return new SpectateStatePayload(false, "", "", 0, "");
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
