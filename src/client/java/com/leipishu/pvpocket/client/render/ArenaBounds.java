package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.network.ArenaBoundsPayload;

/**
 * Client-side copy of the plot outline the server is currently enforcing.
 *
 * <p>A plain holder, so the renderer stays a stateless callback and the network receiver has one
 * place to write. Both run on the client thread, so no synchronisation is needed.
 *
 * <p>Call {@link #clear()} on disconnect, or a stale box would be drawn in the next world.
 */
public final class ArenaBounds {

    private static ArenaBoundsPayload current = ArenaBoundsPayload.hidden();

    private ArenaBounds() {
    }

    public static void update(ArenaBoundsPayload payload) {
        current = payload;
    }

    public static void clear() {
        current = ArenaBoundsPayload.hidden();
    }

    public static boolean active() {
        return current.active();
    }

    public static ArenaBoundsPayload get() {
        return current;
    }
}
