package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server to client: "the plot you are fighting on is this box".
 *
 * <p>Sent so the client can draw the boundary. The server has always known where the edge is — that
 * is how it decides who left — but the player had no way to see it, so the only way to learn the
 * shape of the arena was to walk into an invisible wall and get snapped back. The fix for that is
 * not a block ring: a ring is mineable, and a mineable boundary is a suggestion. It is a purely
 * client-side overlay that costs the world nothing.</p>
 *
 * <p>The coordinates are the four vertical planes, already expanded to the outer face
 * ({@code x1 = maxX + 1}), because that is the same limit {@code ArenaRegion.contains} uses. Sending
 * the inclusive block corners instead would put the drawn wall half a block inside the real one.</p>
 *
 * <p>{@code mode} is the {@code e.boundary} rule index. It travels with the bounds because the two
 * rules deserve different colours — a boundary you are snapped back from and a boundary that loses
 * you the round should not look identical.</p>
 *
 * <p>It also carries {@link #MODE_PREPARE}, which is not a rule at all: during the preparation
 * window the wall is real (a barrier ring is standing at these coordinates) and the curtain is
 * painted white to say so. The three modes are one field rather than a boolean because a client
 * should not have to correlate two flags to decide what to draw.</p>
 */
public record ArenaBoundsPayload(boolean active,
                                 double x0, double z0, double x1, double z1,
                                 double floorY, double height,
                                 int mode)
        implements CustomPayload {

    /** {@code e.boundary = 拉回}: blue, walk-through. */
    public static final int MODE_PUSH_BACK = 0;
    /** {@code e.boundary = 判负}: red, walk-through, five-second countdown. */
    public static final int MODE_COUNTDOWN = 1;
    /** Preparation window: white, and backed by actual barrier blocks. */
    public static final int MODE_PREPARE = 2;

    public static final Id<ArenaBoundsPayload> ID =
            new Id<>(Identifier.of("pvpocket", "arena_bounds"));

    public static final PacketCodec<RegistryByteBuf, ArenaBoundsPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.active);
                buf.writeDouble(value.x0);
                buf.writeDouble(value.z0);
                buf.writeDouble(value.x1);
                buf.writeDouble(value.z1);
                buf.writeDouble(value.floorY);
                buf.writeDouble(value.height);
                buf.writeVarInt(value.mode);
            },
            buf -> new ArenaBoundsPayload(
                    buf.readBoolean(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readVarInt()));

    /** The "stop drawing it" packet. Immutable, so one instance is enough. */
    public static ArenaBoundsPayload hidden() {
        return new ArenaBoundsPayload(false, 0, 0, 0, 0, 0, 0, 0);
    }

    /** True when this curtain stands on a real wall rather than on a rule. */
    public boolean solid() {
        return mode == MODE_PREPARE;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
