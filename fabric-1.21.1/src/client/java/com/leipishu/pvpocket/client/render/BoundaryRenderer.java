package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.network.ArenaBoundsPayload;
import com.leipishu.pvpocket.network.PrepareScreensPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * Draws the arena boundary as a translucent curtain you can walk through.
 *
 * <p>This is the answer to "there is no way to tell where the boundary is" that is not a wall of
 * blocks. A block ring would have to be broken to leave (which makes the countdown dead code) and
 * would have to be mineable (which makes it a suggestion rather than a boundary). A render-only
 * overlay costs the world nothing, cannot be broken, and cannot be climbed.</p>
 *
 * <p>Deliberate choices:</p>
 * <ul>
 *   <li><b>Built from segments, not from one quad per edge.</b> An edge can be 147 blocks long; a
 *       single quad for it is a wall that pops into existence the moment you come within range of
 *       any part of it, including the parts a hundred blocks behind you. Each edge is cut into
 *       {@link #SEGMENT}-block pieces and each piece fades on its own distance, so the boundary
 *       appears a few blocks at a time as you walk towards it. It also cuts the fill rate: only the
 *       pieces near the camera produce vertices at all.</li>
 *   <li><b>Coloured by the rule, always.</b> {@code e.boundary = 拉回} is blue, {@code 判负} is red.
 *       Two rules that behave completely differently should not look the same — and the colour is
 *       the fastest way to confirm from inside the fight that the rule you set is the rule in force.
 *       The countdown does not change it: the boundary is still the boundary while the sides are
 *       held apart.</li>
 *   <li><b>It grows with you.</b> The height the server sends is a minimum; the curtain always
 *       reaches a couple of blocks above the camera. Standing on the ground that changes nothing —
 *       climbing a tower and finding the boundary has ended below your feet is the failure this
 *       avoids.</li>
 *   <li><b>The preparation screens are drawn here too, and they are the white solid ones.</b> One
 *       plane in front of each side, near-opaque, at its real height with a hard top edge, because
 *       that wall has a top and is backed by real barrier blocks. See {@link #drawScreens}.</li>
 * </ul>
 *
 * <p>Geometry goes through {@link RenderLayer#getDebugQuads()}, which is the vanilla layer for exactly
 * this: {@code POSITION_COLOR} vertices, translucent, culling disabled. Culling off is why each quad
 * is emitted once and still visible from both sides.</p>
 */
@Environment(EnvType.CLIENT)
public final class BoundaryRenderer {

    private static final RegistryKey<World> ARENA_WORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("pvpocket", "arena"));

    /** Past this distance a segment is not drawn at all. */
    private static final double RENDER_RANGE = 26.0D;
    /** Inside this distance a segment is drawn at full strength. */
    private static final double FULL_STRENGTH = 14.0D;

    /**
     * Length of one drawn piece of an edge, in blocks.
     *
     * <p>Two is a compromise between the two costs: shorter pieces fade more smoothly but emit more
     * quads, and every quad is three draw bands. At two blocks the fade is indistinguishable from a
     * continuous gradient at walking speed, and a 147-block edge produces 74 pieces of which only
     * the dozen or so within {@link #RENDER_RANGE} ever reach the buffer.</p>
     */
    private static final double SEGMENT = 2.0D;

    /** Height of the near-opaque band at the foot of the curtain. */
    private static final double BASE_BAND = 0.28D;
    private static final float BASE_ALPHA = 0.70F;
    private static final float CURTAIN_ALPHA = 0.40F;

    /** A solid wall is more opaque: it is the only cue that the thing in front of you is real. */
    private static final float SOLID_BASE_ALPHA = 0.88F;
    private static final float SOLID_BODY_ALPHA = 0.55F;

    /** The preparation screens' colour: white, very slightly blue so it does not read as fog. */
    private static final float SOLID_R = 0.93F;
    private static final float SOLID_G = 0.95F;
    private static final float SOLID_B = 1.00F;

    /**
     * How far above the camera the curtain always reaches.
     *
     * <p>The server sends a fixed minimum height, which is right for someone standing on the ground
     * and useless for someone who climbed. A four-block band ends below your eyeline the moment you
     * are four blocks up, so the one question a player on a tower is actually asking — "where does
     * the arena end" — would get answered by nothing at all.</p>
     */
    private static final double CURTAIN_ABOVE_CAMERA = 2.5D;

    /** Hard cap, so flying to the build limit does not draw a curtain up to the sky. */
    private static final double MAX_CURTAIN = 96.0D;

    /** Height of the soft top edge, in blocks. */
    private static final double TOP_FADE = 3.0D;

    private BoundaryRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BoundaryRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        // Either overlay is worth drawing on its own: the screens exist only during the countdown,
        // when the bounds are always up as well, but a client that lost the bounds packet must not
        // also lose the wall it is standing behind.
        if (!ArenaBounds.active() && PrepareScreens.get().isEmpty()) {
            return;
        }
        ClientWorld world = context.world();
        MinecraftClient client = MinecraftClient.getInstance();
        if (world == null || client.player == null) {
            return;
        }
        // The bounds are only meaningful inside the arena dimension. They are cleared when the round
        // ends, but a teleport out of the dimension mid-round would otherwise leave a curtain hanging
        // at coordinates that mean nothing in the world the player is now standing in.
        if (!world.getRegistryKey().equals(ARENA_WORLD)) {
            return;
        }
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) {
            return;
        }

        Vec3d cam = context.camera().getPos();
        matrices.push();
        // Vertices are sent in world coordinates, so the camera has to be cancelled out here. Vanilla
        // does the same thing when it draws the debug frustum.
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer vc = consumers.getBuffer(RenderLayer.getDebugQuads());

        if (ArenaBounds.active()) {
            drawBoundary(vc, matrix, cam, ArenaBounds.get());
        }
        // Independent of the bounds on purpose: the screens are not the boundary, and a client that
        // somehow got the screens without the plot outline should still see the wall it is standing
        // behind rather than walk into nothing.
        drawScreens(vc, matrix, cam);

        matrices.pop();
    }

    /**
     * The boundary itself: four edges, in the colour of whichever rule is in force.
     *
     * <p>It used to turn white and solid during the countdown, because a real barrier ring stood at
     * these coordinates for those seconds. That made the boundary look like a different object for
     * five seconds — and hid the one colour that answers "is this a push-back arena or a forfeit
     * one" exactly when the player is standing still and could read it. The solid white moved to
     * {@link #drawScreens}, which is the thing that actually becomes solid.</p>
     */
    private static void drawBoundary(VertexConsumer vc, Matrix4f matrix, Vec3d cam,
                                     ArenaBoundsPayload bounds) {
        // Blue for 拉回 (the boundary undoes your step), red for 判负 (the boundary ends your round).
        float r;
        float g;
        float b;
        if (bounds.mode() == ArenaBoundsPayload.MODE_PUSH_BACK) {
            r = 0.36F;
            g = 0.61F;
            b = 1.00F;
        } else {
            r = 0.89F;
            g = 0.43F;
            b = 0.43F;
        }

        double yLow = bounds.floorY();
        // A rule boundary has no physical height to be honest about, so it grows with the camera:
        // standing on a tower and finding the curtain has ended below your feet is the failure this
        // avoids.
        double yHigh = Math.max(bounds.floorY() + bounds.height(),
                Math.min(cam.y + CURTAIN_ABOVE_CAMERA, bounds.floorY() + MAX_CURTAIN));
        double bandTop = Math.min(yLow + BASE_BAND, yHigh);
        double fadeStart = Math.max(bandTop, yHigh - TOP_FADE);

        // Four edges. Each is culled and faded per segment, not per edge: standing two blocks from
        // the west wall but five hundred blocks north of the plot would pass a whole-edge distance
        // test and then draw a wall nobody is near.
        drawEdge(vc, matrix, cam, bounds.x0(), bounds.z0(), bounds.x0(), bounds.z1(),
                yLow, bandTop, fadeStart, yHigh, r, g, b, BASE_ALPHA, CURTAIN_ALPHA);
        drawEdge(vc, matrix, cam, bounds.x1(), bounds.z0(), bounds.x1(), bounds.z1(),
                yLow, bandTop, fadeStart, yHigh, r, g, b, BASE_ALPHA, CURTAIN_ALPHA);
        drawEdge(vc, matrix, cam, bounds.x0(), bounds.z0(), bounds.x1(), bounds.z0(),
                yLow, bandTop, fadeStart, yHigh, r, g, b, BASE_ALPHA, CURTAIN_ALPHA);
        drawEdge(vc, matrix, cam, bounds.x0(), bounds.z1(), bounds.x1(), bounds.z1(),
                yLow, bandTop, fadeStart, yHigh, r, g, b, BASE_ALPHA, CURTAIN_ALPHA);
    }

    /**
     * The screens standing in front of each side during the countdown, painted solid white.
     *
     * <p>This is the treatment the boundary used to get: near-opaque, its real height, and a hard
     * top edge instead of a soft one — the wall has an actual top, so the drawing should say where
     * it is. The blocks behind it are {@code barrier}, so what you see and what you bump into are
     * the same plane.</p>
     *
     * <p>Same distance fade as the boundary, for the same reason: a screen spans the whole fight
     * area, and drawing all of it at full strength would fill the view with white from any angle.
     * The piece in front of you is always within full strength.</p>
     */
    private static void drawScreens(VertexConsumer vc, Matrix4f matrix, Vec3d cam) {
        for (PrepareScreensPayload.Screen screen : PrepareScreens.get()) {
            double px;
            double pz;
            double qx;
            double qz;
            if (screen.alongZ()) {
                // A plane at a constant Z, spanning X.
                px = screen.from();
                pz = screen.plane();
                qx = screen.to();
                qz = screen.plane();
            } else {
                px = screen.plane();
                pz = screen.from();
                qx = screen.plane();
                qz = screen.to();
            }
            double yLow = screen.floorY();
            double yHigh = screen.floorY() + screen.height();
            double bandTop = Math.min(yLow + BASE_BAND, yHigh);
            // fadeStart == yHigh means "no soft top": drawEdge skips the fading band when the two are
            // equal, which is exactly the hard edge a real wall has.
            drawEdge(vc, matrix, cam, px, pz, qx, qz, yLow, bandTop, yHigh, yHigh,
                    SOLID_R, SOLID_G, SOLID_B, SOLID_BASE_ALPHA, SOLID_BODY_ALPHA);
        }
    }

    private static void drawEdge(VertexConsumer vc, Matrix4f matrix, Vec3d cam,
                                 double px, double pz, double qx, double qz,
                                 double yLow, double bandTop, double fadeStart, double yHigh,
                                 float r, float g, float b,
                                 float baseAlpha, float bodyAlpha) {
        double dx = qx - px;
        double dz = qz - pz;
        double length = Math.hypot(dx, dz);
        if (length <= 0.0D) {
            return;
        }
        int pieces = Math.max(1, (int) Math.ceil(length / SEGMENT));
        for (int i = 0; i < pieces; i++) {
            double t0 = (double) i / pieces;
            double t1 = (double) (i + 1) / pieces;
            double ax = px + dx * t0;
            double az = pz + dz * t0;
            double bx = px + dx * t1;
            double bz = pz + dz * t1;

            // Per-endpoint strength, so a piece straddling the fade radius is drawn as a gradient
            // rather than popping in and out as a whole.
            float a0 = strength(cam.x, cam.z, ax, az);
            float a1 = strength(cam.x, cam.z, bx, bz);
            if (a0 <= 0.0F && a1 <= 0.0F) {
                continue;
            }

            // Three bands: the near-opaque foot, the body, and (for a rule boundary) a soft top edge.
            // A solid wall has no soft top — its top is where the barrier blocks stop.
            if (bandTop > yLow) {
                quad(vc, matrix, ax, az, bx, bz, yLow, bandTop, r, g, b,
                        baseAlpha * a0, baseAlpha * a1, baseAlpha * a0, baseAlpha * a1);
            }
            if (fadeStart > bandTop) {
                quad(vc, matrix, ax, az, bx, bz, bandTop, fadeStart, r, g, b,
                        bodyAlpha * a0, bodyAlpha * a1, bodyAlpha * a0, bodyAlpha * a1);
            }
            if (yHigh > fadeStart) {
                quad(vc, matrix, ax, az, bx, bz, fadeStart, yHigh, r, g, b,
                        bodyAlpha * a0, bodyAlpha * a1, 0.0F, 0.0F);
            }
        }
    }

    /**
     * Opacity at one point on an edge, from its horizontal distance to the camera.
     *
     * <p>Returns 0 past {@link #RENDER_RANGE}, which the caller uses to skip the piece entirely
     * rather than emit invisible vertices — the check exists to save work, not just to look right.
     * This is what makes the boundary appear a few blocks at a time as the player walks along it:
     * the far end of a long edge is simply not in the buffer.</p>
     */
    private static float strength(double camX, double camZ, double x, double z) {
        double d = Math.hypot(camX - x, camZ - z);
        if (d >= RENDER_RANGE) {
            return 0.0F;
        }
        if (d <= FULL_STRENGTH) {
            return 1.0F;
        }
        return (float) ((RENDER_RANGE - d) / (RENDER_RANGE - FULL_STRENGTH));
    }

    private static void quad(VertexConsumer vc, Matrix4f matrix,
                             double ax, double az, double bx, double bz,
                             double yLow, double yHigh,
                             float r, float g, float b,
                             float alphaLowA, float alphaLowB,
                             float alphaHighA, float alphaHighB) {
        vc.vertex(matrix, (float) ax, (float) yLow, (float) az).color(r, g, b, alphaLowA);
        vc.vertex(matrix, (float) bx, (float) yLow, (float) bz).color(r, g, b, alphaLowB);
        vc.vertex(matrix, (float) bx, (float) yHigh, (float) bz).color(r, g, b, alphaHighB);
        vc.vertex(matrix, (float) ax, (float) yHigh, (float) az).color(r, g, b, alphaHighA);
    }
}
