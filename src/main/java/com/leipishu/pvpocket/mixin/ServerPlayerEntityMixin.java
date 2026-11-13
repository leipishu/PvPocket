package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.loadout.LoadoutLock;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses to throw a locked loadout item out of the hand.
 *
 * <p>Hooks {@code drop(boolean)} at HEAD. {@code ServerPlayNetworkHandler.onPlayerAction} calls it
 * for both {@code DROP_ITEM} and {@code DROP_ALL_ITEMS}, and it both empties the slot and spawns
 * the item entity, so cancelling here leaves the stack where it was.
 *
 * <p>1.21.11 changed the return type to {@code void}, so the handler cancels instead of returning
 * {@code false}.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {

    //? if >=1.21.11 {
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noDropLocked(boolean entireStack, CallbackInfo ci) {
    //?} else {
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noDropLocked(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
    //?}
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (LoadoutLock.isLocked(self.getMainHandItem())) {
            SessionManager.reportDropDenial(self);
            //? if >=1.21.11 {
            ci.cancel();
            //?} else {
            cir.setReturnValue(false);
            //?}
        }
    }
}
