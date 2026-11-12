package com.leipishu.pvpocket.combat;

/**
 * Combat rule sets a round can run under.
 *
 * <p>Only the players registered in {@link CombatState} as legacy are affected; everyone else,
 * including participants of a modern round, keeps vanilla behaviour.</p>
 */
public enum CombatMode {
    MODERN("modern"),
    LEGACY_1_8("legacy");

    private final String id;

    CombatMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static CombatMode byId(String id) {
        for (CombatMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return MODERN;
    }
}
