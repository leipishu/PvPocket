package com.leipishu.pvpocket.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every configurable rule, in panel order.
 *
 * <p>This is the executable form of §3.4 of the locked specification. Defaults are the ones the
 * spec recommends, so a session that is proposed and accepted without touching anything runs
 * exactly the configuration the design was validated against.</p>
 *
 * <p>Two entries are deliberately not free-form:</p>
 * <ul>
 *   <li>{@code c.modeConflict} is {@link RuleKind#FIXED} — "both sides must agree" is a structural
 *       property of the negotiation, not something a proposer may vote away.</li>
 *   <li>{@code e.template} exposes only the flat template until the template system lands in P1.</li>
 * </ul>
 */
public final class RuleCatalog {

    private static final List<RuleDefinition> ORDERED = new ArrayList<>();
    private static final Map<String, RuleDefinition> BY_ID = new LinkedHashMap<>();

    static {
        // A. 对局结构
        // 赛制按"局数"选择，胜场由局数推导（局数 ÷ 2 + 1）。上限 15 局 8 胜：再多的话一场决斗
        // 会超过一小时，而模组的定位是"生存服里随时能打的一场"，不是联赛。
        add(RuleDefinition.enumOf("a.series", RuleGroup.A, "赛制",
                "先赢到「局数 ÷ 2 + 1」局的一方获胜。每局结束物品与场地都会复原。", 1,
                "1 局", "3 局 2 胜", "5 局 3 胜", "7 局 4 胜",
                "9 局 5 胜", "11 局 6 胜", "13 局 7 胜", "15 局 8 胜"));
        add(RuleDefinition.enumOf("a.roundLimit", RuleGroup.A, "单局时长上限",
                "超过后按超时判定结算。0 表示无限制。", 5,
                "无限制", "1 分钟", "3 分钟", "5 分钟", "10 分钟", "15 分钟"));
        add(RuleDefinition.enumOf("a.timeoutVerdict", RuleGroup.A, "超时判定",
                "时间耗尽时如何决定这一局。", 0, "血量高者胜", "平局", "双败"));
        add(RuleDefinition.intRange("a.countdown", RuleGroup.A, "回合准备时间",
                "传送进场后、正式开打前的准备时间，倒计时逐秒显示在聊天栏。准备期间双方无法造成或受到伤害。",
                0, 30, 1, 5, "秒"));
        add(RuleDefinition.intRange("a.intermission", RuleGroup.A, "局间间隔",
                "多局赛制下两局之间的休整时间。", 1, 10, 1, 3, "秒"));
        add(RuleDefinition.multi("a.winConditions", RuleGroup.A, "胜利条件",
                "这一局可以因哪些原因结束。至少保留一项。", 0b1111,
                "击杀", "出界", "投降", "超时"));

        // B. 参与者
        add(RuleDefinition.intRange("b.teamCount", RuleGroup.B, "队伍数量",
                "分成几方对抗。2 为经典 1v1 / 2v2。", 1, 4, 1, 2, " 队"));
        add(RuleDefinition.intRange("b.teamSize", RuleGroup.B, "每队人数",
                "每支队伍的人数。总人数 = 队伍数量 × 每队人数。", 1, 4, 1, 1, " 人"));
        add(RuleDefinition.intRange("b.maxPlayers", RuleGroup.B, "总人数上限",
                "超过这个人数无法开始。", 2, 16, 1, 4, " 人"));
        add(RuleDefinition.toggle("b.friendlyFire", RuleGroup.B, "友伤",
                "开启后队友之间可以互相造成伤害。", false));
        add(RuleDefinition.toggle("b.spectator", RuleGroup.B, "允许观战",
                "开启后非参与者可以进入场地旁观。", false));

        // C. 战斗
        add(RuleDefinition.enumOf("c.mode", RuleGroup.C, "战斗模式",
                "经典模式关闭攻击冷却，并按 1.8 规则处理格挡、击退与护甲。", 0,
                "现代", "经典 1.8"));
        add(RuleDefinition.fixed("c.modeConflict", RuleGroup.C, "模式冲突",
                "战斗模式必须全员同意才能开始，不能由发起方单方面决定。", "全员同意"));
        add(RuleDefinition.intRange("c.maxHealth", RuleGroup.C, "最大生命",
                "进场后双方的生命上限。原版为 20。", 1, 40, 1, 20, " 点"));
        add(RuleDefinition.toggle("c.naturalRegen", RuleGroup.C, "自然回血",
                "关闭后只有药水与金苹果可以回复生命。", true));
        add(RuleDefinition.enumOf("c.hunger", RuleGroup.C, "饥饿消耗",
                "关闭后饥饿值不会下降，也不会因饥饿掉血。", 0, "正常", "关闭"));
        add(RuleDefinition.toggle("c.fallDamage", RuleGroup.C, "掉落伤害",
                "关闭后高处落地不受伤害。", true));
        add(RuleDefinition.percent("c.knockbackResistance", RuleGroup.C, "击退抗性",
                "对所有参与者统一施加的击退抗性。", 0));

        // D. 配装
        add(RuleDefinition.enumOf("d.loadoutScope", RuleGroup.D, "配装归属",
                "每队统一时，同队成员使用同一套配装。", 0, "每人独立", "每队统一"));
        add(RuleDefinition.toggle("d.allowSwapBetweenRounds", RuleGroup.D, "局间可换配装",
                "开启后每局开始前可以重新选择配装。", false));
        add(RuleDefinition.toggle("d.allowPickup", RuleGroup.D, "允许拾取场地物品",
                "关闭后无法捡起场地里掉落的物品。", false));
        add(RuleDefinition.toggle("d.allowCrafting", RuleGroup.D, "允许合成",
                "关闭后工作台与背包合成格不可用。", false));
        add(RuleDefinition.toggle("d.allowConsumables", RuleGroup.D, "允许消耗品",
                "关闭后药水、食物、金苹果不可使用。", true));

        // E. 场地
        add(RuleDefinition.intRange("e.sizeX", RuleGroup.E, "场地长度",
                "场地在 X 方向的格数。", 16, 128, 8, 48, " 格"));
        add(RuleDefinition.intRange("e.sizeZ", RuleGroup.E, "场地宽度",
                "场地在 Z 方向的格数。", 16, 128, 8, 48, " 格"));
        add(RuleDefinition.intRange("e.sizeY", RuleGroup.E, "场地高度",
                "场地在 Y 方向的格数，决定可搭建的垂直空间。", 8, 64, 4, 32, " 格"));
        add(RuleDefinition.enumOf("e.template", RuleGroup.E, "场地模板",
                "平坦为唯一的 P0 模板，其余模板在后续版本加入。", 0, "平坦"));
        add(RuleDefinition.enumOf("e.boundary", RuleGroup.E, "边界规则",
                "拉回会把越界者放到最近的边境内；判负会开始 5 秒倒计时，倒计时结束仍在场外就输掉这一局。",
                1, "拉回", "倒计时判负"));
        add(RuleDefinition.enumOf("e.timeOfDay", RuleGroup.E, "时间",
                "固定时间后场地时间不再流逝，昼夜循环关闭；「自然」保留原版循环。", 1,
                "自然", "清晨", "正午", "黄昏", "午夜"));
        add(RuleDefinition.enumOf("e.weather", RuleGroup.E, "天气",
                "每局开始时设定。天气是维度级属性，多场同时进行时以最后一局为准。", 0,
                "晴朗", "降雨", "雷暴"));
        add(RuleDefinition.multi("e.hazard", RuleGroup.E, "环境危害",
                "场地内生效的额外伤害来源。", 0b0010, "无", "掉落伤害", "岩浆", "虚空"));

        // F. 方块
        add(RuleDefinition.toggle("f.allowPlace", RuleGroup.F, "允许放置方块",
                "开启后可以在场地内放置白名单内的方块。", false));
        add(RuleDefinition.toggle("f.protectTerrain", RuleGroup.F, "地形保护",
                "开启后地图本身的方块不可破坏，只有本局放置的方块能拆。关闭后可以挖穿场地，"
                        + "本局结束时地形仍会复原。", true));
        add(RuleDefinition.toggle("f.allowBreakOwn", RuleGroup.F, "允许拆除自己放置的方块",
                "只对本次对局中放置的方块生效，场地本体是否可破坏由「地形保护」决定。", true));
        add(RuleDefinition.multi("f.blockFilter", RuleGroup.F, "方块限制",
                "选中的项目会被禁止使用。默认禁掉床、重生锚、TNT 与流体桶。", 0b1111,
                "床", "重生锚", "TNT", "流体桶", "末地水晶"));
        add(RuleDefinition.toggle("f.allowExplosives", RuleGroup.F, "允许爆炸物",
                "关闭后 TNT、苦力怕与末地水晶不产生爆炸。", false));

        // G. 其他
        add(RuleDefinition.toggle("g.enderPearl", RuleGroup.G, "允许末影珍珠",
                "关闭后末影珍珠无法投掷。", false));
        add(RuleDefinition.toggle("g.allowSurrender", RuleGroup.G, "允许投降",
                "关闭后只能通过击杀或超时结束这一局。", true));
        add(RuleDefinition.toggle("g.showOpponentHealth", RuleGroup.G, "显示对手血量",
                "在界面上方显示对手的生命值。", true));
        add(RuleDefinition.enumOf("g.chatScope", RuleGroup.G, "决斗中聊天",
                "限制对局期间的聊天可见范围。", 1, "全服", "仅队友", "禁言"));
    }

    private RuleCatalog() {
    }

    private static void add(RuleDefinition def) {
        ORDERED.add(def);
        BY_ID.put(def.id, def);
    }

    public static List<RuleDefinition> all() {
        return Collections.unmodifiableList(ORDERED);
    }

    public static List<RuleDefinition> of(RuleGroup group) {
        List<RuleDefinition> out = new ArrayList<>();
        for (RuleDefinition def : ORDERED) {
            if (def.group == group) {
                out.add(def);
            }
        }
        return out;
    }

    public static RuleDefinition byId(String id) {
        return BY_ID.get(id);
    }

    public static int size() {
        return ORDERED.size();
    }
}
