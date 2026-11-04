package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.network.ArenaBoundsPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side copy of the plot outline the server is currently enforcing.
 *
 * <p>A plain holder rather than a field on the renderer, so the renderer can be a stateless callback
 * registered once and the network receiver has somewhere obvious to write. Both run on the client
 * thread — the receiver hops there through {@code client.execute} — so no synchronisation is needed,
 * and adding some would only hide a threading mistake if one ever appeared.</p>
 *
 * <p>{@link #clear()} is called on disconnect. A stale box would otherwise be drawn in the next world
 * the player joins, at coordinates that mean nothing there.</p>
 */
@Environment(EnvType.CLIENT)
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
