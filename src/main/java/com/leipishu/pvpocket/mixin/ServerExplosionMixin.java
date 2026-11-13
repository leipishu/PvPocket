package com.leipishu.pvpocket.mixin;

//? if >=1.21.11 {

import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code f.allowExplosives} on the server: a detonation that is not allowed does not happen.
 *
 * <p>From 1.21.11 the interface {@code Explosion} is a read-only view and {@code ServerExplosion}
 * is the only implementation, so the block search, the entity damage, the drops and the sound all
 * happen inside one {@code explode}. One hook covers what {@code explode} and
 * {@code finalizeExplosion} covered before it.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noExplosion(CallbackInfoReturnable<Integer> cir) {
        ServerExplosion self = (ServerExplosion) (Object) this;
        // Wind charges are not explosives: they move players without breaking blocks or dealing
        // explosion damage, so {@code f.allowExplosives} leaves them alone.
        boolean windBurst = self.getDirectSourceEntity() instanceof AbstractWindCharge;
        if (windBurst || SessionManager.explosivesAllowed(self.level(), self.center())) {
            return;
        }
        // The return value is the count of destroyed blocks; nothing happened, so none.
        cir.setReturnValue(0);
    }
}
//?}
