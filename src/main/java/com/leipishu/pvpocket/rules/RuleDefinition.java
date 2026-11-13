package com.leipishu.pvpocket.rules;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

/**
 * One configurable rule.
 *
 * <p>Immutable and shared by both sides: the catalog is compiled into the jar, so the server sends
 * only values. A rule the client does not know is kept as an opaque value by {@link DuelRules}.
 *
 * <p>Text is not stored here. Every string is a translation key derived from {@link #id}:
 * {@code pvpocket.rule.<id>.label}, {@code .hint}, {@code .c<N>} for the Nth choice and
 * {@code .unit} for the suffix a numeric value carries.
 */
public final class RuleDefinition {

    public final String id;
    public final RuleGroup group;
    /** Label key, {@code pvpocket.rule.<id>.label}. */
    public final String labelKey;
    /** Hint key, {@code pvpocket.rule.<id>.hint}. */
    public final String hintKey;
    public final RuleKind kind;

    public final int min;
    public final int max;
    public final int step;
    /** Translation key per choice; null for kinds that have none. */
    public final String[] choiceKeys;
    public final int defaultValue;
    /**
     * Format template for the value's suffix, e.g. {@code "%s s"}. Null when the value stands
     * alone, as a percentage and a bare count have nothing to append.
     */
    public final String unitKey;
    /** Renders an INT rule as a percentage of {@code max} (knockback resistance). */
    public final boolean percent;
    /**
     * Value is divided by this before display. 1 for every rule; 10 for the loadout editor's
     * damage and armour fields, which are stored in tenths so that 6.5 is expressible.
     */
    public final int divisor;

    private RuleDefinition(String id, RuleGroup group, String labelKey, String hintKey,
                           RuleKind kind, int min, int max, int step, String[] choiceKeys,
                           int defaultValue, String unitKey, boolean percent, int divisor) {
        this.id = id;
        this.group = group;
        this.labelKey = labelKey;
        this.hintKey = hintKey;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.step = step;
        this.choiceKeys = choiceKeys;
        this.defaultValue = defaultValue;
        this.unitKey = unitKey;
        this.percent = percent;
        this.divisor = divisor;
    }

    // ------------------------------------------------------------------ key layout

    public static String labelKey(String id) {
        return "pvpocket.rule." + id + ".label";
    }

    public static String hintKey(String id) {
        return "pvpocket.rule." + id + ".hint";
    }

    public static String unitKey(String id) {
        return "pvpocket.rule." + id + ".unit";
    }

    /** Choice keys {@code .c0 .. .c<n-1>} for a rule whose choices are written out in order. */
    public static String[] choices(String id, int count) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = "pvpocket.rule." + id + ".c" + i;
        }
        return out;
    }

    // ------------------------------------------------------------------ factories

    public static RuleDefinition toggle(String id, RuleGroup g, boolean def) {
        return new RuleDefinition(id, g, labelKey(id), hintKey(id), RuleKind.TOGGLE, 0, 1, 1, null,
                def ? 1 : 0, null, false, 1);
    }

    /**
     * A toggle whose label and hint keys belong to another rule.
     *
     * <p>The row id is {@code lo.flag.infinity} so it cannot collide with a rule, while the words
     * come from {@code pvpocket.flag.infinity}, the same key the diagnostic command prints.
     */
    public static RuleDefinition toggleNamed(String id, RuleGroup g, String labelKey, String hintKey,
                                             boolean def) {
        return new RuleDefinition(id, g, labelKey, hintKey, RuleKind.TOGGLE, 0, 1, 1, null,
                def ? 1 : 0, null, false, 1);
    }

    public static RuleDefinition intRange(String id, RuleGroup g, int min, int max, int step, int def) {
        return new RuleDefinition(id, g, labelKey(id), hintKey(id), RuleKind.INT, min, max, step, null,
                def, unitKey(id), false, 1);
    }

    /**
     * An INT rule displayed with one decimal place.
     *
     * <p>Stored value is tenths, and {@code min}, {@code max}, {@code step} and {@code def} are in
     * tenths too. Used by the loadout editor, where 7.0 and 7.5 damage are different weapons.
     */
    public static RuleDefinition decimal(String id, RuleGroup g, int min, int max, int step, int def) {
        return new RuleDefinition(id, g, labelKey(id), hintKey(id), RuleKind.INT, min, max, step, null,
                def, unitKey(id), false, 10);
    }

    public static RuleDefinition percent(String id, RuleGroup g, int def) {
        return new RuleDefinition(id, g, labelKey(id), hintKey(id), RuleKind.INT, 0, 100, 5, null,
                def, null, true, 1);
    }

    public static RuleDefinition enumOf(String id, RuleGroup g, int def, String... choiceKeys) {
        return new RuleDefinition(id, g, labelKey(id), hintKey(id), RuleKind.ENUM, 0,
                choiceKeys.length - 1, 1, choiceKeys, def, null, false, 1);
    }

    public static RuleDefinition multi(String id, RuleGroup g, int def, String... choiceKeys) {
        return new RuleDefinition(id, g, labelKey(id), hintKey(id), RuleKind.MULTI, 0,
                (1 << choiceKeys.length) - 1, 1, choiceKeys, def, null, false, 1);
    }

    public static RuleDefinition fixed(String id, RuleGroup g, String shownKey) {
        return new RuleDefinition(id, g, labelKey(id), hintKey(id), RuleKind.FIXED, 0, 0, 1,
                new String[]{shownKey}, 0, null, false, 1);
    }

    // ------------------------------------------------------------------ text

    public MutableComponent labelText() {
        return PvLang.t(labelKey);
    }

    public MutableComponent hintText() {
        return PvLang.t(hintKey);
    }

    /** Label for the self-drawn GUI, which measures strings rather than {@link Text}. */
    public String label() {
        return PvLang.str(labelKey);
    }

    /** Tooltip for the self-drawn GUI. */
    public String hint() {
        return PvLang.str(hintKey);
    }

    public int choiceCount() {
        return choiceKeys == null ? 0 : choiceKeys.length;
    }

    /** The Nth choice as {@link Text}, for chat. Out-of-range values clamp to the first choice. */
    public MutableComponent choiceText(int value) {
        if (choiceCount() == 0) {
            return Component.empty();
        }
        int i = Math.max(0, Math.min(choiceKeys.length - 1, value));
        return PvLang.t(choiceKeys[i]);
    }

    /** The Nth choice as a string, for the self-drawn GUI. */
    public String choiceLabel(int value) {
        return PvLang.str(choiceText(value));
    }

    // ------------------------------------------------------------------ values

    /**
     * Clamps a raw value into the range this definition accepts.
     *
     * <p>INT rules are also snapped onto the {@link #step} grid, which is anchored at {@link #min}
     * rather than at zero: {@code e.sizeX} runs 16, 24, ... 224. A value that arrived from outside
     * the panel, such as a stored {@code e.sizeX=45}, is moved to 48.
     */
    public int clamp(int value) {
        switch (kind) {
            case TOGGLE:
                return value == 0 ? 0 : 1;
            case INT: {
                int v = Math.max(min, Math.min(max, value));
                if (step > 1) {
                    // Round to nearest, then step back: max need not sit on the grid.
                    int snapped = min + Math.round((v - min) / (float) step) * step;
                    if (snapped > max) {
                        snapped -= step;
                    }
                    v = Math.max(min, snapped);
                }
                return v;
            }
            case ENUM:
                return Math.max(min, Math.min(max, value));
            case MULTI:
                return value & max;
            default:
                return 0;
        }
    }

    /** Steps a value by {@code direction} units, honouring {@link #step} for INT rules. */
    public int step(int value, int direction) {
        if (kind == RuleKind.INT) {
            int next = value + direction * step;
            if (next > max) {
                next = max;
            }
            if (next < min) {
                next = min;
            }
            return next;
        }
        if (kind == RuleKind.ENUM) {
            int span = max - min + 1;
            return min + Math.floorMod(value - min + direction, span);
        }
        return clamp(value);
    }

    /**
     * Human-readable value, used by the panel and by every chat line that quotes a rule.
     *
     * <p>Returns a component rather than a string because the server has no language and would
     * otherwise bake the wrong words into the value.
     */
    public MutableComponent formatText(int value) {
        switch (kind) {
            case TOGGLE:
                return PvLang.t(value == 0 ? "pvpocket.value.off" : "pvpocket.value.on");
            case ENUM:
                return choiceText(value);
            case MULTI: {
                MutableComponent out = Component.empty();
                boolean first = true;
                for (int i = 0; i < choiceCount(); i++) {
                    if ((value & (1 << i)) != 0) {
                        if (!first) {
                            out.append(Component.literal("·"));
                        }
                        out.append(PvLang.t(choiceKeys[i]));
                        first = false;
                    }
                }
                return first ? PvLang.t("pvpocket.value.none") : out;
            }
            case FIXED:
                return choiceText(0);
            default:
                if (percent) {
                    return Component.literal(value + "%");
                }
                String number = divisor > 1
                        ? String.format("%.1f", value / (double) divisor)
                        : Integer.toString(value);
                return unitKey == null ? Component.literal(number) : PvLang.t(unitKey, number);
        }
    }

    /** The same value as a string, for the self-drawn GUI. */
    public String format(int value) {
        return PvLang.str(formatText(value));
    }

    public boolean defaultFlag() {
        return defaultValue != 0;
    }
}
