package com.leipishu.pvpocket.combat;

/**
 * Numeric constants for the legacy rule set, each traced to a line of 1.8.9 source.
 *
 * <p>These are behavioural constants, not guesses. Every one carries the method it came from so a
 * reviewer can check it without re-decompiling, and so a wrong value can never hide inside combat
 * logic. Source: MCP-919 (1.8.9, MCP stable 22 mappings).</p>
 *
 * <p>Two popular beliefs about 1.8 blocking turned out to be <em>false</em> when checked against the
 * source, and both are recorded here because they are easy to reintroduce by accident:</p>
 * <ul>
 *   <li>Blocking is not a flat 50% damage cut. See {@link #applyBlocking}.</li>
 *   <li>Blocking does <em>not</em> reduce knockback at all. {@code EntityLivingBase#knockBack} uses
 *       a fixed 0.4 strength and never consults blocking state, and
 *       {@code EntityPlayer#attackTargetEntityWithCurrentItem} adds its sprint/enchant knockback
 *       without a blocking check. Only {@code knockbackResistance} matters.</li>
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
}
