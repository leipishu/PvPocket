package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the bounds of the arena plot the player is fighting on.
 *
 * <p>Sent so the client can draw a boundary overlay rather than the player learning the shape by
 * walking into an invisible wall. The coordinates are the four vertical planes, already expanded
 * to the outer face ({@code x1 = maxX + 1}), which is the limit {@code ArenaRegion.contains} uses.
 * Inclusive block corners would draw the wall half a block inside the real one.
 *
 * <p>{@code mode} is the {@code e.boundary} rule index, carried with the bounds so each rule gets
 * its own colour. {@code active = false} means stop drawing.
 */
public record ArenaBoundsPayload(boolean active,
                                 double x0, double z0, double x1, double z1,
                                 double floorY, double height,
                                 int mode)
        implements CustomPacketPayload {

    /** Snap-back {@code e.boundary} mode: blue outline, walk-through. */
    public static final int MODE_PUSH_BACK = 0;
    /** Lose-the-round {@code e.boundary} mode: red outline, five-second countdown. */
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
