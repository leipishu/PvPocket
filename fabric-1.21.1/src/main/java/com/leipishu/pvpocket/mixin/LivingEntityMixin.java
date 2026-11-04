package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.LegacyCombatValues;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.DamageTypeTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocking and its side effects.
 *
 * <p>Every hook is guarded by the same question — is this player currently blocking under the
 * legacy rule set — and nothing else. Anything that is not a registered legacy player falls
 * through untouched, so a modern player standing next to a legacy duel is never affected.</p>
 *
 * <p>There are exactly two effects, because that is exactly what 1.8 has: damage reduction and a
 * movement penalty. Notably there is <em>no</em> knockback hook — see
 * {@link LegacyCombatValues} for the source evidence that 1.8 blocking never reduced knockback.</p>
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
     * 1.8 scales movement input rather than the speed attribute. Doing the same here keeps the
     * penalty self-clearing: the input is recomputed every tick, so nothing can leak into the
     * player's saved attributes.
     *
     * <p>Unlike the hook above this one is <em>not</em> restricted to the logical server. The client
     * predicts its own movement, so if only the server slowed the player down the client would keep
     * sending full-speed positions and the server would reject them, producing a visible pull-back
     * every tick. The client's view of the flag comes from {@code CombatStatePayload}, so both sides
     * scale by the same factor and agree.</p>
     */
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void pvpocket$blockingSlowdown(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof PlayerEntity player && CombatState.isBlocking(player.getUuid())) {
            player.sidewaysSpeed *= LegacyCombatValues.BLOCKING_MOVEMENT_MULTIPLIER;
            player.forwardSpeed *= LegacyCombatValues.BLOCKING_MOVEMENT_MULTIPLIER;
        }
    }
}
