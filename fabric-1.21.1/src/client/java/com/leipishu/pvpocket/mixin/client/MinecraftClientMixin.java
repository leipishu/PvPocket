package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.client.BlockingInput;
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
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void pvpocket$blockingInput(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null || client.getNetworkHandler() == null) {
            BlockingInput.reset();
            return;
        }
        boolean blocking = client.options.useKey.isPressed()
                && client.player.getMainHandStack().getItem() instanceof SwordItem
                && !client.player.isSpectator()
                && CombatState.isLegacy(client.player.getUuid());
        BlockingInput.update(blocking);
    }
}
