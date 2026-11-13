package com.leipishu.pvpocket.loadout;

import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.network.chat.MutableComponent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The value lists and hard limits the loadout editor draws from.
 *
 * <p>Every list is a whitelist rather than a free-text field, because the loadout arrives from a
 * client and ends up as real items in a survival player's inventory. Effects that would break a
 * duel, such as levitation or bad omen, are absent.
 *
 * <p>Display names are {@code pvpocket.option.<list>.<registry path>}, where list is {@code food},
 * {@code effect} or {@code block} and the path is the part of the id after {@code minecraft:}.
 */
public final class LoadoutCatalog {

    /**
     * Upper bound on rows in one loadout. A full inventory is 36 slots; more cannot be worn.
     */
    public static final int MAX_ENTRIES = 36;

    public static final int DAMAGE_MIN_TENTHS = 10;
    public static final int DAMAGE_MAX_TENTHS = 400;
    public static final int ARMOR_MAX_TENTHS = 300;
    public static final int DURABILITY_MAX = 10000;
    public static final int KNOCKBACK_MAX = 3;
    /** Upper bound on the companion stack: arrows, obsidian or flint and steel, all stack to 64. */
    public static final int COMPANION_MAX = 64;
    public static final int DURATION_MIN_SEC = 1;
    public static final int DURATION_MAX_SEC = 300;
    public static final int AMPLIFIER_MAX = 4;

    /**
     * Firework rocket flight duration, the same 1-3 the crafting table produces.
     *
     * <p>The component is an unsigned byte, so 0 and 4-255 are representable but meaningless.
     * Offering the range the game has keeps the slider honest.
     */
    public static final int FIREWORK_FLIGHT_MIN = 1;
    public static final int FIREWORK_FLIGHT_MAX = 3;

    /** Team colours used when a block entry is marked as team-coloured wool. */
    public static final String[] TEAM_COLORS = {"red", "blue"};

    /** One whitelisted item: the id it grants, and the key that names it. */
    public record Option(String id, String labelKey) {

        public MutableComponent labelText() {
            return PvLang.t(labelKey);
        }

        public String label() {
            return PvLang.str(labelKey);
        }
    }

    private static final List<Option> FOODS = List.of(
            food("minecraft:golden_carrot"),
            food("minecraft:cooked_beef"),
            food("minecraft:cooked_porkchop"),
            food("minecraft:cooked_chicken"),
            food("minecraft:bread"),
            food("minecraft:baked_potato"),
            food("minecraft:golden_apple"),
            food("minecraft:enchanted_golden_apple"),
            food("minecraft:milk_bucket"));

    /**
     * Splash potion effects.
     *
     * <p>{@code durationMatters} is false for the instant effects, whose duration is ignored; the
     * editor greys the row out instead.
     *
     * <p>Two keys per effect. {@link #labelKey()} is the bare name, used where the effect is
     * already in context. {@link #pickKey()} is the name in the editor's cycle control, where the
     * reader needs to know whether the choice is a buff or a debuff.
     */
    public record EffectOption(String id, String labelKey, boolean buff, boolean durationMatters) {

        public MutableComponent labelText() {
            return PvLang.t(labelKey);
        }

        public String label() {
            return PvLang.str(labelKey);
        }

        /** Key for the picker entry, which names the qualifier as well as the effect. */
        public String pickKey() {
            return labelKey + ".pick";
        }
    }

    private static final List<EffectOption> EFFECTS = List.of(
            effect("minecraft:speed", true, true),
            effect("minecraft:strength", true, true),
            effect("minecraft:regeneration", true, true),
            effect("minecraft:resistance", true, true),
            effect("minecraft:fire_resistance", true, true),
            effect("minecraft:water_breathing", true, true),
            effect("minecraft:jump_boost", true, true),
            effect("minecraft:absorption", true, true),
            effect("minecraft:slow_falling", true, true),
            effect("minecraft:invisibility", true, true),
            effect("minecraft:night_vision", true, true),
            effect("minecraft:instant_health", true, false),
            effect("minecraft:slowness", false, true),
            effect("minecraft:weakness", false, true),
            effect("minecraft:poison", false, true),
            effect("minecraft:blindness", false, true),
            effect("minecraft:nausea", false, true),
            effect("minecraft:mining_fatigue", false, true),
            effect("minecraft:instant_damage", false, false));

    private static final List<Option> BLOCKS = List.of(
            block("minecraft:white_wool"),
            block("minecraft:oak_planks"),
            block("minecraft:cobblestone"),
            block("minecraft:stone"),
            block("minecraft:dirt"),
            block("minecraft:sand"),
            block("minecraft:gravel"),
            block("minecraft:glass"),
            block("minecraft:obsidian"),
            block("minecraft:netherrack"),
            block("minecraft:ladder"),
            block("minecraft:scaffolding"),
            block("minecraft:oak_fence"),
            block("minecraft:hay_block"),
            block("minecraft:slime_block"));

    private static final Map<String, Option> FOOD_BY_ID = index(FOODS);
    private static final Map<String, EffectOption> EFFECT_BY_ID = indexEffects(EFFECTS);
    private static final Map<String, Option> BLOCK_BY_ID = index(BLOCKS);

    private LoadoutCatalog() {
    }

    private static Option food(String id) {
        return new Option(id, "pvpocket.option.food." + pathOf(id));
    }

    private static EffectOption effect(String id, boolean buff, boolean durationMatters) {
        return new EffectOption(id, "pvpocket.option.effect." + pathOf(id), buff, durationMatters);
    }

    private static Option block(String id) {
        return new Option(id, "pvpocket.option.block." + pathOf(id));
    }

    /** Strips the namespace: {@code minecraft:golden_carrot} -> {@code golden_carrot}. */
    private static String pathOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static Map<String, Option> index(List<Option> options) {
        Map<String, Option> map = new LinkedHashMap<>();
        for (Option option : options) {
            map.put(option.id, option);
        }
        return map;
    }

    private static Map<String, EffectOption> indexEffects(List<EffectOption> options) {
        Map<String, EffectOption> map = new LinkedHashMap<>();
        for (EffectOption option : options) {
            map.put(option.id, option);
        }
        return map;
    }

    public static List<Option> foods() {
        return FOODS;
    }

    public static List<EffectOption> effects() {
        return EFFECTS;
    }

    public static List<Option> blocks() {
        return BLOCKS;
    }

    public static boolean isFood(String id) {
        return FOOD_BY_ID.containsKey(id);
    }

    public static boolean isEffect(String id) {
        return EFFECT_BY_ID.containsKey(id);
    }

    public static boolean isBlock(String id) {
        return BLOCK_BY_ID.containsKey(id);
    }

    /** The translation key naming a whitelisted variant, or null when the id is not whitelisted. */
    public static String labelKeyOf(String id) {
        Option option = FOOD_BY_ID.get(id);
        if (option != null) {
            return option.labelKey();
        }
        EffectOption effect = EFFECT_BY_ID.get(id);
        if (effect != null) {
            return effect.labelKey();
        }
        option = BLOCK_BY_ID.get(id);
        return option != null ? option.labelKey() : null;
    }

    /**
     * The name of a whitelisted variant, for the self-drawn GUI.
     *
     * <p>An unrecognised id renders as the id itself rather than as a blank, so a variant this
     * build does not ship is visible as {@code minecraft:foo}.
     */
    public static String labelOf(String id) {
        String key = labelKeyOf(id);
        return key == null ? id : PvLang.str(key);
    }

    public static String teamColor(int teamIndex) {
        return TEAM_COLORS[Math.floorMod(teamIndex, TEAM_COLORS.length)];
    }
}
