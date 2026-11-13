package com.leipishu.pvpocket.client.render;

import com.leipishu.pvpocket.network.PrepareScreensPayload;

import java.util.List;

/**
 * Client-side copy of the screens standing in front of the sides during the countdown.
 *
 * <p>A plain holder next to {@link ArenaBounds}, written from the client thread by the network
 * receiver, so no synchronisation is needed.
 *
 * <p>Separate from the bounds because the lifetimes differ: the bounds outlive a round, the screens
 * exist for the countdown only.
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
