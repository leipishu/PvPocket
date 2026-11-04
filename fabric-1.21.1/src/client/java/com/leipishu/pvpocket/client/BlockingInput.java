package com.leipishu.pvpocket.client;

import com.leipishu.pvpocket.network.BlockingPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * Tracks whether the local player is holding a sword block and only sends a packet when the state
 * flips. Sending every tick would be wasteful; sending on change keeps the server view accurate to
 * within one tick.
 */
public final class BlockingInput {

    private static boolean last;

    private BlockingInput() {
    }

    public static void update(boolean blocking) {
        if (blocking == last) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            last = false;
            return;
        }
        last = blocking;
        ClientPlayNetworking.send(new BlockingPayload(blocking));
    }

    /** Called when leaving a world so the next join starts from a clean state. */
    public static void reset() {
        last = false;
    }
}
