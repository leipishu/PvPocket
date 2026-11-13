package com.leipishu.pvpocket.loadout;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The "Q does nothing on this item" mark, carried by the stack itself.
 *
 * <p>The mark lives on the stack rather than in a slot table, because a player can move, split or
 * store the item, and a table keyed by item type would lock every item of that type.
 *
 * <p>The payload sits in {@code custom_data}, which every vanilla path that copies a stack also
 * copies. The value is {@link LoadoutEntry#fingerprint()}, not a boolean, so {@link #restamp} can
 * decide per stack whether the row that produced it still asks for the lock.
 *
 * <p>Only marked stacks are affected; any other item behaves as usual.
 */
public final class LoadoutLock {

    /** NBT key inside {@code custom_data}, namespaced so a map's own data cannot collide. */
    private static final String KEY = "pvpocket:no_drop";

    private LoadoutLock() {
    }

    /** Marks a freshly built stack with the row that produced it. */
    public static void mark(ItemStack stack, String fingerprint) {
        if (stack.isEmpty()) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, nbt -> nbt.putString(KEY, fingerprint));
    }

    /** Whether Q-key dropping is refused for this stack. Empty stacks are never locked. */
    public static boolean isLocked(ItemStack stack) {
        return !fingerprint(stack).isEmpty();
    }

    /**
     * The row that produced this stack, or an empty string when the loadout never granted it.
     */
    public static String fingerprint(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : data.copyTag().getString(KEY);
    }

    /**
     * Takes the drop lock off stacks the loadout does not lock.
     *
     * <p>Call after a loadout is saved, on both the personal and the in-session path. Without it
     * the switch only affects the next grant.
     *
     * <p>Removal only. Adding a mark needs an answer to "is this stack the row's item?", which is
     * unknowable outside a granted round, so {@code LoadoutBuilder.grant} stamps what it builds.
     *
     * <p>Only stacks that already carry a fingerprint are touched, and a stack is unlocked only
     * when no row that still locks matches it.
     */
    public static void restamp(Player player, Loadout loadout) {
        if (loadout == null) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            String fingerprint = fingerprint(stack);
            if (fingerprint.isEmpty() || loadout.locks(fingerprint)) {
                continue;
            }
            CustomData.update(DataComponents.CUSTOM_DATA, stack, nbt -> nbt.remove(KEY));
            changed = true;
        }
        if (changed) {
            player.containerMenu.broadcastChanges();
        }
    }
}
