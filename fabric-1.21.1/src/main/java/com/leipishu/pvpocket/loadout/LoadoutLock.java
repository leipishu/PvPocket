package com.leipishu.pvpocket.loadout;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
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
 * codec — for one boolean that only this mod reads.</p>
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

    /** Marks a freshly built stack. Called by the builder, once per granted stack. */
    public static void mark(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.putBoolean(KEY, true));
    }

    /** Whether Q-key dropping is refused for this stack. Empty stacks are never locked. */
    public static boolean isLocked(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && data.copyNbt().getBoolean(KEY);
    }
}
