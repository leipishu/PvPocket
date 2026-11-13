package com.leipishu.pvpocket.client.preset;

import com.leipishu.pvpocket.network.PresetSharePayload;
import com.leipishu.pvpocket.network.PresetShareResultPayload;
import com.leipishu.pvpocket.preset.PresetKind;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;

import java.util.function.Consumer;

/**
 * The client half of the share-code exchange.
 *
 * <p>A single listener, not a list: only one preset dialog can be open at a time.
 *
 * <p>Requests are best-effort. When the server does not know the channel, nothing is sent and the
 * dialog says so.
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
        if (!PvClientPlatform.get().canSend(PresetSharePayload.ID)) {
            return false;
        }
        PvClientPlatform.get().send(PresetSharePayload.publish(kind.id, name, encoded));
        return true;
    }

    public static boolean fetch(String code) {
        if (!PvClientPlatform.get().canSend(PresetSharePayload.ID)) {
            return false;
        }
        PvClientPlatform.get().send(PresetSharePayload.fetch(code));
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
