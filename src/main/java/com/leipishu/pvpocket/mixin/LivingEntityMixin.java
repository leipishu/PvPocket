package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.LegacyCombatValues;
import com.leipishu.pvpocket.combat.LegacyKnockback;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The victim side of the legacy rule set: everything that happens to a player.
 *
 * <p>Every hook asks whether the player is registered as legacy and leaves everything else
 * untouched. There are four effects, matching 1.8: blocking damage reduction, a blocking movement
 * penalty, fixed-strength hurt knockback, and linear armour. 1.8 blocking never reduced knockback,
 * so there is no blocking-knockback hook.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    /**
     * Vanilla armour durability loss, which the 1.8 armour calculation also runs.
     *
     * <p>{@code hurtArmor} is protected and lives in another package, so a direct call from here is
     * illegal. {@code @Shadow} declares it once and is legal on every loader.
     */
    @Shadow
    protected abstract void hurtArmor(DamageSource source, float amount);

    /**
     * Applies 1.8 blocking damage reduction at HEAD of the hurt entry point, before armour runs.
     *
     * <p>1.8 also reduced blocking damage before {@code applyArmorCalculations}.
     * {@code DamageTypeTags.BYPASSES_ARMOR} stands in for 1.8's unblockable sources, which are the
     * same set.
     *
     * <p>1.21.11 renamed the entry point to {@code hurtServer} and moved it to the server-only
     * side, so {@code ServerLevel} became the first parameter and the amount moved to slot 3.
     */
    //? if >=1.21.11 {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, index = 3)
    private float pvpocket$blockingDamage(float amount, ServerLevel level, DamageSource source) {
    //?} else {
    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true, index = 2)
    private float pvpocket$blockingDamage(float amount, DamageSource source) {
    //?}
        LivingEntity self = (LivingEntity) (Object) this;
        if (amount <= 0.0F
                || source.is(DamageTypeTags.BYPASSES_ARMOR)
                || !(self instanceof Player player)
                || !CombatState.isBlocking(player.getUUID())) {
            return amount;
        }
        return LegacyCombatValues.applyBlocking(amount);
    }

    /**
     * Applies 1.8 armour at HEAD of {@code getDamageAfterArmorAbsorb}: linear in armour points,
     * with no toughness term.
     *
     * <p>Hooking here rather than at the damage calculation keeps {@code damageArmor} running, so
     * armour durability is still consumed.
     */
    @Inject(method = "getDamageAfterArmorAbsorb", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyArmor(DamageSource source, float amount,
                                      CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (source.is(DamageTypeTags.BYPASSES_ARMOR)
                || !(self instanceof Player player)
                || !CombatState.isLegacy(player.getUUID())) {
            return;
        }
        hurtArmor(source, amount);
        cir.setReturnValue(LegacyCombatValues.applyArmor(amount, self.getArmorValue()));
    }

    /**
     * Applies 1.8 hurt knockback at HEAD of {@code knockback}: fixed strength, halve then push, and
     * the victim always pops up.
     *
     * <p>Covers every damage source, including mobs and projectiles, because the hurt response
     * belongs to the victim's rule set. The attacker's impulse is redirected in
     * {@code PlayerEntityMixin}, so it never reaches this hook.
     */
    @Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyHurtKnockback(double strength, double x, double z, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && CombatState.isLegacy(player.getUUID())) {
            LegacyKnockback.applyHurt(self, x, z);
            ci.cancel();
        }
    }

    /**
     * Scales movement input for a blocking player at HEAD of {@code aiStep}, and drops sprint.
     *
     * <p>Scaling input rather than the speed attribute keeps the penalty self-clearing, because the
     * input is recomputed every tick. The hook runs on both sides: the client predicts its own
     * movement, and a server-only penalty would be rejected as a pull-back every tick.
     */
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void pvpocket$blockingSlowdown(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && CombatState.isBlocking(player.getUUID())) {
            player.xxa *= LegacyCombatValues.BLOCKING_MOVEMENT_MULTIPLIER;
            player.zza *= LegacyCombatValues.BLOCKING_MOVEMENT_MULTIPLIER;
            if (player.isSprinting()) {
                player.setSprinting(false);
            }
        }
    }

    /**
     * Records career damage at HEAD of {@code actuallyHurt}, where the final amount exists.
     *
     * <p>That point is after armour and before absorption and the health subtraction. Restricted to
     * {@link ServerPlayerEntity} so the client's prediction never counts. Membership rules live in
     * {@code SessionManager#noteDamage}.
     *
     * <p>1.21.11 gave the method a leading {@code ServerLevel}, because damage is resolved on the
     * server alone.
     */
    //? if >=1.21.11 {
    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void pvpocket$recordDuelDamage(ServerLevel level, DamageSource source, float amount,
                                           CallbackInfo ci) {
    //?} else {
    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void pvpocket$recordDuelDamage(DamageSource source, float amount, CallbackInfo ci) {
    //?}
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer victim
                && source.getEntity() instanceof ServerPlayer attacker) {
            SessionManager.noteDamage(victim, attacker, amount);
        }
    }
}
