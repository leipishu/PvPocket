package com.leipishu.pvpocket.combat;

import net.minecraft.util.math.MathHelper;

/**
 * Numeric constants for the legacy rule set, each traced to a line of 1.8.9 source.
 *
 * <p>These are behavioural constants, not guesses. Every one carries the method it came from so a
 * reviewer can check it without re-decompiling, and so a wrong value can never hide inside combat
 * logic. Source: MCP-919 (1.8.9, MCP stable 22 mappings).</p>
 *
 * <p>Three popular beliefs about 1.8 turned out to be <em>false</em> when checked against the source,
 * and all three are recorded here because they are easy to reintroduce by accident:</p>
 * <ul>
 *   <li>Blocking is not a flat 50% damage cut. See {@link #applyBlocking}.</li>
 *   <li>Blocking does <em>not</em> reduce knockback at all. {@code EntityLivingBase#knockBack} uses
 *       a fixed 0.4 strength and never consults blocking state, and
 *       {@code EntityPlayer#attackTargetEntityWithCurrentItem} adds its sprint/enchant knockback
 *       without a blocking check. Only {@code knockbackResistance} matters.</li>
 *   <li>1.8 armour is not "4% per point" — that is the net effect of a linear formula with no
 *       toughness term at all. See {@link #applyArmor}.</li>
 * </ul>
 */
public final class LegacyCombatValues {

    /** 1.8 has no attack cooldown, so charge progress is always treated as full. */
    public static final float FULL_CHARGE = 1.0F;

    /** See {@link #applyBlocking}. */
    public static final float BLOCKING_DAMAGE_OFFSET = 1.0F;

    /** See {@link #applyBlocking}. */
    public static final float BLOCKING_DAMAGE_SCALE = 0.5F;

    /**
     * 1.8 scales movement input to 20% while an item is in use, and a sword block <em>is</em> an item
     * use ({@code ItemSword} reports {@code EnumAction.BLOCK}). Source:
     * {@code EntityPlayerSP#onLivingUpdate} — {@code moveStrafe *= 0.2F; moveForward *= 0.2F}.
     */
    public static final float BLOCKING_MOVEMENT_MULTIPLIER = 0.2F;

    /**
     * 1.8 armour denominator. Source: {@code EntityLivingBase#applyArmorCalculations} —
     * {@code int i = 25 - getTotalArmorValue(); damage = damage * i / 25.0F}.
     */
    public static final float ARMOR_DENOMINATOR = 25.0F;

    /**
     * Fixed strength of the hurt-response knockback. Source:
     * {@code EntityLivingBase#attackEntityFrom} calls {@code knockBack(entity, amount, dx, dz)}, and
     * {@code knockBack} hard-codes {@code float f1 = 0.4F} — its {@code amount} parameter is never
     * read, which is why 1.8 knockback does not scale with damage.
     */
    public static final double HURT_KNOCKBACK_STRENGTH = 0.4D;

    /**
     * Upward impulse added by a sprint/enchant hit. Source:
     * {@code EntityPlayer#attackTargetEntityWithCurrentItem} —
     * {@code addVelocity(-sin(yaw) * i * 0.5, 0.1, cos(yaw) * i * 0.5)}.
     */
    public static final double ATTACK_KNOCKBACK_UP = 0.1D;

    /**
     * Horizontal impulse per knockback level, the {@code 0.5} multiplier on {@code i} in the same
     * expression. {@code i} is the knockback enchantment level plus one if the attacker is
     * sprinting.
     */
    public static final double ATTACK_KNOCKBACK_PER_LEVEL = 0.5D;

    private LegacyCombatValues() {
    }

    /**
     * The 1.8 blocking damage formula, verbatim from {@code EntityPlayer#damageEntity}:
     *
     * <pre>{@code
     * if (!damageSrc.isUnblockable() && this.isBlocking() && damageAmount > 0.0F) {
     *     damageAmount = (1.0F + damageAmount) * 0.5F;
     * }
     * }</pre>
     *
     * <p>Note the {@code + 1}: this is a halving <em>plus</em> half a heart, not a flat 50% cut. A
     * 2-damage hit becomes 1.5, a 10-damage hit becomes 5.5. It is deliberately not expressible as a
     * single multiplier, which is why it lives in a method rather than a constant.</p>
     *
     * <p>Also note the position: this runs <em>before</em> armor and potion damage calculations, so
     * armor mitigates the already-reduced value.</p>
     */
    public static float applyBlocking(float amount) {
        return (BLOCKING_DAMAGE_OFFSET + amount) * BLOCKING_DAMAGE_SCALE;
    }

    /**
     * The 1.8 armour formula, verbatim from {@code EntityLivingBase#applyArmorCalculations}:
     *
     * <pre>{@code
     * int i = 25 - this.getTotalArmorValue();
     * damage = damage * i / 25.0F;
     * }</pre>
     *
     * <p>Purely linear, with no toughness term. This is the single largest numeric divergence from
     * 1.21.1, whose {@code DamageUtil#getDamageLeft} subtracts {@code damage / (2 + toughness / 4)}
     * from the armour points before applying the same denominator of 25. That makes modern armour
     * progressively weaker against large hits; 1.8 armour is not.</p>
     *
     * <p>Armour is clamped to {@code [0, 25]}: 1.8 could never exceed 20 points, and a value above 25
     * would otherwise yield negative damage.</p>
     */
    public static float applyArmor(float amount, int armorPoints) {
        int remaining = (int) ARMOR_DENOMINATOR
                - MathHelper.clamp(armorPoints, 0, (int) ARMOR_DENOMINATOR);
        return amount * remaining / ARMOR_DENOMINATOR;
    }
}
