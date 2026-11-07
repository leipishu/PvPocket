package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.LegacyCombatValues;
import com.leipishu.pvpocket.combat.LegacyKnockback;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.DamageTypeTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The victim side of the legacy rule set: everything that happens <em>to</em> a player.
 *
 * <p>Every hook is guarded by the same question — is this player currently registered as legacy — and
 * nothing else. Anything that is not a registered legacy player falls through untouched, so a modern
 * player standing next to a legacy duel is never affected.</p>
 *
 * <p>There are four effects, because that is what 1.8 has: blocking damage reduction, a blocking
 * movement penalty, the fixed-strength hurt knockback, and linear armour. Notably there is <em>no</em>
 * blocking-knockback hook — see {@link LegacyCombatValues} for the source evidence that 1.8 blocking
 * never reduced knockback.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    /**
     * 1.8 blocking damage reduction, applied at the same point in the pipeline as the original.
     *
     * <p>{@code damage} computes the final amount and then hands it to {@code applyDamage}, which is
     * where armor and absorption live. HEAD is therefore before armor, matching 1.8's
     * {@code damageEntity}, which also reduced blocking damage before calling
     * {@code applyArmorCalculations}.</p>
     *
     * <p>{@code DamageTypeTags.BYPASSES_ARMOR} is the modern stand-in for 1.8's
     * {@code DamageSource#isUnblockable}: in 1.8 every source built with
     * {@code setDamageBypassesArmor()} (fire, drowning, starvation, fall, void, magic, wither,
     * indirect magic) was marked unblockable, which is exactly the modern bypass-armor set.</p>
     */
    @ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true, index = 2)
    private float pvpocket$blockingDamage(float amount, DamageSource source) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (amount <= 0.0F
                || source.isIn(DamageTypeTags.BYPASSES_ARMOR)
                || !(self instanceof PlayerEntity player)
                || !CombatState.isBlocking(player.getUuid())) {
            return amount;
        }
        return LegacyCombatValues.applyBlocking(amount);
    }

    /**
     * 1.8 armour: linear in armour points, with no toughness term.
     *
     * <p>Hooked at HEAD rather than at the {@code DamageUtil#getDamageLeft} call so that
     * {@code damageArmor} still runs — 1.8 also damaged armour inside
     * {@code applyArmorCalculations}, so skipping it would silently make legacy duels consume less
     * armour durability than vanilla.</p>
     */
    @Inject(method = "applyArmorToDamage", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyArmor(DamageSource source, float amount,
                                      CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (source.isIn(DamageTypeTags.BYPASSES_ARMOR)
                || !(self instanceof PlayerEntity player)
                || !CombatState.isLegacy(player.getUuid())) {
            return;
        }
        self.damageArmor(source, amount);
        cir.setReturnValue(LegacyCombatValues.applyArmor(amount, self.getArmor()));
    }

    /**
     * 1.8 hurt knockback: fixed strength, halve-then-push, always pops the victim up.
     *
     * <p>This covers every damage source, including mobs and projectiles, because the hurt response
     * is a property of the victim's rule set rather than the attacker's. The attacker's own knockback
     * impulse is handled separately in {@code PlayerEntityMixin}, which redirects the call site inside
     * {@code attack} so that it never reaches this hook.</p>
     */
    @Inject(method = "takeKnockback", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyHurtKnockback(double strength, double x, double z, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof PlayerEntity player && CombatState.isLegacy(player.getUuid())) {
            LegacyKnockback.applyHurt(self, x, z);
            ci.cancel();
        }
    }

    /**
     * 1.8 scales movement input rather than the speed attribute. Doing the same here keeps the
     * penalty self-clearing: the input is recomputed every tick, so nothing can leak into the
     * player's saved attributes.
     *
     * <p>Unlike the hooks above this one is <em>not</em> restricted to the logical server. The client
     * predicts its own movement, so if only the server slowed the player down the client would keep
     * sending full-speed positions and the server would reject them, producing a visible pull-back
     * every tick. The client's view of the flag comes from {@code CombatStatePayload}, so both sides
     * scale by the same factor and agree.</p>
     *
     * <p>Sprint is dropped as well, because 1.8 could not block and sprint at once. There it fell out
     * of the arithmetic: the scaled {@code moveForward} (1.0 × 0.2) is below the 0.8 the sprint check
     * wants, so the block cancelled the sprint on the tick it started. 1.21's sprint start has no
     * "using an item" test at all, so without this a blocking player keeps full sprint speed at 20%
     * input — noticeably faster than the 1.8 shuffle, and it also keeps the sprint knockback bonus
     * available while defending.</p>
     */
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void pvpocket$blockingSlowdown(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof PlayerEntity player && CombatState.isBlocking(player.getUuid())) {
            player.sidewaysSpeed *= LegacyCombatValues.BLOCKING_MOVEMENT_MULTIPLIER;
            player.forwardSpeed *= LegacyCombatValues.BLOCKING_MOVEMENT_MULTIPLIER;
            if (player.isSprinting()) {
                player.setSprinting(false);
            }
        }
    }
}
