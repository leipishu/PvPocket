package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Paints the arena dimension's sky: a gradient sphere, above the horizon and below it.
 *
 * <p>Vanilla's sky stops at the horizon, so looking down from the lobby showed the frame's clear
 * colour, which is black. One flat colour closed that hole and looked like a painted room, so the
 * sphere carries the usual sky gradient: deep blue overhead, pale and hazy at the horizon, a little
 * deeper underneath, all of it lerped from the horizon colour by a power of the elevation.
 *
 * <p>The colours are this file's own rather than vanilla's, because the sky colour is reached three
 * different ways across the five versions. One palette dimmed by the world's sky darkening survives
 * all of them, at the cost of the sun, moon, stars and clouds, which leave no depth behind.
 */
public final class ArenaSky {

    /** Bands from straight up to straight down, evenly spaced in elevation. */
    private static final int RINGS = 16;

    /** Segments around the vertical axis. */
    private static final int SEGMENTS = 32;

    /** Daylight overhead, the deep end of the gradient. */
    private static final float ZENITH_R = 0.20F;
    private static final float ZENITH_G = 0.38F;
    private static final float ZENITH_B = 0.75F;

    /** Daylight at the horizon: pale and hazy, the bright end. */
    private static final float HORIZON_R = 0.70F;
    private static final float HORIZON_G = 0.81F;
    private static final float HORIZON_B = 0.94F;

    /** Below the horizon the haze deepens again, so looking down is not a bright wall. */
    private static final float NADIR_R = 0.44F;
    private static final float NADIR_G = 0.56F;
    private static final float NADIR_B = 0.72F;

    /** Below one the haze piles up at the horizon; above it the blue climbs slowly. */
    private static final float GRADIENT_POWER = 0.55F;

    /** How much of each stop the night takes away. The horizon keeps more light than the zenith,
     * the way a real night sky stays paler near the ground. */
    private static final float NIGHT_ZENITH = 0.88F;
    private static final float NIGHT_HORIZON = 0.62F;
    private static final float NIGHT_NADIR = 0.70F;

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
        float radius = radius(client);
        poses.pushPose();
        // Vertices are sent in world coordinates, so the camera offset is cancelled here.
        poses.translate(-cam.x, -cam.y, -cam.z);
        PvWorldGeometry.draw(frame, poses, PvRenderTypes.debugQuads(),
                (vc, matrix) -> sphere(vc, matrix, cam, radius, night));
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
                               float night) {
        for (int ring = 0; ring < RINGS; ring++) {
            double high = 1.0D - 2.0D * ring / RINGS;
            double low = 1.0D - 2.0D * (ring + 1) / RINGS;
            for (int seg = 0; seg < SEGMENTS; seg++) {
                double from = Math.PI * 2.0D * seg / SEGMENTS;
                double to = Math.PI * 2.0D * (seg + 1) / SEGMENTS;
                vertex(vc, matrix, cam, radius, high, from, night);
                vertex(vc, matrix, cam, radius, high, to, night);
                vertex(vc, matrix, cam, radius, low, to, night);
                vertex(vc, matrix, cam, radius, low, from, night);
            }
        }
    }

    /** Elevation runs from 1 straight up through 0 at the horizon to -1 straight down. */
    private static void vertex(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float radius,
                               double elevation, double theta, float night) {
        double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - elevation * elevation)) * radius;
        float x = (float) (cam.x + Math.cos(theta) * horizontal);
        float y = (float) (cam.y + elevation * radius);
        float z = (float) (cam.z + Math.sin(theta) * horizontal);
        float t = (float) Math.pow(Math.abs(elevation), GRADIENT_POWER);
        boolean up = elevation >= 0.0D;
        float dim = 1.0F - night * mix(NIGHT_HORIZON, up ? NIGHT_ZENITH : NIGHT_NADIR, t);
        float r = mix(HORIZON_R, up ? ZENITH_R : NADIR_R, t) * dim;
        float g = mix(HORIZON_G, up ? ZENITH_G : NADIR_G, t) * dim;
        float b = mix(HORIZON_B, up ? ZENITH_B : NADIR_B, t) * dim;
        vc.addVertex(matrix, x, y, z).setColor(r, g, b, 1.0F);
    }

    private static float mix(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
