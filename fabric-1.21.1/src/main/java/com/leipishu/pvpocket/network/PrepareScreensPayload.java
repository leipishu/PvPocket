package com.leipishu.pvpocket.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: "these planes are standing in front of the sides right now".
 *
 * <p>The preparation window has two jobs. The boundary ring stops a side leaving; a screen in front
 * of each side stops the two sides meeting. The ring is a rule-shaped thing and is drawn by the
 * boundary curtain in its rule colour. The screens are not a rule at all — they are a promise that
 * the fight has not started — so they get their own packet and their own look: a solid white wall,
 * the same treatment the boundary itself used to get during the countdown.</p>
 *
 * <p>A list rather than a fixed pair: a match has up to four sides, and sides 2 and 3 are screened
 * on the perpendicular axis, so the count and the orientation both vary. An empty list is the
 * "nothing is standing" message, which is what the round sends the moment it goes live.</p>
 *
 * <p>The coordinates are world coordinates, already resolved by the server: the client has no
 * business re-deriving where a screen stands from pads and insets, and two derivations of the same
 * plane is how a wall ends up drawn a block away from the blocks you are bumping into.</p>
 */
public record PrepareScreensPayload(List<Screen> screens) implements CustomPayload {

    /**
     * One vertical plane, one block thick.
     *
     * <p>{@code alongZ} says which way the plane faces: true for a wall at a constant Z spanning X,
     * false for the mirror image. {@code from}/{@code to} are the ends of the span on the other
     * axis — the full width of the fight area, so there is no way around it.</p>
     */
    public record Screen(boolean alongZ, double plane, double from, double to,
                         double floorY, double height) {
    }

    public static final Id<PrepareScreensPayload> ID =
            new Id<>(Identifier.of("pvpocket", "prepare_screens"));

    public static final PacketCodec<RegistryByteBuf, PrepareScreensPayload> CODEC = PacketCodec.of(
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
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
