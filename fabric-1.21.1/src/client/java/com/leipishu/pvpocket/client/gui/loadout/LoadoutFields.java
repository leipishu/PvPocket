package com.leipishu.pvpocket.client.gui.loadout;

import com.leipishu.pvpocket.loadout.ItemKind;
import com.leipishu.pvpocket.loadout.LoadoutCatalog;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.loadout.Material;
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
 * "无限" on a sword is a promise that it could be enabled one day; a field that is simply absent is
 * the truth.</p>
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
                RuleDefinition.intRange("lo.count", RuleGroup.D, "数量",
                        "这一格放多少个。", 1, 64, 1, entry.count, " 个"),
                () -> entry.count,
                v -> entry.count = v));

        if (kind.materialised) {
            Material[] materials = Material.values();
            String[] labels = Arrays.stream(materials).map(m -> m.label).toArray(String[]::new);
            out.add(new Field(
                    RuleDefinition.enumOf("lo.material", RuleGroup.D, "材质",
                            "只改变外观与物品名，不改变伤害、耐久或任何数值。",
                            Math.max(0, Math.min(labels.length - 1, entry.material.ordinal())), labels),
                    () -> entry.material.ordinal(),
                    v -> entry.material = materials[Math.floorMod(v, materials.length)]));
        }

        if (kind.page == ItemKind.Page.WEAPON || kind == ItemKind.PICKAXE) {
            out.add(new Field(
                    RuleDefinition.decimal("lo.damage", RuleGroup.D, "攻击伤害",
                            "空手是 1.0。这里填的是总伤害，不是加成。",
                            LoadoutCatalog.DAMAGE_MIN_TENTHS, LoadoutCatalog.DAMAGE_MAX_TENTHS, 5,
                            entry.damageTenths == 0 ? 70 : entry.damageTenths, " 点"),
                    () -> entry.damageTenths,
                    v -> entry.damageTenths = v));
        }

        if (kind.isArmor()) {
            out.add(new Field(
                    RuleDefinition.decimal("lo.armor", RuleGroup.D, "护甲值",
                            "每点护甲按 1.8 的曲线减伤，20 点为原版钻石全套水平。",
                            0, LoadoutCatalog.ARMOR_MAX_TENTHS, 5, entry.armorTenths, " 点"),
                    () -> entry.armorTenths,
                    v -> entry.armorTenths = v));
        }

        if (kind != ItemKind.FOOD && kind != ItemKind.POTION && kind != ItemKind.BLOCK) {
            out.add(new Field(
                    RuleDefinition.intRange("lo.durability", RuleGroup.D, "耐久",
                            "0 表示沿用原版耐久。被「不可破坏」覆盖。",
                            LoadoutEntry.DURABILITY_DEFAULT, LoadoutCatalog.DURABILITY_MAX, 50,
                            Math.max(0, entry.durability), " 次"),
                    () -> Math.max(0, entry.durability),
                    v -> entry.durability = v));
            out.add(new Field(
                    RuleDefinition.toggle("lo.unbreakable", RuleGroup.D, "不可破坏",
                            "开启后这把武器整局不会损耗，耐久一栏失效。",
                            entry.durability == LoadoutEntry.DURABILITY_UNBREAKABLE),
                    () -> entry.durability == LoadoutEntry.DURABILITY_UNBREAKABLE ? 1 : 0,
                    v -> entry.durability = v != 0
                            ? LoadoutEntry.DURABILITY_UNBREAKABLE
                            : Math.max(1, entry.durability)));
        }

        if (kind.page == ItemKind.Page.WEAPON) {
            out.add(new Field(
                    RuleDefinition.intRange("lo.knockback", RuleGroup.D, "击退等级",
                            "对应原版击退附魔等级，每级多 0.5 格击退距离。",
                            0, LoadoutCatalog.KNOCKBACK_MAX, 1, entry.knockback, " 级"),
                    () -> entry.knockback,
                    v -> entry.knockback = v));
        }

        for (WeaponFlag flag : WeaponFlag.of(kind)) {
            out.add(new Field(
                    RuleDefinition.toggle("lo.flag." + flag.id, RuleGroup.D, flag.label,
                            flagHint(flag), entry.has(flag)),
                    () -> entry.has(flag) ? 1 : 0,
                    v -> entry.set(flag, v != 0)));
        }

        if (kind == ItemKind.BOW) {
            out.add(new Field(
                    RuleDefinition.intRange("lo.arrows", RuleGroup.D, "箭矢数量",
                            "只在没有「无限」时生效。进场时额外发放这么多支箭。",
                            0, LoadoutCatalog.ARROW_MAX, 8, entry.arrowCount, " 支"),
                    () -> entry.arrowCount,
                    v -> entry.arrowCount = v));
        }

        switch (kind) {
            case POTION -> {
                List<LoadoutCatalog.EffectOption> effects = LoadoutCatalog.effects();
                String[] labels = effects.stream()
                        .map(e -> (e.buff() ? "增益 · " : "减益 · ") + e.label())
                        .toArray(String[]::new);
                out.add(new Field(
                        RuleDefinition.enumOf("lo.effect", RuleGroup.D, "药水效果",
                                "全部是喷溅型，落地时对范围内所有人生效。",
                                indexOfEffect(effects, entry.variant), labels),
                        () -> indexOfEffect(effects, entry.variant),
                        v -> entry.variant = effects.get(Math.floorMod(v, effects.size())).id()));
                out.add(new Field(
                        RuleDefinition.intRange("lo.duration", RuleGroup.D, "持续时间",
                                "瞬间类效果忽略这一项。",
                                LoadoutCatalog.DURATION_MIN_SEC, LoadoutCatalog.DURATION_MAX_SEC, 5,
                                Math.max(LoadoutCatalog.DURATION_MIN_SEC, entry.durationSec), " 秒"),
                        () -> entry.durationSec,
                        v -> entry.durationSec = v));
                out.add(new Field(
                        RuleDefinition.enumOf("lo.amplifier", RuleGroup.D, "效果等级",
                                "I 为原版默认强度，每级再叠一层。",
                                Math.max(0, Math.min(LoadoutCatalog.AMPLIFIER_MAX, entry.amplifier)),
                                "I", "II", "III", "IV", "V"),
                        () -> entry.amplifier,
                        v -> entry.amplifier = v));
            }
            case FOOD -> {
                List<LoadoutCatalog.Option> foods = LoadoutCatalog.foods();
                String[] labels = foods.stream().map(LoadoutCatalog.Option::label).toArray(String[]::new);
                out.add(new Field(
                        RuleDefinition.enumOf("lo.food", RuleGroup.D, "食物种类",
                                "只允许列表内的食物，避免通过配装拿到特殊物品。",
                                indexOfOption(foods, entry.variant), labels),
                        () -> indexOfOption(foods, entry.variant),
                        v -> entry.variant = foods.get(Math.floorMod(v, foods.size())).id()));
            }
            case BLOCK -> {
                List<LoadoutCatalog.Option> blocks = LoadoutCatalog.blocks();
                String[] labels = blocks.stream().map(LoadoutCatalog.Option::label).toArray(String[]::new);
                out.add(new Field(
                        RuleDefinition.enumOf("lo.block", RuleGroup.D, "方块种类",
                                "「羊毛（按队伍染色）」会按你所在的队伍自动换成红或蓝。",
                                indexOfOption(blocks, entry.variant), labels),
                        () -> indexOfOption(blocks, entry.variant),
                        v -> entry.variant = blocks.get(Math.floorMod(v, blocks.size())).id()));
            }
            default -> {
            }
        }

        return out;
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

    private static String flagHint(WeaponFlag flag) {
        return switch (flag) {
            case SWEEPING -> "横扫时对周围的敌人造成溅射伤害。只有剑可以带。";
            case INFINITY -> "射箭不消耗箭矢。关闭时需要指定箭矢数量。";
            case FLAME -> "箭矢点燃目标。";
            case PUNCH -> "箭矢把目标推得更远。";
            case RIPTIDE -> "雨天或水中可以借激流冲刺。场地上没有水时用不了。";
            case PIERCING -> "弩箭可以穿透多个目标。";
        };
    }
}
