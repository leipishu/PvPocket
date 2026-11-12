package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.network.PrepareScreensPayload;

import java.util.List;

/**
 * Client-side copy of the screens standing in front of the sides during the countdown.
 *
 * <p>A plain holder next to {@link ArenaBounds} for the same reason: the renderer is a stateless
 * callback registered once, so the network receiver needs somewhere obvious to write. Both run on
 * the client thread — the receiver hops there through {@code client.execute} — so no
 * synchronisation is needed.</p>
 *
 * <p>Kept separate from the bounds because the two have different lifetimes. The bounds outlive a
 * round; the screens exist for the countdown only, and the round clears them the moment it goes
 * live. Folding them into one payload would mean the boundary packet had to be re-sent to change
 * something that is not the boundary.</p>
 */
public final class PrepareScreens {

    private static List<PrepareScreensPayload.Screen> current = List.of();

    private PrepareScreens() {
    }

    public static void update(PrepareScreensPayload payload) {
        current = payload.screens();
    }

    public static void clear() {
        current = List.of();
    }

    public static List<PrepareScreensPayload.Screen> get() {
        return current;
    }
}
