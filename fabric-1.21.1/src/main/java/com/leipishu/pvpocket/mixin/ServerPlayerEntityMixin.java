package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.loadout.LoadoutLock;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁止丢弃: the Q key refuses to throw a locked loadout item out of the hand.
 *
 * <p>{@code dropSelectedItem} is the whole of the no-container-open path: {@code
 * ServerPlayNetworkHandler.onPlayerAction} calls it for both {@code DROP_ITEM} (one) and {@code
 * DROP_ALL_ITEMS} (the Ctrl variant), and it is the method that both empties the inventory slot and
 * spawns the item entity. Cancelling at its head therefore leaves the stack where it was — nothing to
 * undo afterwards, which cancelling a later step would have required.</p>
 *
 * <p>Returning {@code false} is the honest answer to "did anything drop": the caller ignores it, but
 * a future caller reading the boolean should not be told a drop happened.</p>
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noDropLocked(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (LoadoutLock.isLocked(self.getMainHandStack())) {
            SessionManager.reportDropDenial(self);
            cir.setReturnValue(false);
        }
    }
}
