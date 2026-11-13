package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores 1.8's targeting margin on duelists.
 *
 * <p>1.8's {@code Entity#getCollisionBorderSize} returns {@code 0.1F} for every entity and the pick
 * ray expands each bounding box by it; 1.21 returns {@code 0.0F}. Hooks {@code getPickRadius} at
 * HEAD and returns {@code 0.1F}.
 *
 * <p>Applied only to players registered in a legacy round, so a duelist standing next to a modern
 * player does not hand that player a larger hitbox.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    /** Margin 1.8.9 returns from {@code Entity#getCollisionBorderSize}. */
    private static final float LEGACY_TARGETING_MARGIN = 0.1F;

    @Inject(method = "getPickRadius", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyTargetingMargin(CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof Player player && CombatState.isLegacy(player.getUUID())) {
            cir.setReturnValue(LEGACY_TARGETING_MARGIN);
        }
    }
}
