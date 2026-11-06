package com.leipishu.pvpocket.client.gui;

/**
 * The animation layer: one clock, one damping function, three easings.
 *
 * <p>Everything here is frame-rate independent on purpose. A tween driven by "add 0.1 per frame"
 * looks calm at 30 fps and frantic at 240 fps, and a player who upgrades their GPU should not get a
 * different interface. The conversion is done once, in {@link #dt(float)}: Minecraft hands screens a
 * delta in ticks, and one tick is 50 ms.</p>
 *
 * <p>{@link #damp} is an exponential approach rather than a fixed-duration tween. That is the right
 * shape for hover and press feedback: the target can change mid-flight — the mouse can leave before
 * the fade finishes — and an exponential approach retargets without a discontinuity, whereas a
 * tween has to be restarted from wherever it happened to be.</p>
 */
public final class PvAnim {

    /** Longest frame step the animation layer will honour, in seconds. */
    private static final float MAX_STEP = 0.1F;

    private PvAnim() {
    }

    /**
     * Converts the screen's tick delta into seconds.
     *
     * <p>Clamped at both ends. A zero step happens on the frame a screen opens; a large one happens
     * after a stall (chunk load, alt-tab), and letting that through would teleport every animation
     * to its target in one frame — visible as a flash rather than as a fade.</p>
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
     * <p>{@code tau} is the time constant: after that many seconds the value has covered ~63% of
     * the remaining distance, and after 3τ it is visually finished. Callers pass the time to
     * "looks done", divided by three.</p>
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
        // Snap when the remainder is below one device pixel's worth of the animated property, so a
        // hover does not leave a control permanently one ten-thousandth of the way between states.
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
     * Overshoots by ~10% before settling.
     *
     * <p>Only the switch knob uses it. A 10% overshoot on a 14px knob is under two pixels, which
     * reads as weight; the same curve on a panel would read as a bounce, which is not this product's
     * voice.</p>
     */
    public static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.4F;
        float c3 = c1 + 1F;
        float u = t - 1F;
        return 1F + c3 * u * u * u + c1 * u * u;
    }
}
