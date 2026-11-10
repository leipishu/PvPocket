package com.leipishu.pvpocket.client.gui.loadout;

import com.leipishu.pvpocket.loadout.ItemKind;
import com.leipishu.pvpocket.loadout.LoadoutCatalog;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.loadout.Material;
import com.leipishu.pvpocket.loadout.TridentStyle;
import com.leipishu.pvpocket.loadout.WeaponFlag;
import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.rules.RuleGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Describes one loadout row's editable fields as ordinary {@link RuleDefinition}s.
 *
 * <p>The whole point of doing it this way is that the loadout editor does not need a second widget
 * vocabulary. A damage slider is a slider; a material picker is a cycle; an enchantment toggle is a
 * toggle. Reusing {@code RuleRow} and the control set means the editor looks and behaves exactly like
 * the rule panel, which is what makes two screens feel like one product.</p>
 *
 * <p>Fields that do not apply to a kind are not built at all rather than shown disabled. A greyed-out
 * "Infinity" on a sword is a promise that it could be enabled one day; a field that is simply absent
 * is the truth.</p>
 *
 * <p>Every field's id is a translation key stem: {@code lo.count} reads its label from
 * {@code pvpocket.rule.lo.count.label} and its hint from {@code .hint}, exactly like a real rule. The
 * choice arrays below are arrays of <em>keys</em> taken from the catalog the values come from, so a
 * material renamed in one place cannot end up labelled as another.</p>
 */
public final class LoadoutFields {

    /** A definition plus how to read and write the value it edits. */
    public record Field(RuleDefinition def, IntSupplier get, IntConsumer set) {
    }

    private LoadoutFields() {
    }

    public static List<Field> of(LoadoutEntry entry) {
        List<Field> out = new ArrayList<>();
        ItemKind kind = entry.kind;

        out.add(new Field(
                RuleDefinition.intRange("lo.count", RuleGroup.D, 1, 64, 1, entry.count),
                () -> entry.count,
                v -> entry.count = v));

        if (kind.materialised) {
            Material[] materials = Material.values();
            String[] labels = Arrays.stream(materials).map(m -> m.labelKey).toArray(String[]::new);
            out.add(new Field(
                    RuleDefinition.enumOf("lo.material", RuleGroup.D,
                            Math.max(0, Math.min(labels.length - 1, entry.material.ordinal())), labels),
                    () -> entry.material.ordinal(),
                    v -> entry.material = materials[Math.floorMod(v, materials.length)]));
        }

        if (kind.page == ItemKind.Page.WEAPON || kind == ItemKind.PICKAXE) {
            out.add(new Field(
                    RuleDefinition.decimal("lo.damage", RuleGroup.D,
                            LoadoutCatalog.DAMAGE_MIN_TENTHS, LoadoutCatalog.DAMAGE_MAX_TENTHS, 5,
                            entry.damageTenths == 0 ? 70 : entry.damageTenths),
                    () -> entry.damageTenths,
                    v -> entry.damageTenths = v));
        }

        if (kind.isArmor()) {
            out.add(new Field(
                    RuleDefinition.decimal("lo.armor", RuleGroup.D,
                            0, LoadoutCatalog.ARMOR_MAX_TENTHS, 5, entry.armorTenths),
                    () -> entry.armorTenths,
                    v -> entry.armorTenths = v));
        }

        if (kind.hasDurability()) {
            out.add(new Field(
                    RuleDefinition.intRange("lo.durability", RuleGroup.D,
                            LoadoutEntry.DURABILITY_DEFAULT, LoadoutCatalog.DURABILITY_MAX, 50,
                            Math.max(0, entry.durability)),
                    () -> Math.max(0, entry.durability),
                    v -> entry.durability = v));
            out.add(new Field(
                    RuleDefinition.toggle("lo.unbreakable", RuleGroup.D,
                            entry.durability == LoadoutEntry.DURABILITY_UNBREAKABLE),
                    () -> entry.durability == LoadoutEntry.DURABILITY_UNBREAKABLE ? 1 : 0,
                    v -> entry.durability = v != 0
                            ? LoadoutEntry.DURABILITY_UNBREAKABLE
                            : Math.max(1, entry.durability)));
        }

        if (kind.page == ItemKind.Page.WEAPON) {
            out.add(new Field(
                    RuleDefinition.intRange("lo.knockback", RuleGroup.D,
                            0, LoadoutCatalog.KNOCKBACK_MAX, 1, entry.knockback),
                    () -> entry.knockback,
                    v -> entry.knockback = v));
        }

        for (WeaponFlag flag : WeaponFlag.of(kind)) {
            out.add(new Field(
                    // The flag's own name and tooltip keys, not a copy under lo.*: an enchantment is
                    // named once and used by both the loadout editor and the diagnostic command.
                    RuleDefinition.toggleNamed("lo.flag." + flag.id, RuleGroup.D,
                            flag.labelKey, flag.hintKey, entry.has(flag)),
                    () -> entry.has(flag) ? 1 : 0,
                    v -> entry.set(flag, v != 0)));
        }

        if (kind.hasCompanion()) {
            // One field, three meanings: the label names the item that actually arrives, so the
            // player reads "obsidian" rather than a generic word they have to translate themselves.
            // Step 1 for TNT because one flint and steel is all it takes; steps of 8 for stacks you
            // bring by the handful.
            int step = kind == ItemKind.TNT ? 1 : 8;
            out.add(new Field(
                    RuleDefinition.intRange(companionFieldId(kind), RuleGroup.D,
                            0, LoadoutCatalog.COMPANION_MAX, step, entry.companionCount),
                    () -> entry.companionCount,
                    v -> entry.companionCount = v));
        }

        switch (kind) {
            case POTION -> {
                List<LoadoutCatalog.EffectOption> effects = LoadoutCatalog.effects();
                String[] labels = effects.stream()
                        .map(LoadoutCatalog.EffectOption::pickKey)
                        .toArray(String[]::new);
                out.add(new Field(
                        RuleDefinition.enumOf("lo.effect", RuleGroup.D,
                                indexOfEffect(effects, entry.variant), labels),
                        () -> indexOfEffect(effects, entry.variant),
                        v -> entry.variant = effects.get(Math.floorMod(v, effects.size())).id()));
                out.add(new Field(
                        RuleDefinition.intRange("lo.duration", RuleGroup.D,
                                LoadoutCatalog.DURATION_MIN_SEC, LoadoutCatalog.DURATION_MAX_SEC, 5,
                                Math.max(LoadoutCatalog.DURATION_MIN_SEC, entry.durationSec)),
                        () -> entry.durationSec,
                        v -> entry.durationSec = v));
                out.add(new Field(
                        RuleDefinition.enumOf("lo.amplifier", RuleGroup.D,
                                Math.max(0, Math.min(LoadoutCatalog.AMPLIFIER_MAX, entry.amplifier)),
                                RuleDefinition.choices("lo.amplifier", 5)),
                        () -> entry.amplifier,
                        v -> entry.amplifier = v));
            }
            case FOOD -> {
                List<LoadoutCatalog.Option> foods = LoadoutCatalog.foods();
                String[] labels = foods.stream()
                        .map(LoadoutCatalog.Option::labelKey)
                        .toArray(String[]::new);
                out.add(new Field(
                        RuleDefinition.enumOf("lo.food", RuleGroup.D,
                                indexOfOption(foods, entry.variant), labels),
                        () -> indexOfOption(foods, entry.variant),
                        v -> entry.variant = foods.get(Math.floorMod(v, foods.size())).id()));
            }
            case BLOCK -> {
                List<LoadoutCatalog.Option> blocks = LoadoutCatalog.blocks();
                String[] labels = blocks.stream()
                        .map(LoadoutCatalog.Option::labelKey)
                        .toArray(String[]::new);
                out.add(new Field(
                        RuleDefinition.enumOf("lo.block", RuleGroup.D,
                                indexOfOption(blocks, entry.variant), labels),
                        () -> indexOfOption(blocks, entry.variant),
                        v -> entry.variant = blocks.get(Math.floorMod(v, blocks.size())).id()));
            }
            case TRIDENT -> {
                // Three choices in one cycle, not two toggles: 忠诚 and 激流 are alternatives, and a
                // pair of switches that silently turn each other off is a puzzle, not a control.
                TridentStyle[] styles = TridentStyle.values();
                String[] labels = Arrays.stream(styles)
                        .map(s -> s.labelKey)
                        .toArray(String[]::new);
                out.add(new Field(
                        RuleDefinition.enumOf("lo.trident", RuleGroup.D,
                                entry.tridentStyle.ordinal(), labels),
                        () -> entry.tridentStyle.ordinal(),
                        v -> entry.tridentStyle = styles[Math.floorMod(v, styles.length)]));
            }
            case FIREWORK_ROCKET -> out.add(new Field(
                    RuleDefinition.intRange("lo.firework_flight", RuleGroup.D,
                            LoadoutCatalog.FIREWORK_FLIGHT_MIN, LoadoutCatalog.FIREWORK_FLIGHT_MAX, 1,
                            Math.max(LoadoutCatalog.FIREWORK_FLIGHT_MIN,
                                    Math.min(LoadoutCatalog.FIREWORK_FLIGHT_MAX, entry.fireworkFlight))),
                    () -> entry.fireworkFlight,
                    v -> entry.fireworkFlight = v));
            default -> {
            }
        }

        // Last on purpose. Everything above describes what the item *is*; this one describes a rule
        // about it, and putting it between two "what it is" rows would read as another attribute.
        // It applies to every kind, including potions and blocks, so it cannot live in the switch.
        out.add(new Field(
                RuleDefinition.toggle("lo.no_drop", RuleGroup.D, entry.noDrop),
                () -> entry.noDrop ? 1 : 0,
                v -> entry.noDrop = v != 0));

        return out;
    }

    /** The rule id whose label names the companion item for this kind. */
    private static String companionFieldId(ItemKind kind) {
        return switch (kind) {
            case END_CRYSTAL -> "lo.companion.crystal";
            case TNT -> "lo.companion.tnt";
            default -> "lo.arrows";
        };
    }

    private static int indexOfOption(List<LoadoutCatalog.Option> options, String id) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private static int indexOfEffect(List<LoadoutCatalog.EffectOption> options, String id) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(id)) {
                return i;
            }
        }
        return 0;
    }
}
