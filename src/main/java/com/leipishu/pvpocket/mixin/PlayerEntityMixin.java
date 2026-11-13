package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.LegacyCombatValues;
import com.leipishu.pvpocket.combat.LegacyKnockback;
import com.leipishu.pvpocket.session.SessionManager;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The attacker side of the legacy rule set.
 *
 * <p>Six differences from 1.21.1, all inside the attack path: no attack cooldown, an additive
 * sprint knockback instead of a second velocity-halving knockback, no sweeping attack, sprint-crits
 * allowed, no 1.9 attack sounds, and no damage-indicator particles.
 *
 * <p>1.21.11 split {@code attack} into helpers, so four of the six hooks moved: knockback to
 * {@code causeExtraKnockback} and {@code doSweepAttack}, the sweep gate to {@code isSweepAttack},
 * the crit gate to {@code canCriticalAttack}, the indicator to {@code damageStatsAndHearts}, and
 * every attack sound to {@code playServerSideSound}.
 */
@Mixin(Player.class)
public abstract class PlayerEntityMixin {

    /** Forces charge progress to full: 1.8 has no attack cooldown. */
    @Inject(method = "getAttackStrengthScale", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyFullCharge(float baseTime, CallbackInfoReturnable<Float> cir) {
        Player self = (Player) (Object) this;
        if (CombatState.isLegacy(self.getUUID())) {
            cir.setReturnValue(LegacyCombatValues.FULL_CHARGE);
        }
    }

    /**
     * Replaces the vanilla sprint and enchant knockback with 1.8's additive impulse.
     *
     * <p>1.8 pushes the victim away from the attacker with a fixed strength and never halves the
     * velocity a second time. The sweep site stays unreachable for a legacy attacker because the
     * sweep is disabled below; every other caller forwards to vanilla.
     */
    //? if >=1.21.11 {
    @Redirect(method = {"causeExtraKnockback", "doSweepAttack"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDD)V"))
    //?} else {
    @Redirect(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDD)V"))
    //?}
    private void pvpocket$legacyAttackKnockback(LivingEntity victim, double strength,
                                                double x, double z) {
        Player self = (Player) (Object) this;
        if (CombatState.isLegacy(self.getUUID())) {
            LegacyKnockback.applyAttack(victim, x, z, strength);
        } else {
            victim.knockback(strength, x, z);
        }
    }

    /**
     * Disables the sweeping attack for legacy attackers by emptying the held-stack lookup.
     *
     * <p>The sweep gate tests that stack with {@code is(ItemTags.SWORDS)}, so an empty stack makes
     * the test fail. Without it every stationary sword hit would sweep, because the charge check
     * {@code h > 0.9F} is always true for a legacy player.
     */
    //? if >=1.21.11 {
    @ModifyExpressionValue(method = "isSweepAttack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getItemInHand"
                            + "(Lnet/minecraft/world/InteractionHand;)"
                            + "Lnet/minecraft/world/item/ItemStack;"))
    //?} else {
    @ModifyExpressionValue(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getItemInHand"
                            + "(Lnet/minecraft/world/InteractionHand;)"
                            + "Lnet/minecraft/world/item/ItemStack;"))
    //?}
    private ItemStack pvpocket$noSweepForLegacy(ItemStack original) {
        Player self = (Player) (Object) this;
        return CombatState.isLegacy(self.getUUID()) ? ItemStack.EMPTY : original;
    }

    /**
     * Allows the sprint-crit for legacy attackers.
     *
     * <p>Hooks the sprint test inside the critical-hit condition. In 1.21.1 that test shared
     * {@code attack} with the sprint-knockback test and was picked out by {@code ordinal = 1};
     * 1.21.11 gave the crit condition its own method, which holds the only call.
     */
    //? if >=1.21.11 {
    @ModifyExpressionValue(method = "canCriticalAttack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;isSprinting()Z"))
    //?} else {
    @ModifyExpressionValue(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;isSprinting()Z",
                    ordinal = 1))
    //?}
    private boolean pvpocket$allowSprintCrit(boolean original) {
        Player self = (Player) (Object) this;
        return !CombatState.isLegacy(self.getUUID()) && original;
    }

    /**
     * Silences the 1.9 attack sounds for legacy attackers.
     *
     * <p>1.8 plays one sound per hit, {@code entity.player.hurt} on the victim. 1.21.11 funnels
     * every attack sound through one private helper, so a single wrap covers all six; in 1.21.1 the
     * two {@code Level.playSound} overloads were redirected instead, because {@code attack} used
     * both the "no damage" overload and the volume-and-pitch one.
     */
    //? if >=1.21.11 {
    @WrapOperation(method = {"attack", "attackVisualEffects", "doSweepAttack"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;playServerSideSound"
                            + "(Lnet/minecraft/sounds/SoundEvent;)V"))
    private void pvpocket$muteAttackSound(Player self, SoundEvent sound, Operation<Void> original) {
        if (!pvpocket$isLegacyAttacker()) {
            original.call(self, sound);
        }
    }
    //?} else {
    @Redirect(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound("
                            + "Lnet/minecraft/world/entity/player/Player;DDD"
                            + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;)V"))
    private void pvpocket$muteAttackSound(Level world, Player except, double x, double y,
                                          double z, SoundEvent sound, SoundSource category) {
        if (!pvpocket$isLegacyAttacker()) {
            world.playSound(except, x, y, z, sound, category);
        }
    }

    @Redirect(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;playSound("
                            + "Lnet/minecraft/world/entity/player/Player;DDD"
                            + "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void pvpocket$muteAttackSound(Level world, Player except, double x, double y,
                                          double z, SoundEvent sound, SoundSource category,
                                          float volume, float pitch) {
        if (!pvpocket$isLegacyAttacker()) {
            world.playSound(except, x, y, z, sound, category, volume, pitch);
        }
    }
    //?}

    /**
     * Drops the 1.9 damage-indicator particles for legacy attackers.
     *
     * <p>{@code DAMAGE_INDICATOR}, the small dark hearts on a hit for more than two points, does
     * not exist in 1.8. It is spawned server-side only, so this redirects {@code sendParticles}.
     */
    //? if >=1.21.11 {
    @Redirect(method = "damageStatsAndHearts",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;sendParticles("
                            + "Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    //?} else {
    @Redirect(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;sendParticles("
                            + "Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    //?}
    private int pvpocket$noDamageIndicator(ServerLevel world, ParticleOptions particle,
                                           double x, double y, double z, int count,
                                           double deltaX, double deltaY, double deltaZ, double speed) {
        if (pvpocket$isLegacyAttacker()) {
            return 0;
        }
        return world.sendParticles(particle, x, y, z, count, deltaX, deltaY, deltaZ, speed);
    }

    private boolean pvpocket$isLegacyAttacker() {
        return CombatState.isLegacy(((Player) (Object) this).getUUID());
    }

    /**
     * Turns off natural regeneration: with {@code c.naturalRegen} off, hunger does not heal.
     *
     * <p>Hooks {@code isHurt}, the single gate the hunger manager asks before regenerating. Golden
     * apples and potions heal through {@code heal()} and are unaffected.
     */
    @Inject(method = "isHurt", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noNaturalRegen(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (!SessionManager.naturalRegenAllowed(self.getUUID())) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Turns off hunger drain: with {@code c.hunger} off, nothing adds exhaustion.
     *
     * <p>Hooks {@code causeFoodExhaustion} at HEAD. Cancelling {@code HungerManager#update} instead
     * would also freeze the regeneration timer and the starvation damage.
     */
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noHungerDrain(float exhaustion, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!SessionManager.hungerEnabled(self.getUUID())) {
            ci.cancel();
        }
    }
}
