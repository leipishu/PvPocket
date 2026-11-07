package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.BlockingInput;
import com.leipishu.pvpocket.client.PvPocketClient;
import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.SwordItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns "right mouse held with a sword" into a blocking intent.
 *
 * <p>Vanilla never enters a use state for swords, so the server would have no way to know the key
 * is held. The client is the only place that information exists, hence this hook.</p>
 *
 * <p>The rule-set check here is an optimisation, not a security boundary: the server re-validates
 * every request. Skipping the packet for players who are not in a legacy round keeps a busy server
 * from carrying a stream of pointless payloads from everyone holding right-click.</p>
 *
 * <p>Every term of that gate is a way for the feature to fail silently, and all of them look the same
 * from inside the game — "right-click does nothing". A single line per change of the three bits is
 * therefore kept at INFO: it is the only way to tell "the request never left" from "the server
 * refused it" (the server logs the other half).</p>
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    /** Last logged (key, sword, legacy) triple, packed. -1 so the first observation always logs. */
    private static int lastGate = -1;

    /** A payload that arrived before the player existed is applied here, before anything reads it. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void pvpocket$applyPendingState(CallbackInfo ci) {
        PvPocketClient.applyPendingState();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void pvpocket$blockingInput(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null || client.getNetworkHandler() == null) {
            BlockingInput.reset();
            return;
        }
        boolean key = client.options.useKey.isPressed();
        boolean sword = client.player.getMainHandStack().getItem() instanceof SwordItem;
        boolean legacy = CombatState.isLegacy(client.player.getUuid());
        boolean blocking = key
                && sword
                && !client.player.isSpectator()
                && legacy;
        if (key) {
            logGate(key, sword, legacy, blocking);
        }
        BlockingInput.update(blocking);
    }

    private static void logGate(boolean key, boolean sword, boolean legacy, boolean blocking) {
        int gate = (key ? 1 : 0) | (sword ? 2 : 0) | (legacy ? 4 : 0);
        if (gate == lastGate) {
            return;
        }
        lastGate = gate;
        PvPocket.LOGGER.info("Block input: key={} sword={} legacy={} -> blocking={}",
                key, sword, legacy, blocking);
    }
}
