package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores 1.8's targeting margin on duelists.
 *
 * <p>1.8's {@code Entity#getCollisionBorderSize} returns {@code 0.1F} for every entity, and the
 * pick ray expands each bounding box by it before testing. 1.21 returns {@code 0.0F}, so the same
 * crosshair position that connected in 1.8 misses here — the boxes are the same size, the tolerance
 * around them is not. Ten centimetres does not sound like much, but it is the difference between
 * "my sword goes through them" and not, on every single swing.</p>
 *
 * <p>Applied only to players registered in a legacy round, so a duelist standing next to a modern
 * player does not hand that player a larger hitbox.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    /** {@code Entity#getCollisionBorderSize} in 1.8.9. */
    private static final float LEGACY_TARGETING_MARGIN = 0.1F;

    @Inject(method = "getTargetingMargin", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyTargetingMargin(CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof PlayerEntity player && CombatState.isLegacy(player.getUuid())) {
            cir.setReturnValue(LEGACY_TARGETING_MARGIN);
        }
    }
}
