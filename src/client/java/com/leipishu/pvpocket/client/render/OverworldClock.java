package com.leipishu.pvpocket.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/**
 * The overworld's clock, sampled while the client is there.
 *
 * <p>The lobby sits in the arena dimension, whose clock the match pins to a fixed time. A waiting
 * room fixed at midnight is the one place that setting should not reach, so the lobby's sky follows
 * the overworld instead. The client cannot read another dimension's clock, so it keeps the last
 * overworld sample and runs it forward at twenty ticks a second.
 *
 * <p>Sampled every tick while in the overworld and nowhere else: the nether and the end have their
 * own clocks, and a sample from one of those would be a wrong time rather than a stale one.
 */
public final class OverworldClock {

    private static final long MILLIS_PER_TICK = 50L;

    private static long sampleTime;
    private static long sampleAt;
    private static boolean sampled;

    private OverworldClock() {
    }

    /** Takes a sample when the player is in the overworld. Called from the client tick. */
    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || !level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        sampleTime = ArenaSky.dayTimeOf(level);
        sampleAt = System.currentTimeMillis();
        sampled = true;
    }

    /**
     * The overworld's day time now, or {@code fallback} when there was never a sample.
     *
     * <p>The fallback is the arena's own clock: a player who reconnected straight into a match has
     * not been to the overworld this session, and the arena's time is then the only one there is.
     */
    public static long now(long fallback) {
        if (!sampled) {
            return fallback;
        }
        return sampleTime + (System.currentTimeMillis() - sampleAt) / MILLIS_PER_TICK;
    }
}
