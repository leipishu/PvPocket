package com.leipishu.pvpocket.combat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;

/**
 * "May this stack be used right now", answered in one place.
 *
 * <p>The answer depends on the item and the gate mask the rule set produced. Both sides call this
 * with their own copy of the mask, because a veto the client does not know about is a predicted
 * block that exists on one machine only.
 *
 * <p>Classification lives here rather than in the callers: two entry points (block placement and
 * plain item use) and both sides need the same answer to "what counts as a consumable".
 */
public final class UsePolicy {

    /** Nothing is restricted. Also the value for "this player is not in a round". */
    public static final int ALL = 0b111;

    public static final int ALLOW_PLACE = 1;
    public static final int ALLOW_CONSUMABLES = 1 << 1;
    public static final int ALLOW_ENDER_PEARL = 1 << 2;

    /** Why a use was refused. The caller turns this into a message; the check itself is silent. */
    public enum Denial {
        NONE,
        /** The {@code f.allowPlace} switch is off. */
        PLACE,
        /** The {@code d.allowConsumables} switch is off. */
        CONSUMABLE,
        /** The {@code g.enderPearl} switch is off. */
        PEARL,
        /** Legacy 1.8 has no shields at all, which is a property of the rule set, not a rule. */
        SHIELD
    }

    private UsePolicy() {
    }

    /**
     * Does this stack put a block into the world when used on a block?
     *
     * <p>Buckets are not in here even though they place fluid: {@code BucketItem} does that from
     * {@code use}, not {@code useOnBlock}, and whether a bucket can be filled has nothing to do
     * with {@code f.allowPlace}. They are handled by {@link #useDenial} instead.
     */
    public static boolean placesBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem || stack.getItem() instanceof EndCrystalItem;
    }

    /** Potions (including splash and lingering), anything with a food component, and milk. */
    public static boolean isConsumable(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof PotionItem
                || item == Items.MILK_BUCKET
                || stack.has(DataComponents.FOOD);
    }

    public static Denial placementDenial(int gate, ItemStack stack) {
        if (!placesBlock(stack)) {
            return Denial.NONE;
        }
        return (gate & ALLOW_PLACE) != 0 ? Denial.NONE : Denial.PLACE;
    }

    public static Denial useDenial(int gate, ItemStack stack) {
        if ((gate & ALLOW_ENDER_PEARL) == 0 && stack.is(Items.ENDER_PEARL)) {
            return Denial.PEARL;
        }
        if ((gate & ALLOW_CONSUMABLES) == 0 && isConsumable(stack)) {
            return Denial.CONSUMABLE;
        }
        return Denial.NONE;
    }

    /**
     * The shield veto, which comes from the rule set rather than from a rule.
     *
     * <p>Kept apart from {@link #useDenial} because there is no switch that turns shields back on
     * in 1.8, and a gate bit no panel can set would be dead. Grant already strips shields from the
     * loadout; this covers a shield that reaches an inventory another way.
     */
    public static Denial shieldDenial(boolean legacy, ItemStack stack) {
        return legacy && stack.is(Items.SHIELD) ? Denial.SHIELD : Denial.NONE;
    }
}
