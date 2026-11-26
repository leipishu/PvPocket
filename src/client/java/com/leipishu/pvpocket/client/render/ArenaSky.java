package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.leipishu.pvpocket.arena.Lobby;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The arena's sky: a coloured dome with the sun, moon, stars and clouds drawn inside it.
 *
 * <p>The arena is a void, so vanilla's sky is a flat colour and a sun drawn without depth. This
 * replaces both: everything is geometry in one pass, at its own distance, so the layers occlude
 * each other instead of stacking by draw order.
 *
 * <p>The rule panel's environment options are all visible here, because a switch nobody can see is
 * a switch that does nothing: {@code e.timeOfDay} moves the sun through four fixed times and
 * "natural", and {@code e.weather} decides the cloud cover and how grey the sky goes.
 */
public final class ArenaSky {

    /** Bands from straight up to straight down, evenly spaced in elevation. */
    private static final int RINGS = 16;

    /** Segments around the vertical axis. */
    private static final int SEGMENTS = 32;

    private static final float TAU = (float) (Math.PI * 2.0D);

    /** Daylight overhead, the deep end of the gradient. */
    private static final float DAY_UP_R = 0.20F;
    private static final float DAY_UP_G = 0.38F;
    private static final float DAY_UP_B = 0.75F;

    /** Daylight at the horizon: pale and hazy, the bright end. */
    private static final float DAY_HORIZON_R = 0.70F;
    private static final float DAY_HORIZON_G = 0.81F;
    private static final float DAY_HORIZON_B = 0.94F;

    /** Below the horizon the haze deepens again, so looking down is not a bright wall. */
    private static final float DAY_DOWN_R = 0.44F;
    private static final float DAY_DOWN_G = 0.56F;
    private static final float DAY_DOWN_B = 0.72F;

    /** Night keeps a trace of blue rather than going black: a void with no stars reads as a bug. */
    private static final float NIGHT_UP_R = 0.015F;
    private static final float NIGHT_UP_G = 0.022F;
    private static final float NIGHT_UP_B = 0.055F;
    private static final float NIGHT_HORIZON_R = 0.045F;
    private static final float NIGHT_HORIZON_G = 0.065F;
    private static final float NIGHT_HORIZON_B = 0.125F;
    private static final float NIGHT_DOWN_R = 0.025F;
    private static final float NIGHT_DOWN_G = 0.035F;
    private static final float NIGHT_DOWN_B = 0.070F;

    /** Sunrise and sunset, applied only to the horizon on the sun's side. */
    private static final float DUSK_R = 0.95F;
    private static final float DUSK_G = 0.48F;
    private static final float DUSK_B = 0.22F;

    /** Overcast: everything grey and flat, the way a rainy sky loses its gradient. */
    private static final float OVERCAST_UP_R = 0.34F;
    private static final float OVERCAST_UP_G = 0.36F;
    private static final float OVERCAST_UP_B = 0.40F;
    private static final float OVERCAST_HORIZON_R = 0.50F;
    private static final float OVERCAST_HORIZON_G = 0.52F;
    private static final float OVERCAST_HORIZON_B = 0.55F;
    private static final float OVERCAST_DOWN_R = 0.30F;
    private static final float OVERCAST_DOWN_G = 0.31F;
    private static final float OVERCAST_DOWN_B = 0.34F;

    /** Below one the haze piles up at the horizon; above it the blue climbs slowly. */
    private static final float GRADIENT_POWER = 0.55F;

    /** Share of the far plane the sphere may use, so it is never clipped. */
    private static final float FAR_SHARE = 0.8F;

    /** Stars in the upper hemisphere: enough for a clear night, cheap enough for one batch. */
    private static final int STARS = 220;

    /** Side of one cloud cell in blocks, and how thick the slab is. */
    private static final int CLOUD_CELL = 16;
    private static final float CLOUD_THICKNESS = 6.0F;

    /** How high the slab sits above the camera, as a share of the sky radius. */
    private static final float CLOUD_HEIGHT = 0.45F;

    /** Blocks per second the layer drifts, so it reads as weather rather than as terrain. */
    private static final float CLOUD_DRIFT = 1.6F;

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
        // The lobby is in this dimension but is not part of the match: its sky is always clear and
        // follows the overworld, so a waiting room never sits under the round's fixed weather.
        boolean lobby = Lobby.contains(level, BlockPos.containing(cam.x, cam.y, cam.z));
        Sky sky = Sky.of(level, lobby);
        float radius = radius(client);
        poses.pushPose();
        // Vertices are sent in world coordinates, so the camera offset is cancelled here.
        poses.translate(-cam.x, -cam.y, -cam.z);
        PvWorldGeometry.draw(frame, poses, PvRenderTypes.debugQuads(), (vc, matrix) -> {
            // Back to front: the dome, then the discs and stars at nine tenths of it, then the
            // cloud slab. Clouds last so a thin sheet dims the sun rather than hiding it.
            dome(vc, matrix, cam, radius, sky);
            discs(vc, matrix, cam, radius, sky);
            stars(vc, matrix, cam, radius, sky, seconds());
            clouds(vc, matrix, cam, radius, sky, seconds());
        });
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

    // ------------------------------------------------------------------ dome

    /** The inside of a sphere, as quads. Winding does not matter: the type has culling off. */
    private static void dome(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float radius, Sky sky) {
        for (int ring = 0; ring < RINGS; ring++) {
            double high = 1.0D - 2.0D * ring / RINGS;
            double low = 1.0D - 2.0D * (ring + 1) / RINGS;
            for (int seg = 0; seg < SEGMENTS; seg++) {
                double from = Math.PI * 2.0D * seg / SEGMENTS;
                double to = Math.PI * 2.0D * (seg + 1) / SEGMENTS;
                vertex(vc, matrix, cam, radius, high, from, sky);
                vertex(vc, matrix, cam, radius, high, to, sky);
                vertex(vc, matrix, cam, radius, low, to, sky);
                vertex(vc, matrix, cam, radius, low, from, sky);
            }
        }
    }

    /** Elevation runs from 1 straight up through 0 at the horizon to -1 straight down. */
    private static void vertex(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float radius,
                               double elevation, double theta, Sky sky) {
        double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - elevation * elevation)) * radius;
        float x = (float) (cam.x + Math.cos(theta) * horizontal);
        float y = (float) (cam.y + elevation * radius);
        float z = (float) (cam.z + Math.sin(theta) * horizontal);
        float t = (float) Math.pow(Math.abs(elevation), GRADIENT_POWER);
        boolean up = elevation >= 0.0D;

        float r = mix(sky.horizonR, up ? sky.upR : sky.downR, t);
        float g = mix(sky.horizonG, up ? sky.upG : sky.downG, t);
        float b = mix(sky.horizonB, up ? sky.upB : sky.downB, t);

        // The warm band is only where the sun is, and only near the horizon: a sunset all the way
        // round would be a ring, not a sunset.
        if (sky.dusk > 0.001F) {
            double facing = 0.5D + 0.5D * (Math.cos(theta) * sky.sunX + Math.sin(theta) * sky.sunZ);
            float glow = sky.dusk * (1.0F - t) * (float) (facing * facing);
            r = mix(r, DUSK_R, glow);
            g = mix(g, DUSK_G, glow);
            b = mix(b, DUSK_B, glow);
        }
        vc.addVertex(matrix, x, y, z).setColor(r, g, b, 1.0F);
    }

    // ------------------------------------------------------------------ clouds

    /**
     * The cloud layer: a grid of solid cells, each drawn as a bottom and the sides that face air.
     *
     * <p>This is the vanilla shape, not a painted sheet. A cell is {@link #CLOUD_CELL} blocks
     * across and {@link #CLOUD_THICKNESS} thick, so from below a cloud has an edge you can see
     * along and sides that catch a different shade; the layer drifts, which separates clouds from
     * terrain. Only the bottom and the exposed sides are built: the slab is always above the
     * camera, so its top is never in view.
     *
     * <p>Cells are hashed at a quarter resolution so they clump into blobs; the alpha fades out
     * near the dome, since a hard edge there would be a ring across the horizon.
     */
    private static void clouds(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float radius,
                               Sky sky, float seconds) {
        float bottom = (float) cam.y + radius * CLOUD_HEIGHT;
        float top = bottom + CLOUD_THICKNESS;
        float scroll = seconds * CLOUD_DRIFT;
        int reach = (int) (radius * 0.98F / CLOUD_CELL);
        int baseI = (int) Math.floor((cam.x + scroll) / CLOUD_CELL);
        int baseJ = (int) Math.floor(cam.z / CLOUD_CELL);
        float threshold = sky.overcast > 0.55F ? 0.40F : sky.overcast > 0.05F ? 0.48F : 0.62F;
        float dim = (1.0F - sky.overcast * 0.55F) * (0.24F + 0.76F * sky.day);
        float baseR = mix(0.98F, 0.62F, sky.overcast) * dim;
        float baseG = mix(0.99F, 0.64F, sky.overcast) * dim;
        float baseB = mix(1.0F, 0.68F, sky.overcast) * dim;
        // The underside is in the cloud's own shadow; the sides catch more of the light.
        float underShade = 0.86F;
        float sideShade = 0.94F;
        // Fair-weather cloud is thin: at full opacity it reads as a lid over the arena. A solid
        // overcast is the opposite case, and keeps the opacity it had.
        float opacity = clamp01(0.45F + sky.overcast * 2.0F);
        for (int di = -reach; di <= reach; di++) {
            for (int dj = -reach; dj <= reach; dj++) {
                int ci = baseI + di;
                int cj = baseJ + dj;
                if (!solid(ci, cj, threshold)) {
                    continue;
                }
                float x0 = ci * CLOUD_CELL - scroll;
                float z0 = cj * CLOUD_CELL;
                float x1 = x0 + CLOUD_CELL;
                float z1 = z0 + CLOUD_CELL;
                float cx = x0 + CLOUD_CELL * 0.5F;
                float cz = z0 + CLOUD_CELL * 0.5F;
                float dist = (float) Math.sqrt((cx - cam.x) * (cx - cam.x)
                        + (cz - cam.z) * (cz - cam.z));
                if (dist > radius * 0.98F) {
                    continue;
                }
                float alpha = smoothstep(0.98F, 0.62F, dist / radius) * opacity;
                if (alpha <= 0.01F) {
                    continue;
                }
                // Bottom face, seen from below.
                quad(vc, matrix, x0, bottom, z0, x1, bottom, z0, x1, bottom, z1, x0, bottom, z1,
                        baseR * underShade, baseG * underShade, baseB * underShade, alpha);
                // Sides, only where the neighbour is air.
                if (!solid(ci - 1, cj, threshold)) {
                    quad(vc, matrix, x0, bottom, z0, x0, bottom, z1, x0, top, z1, x0, top, z0,
                            baseR * sideShade, baseG * sideShade, baseB * sideShade, alpha);
                }
                if (!solid(ci + 1, cj, threshold)) {
                    quad(vc, matrix, x1, bottom, z1, x1, bottom, z0, x1, top, z0, x1, top, z1,
                            baseR * sideShade, baseG * sideShade, baseB * sideShade, alpha);
                }
                if (!solid(ci, cj - 1, threshold)) {
                    quad(vc, matrix, x1, bottom, z0, x0, bottom, z0, x0, top, z0, x1, top, z0,
                            baseR * sideShade, baseG * sideShade, baseB * sideShade, alpha);
                }
                if (!solid(ci, cj + 1, threshold)) {
                    quad(vc, matrix, x0, bottom, z1, x1, bottom, z1, x1, top, z1, x0, top, z1,
                            baseR * sideShade, baseG * sideShade, baseB * sideShade, alpha);
                }
            }
        }
    }

    /**
     * Whether one cloud cell is solid.
     *
     * <p>Three octaves: a coarse one that makes the blobs, a middle one that rags their edges, and
     * a per-cell one that breaks the grid. Sampled at quarter, half and full resolution.
     */
    private static boolean solid(int ci, int cj, float threshold) {
        float density = hash(ci / 4, cj / 4, 17) * 0.62F
                + hash(ci / 2, cj / 2, 91) * 0.28F
                + hash(ci, cj, 33) * 0.10F;
        return density >= threshold;
    }

    // ------------------------------------------------------------------ sun and moon

    /** The sun by day, the moon by night, and a soft halo around whichever is up. */
    private static void discs(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float radius, Sky sky) {
        // Weather hides both: an overcast sky has no disc in it.
        float visibility = 1.0F - sky.overcast * 0.92F;
        if (sky.sunY > -0.12F && visibility > 0.02F) {
            Vec3 sun = new Vec3(sky.sunX, sky.sunY, sky.sunZ);
            disc(vc, matrix, cam, radius * 0.90F, sun, radius * 0.075F,
                    1.0F, 0.95F, 0.82F, 0.22F * visibility * sky.day);
            disc(vc, matrix, cam, radius * 0.90F, sun, radius * 0.040F,
                    1.0F, 0.97F, 0.88F, visibility);
        }
        if (sky.sunY < 0.12F && visibility > 0.02F) {
            Vec3 moon = new Vec3(-sky.sunX, -sky.sunY, -sky.sunZ);
            disc(vc, matrix, cam, radius * 0.90F, moon, radius * 0.030F,
                    0.82F, 0.86F, 1.0F, 0.16F * visibility * (1.0F - sky.day));
            disc(vc, matrix, cam, radius * 0.90F, moon, radius * 0.032F,
                    0.88F, 0.91F, 1.0F, visibility * (1.0F - sky.day));
        }
    }

    /** A camera-facing square at {@code distance} along {@code dir}. */
    private static void disc(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float distance,
                             Vec3 dir, float size, float r, float g, float b, float alpha) {
        Vec3 up = Math.abs(dir.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = dir.cross(up).normalize();
        Vec3 top = right.cross(dir).normalize();
        Vec3 center = cam.add(dir.scale(distance));
        Vec3 a = center.add(right.scale(-size)).add(top.scale(-size));
        Vec3 b2 = center.add(right.scale(size)).add(top.scale(-size));
        Vec3 c = center.add(right.scale(size)).add(top.scale(size));
        Vec3 d = center.add(right.scale(-size)).add(top.scale(size));
        quad(vc, matrix, (float) a.x, (float) a.y, (float) a.z,
                (float) b2.x, (float) b2.y, (float) b2.z,
                (float) c.x, (float) c.y, (float) c.z,
                (float) d.x, (float) d.y, (float) d.z, r, g, b, alpha);
    }

    // ------------------------------------------------------------------ stars

    /**
     * A fixed field of stars in the upper hemisphere.
     *
     * <p>Directions come from a hash rather than a table, so the field is the same on every client
     * and costs no memory. They fade out with daylight and with cloud cover.
     */
    private static void stars(VertexConsumer vc, Matrix4f matrix, Vec3 cam, float radius, Sky sky,
                              float seconds) {
        float visibility = (1.0F - sky.day) * (1.0F - sky.overcast);
        if (visibility <= 0.02F) {
            return;
        }
        for (int i = 0; i < STARS; i++) {
            float theta = hash(i, 3, 41) * TAU;
            float elevation = 0.08F + hash(i, 7, 73) * 0.92F;
            float horizontal = (float) Math.sqrt(Math.max(0.0D, 1.0D - elevation * elevation));
            Vec3 dir = new Vec3(Math.cos(theta) * horizontal, elevation, Math.sin(theta) * horizontal);
            // Its own phase and rate, so the field shimmers rather than pulsing as one.
            float phase = hash(i, 17, 53) * TAU;
            float rate = 0.9F + hash(i, 19, 61) * 1.8F;
            float twinkle = 0.55F + 0.45F * (float) Math.sin(seconds * rate + phase);
            float alpha = visibility * (0.30F + 0.70F * hash(i, 11, 19)) * twinkle;
            float size = radius * (0.0016F + 0.0026F * hash(i, 13, 29));
            disc(vc, matrix, cam, radius * 0.92F, dir, size, 1.0F, 1.0F, 1.0F, alpha);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Everything the dome needs about the current sky, derived once per frame. */
    private static final class Sky {
        final float sunX;
        final float sunY;
        final float sunZ;
        final float day;
        final float dusk;
        final float overcast;
        final float upR;
        final float upG;
        final float upB;
        final float horizonR;
        final float horizonG;
        final float horizonB;
        final float downR;
        final float downG;
        final float downB;

        private Sky(float sunX, float sunY, float sunZ, float day, float dusk, float overcast) {
            this.sunX = sunX;
            this.sunY = sunY;
            this.sunZ = sunZ;
            this.day = day;
            this.dusk = dusk;
            this.overcast = overcast;
            this.upR = mix(mix(NIGHT_UP_R, DAY_UP_R, day), OVERCAST_UP_R, overcast);
            this.upG = mix(mix(NIGHT_UP_G, DAY_UP_G, day), OVERCAST_UP_G, overcast);
            this.upB = mix(mix(NIGHT_UP_B, DAY_UP_B, day), OVERCAST_UP_B, overcast);
            this.horizonR = mix(mix(NIGHT_HORIZON_R, DAY_HORIZON_R, day),
                    OVERCAST_HORIZON_R, overcast);
            this.horizonG = mix(mix(NIGHT_HORIZON_G, DAY_HORIZON_G, day),
                    OVERCAST_HORIZON_G, overcast);
            this.horizonB = mix(mix(NIGHT_HORIZON_B, DAY_HORIZON_B, day),
                    OVERCAST_HORIZON_B, overcast);
            this.downR = mix(mix(NIGHT_DOWN_R, DAY_DOWN_R, day), OVERCAST_DOWN_R, overcast);
            this.downG = mix(mix(NIGHT_DOWN_G, DAY_DOWN_G, day), OVERCAST_DOWN_G, overcast);
            this.downB = mix(mix(NIGHT_DOWN_B, DAY_DOWN_B, day), OVERCAST_DOWN_B, overcast);
        }

        static Sky of(ClientLevel level, boolean lobby) {
            // 0 at sunrise, 0.25 at noon, 0.5 at dusk, 0.75 at midnight. The server freezes the
            // clock for a fixed e.timeOfDay, so these are the four fixed settings as well.
            long dayTime = lobby ? OverworldClock.now(dayTimeOf(level)) : dayTimeOf(level);
            float time = (float) ((dayTime % 24000L) / 24000.0D);
            float angle = time * TAU;
            float sunY = (float) Math.sin(angle);
            // A slight tilt keeps the arc off the exact east-west line, which reads as a wall.
            Vec3 sun = new Vec3(Math.cos(angle), sunY, 0.28D).normalize();
            float day = daylight(dayTime);
            // Twilight peaks while the sun crosses the horizon, and is gone by mid-morning.
            float dusk = clamp01(1.0F - Math.abs(sunY) / 0.22F) * (1.0F - day * 0.35F);
            boolean raining = !lobby && level.isRaining();
            boolean thundering = !lobby && level.isThundering();
            float overcast = thundering ? 0.85F : raining ? 0.55F : 0.0F;
            return new Sky((float) sun.x, (float) sun.y, (float) sun.z, day, dusk, overcast);
        }
    }

    /**
     * Wall-clock seconds for the twinkle phase.
     *
     * <p>Not the game time: the arena's clock can be frozen by {@code e.timeOfDay}, and a frozen
     * clock would freeze the stars too.
     */
    private static float seconds() {
        // nanoTime rather than a wall clock with a modulo: the cloud drift multiplies this, and a
        // wrap would slide the whole layer a few thousand blocks in one frame.
        return (float) (System.nanoTime() / 1.0E9D);
    }

    /**
     * How much daylight the given clock time carries: 1 at noon, 0 at midnight.
     *
     * <p>Shared with {@link LobbyLight}, so the lobby's sky and the lobby's light cannot disagree.
     */
    static float daylight(long dayTime) {
        float time = (float) ((dayTime % 24000L) / 24000.0D);
        return smoothstep(-0.12F, 0.18F, (float) Math.sin(time * TAU));
    }

    /**
     * The dimension's day time in ticks.
     *
     * <p>26.x keeps it on the overworld clock, which is the one {@code PvCompat#setDayTime} writes;
     * 1.21.x keeps it on the level.
     */
    static long dayTimeOf(ClientLevel level) {
//? if >=26.1 {
        return level.getOverworldClockTime();
//?} else {
        return level.getDayTime();
//?}
    }

    private static void quad(VertexConsumer vc, Matrix4f matrix,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4,
                             float r, float g, float b, float a) {
        vc.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(matrix, x4, y4, z4).setColor(r, g, b, a);
    }

    /** Deterministic noise in [0,1); the same field on every client and every frame. */
    private static float hash(int a, int b, int salt) {
        int h = a * 374761393 + b * 668265263 + salt * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 65536.0F;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
    }

    private static float mix(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
