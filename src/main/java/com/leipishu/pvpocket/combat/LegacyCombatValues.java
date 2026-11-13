package com.leipishu.pvpocket.combat;

import net.minecraft.util.Mth;

/**
 * Numeric constants for the legacy rule set, each traced to a line of 1.8.9 source.
 *
 * <p>Every constant carries the method it came from, so a value can be checked without
 * re-decompiling. Source: MCP-919 (1.8.9, MCP stable 22 mappings).
 *
 * <p>Three points differ from common belief. Blocking is not a flat 50% cut, see
 * {@link #applyBlocking}. Blocking does not reduce knockback: {@code EntityLivingBase#knockBack}
 * uses a fixed 0.4 strength and never consults blocking state. 1.8 armour is a linear formula with
 * no toughness term, see {@link #applyArmor}.
 */
public final class LegacyCombatValues {

    /** No attack cooldown in 1.8, so charge progress is always treated as full. */
    public static final float FULL_CHARGE = 1.0F;

    /** See {@link #applyBlocking}. */
    public static final float BLOCKING_DAMAGE_OFFSET = 1.0F;

    /** See {@link #applyBlocking}. */
    public static final float BLOCKING_DAMAGE_SCALE = 0.5F;

    /**
     * 1.8 scales movement input to 20% while an item is in use, and a sword block is an item use
     * ({@code ItemSword} reports {@code EnumAction.BLOCK}). Source:
     * {@code EntityPlayerSP#onLivingUpdate}, {@code moveStrafe *= 0.2F; moveForward *= 0.2F}.
     */
    public static final float BLOCKING_MOVEMENT_MULTIPLIER = 0.2F;

    /**
     * 1.8 armour denominator. Source: {@code EntityLivingBase#applyArmorCalculations},
     * {@code int i = 25 - getTotalArmorValue(); damage = damage * i / 25.0F}.
     */
    public static final float ARMOR_DENOMINATOR = 25.0F;

    /**
     * Fixed strength of the hurt-response knockback. Source:
     * {@code EntityLivingBase#attackEntityFrom} calls {@code knockBack(entity, amount, dx, dz)},
     * which hard-codes {@code float f1 = 0.4F}. Its {@code amount} parameter is never read, so 1.8
     * knockback does not scale with damage.
     */
    public static final double HURT_KNOCKBACK_STRENGTH = 0.4D;

    /**
     * Upward impulse added by a sprint/enchant hit. Source:
     * {@code EntityPlayer#attackTargetEntityWithCurrentItem},
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
     * The 1.8 blocking formula, verbatim from {@code EntityPlayer#damageEntity}:
     * <pre>{@code
     * if (!damageSrc.isUnblockable() && this.isBlocking() && damageAmount > 0.0F) {
     *     damageAmount = (1.0F + damageAmount) * 0.5F;
     * }
     * }</pre>
     *
     * <p>The {@code + 1} makes this a halving plus half a heart, not a flat 50% cut: 2 damage
     * becomes 1.5 and 10 damage becomes 5.5. It runs before armor, so armor mitigates the result.
     */
    public static float applyBlocking(float amount) {
        return (BLOCKING_DAMAGE_OFFSET + amount) * BLOCKING_DAMAGE_SCALE;
    }

    /**
     * The 1.8 armour formula, verbatim from {@code EntityLivingBase#applyArmorCalculations}:
     * <pre>{@code
     * int i = 25 - this.getTotalArmorValue();
     * damage = damage * i / 25.0F;
     * }</pre>
     *
     * <p>Purely linear, with no toughness term. {@code DamageUtil#getDamageLeft} subtracts
     * {@code damage / (2 + toughness / 4)} first, so large hits land harder on modern armour.
     *
     * <p>Armour is clamped to {@code [0, 25]}, since 1.8 never exceeded 20 points.
     */
    public static float applyArmor(float amount, int armorPoints) {
        int remaining = (int) ARMOR_DENOMINATOR
                - Mth.clamp(armorPoints, 0, (int) ARMOR_DENOMINATOR);
        return amount * remaining / ARMOR_DENOMINATOR;
    }
}
