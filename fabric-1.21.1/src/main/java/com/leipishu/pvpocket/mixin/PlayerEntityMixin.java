package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.LegacyCombatValues;
import com.leipishu.pvpocket.combat.LegacyKnockback;
import com.leipishu.pvpocket.session.SessionManager;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The attacker side of the legacy rule set.
 *
 * <p>Four differences from 1.21.1, all inside {@code PlayerEntity#attack}:</p>
 * <ol>
 *   <li>No attack cooldown — charge progress is forced to full.</li>
 *   <li>The sprint/enchant knockback is an additive impulse, not a second velocity-halving
 *       knockback.</li>
 *   <li>No sweeping attack — it was introduced in 1.9.</li>
 *   <li>A sprinting player can still land a critical hit. 1.21.1 forbids it; 1.8 does not, which is
 *       the whole basis of the sprint-crit.</li>
 * </ol>
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    /** 1.8 has no attack cooldown, so charge progress is always full. */
    @Inject(method = "getAttackCooldownProgress", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyFullCharge(float baseTime, CallbackInfoReturnable<Float> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (CombatState.isLegacy(self.getUuid())) {
            cir.setReturnValue(LegacyCombatValues.FULL_CHARGE);
        }
    }

    /**
     * Replaces the vanilla sprint/enchant knockback with 1.8's additive impulse.
     *
     * <p>This redirects both {@code takeKnockback} call sites in {@code attack} — the sprint impulse
     * and the sweep impulse — with one handler. That is safe because the sweep is disabled separately
     * below, so the second site is unreachable for a legacy attacker; for everyone else the handler
     * forwards to vanilla unchanged.</p>
     *
     * <p>The distinction matters: 1.21.1 routes this through {@code takeKnockback}, which halves the
     * victim's velocity a <em>second</em> time (the hurt response already halved it). 1.8 adds a flat
     * impulse on top of the already-halved velocity, so sprint hits send the victim further.</p>
     */
    @Redirect(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/LivingEntity;takeKnockback(DDD)V"))
    private void pvpocket$legacyAttackKnockback(LivingEntity victim, double strength,
                                                double x, double z) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (CombatState.isLegacy(self.getUuid())) {
            LegacyKnockback.applyAttack(victim, x, z, strength);
        } else {
            victim.takeKnockback(strength, x, z);
        }
    }

    /**
     * Disables the sweeping attack for legacy attackers.
     *
     * <p>The sweep gate is the last condition before the sweep flag is set:
     * {@code if (this.getStackInHand(Hand.MAIN_HAND).getItem() instanceof SwordItem) bl4 = true;}
     * — and {@code attack} calls {@code getStackInHand} exactly once. Returning an empty stack makes
     * that {@code instanceof} fail, which is the narrowest way to suppress the sweep without
     * reimplementing the whole method.</p>
     *
     * <p>This is not cosmetic. For a legacy player the charge check {@code h > 0.9F} is always true
     * (cooldown is forced to full), so without this every stationary sword hit would also sweep every
     * nearby entity — a mechanic that did not exist in 1.8.</p>
     */
    @ModifyExpressionValue(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;getStackInHand(Lnet/minecraft/util/Hand;)Lnet/minecraft/item/ItemStack;"))
    private ItemStack pvpocket$noSweepForLegacy(ItemStack original) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        return CombatState.isLegacy(self.getUuid()) ? ItemStack.EMPTY : original;
    }

    /**
     * Allows the sprint-crit for legacy attackers.
     *
     * <p>1.21.1's critical-hit condition ends with {@code && !this.isSprinting()}; 1.8's ends with
     * {@code && targetEntity instanceof EntityLivingBase} and has no sprinting check at all. This is
     * the second {@code isSprinting()} call in {@code attack} — the first one gates the sprint
     * knockback and must keep its real value.</p>
     */
    @ModifyExpressionValue(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;isSprinting()Z",
                    ordinal = 1))
    private boolean pvpocket$allowSprintCrit(boolean original) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        return !CombatState.isLegacy(self.getUuid()) && original;
    }

    /**
     * {@code c.naturalRegen} off: hunger no longer heals.
     *
     * <p>{@code canFoodHeal} is the single gate the hunger manager asks before regenerating, so
     * returning false here removes natural regeneration and nothing else — golden apples and potions
     * heal through {@code heal()}, not through this.</p>
     */
    @Inject(method = "canFoodHeal", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noNaturalRegen(CallbackInfoReturnable<Boolean> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (!SessionManager.naturalRegenAllowed(self.getUuid())) {
            cir.setReturnValue(false);
        }
    }

    /**
     * {@code c.hunger} off: nothing adds exhaustion, so the bar never drains.
     *
     * <p>Cancelling here rather than in {@code HungerManager#update} keeps the two rules apart —
     * cancelling {@code update} would also freeze the regeneration timer and the starvation damage,
     * which belong to {@code c.naturalRegen} and to the damage veto respectively.</p>
     */
    @Inject(method = "addExhaustion", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noHungerDrain(float exhaustion, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (!SessionManager.hungerEnabled(self.getUuid())) {
            ci.cancel();
        }
    }
}
