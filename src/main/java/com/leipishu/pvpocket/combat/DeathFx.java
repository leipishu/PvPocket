package com.leipishu.pvpocket.combat;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The particle send-off painted where a duel participant falls.
 *
 * <p>Server-side only. {@code sendParticles} broadcasts to every nearby player, so the effect needs
 * no packet and no client hook. The three tiers add layers rather than more of a single dot: a
 * shock ring, a bright core, rising flame and outward sparks. The top tier adds a ghost drift and a
 * glow cloud so the burst stays legible when the client runs a high particle setting.
 */
public final class DeathFx {

    /** No burst: the drop is still cancelled, the fall is simply quiet. */
    public static final int OFF = -1;

    private DeathFx() {
    }

    /**
     * Paints the burst at a fall position in the given level.
     *
     * @param level the server level the player died in
     *
     * @param at the fall position
     *
     * @param tier 0 subtle, 1 normal, 2 grand; a negative tier draws nothing
     */
    public static void spawn(ServerLevel level, Vec3 at, int tier) {
        if (tier < 0) {
            return;
        }
        double x = at.x;
        double y = at.y;
        double z = at.z;
        int core = tier == 2 ? 2 : 1;
        level.sendParticles(ParticleTypes.SONIC_BOOM, x, y + 0.5D, z, core,
                0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.5D, z, core,
                0.0D, 0.0D, 0.0D, 0.0D);
        if (tier >= 1) {
            level.sendParticles(ParticleTypes.END_ROD, x, y + 0.6D, z, tier == 2 ? 48 : 24,
                    0.9D, 0.6D, 0.9D, 0.18D);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.2D, z, tier == 2 ? 32 : 16,
                    0.35D, 1.2D, 0.35D, 0.02D);
        }
        if (tier == 2) {
            level.sendParticles(ParticleTypes.SCULK_SOUL, x, y + 0.3D, z, 24,
                    0.4D, 1.4D, 0.4D, 0.04D);
            level.sendParticles(ParticleTypes.GLOW, x, y + 0.5D, z, 40,
                    0.8D, 0.8D, 0.8D, 0.01D);
        }
    }
}
