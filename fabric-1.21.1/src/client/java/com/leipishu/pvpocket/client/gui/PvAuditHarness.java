package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.client.gui.loadout.LoadoutScreen;
import com.leipishu.pvpocket.client.gui.match.MatchListScreen;
import com.leipishu.pvpocket.client.gui.rule.RuleScreen;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.network.LoadoutOpenPayload;
import com.leipishu.pvpocket.network.MatchListPayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.rules.DuelRules;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Drives {@link PvAudit} across every screen, page and dialog the mod can show.
 *
 * <p>The audit needs real frames: a widget's position is decided in its own render pass (a scroll
 * pane lays its rows out inside {@code render}), text is measured as it is drawn, and the entrance
 * animation has to have finished before anything is where it will stay. So this walks the client
 * through one screen at a time, leaves each one up for a few ticks, and lets the normal render loop
 * do the measuring.</p>
 *
 * <p>It exists as a harness rather than as something a human clicks through because "does this
 * overlap" has to be answerable on a machine. Six screens times two canvas sizes times every page
 * is sixty layouts; nobody re-checks sixty layouts by hand after every layout tweak, which is how
 * the overlaps it finds got there in the first place.</p>
 *
 * <p>Off unless {@code -Dpvpocket.audit=true}; {@code -Dpvpocket.audit.exit=true} closes the client
 * when the walk is done, which is what {@code scripts/audit-ui.sh} uses to run this as a test.</p>
 */
public final class PvAuditHarness {

    /**
     * The canvas sizes to audit.
     *
     * <p>426x240 is a 1280x720 window at GUI scale 3 and is the size the panel is designed on;
     * 320x180 is the same window at scale 4, which is the smallest canvas a player actually gets —
     * the panel loses 106px of width and 60px of height there, and every layout that only fits at
     * the reference size breaks at this one.</p>
     */
    private static final int[][] SIZES = {{426, 240}, {320, 180}};

    /**
     * Ticks a layout stays on screen before the next one replaces it.
     *
     * <p>Three, not one: the first frame of a screen has no hover state and the entrance fade has
     * not finished, and a tooltip needs a dwell before it appears. Anything that only overlaps once
     * the animation settles would be missed by a single frame.</p>
     */
    private static final int FRAMES = 3;

    private static final String PARTICIPANTS =
            "Dev1|u1:8:0:0,Dev2|u2:9:1:0,Dev3|u3:8:0:0,Dev4|u4:9:1:0";

    private record Step(String label, int width, int height, int variant,
                        Supplier<PvScreen> factory) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int cursor;
    private static int frame;
    private static PvScreen current;
    private static boolean finished;

    private PvAuditHarness() {
    }

    public static void register() {
        if (!PvAudit.ON) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(PvAuditHarness::tick);
        System.out.println("[pvaudit] harness armed");
    }

    private static void tick(MinecraftClient client) {
        if (finished) {
            return;
        }
        if (STEPS.isEmpty()) {
            build();
        }
        if (cursor >= STEPS.size()) {
            finish(client);
            return;
        }
        Step step = STEPS.get(cursor);
        if (current == null) {
            current = step.factory.get();
            // setScreen() runs init() at the window's real size; the second call overrides it so the
            // same screen can be measured at a canvas it was not opened at.
            client.setScreen(current);
            current.init(client, step.width(), step.height());
            current.auditVariant(step.variant());
            frame = 0;
            System.out.println("[pvaudit] -- " + step.label() + " v" + step.variant()
                    + " @ " + step.width() + "x" + step.height());
            return;
        }
        if (++frame < FRAMES) {
            return;
        }
        current = null;
        cursor++;
    }

    private static void finish(MinecraftClient client) {
        finished = true;
        int total = PvAudit.summary("layout audit");
        System.out.println("[pvaudit] " + (total == 0 ? "CLEAN" : "FOUND " + total + " overlap(s)"));
        if (Boolean.getBoolean("pvpocket.audit.exit")) {
            client.setScreen(null);
            client.scheduleStop();
        }
    }

    // ------------------------------------------------------------------ the walk

    private static void build() {
        for (int[] size : SIZES) {
            // The reviewer role carries the three-button commit stack and the 还原 chip, which is
            // the arrangement most likely to run out of room; the draft role has a different stack
            // and an editable roster, so both are walked.
            for (int variant = 0; variant < 11; variant++) {
                add("rules/reviewer", size, variant, () -> new RuleScreen(reviewer()));
            }
            for (int variant = 0; variant < 11; variant++) {
                add("rules/draft", size, variant, () -> new RuleScreen(draft()));
            }
            for (int variant = 0; variant < 6; variant++) {
                add("loadout/duel", size, variant, () -> new LoadoutScreen(duelLoadout(), null));
            }
            add("loadout/personal", size, 0, () -> new LoadoutScreen(personalLoadout(), null));
            add("match/list", size, 0, () -> new MatchListScreen(matches()));
        }
    }

    private static void add(String label, int[] size, int variant, Supplier<PvScreen> factory) {
        STEPS.add(new Step(label, size[0], size[1], variant, factory));
    }

    // ------------------------------------------------------------------ synthetic payloads

    private static RulesOpenPayload reviewer() {
        return new RulesOpenPayload("audit", DuelRules.defaults().encode(),
                RulesOpenPayload.ROLE_REVIEWER, 3,
                new RulesOpenPayload.Details("Dev2", PARTICIPANTS, 1, 4, 0, ""));
    }

    private static RulesOpenPayload draft() {
        return new RulesOpenPayload("audit", DuelRules.defaults().encode(),
                RulesOpenPayload.ROLE_DRAFT, 0,
                new RulesOpenPayload.Details("", PARTICIPANTS, 0, 4, 0, "Dev3|u3,Dev4|u4"));
    }

    private static LoadoutOpenPayload duelLoadout() {
        return new LoadoutOpenPayload("audit", Loadout.defaultDuel().encode(), 0, "Dev2");
    }

    /** An empty session id is the personal loadout: a different title, note and 保存 target. */
    private static LoadoutOpenPayload personalLoadout() {
        return new LoadoutOpenPayload("", Loadout.defaultDuel().encode(), 0, "");
    }

    /** Three rows, one of each interesting state: in progress, watching, and a full score line. */
    private static MatchListPayload matches() {
        return new MatchListPayload(String.join("\n",
                "s1|arena_colosseum|ROUND_ACTIVE|2|7|1:0|1|0|Dev1:u1,Dev2:u2",
                "s2|arena_deadwood|ROUND_SETTLED|3|5|2:1|5|1|Dev3:u3,Dev4:u4",
                "s3|flat|WARMUP|1|3|0:0|0|0|Dev1:u1,Dev3:u3"));
    }
}
