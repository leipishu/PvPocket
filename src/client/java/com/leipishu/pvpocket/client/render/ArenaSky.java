package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Paints the arena dimension's whole sky, above the horizon and below it.
 *
 * <p>Vanilla's sky stops at the horizon: below it there is no geometry, so looking down from the
 * lobby showed the frame's clear colour, which is black. In a world with ground nobody notices; the
 * arena is void, so it read as a black half-sphere under a blue one.
 *
 * <p>The colour is this file's own rather than vanilla's, because the sky colour is not reachable
 * the same way on every version: 1.21.1 has {@code Level#getSkyColor}, 1.21.11 dropped it, and 26.1
 * moved it into the environment attribute map. A fixed colour dimmed by the world's sky darkening
 * survives all five, at the cost of the sun, moon, stars and clouds, which leave no depth behind.
 */
public final class ArenaSky {

    /** Rings from straight up to straight down. A flat colour needs no more. */
    private static final int RINGS = 8;

    /** Segments around the vertical axis. */
    private static final int SEGMENTS = 24;

    /** Daylight sky, close to what the overworld's plains give at noon. */
    private static final float SKY_R = 0.47F;
    private static final float SKY_G = 0.65F;
    private static final float SKY_B = 1.00F;

    /** How much of the colour the night takes away. The sky stays readable, not black. */
    private static final float NIGHT_DROP = 0.82F;

    /** Sky darkening runs from 0 at noon to 11 at midnight. */
    private static final float DARKEN_STEPS = 11.0F;

    /** Share of the far plane the sphere may use, so it is never clipped. */
    private static final float FAR_SHARE = 0.8F;

    private ArenaSky() {
    }

    public static void register() {
        PvClientPlatform.get().onWorldRenderAfterTranslucent(ArenaSky::render);
    }

    private static void render(PvClientPlatform.WorldFrame frame) {
        ClientLevel level = frame.level();
        Minecraft client = Minecraft.getInstance();
        if (level == null || client.player == null || !ArenaFog.inArena(level)) {
            return;
        }
        PoseStack poses = frame.poses();
        if (poses == null) {
            return;
        }
//? if >=1.21.11 {
        Vec3 cam = frame.camera().position();
//?} else {
        Vec3 cam = frame.camera().getPosition();
//?}
        float night = Math.min(1.0F, level.getSkyDarken() / DARKEN_STEPS);
        float dim = 1.0F - NIGHT_DROP * night;
        float radius = radius(client);
        poses.pushPose();
        // Vertices are sent in world coordinates, so the camera offset is cancelled here.
        poses.translate(-cam.x, -cam.y, -cam.z);
        PvWorldGeometry.draw(frame, poses, PvRenderTypes.debugQuads(),
                (vc, matrix) -> sphere(vc, matrix, cam, radius,
                        SKY_R * dim, SKY_G * dim, SKY_B * dim));
        poses.popPose();
    }

    /**
     * How far the sphere sits from the camera.
     *
     * <p>Never past {@link ArenaFog#END}: everything the fog leaves visible is inside that, so a
     * sphere at that distance cannot be in front of a plot. The far-plane share keeps it inside the
     * projection on a short render distance, where the fog has already cut the view shorter than
     * the sphere would need to be.
     */
    private static float radius(Minecraft client) {
        int chunks = client.options.getEffectiveRenderDistance();
        return Math.min(ArenaFog.END, chunks * 16.0F * 4.0F * FAR_SHARE);
    }

    /** The inside of a sphere, as quads. Winding does not matter: the type has culling off. */
    private static void sphere(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float radius,
                               float r, float g, float b) {
        for (int ring = 0; ring < RINGS; ring++) {
            double high = Math.PI * ring / RINGS;
            double low = Math.PI * (ring + 1) / RINGS;
            for (int seg = 0; seg < SEGMENTS; seg++) {
                double from = Math.PI * 2.0D * seg / SEGMENTS;
                double to = Math.PI * 2.0D * (seg + 1) / SEGMENTS;
                vertex(vc, matrix, cam, radius, high, from, r, g, b);
                vertex(vc, matrix, cam, radius, high, to, r, g, b);
                vertex(vc, matrix, cam, radius, low, to, r, g, b);
                vertex(vc, matrix, cam, radius, low, from, r, g, b);
            }
        }
    }

    /** Phi runs from 0 at straight up to pi at straight down. */
    private static void vertex(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float radius,
                               double phi, double theta, float r, float g, float b) {
        double horizontal = Math.sin(phi) * radius;
        float x = (float) (cam.x + Math.cos(theta) * horizontal);
        float y = (float) (cam.y + Math.cos(phi) * radius);
        float z = (float) (cam.z + Math.sin(theta) * horizontal);
        vc.addVertex(matrix, x, y, z).setColor(r, g, b, 1.0F);
    }
}
