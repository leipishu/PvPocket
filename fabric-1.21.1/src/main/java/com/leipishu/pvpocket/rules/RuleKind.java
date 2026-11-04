package com.leipishu.pvpocket.rules;

/**
 * How a rule is edited and how its value is stored.
 *
 * <p>Every rule value is a single {@code int} on the wire and on disk regardless of kind, which
 * keeps the payload tiny and the persistence format trivial:</p>
 * <ul>
 *   <li>{@link #TOGGLE} — 0 / 1</li>
 *   <li>{@link #INT} — the value itself, clamped to [{@code min}, {@code max}]</li>
 *   <li>{@link #ENUM} — the index into {@code choices}</li>
 *   <li>{@link #MULTI} — a bitmask over {@code choices}</li>
 *   <li>{@link #FIXED} — always 0; rendered read-only so the constraint is visible in the panel
 *       instead of being a footnote in the docs</li>
 * </ul>
 */
public enum RuleKind {
    TOGGLE,
    INT,
    ENUM,
    MULTI,
    FIXED
}
