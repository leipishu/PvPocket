package com.leipishu.pvpocket.loadout;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * The "Q does nothing on this item" mark, carried by the stack itself.
 *
 * <p><b>Why the stack and not the slot.</b> {@code LoadoutEntry.slot} says where the item starts, not
 * where it is: a player can move it, drop it into a chest, split the stack, and pick it back up. Any
 * lookup table keyed by slot would be wrong the moment the round begins, and a table keyed by item
 * type would lock every arrow in the world. The one thing that travels with the item is the item.</p>
 *
 * <p><b>Why {@code custom_data} and not a registered component.</b> This is exactly what
 * {@code custom_data} is for: mod-private payload, never rendered in a tooltip, copied by every
 * vanilla path that copies a stack, and serialised by the component system without a codec of our
 * own. A registered {@code DataComponentType} would buy type safety and cost a registry entry plus a
 * codec — for one string that only this mod reads.</p>
 *
 * <p><b>The value is a fingerprint, not a boolean.</b> It used to be {@code true}, and that made the
 * mark impossible to take back: the switch in the editor changed the loadout, but the sword already
 * in the player's hand kept its mark forever, so turning the lock off looked like it did nothing.
 * Storing {@link LoadoutEntry#fingerprint()} lets {@link #restamp} walk the inventory after a save and
 * decide, per stack, whether the row that produced it still asks for the lock.</p>
 *
 * <p><b>Only marked stacks are affected.</b> Nothing here asks "is this player in a duel" or "is this
 * item one of ours": an item picked up off the ground, crafted, or spawned by a map is an ordinary
 * item and behaves like one. The lock is a property of the thing the loadout granted.</p>
 */
public final class LoadoutLock {

    /** NBT key inside {@code custom_data}. Namespaced so it cannot collide with a map's own data. */
    private static final String KEY = "pvpocket:no_drop";

    private LoadoutLock() {
    }

    /** Marks a freshly built stack with the row that produced it. */
    public static void mark(ItemStack stack, String fingerprint) {
        if (stack.isEmpty()) {
            return;
        }
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.putString(KEY, fingerprint));
    }

    /** Whether Q-key dropping is refused for this stack. Empty stacks are never locked. */
    public static boolean isLocked(ItemStack stack) {
        return !fingerprint(stack).isEmpty();
    }

    /** The row that produced this stack, or an empty string when the stack was never granted by us. */
    public static String fingerprint(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data == null ? "" : data.copyNbt().getString(KEY);
    }

    /**
     * Re-applies the current loadout's 禁止丢弃 to everything the player is carrying.
     *
     * <p>Called after a loadout is saved, on both the personal and the in-session path. Without it the
     * switch only affects the next grant, which is indistinguishable from "the switch does not
     * work" — that is exactly how it was reported: turn it off, press Q on the sword in hand, and
     * the sword still refuses to leave.</p>
     *
     * <p>Only stacks that already carry our fingerprint are touched. A vanilla sword picked up off
     * the ground has no fingerprint, so it can never become locked by accident; and the decision for
     * a stack we did grant is read from the loadout rather than guessed from the item, so two rows
     * granting the same item are handled without ambiguity (any row that still locks wins).</p>
     */
    public static void restamp(PlayerEntity player, Loadout loadout) {
        boolean changed = false;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            String fingerprint = fingerprint(stack);
            if (fingerprint.isEmpty() || loadout.locks(fingerprint)) {
                continue;
            }
            NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.remove(KEY));
            changed = true;
        }
        if (changed) {
            player.currentScreenHandler.sendContentUpdates();
        }
    }
}
