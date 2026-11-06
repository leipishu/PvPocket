package com.leipishu.pvpocket.rules;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.text.MutableText;

/**
 * The seven rule groups from the locked specification. The letter is the stable id prefix, so a
 * rule's group can be derived from its id without a lookup — which keeps the wire format readable
 * and makes a partially-known rule set (client built from an older jar) fail loudly instead of
 * silently landing in the wrong tab.
 *
 * <p>The letter doubles as the translation key suffix: the tab strip reads
 * {@code pvpocket.rule.group.<letter>}, and the badge beside it shows the letter itself, which is
 * language-neutral and therefore not translated.</p>
 */
public enum RuleGroup {

    A("A"),
    B("B"),
    C("C"),
    D("D"),
    E("E"),
    F("F"),
    G("G");

    public final String letter;
    public final String labelKey;
    /** One short line describing what the group contains, shown under the panel title. */
    public final String hintKey;

    RuleGroup(String letter) {
        this.letter = letter;
        this.labelKey = "pvpocket.rule.group." + letter.toLowerCase(java.util.Locale.ROOT);
        this.hintKey = labelKey + ".hint";
    }

    public MutableText labelText() {
        return PvLang.t(labelKey);
    }

    /** Tab label for the self-drawn GUI, which measures strings rather than {@code Text}. */
    public String label() {
        return PvLang.str(labelKey);
    }

    /** Resolves the group from a rule id such as {@code c.mode}. Defaults to G for unknown ids. */
    public static RuleGroup of(String ruleId) {
        if (ruleId == null || ruleId.length() < 2) {
            return G;
        }
        char c = Character.toUpperCase(ruleId.charAt(0));
        for (RuleGroup g : values()) {
            if (g.letter.charAt(0) == c) {
                return g;
            }
        }
        return G;
    }
}
