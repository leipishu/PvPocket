package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.LegacyCombatValues;
import com.leipishu.pvpocket.combat.LegacyKnockback;
import com.leipishu.pvpocket.session.SessionManager;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
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
@Mixin(Player.class)
public abstract class PlayerEntityMixin {

    /** 1.8 has no attack cooldown, so charge progress is always full. */
    @Inject(method = "getAttackStrengthScale", at = @At("HEAD"), cancellable = true)
    private void pvpocket$legacyFullCharge(float baseTime, CallbackInfoReturnable<Float> cir) {
        Player self = (Player) (Object) this;
        if (CombatState.isLegacy(self.getUUID())) {
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
                    target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDD)V"))
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
                    target = "Lnet/minecraft/world/entity/player/Player;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack pvpocket$noSweepForLegacy(ItemStack original) {
        Player self = (Player) (Object) this;
        return CombatState.isLegacy(self.getUUID()) ? ItemStack.EMPTY : original;
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
                    target = "Lnet/minecraft/world/entity/player/Player;isSprinting()Z",
                    ordinal = 1))
    private boolean pvpocket$allowSprintCrit(boolean original) {
        Player self = (Player) (Object) this;
        return !CombatState.isLegacy(self.getUUID()) && original;
    }

    /**
     * Silences the 1.9 attack sound set for legacy attackers.
     *
     * <p>1.8 plays exactly one sound when a hit lands: {@code entity.player.hurt} on the victim. The
     * crit / knockback / strong / weak / nodamage / sweep sounds that {@code attack} fires are all
     * 1.9 additions, and they are the main reason a modern trade sounds "chunkier" than a 1.8 one —
     * three sounds land where 1.8 has one, and they fire at a different pitch each time. Muting them
     * leaves the hurt sound as the only feedback, which is what 1.8's rhythm actually is.</p>
     *
     * <p>Both overloads are redirected because {@code attack} uses both: the redirect-overload for
     * the "no damage" branch, the volume/pitch one everywhere else. Scoped to {@code attack}, so
     * nothing outside combat is affected.</p>
     */
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

    /**
     * Drops the 1.9 damage-indicator particles for legacy attackers.
     *
     * <p>{@code DAMAGE_INDICATOR} — the small dark hearts that pop off a target hit for more than
     * two points — does not exist in 1.8. It is also spawned server-side only, so a client-side
     * filter would never see it.</p>
     */
    @Redirect(method = "attack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;sendParticles("
                            + "Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
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
     * {@code c.naturalRegen} off: hunger no longer heals.
     *
     * <p>{@code canFoodHeal} is the single gate the hunger manager asks before regenerating, so
     * returning false here removes natural regeneration and nothing else — golden apples and potions
     * heal through {@code heal()}, not through this.</p>
     */
    @Inject(method = "isHurt", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noNaturalRegen(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (!SessionManager.naturalRegenAllowed(self.getUUID())) {
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
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noHungerDrain(float exhaustion, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!SessionManager.hungerEnabled(self.getUUID())) {
            ci.cancel();
        }
    }
}
