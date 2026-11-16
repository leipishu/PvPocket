package com.leipishu.pvpocket.client.gui.prepare;

import com.leipishu.pvpocket.client.compat.PvScreens;
import net.minecraft.client.Minecraft;

/**
 * Arms the preparing notice, and opens it only once a start has taken longer than a blink.
 *
 * <p>The server announces the start before it leases, builds or loads anything, so a warm start is
 * over before {@link #DELAY_MS} elapses and the panel would flash for a frame on its way to the
 * dimension load. A start that stalls, on the other hand, leaves the player with no answer to their
 * click, and that wait is what the panel is for.
 *
 * <p>The clock is wall time, not ticks: a server thread blocked in a cell scan cannot deliver the
 * tick that a tick-based timer would need, and the point is to show something while it is blocked.
 */
public final class PrepareNotice {

    /** How long a start may take before the player is told that it is under way. */
    private static final long DELAY_MS = 200L;

    /** Session whose notice is armed, or null. Written and read on the client thread only. */
    private static String session;
    private static long showAt;

    private PrepareNotice() {
    }

    /** Starts the clock for a session. A second announcement replaces the first. */
    public static void arm(String sessionId) {
        session = sessionId == null ? "" : sessionId;
        showAt = System.currentTimeMillis() + DELAY_MS;
    }

    /** Drops a pending notice: the round started before it was due. */
    public static void cancel(String sessionId) {
        if (session == null || session.equals(sessionId)) {
            session = null;
        }
    }

    /** Drops any pending notice, on disconnect: the session it belonged to is gone. */
    public static void clear() {
        session = null;
    }

    /** Called every client tick; opens the panel once the delay has passed. */
    public static void tick() {
        if (session == null || System.currentTimeMillis() < showAt) {
            return;
        }
        String due = session;
        session = null;
        PvScreens.open(Minecraft.getInstance(), new PrepareScreen(due));
    }
}
