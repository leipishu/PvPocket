package com.leipishu.pvpocket.client.preset;

import com.leipishu.pvpocket.network.PresetSharePayload;
import com.leipishu.pvpocket.network.PresetShareResultPayload;
import com.leipishu.pvpocket.preset.PresetKind;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.function.Consumer;

/**
 * The client half of the share-code exchange.
 *
 * <p>A single listener, not a list. Only one preset dialog can be open at a time, so a registry of
 * subscribers would exist to be unsubscribed from — and the failure mode of forgetting to unsubscribe
 * (a closed dialog reacting to a packet meant for its replacement) is exactly the kind of bug that
 * only shows up when a player opens the dialog twice quickly.</p>
 *
 * <p>Requests are best-effort: if the server does not know the channel, nothing is sent and the
 * dialog says so rather than waiting forever for an answer that was never asked for.</p>
 */
public final class PresetShare {

    private static Consumer<PresetShareResultPayload> listener;

    private PresetShare() {
    }

    /** Registers the dialog that will receive answers; {@code null} clears it. */
    public static void listener(Consumer<PresetShareResultPayload> callback) {
        listener = callback;
    }

    public static boolean publish(PresetKind kind, String name, String encoded) {
        if (!ClientPlayNetworking.canSend(PresetSharePayload.ID)) {
            return false;
        }
        ClientPlayNetworking.send(PresetSharePayload.publish(kind.id, name, encoded));
        return true;
    }

    public static boolean fetch(String code) {
        if (!ClientPlayNetworking.canSend(PresetSharePayload.ID)) {
            return false;
        }
        ClientPlayNetworking.send(PresetSharePayload.fetch(code));
        return true;
    }

    /** Called from the network handler on the client thread. */
    public static void receive(PresetShareResultPayload payload) {
        Consumer<PresetShareResultPayload> current = listener;
        if (current != null) {
            current.accept(payload);
        }
    }
}
