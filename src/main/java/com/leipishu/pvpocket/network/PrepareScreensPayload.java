package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the planes standing in front of each side right now.
 *
 * <p>A screen is a solid white wall that keeps the two sides apart until the round goes live. It is
 * a list rather than a fixed pair, because a match has up to four sides and sides 2 and 3 are
 * screened on the perpendicular axis. An empty list means nothing is standing.
 *
 * <p>The coordinates are world coordinates already resolved by the server; the client draws them
 * as sent.
 */
public record PrepareScreensPayload(List<Screen> screens) implements CustomPacketPayload {

    /**
     * One vertical plane, one block thick.
     *
     * <p>{@code alongZ} is true for a wall at a constant Z spanning X, false for the mirror image.
     * {@code from}/{@code to} are the ends of the span on the other axis.
     */
    public record Screen(boolean alongZ, double plane, double from, double to,
                         double floorY, double height) {
    }

    public static final Type<PrepareScreensPayload> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pvpocket", "prepare_screens"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PrepareScreensPayload> CODEC = StreamCodec.ofMember(
            (value, buf) -> {
                buf.writeVarInt(value.screens.size());
                for (Screen screen : value.screens) {
                    buf.writeBoolean(screen.alongZ);
                    buf.writeDouble(screen.plane);
                    buf.writeDouble(screen.from);
                    buf.writeDouble(screen.to);
                    buf.writeDouble(screen.floorY);
                    buf.writeDouble(screen.height);
                }
            },
            buf -> {
                int count = buf.readVarInt();
                List<Screen> screens = new ArrayList<>(Math.max(0, count));
                for (int i = 0; i < count; i++) {
                    screens.add(new Screen(
                            buf.readBoolean(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble()));
                }
                return new PrepareScreensPayload(List.copyOf(screens));
            });

    /** The "nothing is standing" packet. */
    public static PrepareScreensPayload none() {
        return new PrepareScreensPayload(List.of());
    }

    public boolean active() {
        return !screens.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
