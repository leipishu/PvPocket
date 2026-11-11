package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.network.BlockingPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Tracks whether the local player is holding a sword block and tells the server.
 *
 * <p>The server cannot see a mouse button, so this flag exists only because the client said so. That
 * makes the edge-triggered version — send on change, never again — fragile in a way that has no
 * recovery: one lost or mistimed update leaves the server believing the sword is down until the
 * player releases and presses again, and from the player's side that is indistinguishable from
 * "blocking does not work". A periodic re-send costs one byte per second while a sword is raised and
 * is idempotent on the far end, which only writes when the value actually changes.</p>
 */
public final class BlockingInput {

    /** Ticks between re-sends of an unchanged "still blocking". */
    private static final int REFRESH_TICKS = 20;

    private static boolean last;
    private static int sinceSend;

    private BlockingInput() {
    }

    public static void update(boolean blocking) {
        if (blocking == last) {
            // Nothing changed: either keep quiet (sword down) or send a heartbeat (sword up).
            if (!blocking || ++sinceSend < REFRESH_TICKS) {
                return;
            }
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            last = false;
            sinceSend = 0;
            return;
        }
        last = blocking;
        sinceSend = 0;
        ClientPlayNetworking.send(new BlockingPayload(blocking));
    }

    /** Called when leaving a world so the next join starts from a clean state. */
    public static void reset() {
        last = false;
        sinceSend = 0;
    }
}
