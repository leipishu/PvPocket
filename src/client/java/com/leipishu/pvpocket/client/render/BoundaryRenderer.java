package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.network.ArenaBoundsPayload;
import com.leipishu.pvpocket.network.PrepareScreensPayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/**
 * Draws the arena boundary as a translucent curtain the player can walk through.
 *
 * <p>Each edge is cut into {@link #SEGMENT}-block pieces faded by distance, so a long edge appears
 * a few blocks at a time. Colour follows the rule: blue for push-back, red for forfeit.
 *
 * <p>The curtain reaches {@link #CURTAIN_ABOVE_CAMERA} above the camera, and the preparation
 * screens are drawn by the same pass in near-opaque white.
 *
 * <p>Geometry uses {@code RenderType.debugQuads()}: translucent POSITION_COLOR quads, culling off.
 */
public final class BoundaryRenderer {

    private static final ResourceKey<Level> ARENA_WORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("pvpocket", "arena"));

    /** Past this distance a segment is not drawn at all. */
    private static final double RENDER_RANGE = 26.0D;
    /** Inside this distance a segment is drawn at full strength. */
    private static final double FULL_STRENGTH = 14.0D;

    /**
     * Length of one drawn piece of an edge, in blocks.
     *
     * <p>Two balances fade smoothness against quad count. A 147-block edge produces 74 pieces, of
     * which only the dozen within {@link #RENDER_RANGE} reach the buffer.
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
     * <p>The server sends a fixed minimum height, which is useless for someone who climbed. A
     * four-block band would end below the eyeline of a player four blocks up.
     */
    private static final double CURTAIN_ABOVE_CAMERA = 2.5D;

    /** Hard cap, so flying to the build limit does not draw a curtain up to the sky. */
    private static final double MAX_CURTAIN = 96.0D;

    /** Height of the soft top edge, in blocks. */
    private static final double TOP_FADE = 3.0D;

    private BoundaryRenderer() {
    }

    public static void register() {
        PvClientPlatform.get().onWorldRenderAfterTranslucent(BoundaryRenderer::render);
    }

    private static void render(PvClientPlatform.WorldFrame frame) {
        // Either overlay is worth drawing alone: the screens exist only during the countdown, but a
        // client that lost the bounds packet must still see the wall it is behind.
        if (!ArenaBounds.active() && PrepareScreens.get().isEmpty()) {
            return;
        }
        ClientLevel world = frame.level();
        Minecraft client = Minecraft.getInstance();
        if (world == null || client.player == null) {
            return;
        }
        // The bounds only mean anything inside the arena dimension: a teleport out mid-round would
        // leave a curtain at coordinates that mean nothing in the new world.
        if (!world.dimension().equals(ARENA_WORLD)) {
            return;
        }
        PoseStack matrices = frame.poses();
        MultiBufferSource consumers = frame.buffers();
        if (matrices == null || consumers == null) {
            return;
        }

        Vec3 cam = frame.camera().getPosition();
        matrices.pushPose();
        // Vertices are sent in world coordinates, so the camera offset is cancelled here.
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = matrices.last().pose();
        VertexConsumer vc = consumers.getBuffer(RenderType.debugQuads());

        if (ArenaBounds.active()) {
            drawBoundary(vc, matrix, cam, ArenaBounds.get());
        }
        // Drawn independently of the bounds: a client with screens but no outline still sees the
        // wall it is standing behind.
        drawScreens(vc, matrix, cam);

        matrices.popPose();
    }

    /**
     * Draws the boundary itself: four edges in the colour of the rule in force.
     *
     * <p>The solid white treatment belongs to {@link #drawScreens}, which is the geometry that
     * becomes solid during the countdown.
     */
    private static void drawBoundary(VertexConsumer vc, Matrix4f matrix, Vec3 cam,
                                     ArenaBoundsPayload bounds) {
        // Blue for push-back (the boundary undoes the step), red for forfeit (it ends the round).
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
        // A rule boundary has no physical height, so it grows with the camera.
        double yHigh = Math.max(bounds.floorY() + bounds.height(),
                Math.min(cam.y + CURTAIN_ABOVE_CAMERA, bounds.floorY() + MAX_CURTAIN));
        double bandTop = Math.min(yLow + BASE_BAND, yHigh);
        double fadeStart = Math.max(bandTop, yHigh - TOP_FADE);

        // Each edge is culled and faded per segment, not per edge: standing near one wall but far
        // along another would pass a whole-edge distance test.
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
     * Draws the screens standing in front of each side during the countdown, in solid white.
     *
     * <p>Near-opaque, at its real height, with a hard top edge: the wall has an actual top, and the
     * blocks behind it are {@code barrier}, so sight and collision are the same plane.
     *
     * <p>The same distance fade as the boundary, since a screen spans the whole fight area.
     */
    private static void drawScreens(VertexConsumer vc, Matrix4f matrix, Vec3 cam) {
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
            // When fadeStart equals yHigh there is no soft top: drawEdge skips the fading band,
            // which is the hard edge a real wall has.
            drawEdge(vc, matrix, cam, px, pz, qx, qz, yLow, bandTop, yHigh, yHigh,
                    SOLID_R, SOLID_G, SOLID_B, SOLID_BASE_ALPHA, SOLID_BODY_ALPHA);
        }
    }

    private static void drawEdge(VertexConsumer vc, Matrix4f matrix, Vec3 cam,
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

            // Three bands: the near-opaque foot, the body, and for a rule boundary a soft top edge.
            // A solid wall has no soft top, since its top is where the barrier blocks stop.
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
     * Returns opacity at one point on an edge, from its horizontal distance to the camera.
     *
     * <p>Returns 0 past {@link #RENDER_RANGE}, which the caller uses to skip the piece and emit no
     * vertices.
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
        vc.addVertex(matrix, (float) ax, (float) yLow, (float) az).setColor(r, g, b, alphaLowA);
        vc.addVertex(matrix, (float) bx, (float) yLow, (float) bz).setColor(r, g, b, alphaLowB);
        vc.addVertex(matrix, (float) bx, (float) yHigh, (float) bz).setColor(r, g, b, alphaHighB);
        vc.addVertex(matrix, (float) ax, (float) yHigh, (float) az).setColor(r, g, b, alphaHighA);
    }
}
