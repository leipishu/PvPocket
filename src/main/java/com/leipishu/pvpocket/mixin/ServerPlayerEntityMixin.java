package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.loadout.LoadoutLock;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses to throw a locked loadout item out of the hand.
 *
 * <p>Hooks {@code drop(Z)Z} at HEAD. {@code ServerPlayNetworkHandler.onPlayerAction} calls it for
 * both {@code DROP_ITEM} and {@code DROP_ALL_ITEMS}, and it both empties the slot and spawns the
 * item entity, so cancelling here leaves the stack where it was.
 *
 * <p>Returns {@code false} because nothing dropped.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noDropLocked(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (LoadoutLock.isLocked(self.getMainHandItem())) {
            SessionManager.reportDropDenial(self);
            cir.setReturnValue(false);
        }
    }
}
