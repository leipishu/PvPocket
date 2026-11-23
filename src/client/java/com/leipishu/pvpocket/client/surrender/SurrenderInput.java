package com.leipishu.pvpocket.client.surrender;

import com.leipishu.pvpocket.client.compat.PvKeys;
import com.leipishu.pvpocket.client.hud.SurrenderHud;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.network.SurrenderAnswerPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The two keys a team-mate needs while a surrender request is open.
 *
 * <p>{@code F6} agrees and {@code F7} refuses; both are unbound in vanilla, and both are listed on
 * the mod's settings page and on the vanilla controls page. A press is only sent while the prompt
 * is up, so a stray key cannot answer a request the player has not seen.
 *
 * <p>Nothing here decides the outcome: the server owns the request, and these are intents.
 */
public final class SurrenderInput {

    private static KeyMapping approve;
    private static KeyMapping decline;

    private SurrenderInput() {
    }

    /** Both keys, for the settings page. */
    public static List<KeyMapping> bindings() {
        if (approve == null || decline == null) {
            return List.of();
        }
        return List.of(approve, decline);
    }

    /** The bound key of the "agree" action, for the prompt's own label. */
    public static String approveLabel() {
        return label(approve);
    }

    /** The bound key of the "refuse" action, for the prompt's own label. */
    public static String declineLabel() {
        return label(decline);
    }

    public static void register() {
        approve = PvKeys.key("key.pvpocket.surrender_approve", GLFW.GLFW_KEY_F6);
        decline = PvKeys.key("key.pvpocket.surrender_decline", GLFW.GLFW_KEY_F7);
        // Registering lists the keys in the controls page; the tick poll below fires them.
        PvClientPlatform.get().registerKeyMapping(approve);
        PvClientPlatform.get().registerKeyMapping(decline);

        PvClientPlatform.get().onClientTick(() -> {
            Minecraft client = Minecraft.getInstance();
            // Drained in a loop, so two presses inside one tick are two intents.
            while (approve.consumeClick()) {
                answer(client, true);
            }
            while (decline.consumeClick()) {
                answer(client, false);
            }
        });
    }

    private static void answer(Minecraft client, boolean approveRequest) {
        if (client.player == null || !SurrenderHud.active()) {
            return;
        }
        if (PvClientPlatform.get().canSend(SurrenderAnswerPayload.ID)) {
            PvClientPlatform.get().send(new SurrenderAnswerPayload(approveRequest));
        }
        // The prompt stays until the server answers; a second press is harmless there.
    }

    private static String label(KeyMapping mapping) {
        if (mapping == null || mapping.isUnbound()) {
            return "?";
        }
        return mapping.getTranslatedKeyMessage().getString();
    }
}
