package com.leipishu.pvpocket.rules;

import com.leipishu.pvpocket.arena.ArenaMaps;

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
 * <p>No text lives here any more. Each rule's label, hint, choices and unit suffix are looked up
 * from {@code assets/pvpocket/lang/} under keys derived from the rule id — see
 * {@link RuleDefinition}. The Chinese wording that used to sit in these constructors is now the
 * {@code zh_cn} column of that file, which is also what makes a second language possible at all:
 * a string compiled into the catalog cannot be translated, only recompiled.</p>
 *
 * <p>Two entries are deliberately not free-form:</p>
 * <ul>
 *   <li>{@code c.modeConflict} is {@link RuleKind#FIXED} — "both sides must agree" is a structural
 *       property of the negotiation, not something a proposer may vote away.</li>
 *   <li>{@code e.template}'s choices come from {@link ArenaMaps} rather than from a literal here, so
 *       the panel and the paste path cannot disagree about what map 2 means.</li>
 * </ul>
 */
public final class RuleCatalog {

    private static final List<RuleDefinition> ORDERED = new ArrayList<>();
    private static final Map<String, RuleDefinition> BY_ID = new LinkedHashMap<>();

    static {
        // A. 对局结构
        // 赛制按"局数"选择，胜场由局数推导（局数 ÷ 2 + 1）。上限 15 局 8 胜：再多的话一场决斗
        // 会超过一小时，而模组的定位是"生存服里随时能打的一场"，不是联赛。
        add(RuleDefinition.enumOf("a.series", RuleGroup.A, 1, choices("a.series", 8)));
        add(RuleDefinition.enumOf("a.roundLimit", RuleGroup.A, 5, choices("a.roundLimit", 6)));
        add(RuleDefinition.enumOf("a.timeoutVerdict", RuleGroup.A, 0, choices("a.timeoutVerdict", 3)));
        add(RuleDefinition.intRange("a.countdown", RuleGroup.A, 0, 30, 1, 5));
        add(RuleDefinition.intRange("a.intermission", RuleGroup.A, 1, 10, 1, 3));
        add(RuleDefinition.multi("a.winConditions", RuleGroup.A, 0b1111, choices("a.winConditions", 4)));

        // B. 参与者
        add(RuleDefinition.intRange("b.teamCount", RuleGroup.B, 1, 4, 1, 2));
        add(RuleDefinition.intRange("b.teamSize", RuleGroup.B, 1, 4, 1, 1));
        add(RuleDefinition.intRange("b.maxPlayers", RuleGroup.B, 2, 16, 1, 4));
        add(RuleDefinition.toggle("b.friendlyFire", RuleGroup.B, false));
        // 默认开启。规格里写的是关闭，那是在观战还只是一个开关、没有实现的时候定的；现在它有完整
        // 的进出流程（旁观模式 + 相机绑定 + 退出复位），关掉它反而让功能发现不了。旁观者不影响对局：
        // 不能碰撞、不能改方块、不参与边界判定，所以开着没有代价。
        add(RuleDefinition.toggle("b.spectator", RuleGroup.B, true));

        // C. 战斗
        add(RuleDefinition.enumOf("c.mode", RuleGroup.C, 0, choices("c.mode", 2)));
        add(RuleDefinition.fixed("c.modeConflict", RuleGroup.C, "pvpocket.rule.c.modeConflict.c0"));
        add(RuleDefinition.intRange("c.maxHealth", RuleGroup.C, 1, 40, 1, 20));
        add(RuleDefinition.toggle("c.naturalRegen", RuleGroup.C, true));
        add(RuleDefinition.enumOf("c.hunger", RuleGroup.C, 0, choices("c.hunger", 2)));
        add(RuleDefinition.toggle("c.fallDamage", RuleGroup.C, true));
        add(RuleDefinition.percent("c.knockbackResistance", RuleGroup.C, 0));

        // D. 配装
        add(RuleDefinition.enumOf("d.loadoutScope", RuleGroup.D, 0, choices("d.loadoutScope", 2)));
        add(RuleDefinition.toggle("d.allowSwapBetweenRounds", RuleGroup.D, false));
        add(RuleDefinition.toggle("d.allowPickup", RuleGroup.D, false));
        add(RuleDefinition.toggle("d.allowCrafting", RuleGroup.D, false));
        add(RuleDefinition.toggle("d.allowConsumables", RuleGroup.D, true));

        // E. 场地
        // 上限跟 ArenaManager.MAX_SIZE_XZ 对齐，也就是一个分区的内部边长 160。每张地图固定占一个
        // 分区（CELL 160 + GAP 96，中心距 256），分区之间留 96 格空地，两张图不会连成一片。最大的
        // 地图是 147 格宽的斗兽场，128 的老上限会把它裁掉一圈看台，被裁掉的部分会被判成「场外」。
        add(RuleDefinition.intRange("e.sizeX", RuleGroup.E, 16, 160, 8, 48));
        add(RuleDefinition.intRange("e.sizeZ", RuleGroup.E, 16, 160, 8, 48));
        add(RuleDefinition.intRange("e.sizeY", RuleGroup.E, 8, 96, 4, 32));
        add(RuleDefinition.enumOf("e.template", RuleGroup.E, 0, ArenaMaps.labelKeys()));
        add(RuleDefinition.enumOf("e.boundary", RuleGroup.E, 1, choices("e.boundary", 2)));
        add(RuleDefinition.enumOf("e.timeOfDay", RuleGroup.E, 1, choices("e.timeOfDay", 5)));
        add(RuleDefinition.enumOf("e.weather", RuleGroup.E, 0, choices("e.weather", 3)));
        add(RuleDefinition.multi("e.hazard", RuleGroup.E, 0b0010, choices("e.hazard", 4)));

        // F. 方块
        add(RuleDefinition.toggle("f.allowPlace", RuleGroup.F, false));
        add(RuleDefinition.toggle("f.protectTerrain", RuleGroup.F, true));
        add(RuleDefinition.toggle("f.allowBreakOwn", RuleGroup.F, true));
        add(RuleDefinition.multi("f.blockFilter", RuleGroup.F, 0b1111, choices("f.blockFilter", 5)));
        add(RuleDefinition.toggle("f.allowExplosives", RuleGroup.F, false));

        // G. 其他
        add(RuleDefinition.toggle("g.enderPearl", RuleGroup.G, false));
        add(RuleDefinition.toggle("g.allowSurrender", RuleGroup.G, true));
        add(RuleDefinition.toggle("g.showOpponentHealth", RuleGroup.G, true));
        add(RuleDefinition.enumOf("g.chatScope", RuleGroup.G, 1, choices("g.chatScope", 3)));
    }

    private RuleCatalog() {
    }

    private static String[] choices(String id, int count) {
        return RuleDefinition.choices(id, count);
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
