package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.network.BlockingPayload;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;

/**
 * Tracks whether the local player is holding a sword block and tells the server.
 *
 * <p>The server cannot see a mouse button, so the client owns this flag. An update is sent when the
 * value changes and re-sent every {@link #REFRESH_TICKS} ticks while the sword stays raised, so one
 * lost packet does not leave the server blocking until the next press.
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
        PvClientPlatform.get().send(new BlockingPayload(blocking));
    }

    /** Called when leaving a world so the next join starts from a clean state. */
    public static void reset() {
        last = false;
        sinceSend = 0;
    }
}
