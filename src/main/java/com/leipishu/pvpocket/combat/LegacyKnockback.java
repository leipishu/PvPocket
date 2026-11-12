package com.leipishu.pvpocket.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * The two knockback mechanisms of 1.8, reproduced for legacy players.
 *
 * <p>1.8 applies knockback in <em>two</em> separate places and they behave differently, which is the
 * part that is easy to get wrong:</p>
 * <ol>
 *   <li><b>Hurt response</b> — {@code EntityLivingBase#attackEntityFrom} calls
 *       {@code knockBack(entity, amount, dx, dz)} on every fresh hit. The strength is a hard-coded
 *       0.4 that does <em>not</em> scale with damage, the entity's current velocity is halved first,
 *       and the vertical component is always set (and clamped) rather than left alone.</li>
 *   <li><b>Attack impulse</b> — {@code EntityPlayer#attackTargetEntityWithCurrentItem} additionally
 *       calls {@code push(...)} when the attacker is sprinting or has the knockback
 *       enchantment. This is a pure additive impulse: it does <em>not</em> halve velocity again.</li>
 * </ol>
 *
 * <p>1.21.1 folds both into {@code LivingEntity#knockback(double, double, double)}, which always
 * halves velocity, always scales by {@code 1 - knockbackResistance} instead of rolling against it, and
 * leaves the vertical component untouched when the victim is airborne. The three methods here exist to
 * undo exactly those three differences.</p>
 */
public final class LegacyKnockback {

    private LegacyKnockback() {
    }

    /**
     * The 1.8 hurt response, from {@code EntityLivingBase#knockBack}.
     *
     * <pre>{@code
     * if (this.rand.nextDouble() >= knockbackResistance) {
     *     this.motionX /= 2.0D; this.motionY /= 2.0D; this.motionZ /= 2.0D;
     *     this.motionX -= dx / f * 0.4F;
     *     this.motionY += 0.4F;
     *     this.motionZ -= dz / f * 0.4F;
     *     if (this.motionY > 0.4D) this.motionY = 0.4D;
     * }
     * }</pre>
     *
     * <p>Resistance is a coin flip, not a multiplier: a full set of netherite (resistance 0.4) makes
     * 40% of hits knock the player back not at all, instead of knocking them 40% less. That is a
     * genuinely different feel and the reason this cannot reuse the vanilla method.</p>
     *
     * @param victim the entity being knocked back
     * @param dx     attacker position minus victim position, X component
     * @param dz     attacker position minus victim position, Z component
     */
    public static void applyHurt(LivingEntity victim, double dx, double dz) {
        double resistance = victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        if (victim.getRandom().nextDouble() < resistance) {
            return;
        }

        // 1.8 jitters the direction before calling knockBack, so a hit from exactly the same block
        // still produces a usable normal. Reproduced here because the caller no longer does it.
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
     *
     * <pre>{@code
     * targetEntity.push(-sin(yaw) * i * 0.5, 0.1, cos(yaw) * i * 0.5);
     * }</pre>
     *
     * <p>The caller is 1.21.1's {@code knockback(strength, x, z)} call site, where
     * {@code x = sin(yaw)}, {@code z = -cos(yaw)} and {@code strength = i * 0.5}. The signs are
     * therefore reconstructed as {@code -x} and {@code -z}; doing it this way keeps the redirect
     * handler free of any assumption about the attacker's yaw.</p>
     *
     * @param target   the entity being knocked back
     * @param x        the {@code x} argument the vanilla call would have received
     * @param z        the {@code z} argument the vanilla call would have received
     * @param strength the {@code strength} argument the vanilla call would have received
     */
    public static void applyAttack(Entity target, double x, double z, double strength) {
        target.push(-x * strength, LegacyCombatValues.ATTACK_KNOCKBACK_UP, -z * strength);
    }
}
