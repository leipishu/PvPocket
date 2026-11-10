package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.component.type.UnbreakableComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a {@link Loadout} into real items.
 *
 * <p>Three decisions shape everything here:</p>
 *
 * <ol>
 *   <li><b>Attributes are replaced, not appended.</b> A vanilla iron sword carries
 *       {@code attribute_modifiers} that make it hit for 6. If the loadout only <em>added</em> a
 *       modifier, the material would still matter and the player's chosen damage would be a lie. The
 *       component is therefore overwritten wholesale, which is what makes "material is display only"
 *       actually true.</li>
 *   <li><b>Enchantments carry the behaviour.</b> Sweeping, Infinity, Riptide and knockback are real
 *       vanilla enchantments rather than re-implemented effects, so they behave like the thing the
 *       player expects and the tooltip tells the truth.</li>
 *   <li><b>Nothing here is random or time-dependent.</b> The same loadout must produce byte-identical
 *       items every round, otherwise "reset to the initial state" would drift.</li>
 * </ol>
 */
public final class LoadoutBuilder {

    /** Damage is written as a modifier on top of the player's bare-hand 1.0. */
    private static final double BARE_HAND_DAMAGE = 1.0D;

    private LoadoutBuilder() {
    }

    /**
     * Builds every stack, in loadout order.
     *
     * <p>Companions are emitted here rather than inside {@link #toStack} because a bow without
     * Infinity, an end crystal and TNT each need a second item: the stack list is the only place
     * that can express "this entry produced two things".</p>
     */
    public static List<ItemStack> build(Loadout loadout, DynamicRegistryManager registries, int teamIndex) {
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
     * The second stack an entry brings, or {@link ItemStack#EMPTY} for the kinds that bring one.
     *
     * <p>Arrows for a bow (unless it has Infinity), obsidian for an end crystal, flint and steel for
     * TNT. One method because the three are one shape: the entry is a single decision, the grant is
     * two stacks, and the count is the field the editor shows. {@link #grant} needs the same answer
     * when it has to find a free slot for it, so it must not be written twice.</p>
     */
    private static ItemStack companionStack(LoadoutEntry entry) {
        if (entry.companionCount <= 0 || !entry.kind.hasCompanion()) {
            return ItemStack.EMPTY;
        }
        if (entry.kind == ItemKind.BOW && entry.has(WeaponFlag.INFINITY)) {
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

    /** Builds one stack. Returns {@link ItemStack#EMPTY} for anything the item registry cannot resolve. */
    public static ItemStack toStack(LoadoutEntry entry, DynamicRegistryManager registries, int teamIndex) {
        Item item = resolveItem(entry, teamIndex);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        int count = Math.max(1, Math.min(entry.count, item.getMaxCount()));
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
     * <p>Written here rather than in {@link #grant} so the two other ways a loadout becomes items —
     * {@code /pvpocket loadout give} and the companion stacks — cannot drift: every stack the builder
     * returns has been through this, or it has no lock because the entry asked for none.</p>
     *
     * <p>The mark carries the entry's fingerprint rather than a bare {@code true}, which is what lets
     * {@link LoadoutLock#restamp} take the lock back off a stack when the player turns the switch off
     * after the item was already handed out.</p>
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
            case FOOD, BLOCK -> Registries.ITEM.get(teamAdjustedVariant(entry, teamIndex));
            default -> entry.kind.resolve(entry.material);
        };
    }

    /**
     * Rewrites the white wool entry into the side's colour.
     *
     * <p>"羊毛套娃" — both sides get wool, but not the same wool, so a player can tell their own
     * structure from the opponent's at a glance. Only the sentinel id is rewritten; if a player
     * deliberately picks a specific colour, that choice wins.</p>
     */
    private static Identifier teamAdjustedVariant(LoadoutEntry entry, int teamIndex) {
        String variant = entry.variant;
        if (entry.kind == ItemKind.BLOCK && "minecraft:white_wool".equals(variant)) {
            variant = "minecraft:" + LoadoutCatalog.teamColor(teamIndex) + "_wool";
        }
        return Identifier.tryParse(variant);
    }

    private static void applyAttributes(ItemStack stack, LoadoutEntry entry) {
        if (entry.damageTenths <= 0 && entry.armorTenths <= 0) {
            return;
        }
        AttributeModifiersComponent.Builder builder = AttributeModifiersComponent.builder();
        if (entry.damageTenths > 0) {
            // The field is total damage, the modifier is the bonus over a bare hand.
            double bonus = Math.max(0.0D, entry.damage() - BARE_HAND_DAMAGE);
            builder.add(EntityAttributes.GENERIC_ATTACK_DAMAGE,
                    new EntityAttributeModifier(PvPocket.id("loadout.damage"), bonus,
                            EntityAttributeModifier.Operation.ADD_VALUE),
                    AttributeModifierSlot.MAINHAND);
        }
        if (entry.armorTenths > 0) {
            builder.add(EntityAttributes.GENERIC_ARMOR,
                    new EntityAttributeModifier(PvPocket.id("loadout.armor"), entry.armor(),
                            EntityAttributeModifier.Operation.ADD_VALUE),
                    armorSlot(entry.kind));
        }
        stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
    }

    private static AttributeModifierSlot armorSlot(ItemKind kind) {
        return switch (kind) {
            case HELMET -> AttributeModifierSlot.HEAD;
            case CHESTPLATE -> AttributeModifierSlot.CHEST;
            case LEGGINGS -> AttributeModifierSlot.LEGS;
            case BOOTS -> AttributeModifierSlot.FEET;
            default -> AttributeModifierSlot.ARMOR;
        };
    }

    private static void applyDurability(ItemStack stack, LoadoutEntry entry) {
        if (!entry.kind.hasDurability()) {
            // A hand-edited string can put a durability on a firework rocket. Writing max_damage
            // there would produce an item with a durability bar that never moves, so the field is
            // ignored rather than honoured.
            return;
        }
        if (entry.durability == LoadoutEntry.DURABILITY_UNBREAKABLE) {
            stack.set(DataComponentTypes.UNBREAKABLE, new UnbreakableComponent(true));
            return;
        }
        if (entry.durability > 0) {
            stack.set(DataComponentTypes.MAX_DAMAGE, entry.durability);
            // A fresh item must not start damaged: MAX_DAMAGE alone leaves the damage value from the
            // base item, which for a re-used stack would carry a previous round's wear into this one.
            stack.set(DataComponentTypes.DAMAGE, 0);
        }
    }

    private static void applyEnchantments(ItemStack stack, LoadoutEntry entry, DynamicRegistryManager registries) {
        ItemEnchantmentsComponent.Builder builder =
                new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        boolean any = false;
        if (entry.knockback > 0) {
            RegistryEntry<Enchantment> knockback = enchantment(registries,
                    net.minecraft.enchantment.Enchantments.KNOCKBACK);
            if (knockback != null) {
                builder.add(knockback, Math.min(entry.knockback, LoadoutCatalog.KNOCKBACK_MAX));
                any = true;
            }
        }
        for (WeaponFlag flag : WeaponFlag.decode(entry.flags)) {
            if (!flag.appliesTo(entry.kind)) {
                continue;
            }
            RegistryEntry<Enchantment> enchantment = enchantment(registries, flag.enchantment);
            if (enchantment != null) {
                builder.add(enchantment, 1);
                any = true;
            }
        }
        // Trident style is an enchantment but not a flag: it is one of three exclusive values, and
        // it is written at its maximum level rather than at 1 like the boolean flags.
        if (entry.kind == ItemKind.TRIDENT && entry.tridentStyle.enchantment != null) {
            RegistryEntry<Enchantment> style = enchantment(registries, entry.tridentStyle.enchantment);
            if (style != null) {
                builder.add(style, TridentStyle.LEVEL);
                any = true;
            }
        }
        if (any) {
            stack.set(DataComponentTypes.ENCHANTMENTS, builder.build());
        }
    }

    /**
     * Gives a firework rocket its flight duration and nothing else.
     *
     * <p>No explosions on purpose: a rocket with a firework star deals area damage when it bursts,
     * which would quietly turn the elytra's fuel into a weapon. The empty explosion list is what
     * makes it pure thrust.</p>
     */
    private static void applyFireworks(ItemStack stack, LoadoutEntry entry) {
        if (entry.kind != ItemKind.FIREWORK_ROCKET) {
            return;
        }
        int flight = Math.max(LoadoutCatalog.FIREWORK_FLIGHT_MIN,
                Math.min(LoadoutCatalog.FIREWORK_FLIGHT_MAX, entry.fireworkFlight));
        stack.set(DataComponentTypes.FIREWORKS, new FireworksComponent(flight, List.of()));
    }

    private static RegistryEntry<Enchantment> enchantment(DynamicRegistryManager registries,
                                                          net.minecraft.registry.RegistryKey<Enchantment> key) {
        return registries.get(RegistryKeys.ENCHANTMENT).getEntry(key).orElse(null);
    }

    private static void applyPotion(ItemStack stack, LoadoutEntry entry) {
        Optional<RegistryEntry.Reference<StatusEffect>> effect =
                Registries.STATUS_EFFECT.getEntry(Identifier.tryParse(entry.variant));
        if (effect.isEmpty()) {
            return;
        }
        // Duration is expressed in seconds in the editor and in ticks in the game. Instant effects
        // ignore it, which is why the editor greys the row out for those rather than pretending.
        int ticks = Math.max(20, entry.durationSec * 20);
        StatusEffectInstance instance = new StatusEffectInstance(effect.get(), ticks, entry.amplifier);
        int color = PotionContentsComponent.getColor(List.of(instance));
        stack.set(DataComponentTypes.POTION_CONTENTS,
                new PotionContentsComponent(Optional.empty(), Optional.of(color), List.of(instance)));
    }

    /**
     * Hands the loadout over without touching what the player already carries.
     *
     * <p>This exists for the {@code /pvpocket loadout give} test path. It is deliberately a different
     * method from {@link #grant}: clearing the inventory is correct at round start, where the snapshot
     * already holds the survival contents, and catastrophic anywhere else.</p>
     */
    public static int give(ServerPlayerEntity player, Loadout loadout, int teamIndex) {
        int given = 0;
        for (ItemStack stack : build(loadout, player.getServerWorld().getRegistryManager(), teamIndex)) {
            if (stack.isEmpty()) {
                continue;
            }
            player.getInventory().offerOrDrop(stack);
            given++;
        }
        player.currentScreenHandler.sendContentUpdates();
        return given;
    }

    /**
     * Replaces the player's inventory with the loadout.
     *
     * <p>Called after the snapshot is on disk, never before: the snapshot is the only copy of the
     * survival inventory, and clearing first would delete it.</p>
     */
    public static int grant(ServerPlayerEntity player, Loadout loadout, int teamIndex) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        int granted = 0;
        for (LoadoutEntry entry : loadout.entries) {
            if (entry.slot < 0 || entry.slot >= 41) {
                continue;
            }
            ItemStack stack = toStack(entry, player.getServerWorld().getRegistryManager(), teamIndex);
            if (stack.isEmpty()) {
                continue;
            }
            inventory.setStack(entry.slot, stack);
            granted++;
        }
        // The companion is a second stack for one entry and has no slot of its own, so it goes to
        // the first free slot rather than overwriting whatever the player put there.
        for (LoadoutEntry entry : loadout.entries) {
            ItemStack companion = companionStack(entry);
            if (companion.isEmpty()) {
                continue;
            }
            if (!inventory.insertStack(companion)) {
                player.dropItem(companion, false);
            }
        }
        player.currentScreenHandler.sendContentUpdates();
        return granted;
    }
}
