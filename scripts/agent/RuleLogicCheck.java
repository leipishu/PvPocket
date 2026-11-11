import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.rules.RuleGroup;

/**
 * 选项切换（ENUM / MULTI / INT）的离线自检。
 *
 * 跑的是编译出来的真实 RuleDefinition，不是把公式抄一遍——所以它验证的是「面板上那个左右箭头
 * 真的能走到每一个选项，而且只走一遍」，而不是「我以为是这么写的」。
 *
 * 不需要客户端：只需要 build/classes/java/main 加上 loom 的依赖类路径。入口是
 * scripts/check-rules-logic.sh，退出码 0 通过 / 1 失败，可以直接挂 CI。
 */
public final class RuleLogicCheck {

    private static int failures;

    public static void main(String[] args) {
        // 目录里真实存在的选项数：2..6（含 e.template 的 6 张图）
        for (int count = 2; count <= 6; count++) {
            RuleDefinition def = RuleDefinition.enumOf("t.enum" + count, RuleGroup.A, 0,
                    RuleDefinition.choices("t.enum" + count, count));
            checkEnum(def, count);
        }
        checkMulti();
        checkInt();
        checkNormalise();
        System.out.println(failures == 0
                ? "[rules-logic] 全部通过"
                : "[rules-logic] " + failures + " 项失败");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void checkEnum(RuleDefinition def, int count) {
        // 1. 从默认值出发，一路向右必须恰好访问每一个选项各一次，并回到起点
        int start = def.defaultValue;
        int v = start;
        boolean[] seen = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (v < 0 || v >= count) {
                fail("enum" + count + ": 第 " + i + " 步越界 " + v);
                return;
            }
            if (seen[v]) {
                fail("enum" + count + ": 第 " + i + " 步重复访问 " + v);
                return;
            }
            seen[v] = true;
            v = def.step(v, 1);
        }
        for (int i = 0; i < count; i++) {
            if (!seen[i]) {
                fail("enum" + count + ": 选项 " + i + " 走不到");
            }
        }
        if (v != start) {
            fail("enum" + count + ": " + count + " 步之后回到 " + v + "，应为 " + start);
        }
        // 2. 向左同理（双向对称）
        v = start;
        for (int i = 0; i < count; i++) {
            v = def.step(v, -1);
        }
        if (v != start) {
            fail("enum" + count + ": 向左 " + count + " 步之后回到 " + v + "，应为 " + start);
        }
        // 3. 越界值被夹回，且夹回之后仍然可循环
        int clamped = def.clamp(count + 5);
        if (clamped != count - 1) {
            fail("enum" + count + ": clamp(" + (count + 5) + ") = " + clamped + "，应为 " + (count - 1));
        }
        if (def.clamp(-3) != 0) {
            fail("enum" + count + ": clamp(-3) 应为 0");
        }
        // 4. 每一个值都有文字（选项键由 id 派生，键缺失时会退回键名本身，不是空串）
        for (int i = 0; i < count; i++) {
            String key = def.choiceKeys[i];
            if (key == null || key.isEmpty()) {
                fail("enum" + count + ": 选项 " + i + " 没有键");
            }
        }
    }

    private static void checkMulti() {
        RuleDefinition def = RuleDefinition.multi("t.multi", RuleGroup.A, 0b1111,
                RuleDefinition.choices("t.multi", 5));
        if (def.max != 0b11111) {
            fail("multi: 上限应为 0b11111，实际 " + def.max);
        }
        if (def.clamp(0b111111) != 0b11111) {
            fail("multi: clamp 没把超出位宽的高位砍掉");
        }
        if (def.clamp(0) != 0) {
            fail("multi: clamp(0) 应保持 0");
        }
        // 默认值必须落在位宽内
        if ((def.defaultValue & ~def.max) != 0) {
            fail("multi: 默认值超出位宽");
        }
    }

    private static void checkInt() {
        RuleDefinition def = RuleDefinition.intRange("t.int", RuleGroup.A, 16, 224, 8, 48);
        // 步进到两端就停住，不越界也不回绕
        int v = 16;
        for (int i = 0; i < 40; i++) {
            v = def.step(v, -1);
        }
        if (v != 16) {
            fail("int: 向左步进 40 次之后应为下限 16，实际 " + v);
        }
        v = 224;
        for (int i = 0; i < 40; i++) {
            v = def.step(v, 1);
        }
        if (v != 224) {
            fail("int: 向右步进 40 次之后应为上限 224，实际 " + v);
        }
        if (def.clamp(1000) != 224 || def.clamp(-1000) != 16) {
            fail("int: clamp 没夹住");
        }
        // 夹的同时要吸附到步长网格：面板上的箭头只能走网格上的值，盘上那个 45 若原样留下来，
        // 屏幕上就有一个「显示 45、按一下变 53」的行，再也回不去。
        if (def.clamp(45) != 48) {
            fail("int: clamp(45) 应吸附到网格上的 48，实际 " + def.clamp(45));
        }
        if (def.clamp(223) != 224) {
            fail("int: clamp(223) 应吸附到上限 224，实际 " + def.clamp(223));
        }
        for (int raw = -64; raw <= 320; raw++) {
            int c = def.clamp(raw);
            if (c < def.min || c > def.max || (c - def.min) % def.step != 0) {
                fail("int: clamp(" + raw + ") = " + c + " 不在 [" + def.min + "," + def.max
                        + "] 的步长网格上");
                break;
            }
        }
        // 默认值必须落在范围内，且本身就在网格上
        if (def.defaultValue < def.min || def.defaultValue > def.max) {
            fail("int: 默认值越界");
        }
        if ((def.defaultValue - def.min) % def.step != 0) {
            fail("int: 默认值不在步长网格上");
        }
    }

    /**
     * 落盘/上线时的规范化：锁住的位、互斥的位、被夹过的值。
     *
     * 这一段比上面几段重要，因为它管的是「规则集从字符串变成内存里的值」那一步——手改的包、
     * 旧版本的存档、别人写的预设都从这里进来，而规范化是唯一挡在它们和对局之间的东西。
     */
    private static void checkNormalise() {
        com.leipishu.pvpocket.rules.DuelRules decoded =
                com.leipishu.pvpocket.rules.DuelRules.decode(
                        "a.winConditions=0;e.hazard=0;e.template=99;c.mode=99");
        // 击杀位锁死：清掉之后必须被强制补回来
        if ((decoded.get("a.winConditions") & 1) == 0) {
            fail("normalise: a.winConditions 的击杀位没有被强制补回");
        }
        // e.hazard 的「无」与其余位互斥；两种写法收敛成同一个值
        int none = decoded.get("e.hazard");
        if (none != 1) {
            fail("normalise: e.hazard=0 应收敛为「无」(1)，实际 " + none);
        }
        int both = com.leipishu.pvpocket.rules.DuelRules.decode("e.hazard=15").get("e.hazard");
        if (both != 1) {
            fail("normalise: e.hazard=15（无+三项）应收敛为「无」(1)，实际 " + both);
        }
        int lavaOnly = com.leipishu.pvpocket.rules.DuelRules.decode("e.hazard=4").get("e.hazard");
        if (lavaOnly != 4) {
            fail("normalise: e.hazard=4 应保持 4，实际 " + lavaOnly);
        }
        // 越界值被夹住，而且要留下痕迹——这是「我同意的图和实际打的图不是一张」的唯一证据
        if (decoded.clampedValues().isEmpty()) {
            fail("normalise: 夹过的值没有记录");
        }
        if (decoded.get("e.template") >= com.leipishu.pvpocket.arena.ArenaMaps.ALL.size()) {
            fail("normalise: e.template 没有被夹进地图数量内");
        }
        // 历史 journal 里真的出现过 e.sizeX=45，而它的步长是 8：盘上值吸附到网格，并且留痕
        com.leipishu.pvpocket.rules.DuelRules offGrid =
                com.leipishu.pvpocket.rules.DuelRules.decode("e.sizeX=45");
        if (offGrid.get("e.sizeX") != 48) {
            fail("normalise: e.sizeX=45 应吸附为 48，实际 " + offGrid.get("e.sizeX"));
        }
        if (!"45->48".equals(offGrid.clampedValues().get("e.sizeX"))) {
            fail("normalise: e.sizeX 的吸附没有留痕");
        }
        // 正常的规则集不应留下任何「被夹过」的痕迹
        if (!com.leipishu.pvpocket.rules.DuelRules.decode("").clampedValues().isEmpty()) {
            fail("normalise: 空规则集不应记录任何夹取");
        }
        // 默认值本身是干净的：不夹、不吸附，所以不该有任何痕迹
        if (!com.leipishu.pvpocket.rules.DuelRules.defaults().clampedValues().isEmpty()) {
            fail("normalise: 默认规则集不应记录任何夹取");
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("  ! " + message);
    }
}
