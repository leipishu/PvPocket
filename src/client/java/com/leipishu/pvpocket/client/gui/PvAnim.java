package com.leipishu.pvpocket.client.gui;

/**
 * Animation helpers: tick-to-second conversion, exponential damping and three easings.
 *
 * <p>Every value here is frame-rate independent. {@link #dt(float)} converts the tick delta that
 * screens receive into seconds, at 50 ms per tick.
 *
 * <p>{@link #damp} approaches its target exponentially, so a target that changes mid-flight
 * retargets without a discontinuity.
 */
public final class PvAnim {

    /** Longest frame step the animation layer will honour, in seconds. */
    private static final float MAX_STEP = 0.1F;

    private PvAnim() {
    }

    /**
     * Converts the screen's tick delta into seconds.
     *
     * <p>The result is clamped to {@code [0, 0.1]}. A large step, such as one after a stall, would
     * otherwise move every animation to its target in one frame.
     */
    public static float dt(float delta) {
        if (!PvTheme.MOTION) {
            return 1F;
        }
        return Math.max(0F, Math.min(MAX_STEP, delta * 0.05F));
    }

    /**
     * Exponential approach towards {@code target}.
     *
     * <p>{@code tau} is the time constant: after that many seconds the value has covered about 63%
     * of the remaining distance. Callers pass the time to "looks done" divided by three.
     */
    public static float damp(float current, float target, float dt, float tau) {
        if (!PvTheme.MOTION) {
            return target;
        }
        if (dt <= 0F) {
            return current;
        }
        if (tau <= 0F) {
            return target;
        }
        float k = 1F - (float) Math.exp(-dt / tau);
        float next = current + (target - current) * k;
        // Snap when the remainder is below one device pixel of the animated property, so a hover
        // does not leave a control a fraction of the way between states.
        return Math.abs(target - next) < 0.002F ? target : next;
    }

    public static float clamp01(float t) {
        return t < 0F ? 0F : (t > 1F ? 1F : t);
    }

    public static float easeOutCubic(float t) {
        float u = 1F - clamp01(t);
        return 1F - u * u * u;
    }

    public static float easeInOutQuad(float t) {
        t = clamp01(t);
        return t < 0.5F ? 2F * t * t : 1F - (float) Math.pow(-2F * t + 2F, 2F) / 2F;
    }

    /**
     * Easing that overshoots by about 10% before settling.
     *
     * <p>Used for the switch knob, where a 10% overshoot on a 14px knob is under two pixels.
     */
    public static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.4F;
        float c3 = c1 + 1F;
        float u = t - 1F;
        return 1F + c3 * u * u * u + c1 * u * u;
    }
}
