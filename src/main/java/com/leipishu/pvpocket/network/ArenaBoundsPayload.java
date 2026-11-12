package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
 * <p>There used to be a third mode for the preparation window, drawn white and solid because a real
 * barrier ring stood at these coordinates. That was wrong on both counts: the boundary is still the
 * boundary during the countdown, and the thing that becomes solid when the countdown starts is the
 * screen in front of each side, not the edge. The screens travel in their own payload
 * ({@link PrepareScreensPayload}) and the boundary keeps the rule's colour throughout.</p>
 */
public record ArenaBoundsPayload(boolean active,
                                 double x0, double z0, double x1, double z1,
                                 double floorY, double height,
                                 int mode)
        implements CustomPacketPayload {

    /** {@code e.boundary = 拉回}: blue, walk-through. */
    public static final int MODE_PUSH_BACK = 0;
    /** {@code e.boundary = 判负}: red, walk-through, five-second countdown. */
    public static final int MODE_COUNTDOWN = 1;

    public static final Type<ArenaBoundsPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "arena_bounds"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArenaBoundsPayload> CODEC = StreamCodec.ofMember(
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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
