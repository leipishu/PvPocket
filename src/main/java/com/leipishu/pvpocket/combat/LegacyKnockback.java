package com.leipishu.pvpocket.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * The two knockback mechanisms of 1.8, reproduced for legacy players.
 *
 * <p>The hurt response, from {@code EntityLivingBase#knockBack}, uses a hard-coded strength of
 * 0.4 that ignores damage, halves velocity first and always sets the vertical component. The
 * attack impulse adds {@code push(...)} when the attacker is sprinting or holds knockback,
 * without halving velocity again.
 *
 * <p>1.21.1 folds both into {@code LivingEntity#knockback(double, double, double)}, which always
 * halves velocity, scales by {@code 1 - knockbackResistance} instead of rolling against it, and
 * leaves the vertical component alone when the victim is airborne.
 */
public final class LegacyKnockback {

    private LegacyKnockback() {
    }

    /**
     * The 1.8 hurt response, from {@code EntityLivingBase#knockBack}.
     *
     * <p>Halves the current velocity, subtracts {@code dx / length * 0.4} and adds {@code 0.4} to
     * the vertical component, which is then clamped to 0.4.
     *
     * <p>Resistance is a coin flip, not a multiplier: netherite (resistance 0.4) makes 40% of hits
     * knock the player back not at all. {@code dx} and {@code dz} are the attacker position minus
     * the victim position.
     */
    public static void applyHurt(LivingEntity victim, double dx, double dz) {
        double resistance = victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        if (victim.getRandom().nextDouble() < resistance) {
            return;
        }

        // 1.8 jitters the direction before calling knockBack, so a hit from the same block still
        // produces a usable normal.
        while (dx * dx + dz * dz < 1.0E-4D) {
            dx = (victim.getRandom().nextDouble() - victim.getRandom().nextDouble()) * 0.01D;
            dz = (victim.getRandom().nextDouble() - victim.getRandom().nextDouble()) * 0.01D;
        }

        double length = Math.sqrt(dx * dx + dz * dz);
        Vec3 velocity = victim.getDeltaMovement();
        double strength = LegacyCombatValues.HURT_KNOCKBACK_STRENGTH;
        double vertical = velocity.y / 2.0D + strength;
        if (vertical > strength) {
            vertical = strength;
        }
        victim.setDeltaMovement(
                velocity.x / 2.0D - dx / length * strength,
                vertical,
                velocity.z / 2.0D - dz / length * strength);
        victim.hasImpulse = true;
    }

    /**
     * The 1.8 sprint/enchant impulse, from {@code EntityPlayer#attackTargetEntityWithCurrentItem}:
     * {@code push(-sin(yaw) * i * 0.5, 0.1, cos(yaw) * i * 0.5)}.
     *
     * <p>The caller is the 1.21.1 {@code knockback(strength, x, z)} call site, where
     * {@code x = sin(yaw)}, {@code z = -cos(yaw)} and {@code strength = i * 0.5}. The signs are
     * reconstructed as {@code -x} and {@code -z}, which keeps the redirect handler free of any
     * assumption about the attacker's yaw.
     */
    public static void applyAttack(Entity target, double x, double z, double strength) {
        target.push(-x * strength, LegacyCombatValues.ATTACK_KNOCKBACK_UP, -z * strength);
    }
}
