package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.network.ArenaBoundsPayload;
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
 * <p>Three deliberate choices:</p>
 * <ul>
 *   <li><b>Drawn only when near.</b> The whole plot is 48 blocks across; a curtain rendered from the
 *       far side of the map is a curtain that eats frame time to show you something you are not
 *       about to touch. Edges fade in over the last few blocks of approach instead of popping.</li>
 *   <li><b>Coloured by the rule.</b> {@code e.boundary = 拉回} is blue, {@code 判负} is red. Two rules
 *       that behave completely differently should not look the same — and the colour is the fastest
 *       way to confirm from inside the fight that the rule you set is the rule in force.</li>
 *   <li><b>A gradient, not a flat quad.</b> Near-opaque at the ground, transparent by four blocks up.
 *       A flat wall reads as solid geometry; a gradient reads as a boundary, and it keeps the arena
 *       looking like an arena rather than a box.</li>
 *   <li><b>It grows with you.</b> The height the server sends is a minimum; the curtain always
 *       reaches a couple of blocks above the camera. Standing on the ground that changes nothing —
 *       climbing a tower and finding the boundary has ended below your feet is the failure this
 *       avoids.</li>
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

    /** Past this distance an edge is not drawn at all. */
    private static final double RENDER_RANGE = 26.0D;
    /** Inside this distance an edge is drawn at full strength. */
    private static final double FULL_STRENGTH = 16.0D;

    /** Height of the near-opaque band at the foot of the curtain. */
    private static final double BASE_BAND = 0.28D;
    private static final float BASE_ALPHA = 0.70F;
    private static final float CURTAIN_ALPHA = 0.40F;

    /**
     * How far above the camera the curtain always reaches.
     *
     * <p>The server sends a fixed minimum height, which is right for someone standing on the ground
     * and useless for someone who climbed. A four-block band ends below your eyeline the moment you
     * are four blocks up, so the one question a player on a tower is actually asking — "where does
     * the arena end" — would get answered by nothing at all. Growing the curtain to a couple of
     * blocks above the camera keeps the answer in view from any height, at no network cost: the
     * camera position is already here.</p>
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
        if (!ArenaBounds.active()) {
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

        ArenaBoundsPayload bounds = ArenaBounds.get();
        Vec3d cam = context.camera().getPos();

        // Blue for 拉回 (the boundary undoes your step), red for 判负 (the boundary ends your round).
        float r;
        float g;
        float b;
        if (bounds.mode() == 0) {
            r = 0.36F;
            g = 0.61F;
            b = 1.00F;
        } else {
            r = 0.89F;
            g = 0.43F;
            b = 0.43F;
        }

        matrices.push();
        // Vertices are sent in world coordinates, so the camera has to be cancelled out here. Vanilla
        // does the same thing when it draws the debug frustum.
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer vc = consumers.getBuffer(RenderLayer.getDebugQuads());

        double yLow = bounds.floorY();
        // The server's height is the floor, not the answer: the curtain has to clear the camera, or
        // it stops being a boundary and becomes a stripe on the ground. Capped so a player who flies
        // to the build limit gets a tall curtain rather than one that reaches into the sky box.
        double yHigh = Math.max(bounds.floorY() + bounds.height(),
                Math.min(cam.y + CURTAIN_ABOVE_CAMERA, bounds.floorY() + MAX_CURTAIN));
        double bandTop = yLow + BASE_BAND;
        double fadeStart = Math.max(bandTop, yHigh - TOP_FADE);

        // Four edges. Each is culled and faded by the distance to the edge itself, not to its plane:
        // standing two blocks from the west wall but five hundred blocks north of the plot would pass
        // a plane-distance test and then draw a wall nobody is near.
        drawEdge(vc, matrix, cam, bounds.x0(), bounds.z0(), bounds.x0(), bounds.z1(),
                yLow, bandTop, fadeStart, yHigh, r, g, b);
        drawEdge(vc, matrix, cam, bounds.x1(), bounds.z0(), bounds.x1(), bounds.z1(),
                yLow, bandTop, fadeStart, yHigh, r, g, b);
        drawEdge(vc, matrix, cam, bounds.x0(), bounds.z0(), bounds.x1(), bounds.z0(),
                yLow, bandTop, fadeStart, yHigh, r, g, b);
        drawEdge(vc, matrix, cam, bounds.x0(), bounds.z1(), bounds.x1(), bounds.z1(),
                yLow, bandTop, fadeStart, yHigh, r, g, b);

        matrices.pop();
    }

    private static void drawEdge(VertexConsumer vc, Matrix4f matrix, Vec3d cam,
                                 double px, double pz, double qx, double qz,
                                 double yLow, double bandTop, double fadeStart, double yHigh,
                                 float r, float g, float b) {
        float strength = strength(cam.x, cam.z, px, pz, qx, qz);
        if (strength <= 0.0F) {
            return;
        }
        // Three bands rather than two: the curtain now stretches to the player's own height, so a
        // single fade from the foot would leave nothing to see at eye level.
        if (bandTop > yLow) {
            quad(vc, matrix, px, pz, qx, qz, yLow, bandTop,
                    r, g, b, BASE_ALPHA * strength, BASE_ALPHA * strength);
        }
        if (fadeStart > bandTop) {
            quad(vc, matrix, px, pz, qx, qz, bandTop, fadeStart,
                    r, g, b, CURTAIN_ALPHA * strength, CURTAIN_ALPHA * strength);
        }
        if (yHigh > fadeStart) {
            quad(vc, matrix, px, pz, qx, qz, fadeStart, yHigh,
                    r, g, b, CURTAIN_ALPHA * strength, 0.0F);
        }
    }

    /**
     * Opacity for one edge, from the horizontal distance between the camera and the nearest point on
     * that edge.
     *
     * <p>Returns 0 outside {@link #RENDER_RANGE} so the caller can skip the quad entirely rather than
     * emit invisible vertices — the check exists to save work, not just to look right.</p>
     */
    private static float strength(double camX, double camZ,
                                  double px, double pz, double qx, double qz) {
        double vx = qx - px;
        double vz = qz - pz;
        double len2 = vx * vx + vz * vz;
        double t = len2 == 0.0D ? 0.0D : ((camX - px) * vx + (camZ - pz) * vz) / len2;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double d = Math.hypot(camX - (px + t * vx), camZ - (pz + t * vz));
        if (d >= RENDER_RANGE) {
            return 0.0F;
        }
        if (d <= FULL_STRENGTH) {
            return 1.0F;
        }
        return (float) ((RENDER_RANGE - d) / (RENDER_RANGE - FULL_STRENGTH));
    }

    private static void quad(VertexConsumer vc, Matrix4f matrix,
                             double px, double pz, double qx, double qz,
                             double yLow, double yHigh,
                             float r, float g, float b,
                             float alphaLow, float alphaHigh) {
        vc.vertex(matrix, (float) px, (float) yLow, (float) pz).color(r, g, b, alphaLow);
        vc.vertex(matrix, (float) qx, (float) yLow, (float) qz).color(r, g, b, alphaLow);
        vc.vertex(matrix, (float) qx, (float) yHigh, (float) qz).color(r, g, b, alphaHigh);
        vc.vertex(matrix, (float) px, (float) yHigh, (float) pz).color(r, g, b, alphaHigh);
    }
}
