package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.BlockingInput;
import com.leipishu.pvpocket.client.PvPocketClient;
import com.leipishu.pvpocket.client.hud.MatchHud;
import com.leipishu.pvpocket.combat.CombatState;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.tags.ItemTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns "right mouse held with a sword" into a blocking intent.
 *
 * <p>Hooks {@code Minecraft.tick} to read the use key and send the intent, and
 * {@code Minecraft.startAttack} to drop the 1.21 miss delay in a legacy round.
 *
 * <p>The rule-set check here is an optimisation; the server re-validates every request.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    /** Last logged (key, sword, legacy) triple, packed. -1 so the first observation always logs. */
    private static int lastGate = -1;

    /**
     * Dev-only: forces the local mirror to report a sword block. Started with
     * {@code -PdebugBlocking}, which passes {@code -Dpvpocket.debug.blocking=true}.
     *
     * <p>Set on the mirror only and never sent; outside a legacy round the server refuses the
     * request and clears it again.
     */
    private static final boolean DEBUG_BLOCKING = Boolean.getBoolean("pvpocket.debug.blocking");

    /** Applies a payload that arrived before the player existed. Hooked at {@code tick} HEAD. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void pvpocket$applyPendingState(CallbackInfo ci) {
        PvPocketClient.applyPendingState();
    }

    /**
     * Suppresses the death screen during a match and respawns immediately.
     *
     * <p>Hooks {@code Minecraft.setScreen}: a {@link DeathScreen} is cancelled and a respawn
     * request is sent instead.
     *
     * <p>Gated on the match HUD rather than the arena dimension, because spectators also stand in
     * that dimension and should still see the screen.
     */
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void pvpocket$skipArenaDeathScreen(Screen screen, CallbackInfo ci) {
        if (!(screen instanceof DeathScreen)) {
            return;
        }
        Minecraft client = (Minecraft) (Object) this;
        if (client.player == null || client.getConnection() == null || !MatchHud.isActive()) {
            return;
        }
        client.getConnection().send(new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void pvpocket$blockingInput(CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.player == null || client.getConnection() == null) {
            BlockingInput.reset();
            return;
        }
        boolean key = client.options.keyUse.isDown();
        boolean sword = client.player.getMainHandItem().is(ItemTags.SWORDS);
        boolean legacy = CombatState.isLegacy(client.player.getUUID());
        if (DEBUG_BLOCKING && sword) {
            // Held locally only; resetting keeps the value off the wire.
            CombatState.setBlocking(client.player.getUUID(), true);
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
     * Removes the 1.21 miss delay in a legacy round.
     *
     * <p>Hooks {@code MultiPlayerGameMode.hasMissTime()} inside {@code Minecraft.startAttack}.
     * Returning false suppresses the 10-tick attack cooldown that a miss sets in survival; block
     * breaking reads the same field and keeps its normal cadence.
     */
    @ModifyExpressionValue(method = "startAttack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;"
                            + "hasMissTime()Z"))
    private boolean pvpocket$noMissLockout(boolean original) {
        Minecraft client = (Minecraft) (Object) this;
        return original && (client.player == null || !CombatState.isLegacy(client.player.getUUID()));
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
