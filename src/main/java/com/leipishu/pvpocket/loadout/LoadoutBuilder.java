package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Unbreakable;
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
     * The second stack an entry brings, or {@link ItemStack#EMPTY} for the kinds that bring one.
     *
     * <p>Arrows for a bow, obsidian for an end crystal, flint and steel for TNT. One method because
     * the three are one shape: the entry is a single decision, the grant is two stacks, and the count
     * is the field the editor shows. {@link #grant} needs the same answer when it has to find a free
     * slot for it, so it must not be written twice.</p>
     *
     * <p><b>An Infinity bow still gets exactly one arrow.</b> It used to get none, on the reasoning
     * that Infinity means the arrow is never consumed — but vanilla refuses to draw at all unless the
     * inventory holds at least one arrow, so a bow granted without any is a bow that cannot shoot.
     * One is the whole of what it needs: the enchantment keeps it there. The count field is ignored
     * for that case rather than clamped, because "32 arrows" and "one arrow" are the same thing to a
     * bow with Infinity and the editor has no business asking.</p>
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

    /** Builds one stack. Returns {@link ItemStack#EMPTY} for anything the item registry cannot resolve. */
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
            case FOOD, BLOCK -> BuiltInRegistries.ITEM.get(teamAdjustedVariant(entry, teamIndex));
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
            // A hand-edited string can put a durability on a firework rocket. Writing max_damage
            // there would produce an item with a durability bar that never moves, so the field is
            // ignored rather than honoured.
            return;
        }
        if (entry.durability == LoadoutEntry.DURABILITY_UNBREAKABLE) {
            stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
            return;
        }
        if (entry.durability > 0) {
            stack.set(DataComponents.MAX_DAMAGE, entry.durability);
            // A fresh item must not start damaged: MAX_DAMAGE alone leaves the damage value from the
            // base item, which for a re-used stack would carry a previous round's wear into this one.
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
        stack.set(DataComponents.FIREWORKS, new Fireworks(flight, List.of()));
    }

    private static Holder<Enchantment> enchantment(RegistryAccess registries,
                                                          net.minecraft.resources.ResourceKey<Enchantment> key) {
        return registries.registryOrThrow(Registries.ENCHANTMENT).getHolder(key).orElse(null);
    }

    private static void applyPotion(ItemStack stack, LoadoutEntry entry) {
        Optional<Holder.Reference<MobEffect>> effect =
                BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.tryParse(entry.variant));
        if (effect.isEmpty()) {
            return;
        }
        // Duration is expressed in seconds in the editor and in ticks in the game. Instant effects
        // ignore it, which is why the editor greys the row out for those rather than pretending.
        int ticks = Math.max(20, entry.durationSec * 20);
        MobEffectInstance instance = new MobEffectInstance(effect.get(), ticks, entry.amplifier);
        int color = PotionContents.getColor(List.of(instance));
        stack.set(DataComponents.POTION_CONTENTS,
                new PotionContents(Optional.empty(), Optional.of(color), List.of(instance)));
    }

    /**
     * Hands the loadout over without touching what the player already carries.
     *
     * <p>This exists for the {@code /pvpocket loadout give} test path. It is deliberately a different
     * method from {@link #grant}: clearing the inventory is correct at round start, where the snapshot
     * already holds the survival contents, and catastrophic anywhere else.</p>
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
     * <p>Called after the snapshot is on disk, never before: the snapshot is the only copy of the
     * survival inventory, and clearing first would delete it.</p>
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
