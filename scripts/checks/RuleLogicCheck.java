import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.rules.RuleGroup;

/**
 * Offline check of option cycling for ENUM, MULTI and INT rules.
 *
 * <p>Runs against the compiled {@link RuleDefinition}, so it verifies that the panel arrow
 * reaches every choice once and returns to the start, not that a copied formula is right.
 *
 * <p>Needs no client: {@code build/classes/java/main} plus the Loom dependency classpath is
 * enough. Entry point is {@code scripts/check-rules-logic.sh}; exit code 0 passes, 1 fails.
 */
public final class RuleLogicCheck {

    private static int failures;

    public static void main(String[] args) {
        // Choice counts that occur in the catalogue: 2 to 6, including the 6 maps of e.template.
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
        // 1. From the default, stepping right visits every choice once and returns to the start.
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
        // 2. The same holds stepping left.
        v = start;
        for (int i = 0; i < count; i++) {
            v = def.step(v, -1);
        }
        if (v != start) {
            fail("enum" + count + ": 向左 " + count + " 步之后回到 " + v + "，应为 " + start);
        }
        // 3. Out-of-range values clamp back and remain cyclable.
        int clamped = def.clamp(count + 5);
        if (clamped != count - 1) {
            fail("enum" + count + ": clamp(" + (count + 5) + ") = " + clamped + "，应为 " + (count - 1));
        }
        if (def.clamp(-3) != 0) {
            fail("enum" + count + ": clamp(-3) 应为 0");
        }
        // 4. Every value has a key; a missing translation falls back to the key name.
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
        // The default must fit the bit width.
        if ((def.defaultValue & ~def.max) != 0) {
            fail("multi: 默认值超出位宽");
        }
    }

    private static void checkInt() {
        RuleDefinition def = RuleDefinition.intRange("t.int", RuleGroup.A, 16, 224, 8, 48);
        // Stepping stops at either end: no wrap and no overflow.
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
        // Clamping also snaps to the step grid, since the arrows only visit grid values.
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
        // The default must be in range and on the grid.
        if (def.defaultValue < def.min || def.defaultValue > def.max) {
            fail("int: 默认值越界");
        }
        if ((def.defaultValue - def.min) % def.step != 0) {
            fail("int: 默认值不在步长网格上");
        }
    }

    /**
     * Checks normalisation on load: locked bits, exclusive bits and clamped values.
     *
     * <p>Decoding turns a rule string into in-memory values. Hand-edited packets, old saves and
     * third-party presets all enter there, and normalisation is what guards the match.
     */
    private static void checkNormalise() {
        com.leipishu.pvpocket.rules.DuelRules decoded =
                com.leipishu.pvpocket.rules.DuelRules.decode(
                        "a.winConditions=0;e.hazard=0;e.template=99;c.mode=99");
        // The kill bit is locked: clearing it forces it back on.
        if ((decoded.get("a.winConditions") & 1) == 0) {
            fail("normalise: a.winConditions 的击杀位没有被强制补回");
        }
        // e.hazard "none" is exclusive with the other bits; both spellings converge on one value.
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
        // Out-of-range values clamp and are recorded; the record is the only evidence of it.
        if (decoded.clampedValues().isEmpty()) {
            fail("normalise: 夹过的值没有记录");
        }
        if (decoded.get("e.template") >= com.leipishu.pvpocket.arena.ArenaMaps.ALL.size()) {
            fail("normalise: e.template 没有被夹进地图数量内");
        }
        // e.sizeX has step 8, so 45 must snap to the grid and be recorded.
        com.leipishu.pvpocket.rules.DuelRules offGrid =
                com.leipishu.pvpocket.rules.DuelRules.decode("e.sizeX=45");
        if (offGrid.get("e.sizeX") != 48) {
            fail("normalise: e.sizeX=45 应吸附为 48，实际 " + offGrid.get("e.sizeX"));
        }
        if (!"45->48".equals(offGrid.clampedValues().get("e.sizeX"))) {
            fail("normalise: e.sizeX 的吸附没有留痕");
        }
        // A valid rule set records no clamping.
        if (!com.leipishu.pvpocket.rules.DuelRules.decode("").clampedValues().isEmpty()) {
            fail("normalise: 空规则集不应记录任何夹取");
        }
        // The defaults are already clean, so they record nothing.
        if (!com.leipishu.pvpocket.rules.DuelRules.defaults().clampedValues().isEmpty()) {
            fail("normalise: 默认规则集不应记录任何夹取");
        }
    }

    private static void fail(String message) {
        failures++;
        System.out.println("  ! " + message);
    }
}
