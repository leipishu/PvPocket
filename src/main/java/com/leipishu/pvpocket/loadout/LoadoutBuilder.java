package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.compat.PvCompat;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Loadout} into real items.
 *
 * <p>Attribute modifiers are replaced rather than appended, so the material is display only and the
 * chosen damage is what the item hits for. Enchantments carry the behaviour: sweeping, Infinity,
 * Riptide and knockback are vanilla enchantments rather than reimplemented effects.
 *
 * <p>Nothing here is random or time-dependent, so the same loadout produces identical items on
 * every round.
 */
public final class LoadoutBuilder {

    /** Damage is written as a modifier on top of the player's bare-hand 1.0. */
    private static final double BARE_HAND_DAMAGE = 1.0D;

    private LoadoutBuilder() {
    }

    /**
     * Builds every stack, in loadout order.
     *
     * <p>Companions are emitted here rather than in {@link #toStack}, because the stack list is the
     * only place that can express "this entry produced two things".
     */
    public static List<ItemStack> build(Loadout loadout, RegistryAccess registries, int teamIndex) {
        List<ItemStack> stacks = new ArrayList<>();
        if (loadout == null) {
            return stacks;
        }
        for (LoadoutEntry entry : loadout.entries) {
            ItemStack stack = toStack(entry, registries, teamIndex);
            if (stack.isEmpty()) {
                continue;
            }
            stacks.add(stack);
            ItemStack companion = companionStack(entry);
            if (!companion.isEmpty()) {
                stacks.add(companion);
            }
        }
        return stacks;
    }

    /**
     * The second stack an entry brings, or {@link ItemStack#EMPTY} when it brings none.
     *
     * <p>Arrows for a bow, obsidian for an end crystal, flint and steel for TNT. {@link #grant}
     * needs the same answer when it looks for a free slot, so it must not be written twice.
     *
     * <p>An Infinity bow gets exactly one arrow, because vanilla refuses to draw unless the
     * inventory holds an arrow and the enchantment keeps that one in place. The count field is
     * ignored for that case rather than clamped.
     */
    private static ItemStack companionStack(LoadoutEntry entry) {
        if (!entry.kind.hasCompanion()) {
            return ItemStack.EMPTY;
        }
        if (entry.kind == ItemKind.BOW && entry.has(WeaponFlag.INFINITY)) {
            return lockIfNeeded(new ItemStack(Items.ARROW, 1), entry);
        }
        if (entry.companionCount <= 0) {
            return ItemStack.EMPTY;
        }
        Item item = switch (entry.kind) {
            case BOW -> Items.ARROW;
            case END_CRYSTAL -> Items.OBSIDIAN;
            case TNT -> Items.FLINT_AND_STEEL;
            default -> null;
        };
        if (item == null) {
            return ItemStack.EMPTY;
        }
        return lockIfNeeded(new ItemStack(item, entry.companionCount), entry);
    }

    /**
     * Builds one stack, or {@link ItemStack#EMPTY} for an item the registry cannot resolve.
     */
    public static ItemStack toStack(LoadoutEntry entry, RegistryAccess registries, int teamIndex) {
        Item item = resolveItem(entry, teamIndex);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        int count = Math.max(1, Math.min(entry.count, item.getDefaultMaxStackSize()));
        ItemStack stack = new ItemStack(item, count);

        if (entry.kind == ItemKind.POTION) {
            applyPotion(stack, entry);
        } else {
            applyAttributes(stack, entry);
            applyDurability(stack, entry);
            applyEnchantments(stack, entry, registries);
            applyFireworks(stack, entry);
        }
        return lockIfNeeded(stack, entry);
    }

    /**
     * Applies the entry's {@code noDrop} to a stack it just produced.
     *
     * <p>Every stack the builder returns passes through here, including companion stacks, so the
     * three ways a loadout becomes items cannot drift.
     *
     * <p>The mark carries the entry's fingerprint rather than a bare flag, which lets
     * {@link LoadoutLock#restamp} take the lock back off a stack.
     */
    private static ItemStack lockIfNeeded(ItemStack stack, LoadoutEntry entry) {
        if (entry.noDrop) {
            LoadoutLock.mark(stack, entry.fingerprint());
        }
        return stack;
    }

    private static Item resolveItem(LoadoutEntry entry, int teamIndex) {
        return switch (entry.kind) {
            case POTION -> Items.SPLASH_POTION;
            case FOOD, BLOCK -> PvCompat.value(BuiltInRegistries.ITEM,
                    teamAdjustedVariant(entry, teamIndex));
            default -> entry.kind.resolve(entry.material);
        };
    }

    /**
     * Rewrites the white wool entry into the side's colour.
     *
     * <p>Both sides get wool, but not the same wool, so a player can tell their own structure from
     * the opponent's. Only the sentinel id is rewritten; a specific colour the player picked wins.
     */
    private static ResourceLocation teamAdjustedVariant(LoadoutEntry entry, int teamIndex) {
        String variant = entry.variant;
        if (entry.kind == ItemKind.BLOCK && "minecraft:white_wool".equals(variant)) {
            variant = "minecraft:" + LoadoutCatalog.teamColor(teamIndex) + "_wool";
        }
        return ResourceLocation.tryParse(variant);
    }

    private static void applyAttributes(ItemStack stack, LoadoutEntry entry) {
        if (entry.damageTenths <= 0 && entry.armorTenths <= 0) {
            return;
        }
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        if (entry.damageTenths > 0) {
            // The field is total damage, the modifier is the bonus over a bare hand.
            double bonus = Math.max(0.0D, entry.damage() - BARE_HAND_DAMAGE);
            builder.add(Attributes.ATTACK_DAMAGE,
                    new AttributeModifier(PvPocket.id("loadout.damage"), bonus,
                            AttributeModifier.Operation.ADD_VALUE),
                    EquipmentSlotGroup.MAINHAND);
        }
        if (entry.armorTenths > 0) {
            builder.add(Attributes.ARMOR,
                    new AttributeModifier(PvPocket.id("loadout.armor"), entry.armor(),
                            AttributeModifier.Operation.ADD_VALUE),
                    armorSlot(entry.kind));
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
    }

    private static EquipmentSlotGroup armorSlot(ItemKind kind) {
        return switch (kind) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.ARMOR;
        };
    }

    private static void applyDurability(ItemStack stack, LoadoutEntry entry) {
        if (!entry.kind.hasDurability()) {
            // A hand-edited string can put a durability on a firework rocket, where max_damage
            // would draw a bar that never moves.
            return;
        }
        if (entry.durability == LoadoutEntry.DURABILITY_UNBREAKABLE) {
            PvCompat.makeUnbreakable(stack);
            return;
        }
        if (entry.durability > 0) {
            stack.set(DataComponents.MAX_DAMAGE, entry.durability);
            // A fresh item must not start damaged: MAX_DAMAGE alone keeps the base item's damage
            // value, which for a re-used stack carries the previous round's wear.
            stack.set(DataComponents.DAMAGE, 0);
        }
    }

    private static void applyEnchantments(ItemStack stack, LoadoutEntry entry, RegistryAccess registries) {
        ItemEnchantments.Mutable builder =
                new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        boolean any = false;
        if (entry.knockback > 0) {
            Holder<Enchantment> knockback = enchantment(registries,
                    net.minecraft.world.item.enchantment.Enchantments.KNOCKBACK);
            if (knockback != null) {
                builder.set(knockback, Math.min(entry.knockback, LoadoutCatalog.KNOCKBACK_MAX));
                any = true;
            }
        }
        for (WeaponFlag flag : WeaponFlag.decode(entry.flags)) {
            if (!flag.appliesTo(entry.kind)) {
                continue;
            }
            Holder<Enchantment> enchantment = enchantment(registries, flag.enchantment);
            if (enchantment != null) {
                builder.set(enchantment, 1);
                any = true;
            }
        }
        // Trident style is an enchantment but not a flag: it is one of three exclusive values, and
        // it is written at its maximum level rather than at 1 like the boolean flags.
        if (entry.kind == ItemKind.TRIDENT && entry.tridentStyle.enchantment != null) {
            Holder<Enchantment> style = enchantment(registries, entry.tridentStyle.enchantment);
            if (style != null) {
                builder.set(style, TridentStyle.LEVEL);
                any = true;
            }
        }
        if (any) {
            stack.set(DataComponents.ENCHANTMENTS, builder.toImmutable());
        }
    }

    /**
     * Gives a firework rocket its flight duration and nothing else.
     *
     * <p>A rocket with a firework star deals area damage when it bursts, so the explosion list
     * stays empty and the rocket is pure thrust.
     */
    private static void applyFireworks(ItemStack stack, LoadoutEntry entry) {
        if (entry.kind != ItemKind.FIREWORK_ROCKET) {
            return;
        }
        int flight = Math.max(LoadoutCatalog.FIREWORK_FLIGHT_MIN,
                Math.min(LoadoutCatalog.FIREWORK_FLIGHT_MAX, entry.fireworkFlight));
        stack.set(DataComponents.FIREWORKS, new Fireworks(flight, List.of()));
    }

    private static Holder<Enchantment> enchantment(RegistryAccess registries,
                                                          net.minecraft.resources.ResourceKey<Enchantment> key) {
        return PvCompat.holder(registries.registryOrThrow(Registries.ENCHANTMENT), key);
    }

    private static void applyPotion(ItemStack stack, LoadoutEntry entry) {
        Holder.Reference<MobEffect> effect = PvCompat.holder(BuiltInRegistries.MOB_EFFECT,
                ResourceLocation.tryParse(entry.variant));
        if (effect == null) {
            return;
        }
        // Duration is seconds in the editor and ticks in the game; instant effects ignore it.
        int ticks = Math.max(20, entry.durationSec * 20);
        MobEffectInstance instance = new MobEffectInstance(effect, ticks, entry.amplifier);
        stack.set(DataComponents.POTION_CONTENTS, PvCompat.potionContents(instance));
    }

    /**
     * Hands the loadout over without touching what the player already carries.
     *
     * <p>Used by the {@code /pvpocket loadout give} test path. {@link #grant} clears the inventory
     * instead, which is only correct at round start.
     */
    public static int give(ServerPlayer player, Loadout loadout, int teamIndex) {
        int given = 0;
        for (ItemStack stack : build(loadout, player.serverLevel().registryAccess(), teamIndex)) {
            if (stack.isEmpty()) {
                continue;
            }
            player.getInventory().placeItemBackInInventory(stack);
            given++;
        }
        player.containerMenu.broadcastChanges();
        return given;
    }

    /**
     * Replaces the player's inventory with the loadout.
     *
     * <p>Call after the snapshot is on disk: the snapshot is the only copy of the survival
     * inventory, and clearing first would delete it.
     */
    public static int grant(ServerPlayer player, Loadout loadout, int teamIndex) {
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        int granted = 0;
        for (LoadoutEntry entry : loadout.entries) {
            if (entry.slot < 0 || entry.slot >= 41) {
                continue;
            }
            ItemStack stack = toStack(entry, player.serverLevel().registryAccess(), teamIndex);
            if (stack.isEmpty()) {
                continue;
            }
            inventory.setItem(entry.slot, stack);
            granted++;
        }
        // The companion is a second stack for one entry and has no slot of its own, so it goes to
        // the first free slot rather than overwriting whatever the player put there.
        for (LoadoutEntry entry : loadout.entries) {
            ItemStack companion = companionStack(entry);
            if (companion.isEmpty()) {
                continue;
            }
            if (!inventory.add(companion)) {
                player.drop(companion, false);
            }
        }
        player.containerMenu.broadcastChanges();
        return granted;
    }
}
