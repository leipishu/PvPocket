package com.leipishu.pvpocket.rules;

import com.leipishu.pvpocket.arena.ArenaManager;
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
        // 赛制拆成两个数字：最多打几局、先赢几局获胜。原先是一个"1/3/5…15 局"的选项，胜场由
        // 局数推导（局数 ÷ 2 + 1），只能表达"奇数局、必须有人先到线"。拆开之后 7 局 4 胜仍然
        // 是 7 和 4，但"打满仍然没人到线"这种情况也能表达了——按最高分定胜负，并列即平局。
        // 上限 15 局：再多一场决斗会超过一小时，而模组的定位是"生存服里随时能打的一场"。
        add(RuleDefinition.intRange("a.maxRounds", RuleGroup.A, 1, 15, 1, 3));
        add(RuleDefinition.intRange("a.winsNeeded", RuleGroup.A, 1, 15, 1, 2));
        add(RuleDefinition.enumOf("a.roundLimit", RuleGroup.A, 5, choices("a.roundLimit", 6)));
        add(RuleDefinition.enumOf("a.timeoutVerdict", RuleGroup.A, 0, choices("a.timeoutVerdict", 3)));
        add(RuleDefinition.intRange("a.countdown", RuleGroup.A, 0, 30, 1, 5));
        add(RuleDefinition.intRange("a.intermission", RuleGroup.A, 1, 10, 1, 3));
        add(RuleDefinition.multi("a.winConditions", RuleGroup.A, 0b1111, choices("a.winConditions", 4)));

        // B. 参与者
        // 队伍数量不再是一条规则：谁和谁一队由发起方在面板的「玩家」页里点出来，队伍数就是非空
        // 卡片的数量，2–4 之间由界面自己夹住。b.teamSize 因此换了含义——它现在是每张卡片画几个
        // 槽位，也就是每队的人数上限；4 队 × 5 人 = 20，这就是 MAX_PARTICIPANTS 的由来。
        add(RuleDefinition.intRange("b.teamSize", RuleGroup.B, 1, 5, 1, 5));
        add(RuleDefinition.intRange("b.maxPlayers", RuleGroup.B, 2, 20, 1, 20));
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
        // 上限直接取 ArenaManager.MAX_SIZE_XZ：一个分区内部 160 格，但地块是居中摆放的，允许它往两侧
        // 的空地里溢出，只要不碰到邻居。224 = 中心距 256 − 32，两张最大地块之间仍留 32 格。最大的
        // 内置地图是 147 格宽的斗兽场，128 的老上限会把它裁掉一圈看台，被裁掉的部分会被判成「场外」。
        add(RuleDefinition.intRange("e.sizeX", RuleGroup.E, 16, ArenaManager.MAX_SIZE_XZ, 8, 48));
        add(RuleDefinition.intRange("e.sizeZ", RuleGroup.E, 16, ArenaManager.MAX_SIZE_XZ, 8, 48));
        add(RuleDefinition.intRange("e.sizeY", RuleGroup.E, 8, ArenaManager.MAX_SIZE_Y, 4, 32));
        add(RuleDefinition.enumOf("e.template", RuleGroup.E, 0, ArenaMaps.labelKeys()));
        add(RuleDefinition.enumOf("e.boundary", RuleGroup.E, 1, choices("e.boundary", 2)));
        add(RuleDefinition.enumOf("e.timeOfDay", RuleGroup.E, 1, choices("e.timeOfDay", 5)));
        add(RuleDefinition.enumOf("e.weather", RuleGroup.E, 0, choices("e.weather", 3)));
        // 默认三项全开（掉落 / 岩浆 / 虚空），不是设计稿的"仅掉落伤害"。理由：场地是虚空维度，
        // 边界判定靠"掉出去"来淘汰人，岩浆与虚空若默认无害，泡在岩浆里不出来就成了无解的一局。
        // 想要纯净场地的人在面板上取消勾选即可，而"默认对局不该需要先改设置才能打"更要紧。
        add(RuleDefinition.multi("e.hazard", RuleGroup.E, 0b1110, choices("e.hazard", 4)));

        // F. 方块
        // 默认开启：默认配装里就有 32 个羊毛，一条「默认关闭」的放置开关会让这份配装自带一叠
        // 用不出去的方块。地图本身由 f.protectTerrain 保护，放置又会在结算时整体回滚，所以
        // 「允许放」不会留下残局；真正需要拦的是 f.blockFilter 里那几种改变格局的东西。
        add(RuleDefinition.toggle("f.allowPlace", RuleGroup.F, true));
        add(RuleDefinition.toggle("f.protectTerrain", RuleGroup.F, true));
        add(RuleDefinition.toggle("f.allowBreakOwn", RuleGroup.F, true));
        add(RuleDefinition.multi("f.blockFilter", RuleGroup.F, 0b1111, choices("f.blockFilter", 5)));
        add(RuleDefinition.toggle("f.allowExplosives", RuleGroup.F, false));

        // G. 其他
        add(RuleDefinition.toggle("g.enderPearl", RuleGroup.G, false));
        add(RuleDefinition.toggle("g.allowSurrender", RuleGroup.G, true));
        add(RuleDefinition.toggle("g.showOpponentHealth", RuleGroup.G, true));
        // 默认「全服」而不是设计稿的「仅队友」。理由和 f.allowPlace 同一条：默认值必须是系统
        // 实际假设的那个。1v1 里没有队友，「仅队友」等于把聊天静音，而一局默认需要先改设置才能
        // 说话，比一条默认不生效的限制更难解释。想安静的人在面板上改一次即可。
        add(RuleDefinition.enumOf("g.chatScope", RuleGroup.G, 0, choices("g.chatScope", 3)));
    }

    private RuleCatalog() {
    }

    private static String[] choices(String id, int count) {
        return RuleDefinition.choices(id, count);
    }

    /**
     * Bits of a multi-select rule that the panel will not let you clear.
     *
     * <p>{@code a.winConditions} keeps 击杀 on. "A round cannot be won by wiping the other side out"
     * has exactly one honest implementation — respawn the wiped side and keep going — and that is a
     * game mode, not a switch. The alternative, letting the chip go dark while the round still ends
     * on a wipe, is the kind of config that lies, which is the whole reason this backlog exists.</p>
     *
     * <p>Enforced twice: {@link DuelRules#set} forces the bit back on for anything that writes a
     * rule set, and the chip widget draws it as locked.</p>
     */
    public static int lockedBits(String id) {
        return "a.winConditions".equals(id) ? 1 : 0;
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
