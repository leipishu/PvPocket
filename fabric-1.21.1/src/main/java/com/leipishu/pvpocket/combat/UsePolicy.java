package com.leipishu.pvpocket.combat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.PotionItem;

/**
 * "May this stack be used right now" — the one place that answers it.
 *
 * <p>The answer is a function of three things and nothing else: which item it is, the gate mask the
 * rule set produced, and the ban mask {@code f.blockFilter} produced. Both sides call this with
 * their own copy of those two masks — the server reads them from {@link CombatState} because it
 * wrote them, the client reads them from the same class because {@code CombatStatePayload} mirrored
 * them. That is deliberate: a veto the client does not know about is a predicted block that only
 * exists on one machine.</p>
 *
 * <p>Keeping the classification here rather than in the callers matters more than it looks. There
 * are two entry points (block placement and plain item use) and three places that need the same
 * answer (both sides of the placement hook, and the item-use hook); the interesting part is
 * "what counts as a bed", and it must be the same answer in all of them.</p>
 */
public final class UsePolicy {

    /** Nothing is restricted. Also the value for "this player is not in a round". */
    public static final int ALL = 0b111;

    public static final int ALLOW_PLACE = 1;
    public static final int ALLOW_CONSUMABLES = 1 << 1;
    public static final int ALLOW_ENDER_PEARL = 1 << 2;

    /** Ban bits, in the order the {@code f.blockFilter} chips are declared in {@code RuleCatalog}. */
    public static final int BAN_BED = 1 << 0;
    public static final int BAN_ANCHOR = 1 << 1;
    public static final int BAN_TNT = 1 << 2;
    public static final int BAN_FLUID = 1 << 3;
    public static final int BAN_CRYSTAL = 1 << 4;

    /** Why a use was refused. The caller turns this into a message; the check itself is silent. */
    public enum Denial {
        NONE,
        /** {@code f.allowPlace} is off. */
        PLACE,
        /** {@code f.blockFilter} bans this kind of block. */
        BANNED,
        /** {@code d.allowConsumables} is off. */
        CONSUMABLE,
        /** {@code g.enderPearl} is off. */
        PEARL,
        /** Legacy 1.8 has no shields at all — not a rule, a property of the rule set. */
        SHIELD
    }

    private UsePolicy() {
    }

    /**
     * The ban bit for a stack, or 0 when {@code f.blockFilter} has nothing to say about it.
     *
     * <p>A milk bucket is not a fluid bucket: it is drunk, not poured, and folding it into the fluid
     * ban would make "关闭流体桶" quietly also remove a consumable. Fish buckets are fluid buckets —
     * what they place is a mob, but what they change is the arena.</p>
     */
    public static int banOf(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof BedItem) {
            return BAN_BED;
        }
        // No RespawnAnchorItem exists — the anchor is a plain BlockItem, so it is named by item.
        if (stack.is(Items.RESPAWN_ANCHOR)) {
            return BAN_ANCHOR;
        }
        if (item instanceof EndCrystalItem) {
            return BAN_CRYSTAL;
        }
        if (stack.is(Items.TNT)) {
            return BAN_TNT;
        }
        if (item instanceof BucketItem) {
            return BAN_FLUID;
        }
        return 0;
    }

    /** The chip index of a ban bit, for naming the restriction in a message. -1 when none. */
    public static int banIndex(int ban) {
        return ban == 0 ? -1 : Integer.numberOfTrailingZeros(ban);
    }

    /**
     * Does this stack put a block into the world when used on a block?
     *
     * <p>Buckets are not in here even though they place fluid: {@code BucketItem} does that from
     * {@code use}, not {@code useOnBlock}, and "can I fill my bucket" has nothing to do with
     * {@code f.allowPlace}. They are handled by {@link #useDenial} instead.</p>
     */
    public static boolean placesBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem || stack.getItem() instanceof EndCrystalItem;
    }

    /** Potions (including splash and lingering), anything with a food component, and milk. */
    public static boolean isConsumable(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof PotionItem
                || item instanceof MilkBucketItem
                || stack.has(DataComponents.FOOD);
    }

    public static Denial placementDenial(int gate, int bans, ItemStack stack) {
        if (!placesBlock(stack)) {
            return Denial.NONE;
        }
        int ban = banOf(stack);
        if (ban != 0 && (bans & ban) != 0) {
            return Denial.BANNED;
        }
        return (gate & ALLOW_PLACE) != 0 ? Denial.NONE : Denial.PLACE;
    }

    public static Denial useDenial(int gate, int bans, ItemStack stack) {
        int ban = banOf(stack);
        if (ban != 0 && (bans & ban) != 0) {
            return Denial.BANNED;
        }
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
     * <p>Kept apart from {@link #useDenial} because it is not a knob: there is no switch that turns
     * shields back on in 1.8, and folding it into the gate mask would have meant a bit no panel can
     * set. Grant already strips shields from the loadout, so this is the second line — it covers the
     * shield that reaches an inventory some other way.</p>
     */
    public static Denial shieldDenial(boolean legacy, ItemStack stack) {
        return legacy && stack.is(Items.SHIELD) ? Denial.SHIELD : Denial.NONE;
    }
}
