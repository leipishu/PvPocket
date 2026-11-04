package com.leipishu.pvpocket.loadout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The value lists and hard limits the loadout editor draws from.
 *
 * <p>Everything here is a whitelist rather than a free-text field. The loadout arrives from a client
 * and ends up as real items in a survival player's inventory, so "type any id you like" is an
 * invitation to hand out command blocks. The lists are deliberately generous where it does not
 * matter (food, blocks) and deliberately short where it does (potion effects that would break a
 * duel, such as levitation or bad omen, are simply absent).</p>
 */
public final class LoadoutCatalog {

    /** Upper bound on rows in one loadout. A full inventory is 36 slots; more than that cannot be worn. */
    public static final int MAX_ENTRIES = 36;

    public static final int DAMAGE_MIN_TENTHS = 10;
    public static final int DAMAGE_MAX_TENTHS = 400;
    public static final int ARMOR_MAX_TENTHS = 300;
    public static final int DURABILITY_MAX = 10000;
    public static final int KNOCKBACK_MAX = 3;
    public static final int ARROW_MAX = 64;
    public static final int DURATION_MIN_SEC = 1;
    public static final int DURATION_MAX_SEC = 300;
    public static final int AMPLIFIER_MAX = 4;

    /** Team colours used when a block entry is marked as team-coloured wool. */
    public static final String[] TEAM_COLORS = {"red", "blue"};

    public record Option(String id, String label) {
    }

    private static final List<Option> FOODS = List.of(
            new Option("minecraft:golden_carrot", "金胡萝卜"),
            new Option("minecraft:cooked_beef", "牛排"),
            new Option("minecraft:cooked_porkchop", "熟猪排"),
            new Option("minecraft:cooked_chicken", "熟鸡肉"),
            new Option("minecraft:bread", "面包"),
            new Option("minecraft:baked_potato", "烤马铃薯"),
            new Option("minecraft:golden_apple", "金苹果"),
            new Option("minecraft:enchanted_golden_apple", "附魔金苹果"),
            new Option("minecraft:milk_bucket", "牛奶"));

    /**
     * Splash potion effects.
     *
     * <p>{@code durationMatters} is false for the instant effects: a splash potion of healing ignores
     * its duration entirely, so showing a duration slider for it would be a control that does nothing.
     * The editor greys the row out instead.</p>
     */
    public record EffectOption(String id, String label, boolean buff, boolean durationMatters) {
    }

    private static final List<EffectOption> EFFECTS = List.of(
            new EffectOption("minecraft:speed", "迅捷", true, true),
            new EffectOption("minecraft:strength", "力量", true, true),
            new EffectOption("minecraft:regeneration", "生命恢复", true, true),
            new EffectOption("minecraft:resistance", "抗性提升", true, true),
            new EffectOption("minecraft:fire_resistance", "抗火", true, true),
            new EffectOption("minecraft:water_breathing", "水下呼吸", true, true),
            new EffectOption("minecraft:jump_boost", "跳跃提升", true, true),
            new EffectOption("minecraft:absorption", "伤害吸收", true, true),
            new EffectOption("minecraft:slow_falling", "缓降", true, true),
            new EffectOption("minecraft:invisibility", "隐身", true, true),
            new EffectOption("minecraft:night_vision", "夜视", true, true),
            new EffectOption("minecraft:instant_health", "瞬间治疗", true, false),
            new EffectOption("minecraft:slowness", "缓慢", false, true),
            new EffectOption("minecraft:weakness", "虚弱", false, true),
            new EffectOption("minecraft:poison", "中毒", false, true),
            new EffectOption("minecraft:blindness", "失明", false, true),
            new EffectOption("minecraft:nausea", "反胃", false, true),
            new EffectOption("minecraft:mining_fatigue", "挖掘疲劳", false, true),
            new EffectOption("minecraft:instant_damage", "瞬间伤害", false, false));

    private static final List<Option> BLOCKS = List.of(
            new Option("minecraft:white_wool", "羊毛（按队伍染色）"),
            new Option("minecraft:oak_planks", "橡木木板"),
            new Option("minecraft:cobblestone", "圆石"),
            new Option("minecraft:stone", "石头"),
            new Option("minecraft:dirt", "泥土"),
            new Option("minecraft:sand", "沙子"),
            new Option("minecraft:gravel", "沙砾"),
            new Option("minecraft:glass", "玻璃"),
            new Option("minecraft:obsidian", "黑曜石"),
            new Option("minecraft:netherrack", "下界岩"),
            new Option("minecraft:ladder", "梯子"),
            new Option("minecraft:scaffolding", "脚手架"),
            new Option("minecraft:oak_fence", "橡木栅栏"),
            new Option("minecraft:hay_block", "干草捆"),
            new Option("minecraft:slime_block", "黏液块"));

    private static final Map<String, Option> FOOD_BY_ID = index(FOODS);
    private static final Map<String, EffectOption> EFFECT_BY_ID = indexEffects(EFFECTS);
    private static final Map<String, Option> BLOCK_BY_ID = index(BLOCKS);

    private LoadoutCatalog() {
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

    public static String labelOf(String id) {
        Option option = FOOD_BY_ID.get(id);
        if (option != null) {
            return option.label();
        }
        EffectOption effect = EFFECT_BY_ID.get(id);
        if (effect != null) {
            return effect.label();
        }
        option = BLOCK_BY_ID.get(id);
        return option != null ? option.label() : id;
    }

    public static String teamColor(int teamIndex) {
        return TEAM_COLORS[Math.floorMod(teamIndex, TEAM_COLORS.length)];
    }
}
