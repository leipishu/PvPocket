package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.LegacyCombatValues;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes the attack cooldown for players in a legacy round.
 *
 * <p>Vanilla scales outgoing damage by charge progress, so forcing the progress to full is
 * equivalent to "every click lands at full damage" — which is exactly what 1.8 does. Returning
 * early for everyone else keeps modern players bit-for-bit vanilla.</p>
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    @Inject(method = "getAttackCooldownProgress", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyFullCharge(float baseTime, CallbackInfoReturnable<Float> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (CombatState.isLegacy(self.getUuid())) {
            cir.setReturnValue(LegacyCombatValues.FULL_CHARGE);
        }
    }
}
