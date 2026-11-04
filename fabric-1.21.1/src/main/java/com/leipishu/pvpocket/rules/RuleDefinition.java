package com.leipishu.pvpocket.rules;

/**
 * One configurable rule.
 *
 * <p>Immutable and shared by both sides: the catalog is compiled into the jar, so the server sends
 * only values and the client always knows how to render them. A rule that exists on the server but
 * not on the client is preserved as an opaque value by {@link DuelRules} rather than dropped.</p>
 */
public final class RuleDefinition {

    public final String id;
    public final RuleGroup group;
    public final String label;
    public final String hint;
    public final RuleKind kind;

    public final int min;
    public final int max;
    public final int step;
    public final String[] choices;
    public final int defaultValue;
    /** Suffix appended to the rendered value, e.g. {@code 秒}. Empty when not needed. */
    public final String unit;
    /** Renders an INT rule as a percentage of {@code max} (knockback resistance). */
    public final boolean percent;

    private RuleDefinition(String id, RuleGroup group, String label, String hint, RuleKind kind,
                           int min, int max, int step, String[] choices, int defaultValue,
                           String unit, boolean percent) {
        this.id = id;
        this.group = group;
        this.label = label;
        this.hint = hint;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.step = step;
        this.choices = choices;
        this.defaultValue = defaultValue;
        this.unit = unit;
        this.percent = percent;
    }

    public static RuleDefinition toggle(String id, RuleGroup g, String label, String hint, boolean def) {
        return new RuleDefinition(id, g, label, hint, RuleKind.TOGGLE, 0, 1, 1, null, def ? 1 : 0, "", false);
    }

    public static RuleDefinition intRange(String id, RuleGroup g, String label, String hint,
                                          int min, int max, int step, int def, String unit) {
        return new RuleDefinition(id, g, label, hint, RuleKind.INT, min, max, step, null, def, unit, false);
    }

    public static RuleDefinition percent(String id, RuleGroup g, String label, String hint, int def) {
        return new RuleDefinition(id, g, label, hint, RuleKind.INT, 0, 100, 5, null, def, "%", true);
    }

    public static RuleDefinition enumOf(String id, RuleGroup g, String label, String hint,
                                        int def, String... choices) {
        return new RuleDefinition(id, g, label, hint, RuleKind.ENUM, 0, choices.length - 1, 1,
                choices, def, "", false);
    }

    public static RuleDefinition multi(String id, RuleGroup g, String label, String hint,
                                       int def, String... choices) {
        return new RuleDefinition(id, g, label, hint, RuleKind.MULTI, 0, (1 << choices.length) - 1, 1,
                choices, def, "", false);
    }

    public static RuleDefinition fixed(String id, RuleGroup g, String label, String hint, String shown) {
        return new RuleDefinition(id, g, label, hint, RuleKind.FIXED, 0, 0, 1, new String[]{shown}, 0, "", false);
    }

    /** Clamps a raw value into the range this definition accepts. */
    public int clamp(int value) {
        switch (kind) {
            case TOGGLE:
                return value == 0 ? 0 : 1;
            case INT:
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

    public String choiceLabel(int value) {
        if (choices == null || choices.length == 0) {
            return "";
        }
        if (kind == RuleKind.ENUM) {
            int i = Math.max(0, Math.min(choices.length - 1, value));
            return choices[i];
        }
        return choices[0];
    }

    /** Human-readable value, used by the panel and by the proposal chat message. */
    public String format(int value) {
        switch (kind) {
            case TOGGLE:
                return value == 0 ? "关" : "开";
            case ENUM:
                return choiceLabel(value);
            case MULTI: {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < choices.length; i++) {
                    if ((value & (1 << i)) != 0) {
                        if (sb.length() > 0) {
                            sb.append('·');
                        }
                        sb.append(choices[i]);
                    }
                }
                return sb.length() == 0 ? "无" : sb.toString();
            }
            case FIXED:
                return choices[0];
            default:
                if (percent) {
                    return value + "%";
                }
                return value + unit;
        }
    }

    public boolean defaultFlag() {
        return defaultValue != 0;
    }
}
