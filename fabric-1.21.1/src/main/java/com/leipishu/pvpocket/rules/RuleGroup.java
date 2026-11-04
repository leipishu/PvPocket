package com.leipishu.pvpocket.rules;

/**
 * The seven rule groups from the locked specification. The letter is the stable id prefix, so a
 * rule's group can be derived from its id without a lookup — which keeps the wire format readable
 * and makes a partially-known rule set (client built from an older jar) fail loudly instead of
 * silently landing in the wrong tab.
 */
public enum RuleGroup {

    A("A", "对局结构"),
    B("B", "参与者"),
    C("C", "战斗"),
    D("D", "配装"),
    E("E", "场地"),
    F("F", "方块"),
    G("G", "其他");

    public final String letter;
    public final String label;

    RuleGroup(String letter, String label) {
        this.letter = letter;
        this.label = label;
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
