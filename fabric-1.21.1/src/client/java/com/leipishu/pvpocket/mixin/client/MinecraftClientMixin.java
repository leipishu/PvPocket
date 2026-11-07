package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.BlockingInput;
import com.leipishu.pvpocket.client.PvPocketClient;
import com.leipishu.pvpocket.combat.CombatState;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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

    /**
     * Dev-only: pretend this client is holding a sword block, so the pose can be inspected without
     * setting up a legacy duel. Started with {@code -PdebugBlocking}, which passes
     * {@code -Dpvpocket.debug.blocking=true}.
     *
     * <p>Set on the local mirror and deliberately <em>not</em> sent: outside a legacy round the
     * server refuses the request and echoes the refusal back, so a forced flag that was also sent
     * would be cleared again on the next tick. That is also why this is a state override rather
     * than a render override — the previous debug switch forced the pose for every sword in the
     * world, which made normal holding look broken.</p>
     */
    private static final boolean DEBUG_BLOCKING = Boolean.getBoolean("pvpocket.debug.blocking");

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
        if (DEBUG_BLOCKING && sword) {
            // Held locally only. BlockingInput is reset rather than told "false" so nothing is sent
            // and the server cannot answer with a state that clears this again.
            CombatState.setBlocking(client.player.getUuid(), true);
            BlockingInput.reset();
            return;
        }
        boolean blocking = key
                && sword
                && !client.player.isSpectator()
                && legacy;
        if (key) {
            logGate(key, sword, legacy, blocking);
        }
        BlockingInput.update(blocking);
    }

    /**
     * A whiff costs no time in 1.8; in 1.21.1 it costs half a second.
     *
     * <p>{@code doAttack} sets {@code attackCooldown = 10} whenever the crosshair is on nothing and
     * {@code interactionManager.hasLimitedAttackSpeed()} is true — which it always is in survival.
     * That counter gates {@code doAttack} itself, so after every miss the next click is swallowed for
     * ten ticks. 1.8 has no equivalent: its {@code leftClickCounter} is only ever set by clicking a
     * block, never by swinging at air.</p>
     *
     * <p>This is the single largest difference in the rhythm of a trade. In 1.8 you can swing, miss,
     * and swing again on the next tick; in 1.21.1 the second swing never happens, and because the
     * counter is invisible the game reads as "my clicks are being dropped".</p>
     *
     * <p>Returning false suppresses the penalty without touching the counter itself, so block
     * breaking (which reads the same field) keeps its normal cadence.</p>
     */
    @ModifyExpressionValue(method = "doAttack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;"
                            + "hasLimitedAttackSpeed()Z"))
    private boolean pvpocket$noMissLockout(boolean original) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        return original && (client.player == null || !CombatState.isLegacy(client.player.getUuid()));
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
