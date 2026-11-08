package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.client.gui.loadout.LoadoutScreen;
import com.leipishu.pvpocket.client.gui.match.MatchListScreen;
import com.leipishu.pvpocket.client.gui.rule.RuleScreen;
import com.leipishu.pvpocket.client.home.HomeScreen;
import com.leipishu.pvpocket.client.home.HomeState;
import com.leipishu.pvpocket.client.preset.PresetStore;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.network.HomeStatePayload;
import com.leipishu.pvpocket.network.LoadoutOpenPayload;
import com.leipishu.pvpocket.network.MatchListPayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.preset.PresetKind;
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
        clearPresets();
        int total = PvAudit.summary("layout audit");
        System.out.println("[pvaudit] " + (total == 0 ? "CLEAN" : "FOUND " + total + " overlap(s)"));
        if (Boolean.getBoolean("pvpocket.audit.exit")) {
            client.setScreen(null);
            client.scheduleStop();
        }
    }

    // ------------------------------------------------------------------ the walk

    private static void build() {
        homeData();
        seedPresets();
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
            // One variant per page, plus whatever dialogs the screen declares. The count is asked
            // from the screen rather than written here: it was hardcoded at five, and the history
            // detail dialog — the most crowded layout on the panel — was consequently never measured
            // at all. A screen that grows a dialog can now say so in one place.
            int homeVariants = new HomeScreen().auditVariantCount();
            for (int variant = 0; variant < homeVariants; variant++) {
                add("home", size, variant, HomeScreen::new);
            }
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
    private static final String MATCH_ROWS = String.join("\n",
            "s1|arena_colosseum|ROUND_ACTIVE|2|7|1:0|1|0|Dev1:u1,Dev2:u2",
            "s2|arena_deadwood|ROUND_SETTLED|3|5|2:1|5|1|Dev3:u3,Dev4:u4",
            "s3|flat|WARMUP|1|3|0:0|0|0|Dev1:u1,Dev3:u3");

    private static MatchListPayload matches() {
        return new MatchListPayload(MATCH_ROWS);
    }

    /**
     * Fills the home panel's cache, so its pages are measured with content instead of with the
     * "nothing here yet" line.
     *
     * <p>A screen audited only in its empty state hides every row it owns: the history cards, the
     * round-dot strips, the four stat columns and the win strip do not exist until there is data.
     * The numbers are arbitrary but shaped like real ones — three teams, a surrendered series, a
     * series with rounds both won and lost — because that is what decides the widths.</p>
     *
     * <p>Twelve records rather than three, cycling those same three shapes. Twelve is past one page
     * at the default page size, which is the only way the audit ever sees the pager: the two arrows
     * and the "2 / 3" counter do not exist on a single-page list, so a three-record fixture would
     * leave the newest footer in the panel completely unmeasured.</p>
     */
    private static void homeData() {
        long now = System.currentTimeMillis();
        String[] shapes = {
                // First, and therefore the newest record: twelve rounds, which is what makes the
                // detail dialog's label column wide enough to matter. "第 12 局" is half again as
                // wide as "第 1 局", and a label column that is assumed rather than measured puts
                // the win/loss mark through the last digit — the bug this fixture exists to keep.
                "arena_colosseum|modern|2|1|8|4|1||420000"
                        + "|1,,90000;1,,120000;0,,210000;1,,80000;0,,95000;1,,70000"
                        + ";1,,110000;0,,60000;1,,100000;1,,85000;0,,75000;1,,65000|Dev2,Dev3|",
                "arena_deadwood|legacy|2|1|2|3|0|SURRENDER|610000"
                        + "|1,,80000;0,,140000;0,,90000;0,,120000|Dev4|",
                "flat|modern|4|2|3|0|1||380000|1,,60000;1,,70000;1,,50000|Dev2,Dev3|Dev4,Dev5",
        };
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                history.append('\n');
            }
            history.append('h').append(i).append('|')
                    .append(now - 600_000L * (i + 1)).append('|')
                    .append(shapes[i % shapes.length]);
        }
        HomeState.update(new HomeStatePayload(
                "24,15,61,38,2840,42,19,4,7," + now,
                history.toString(), MATCH_ROWS, HomeStatePayload.FLAG_HAS_STATS));
    }

    /** Names that say where they came from, in case a crashed run leaves them on disk. */
    private static final String SEED_LOADOUT = "audit-loadout";
    private static final String SEED_RULES = "audit-rules";
    private static boolean seeded;

    /**
     * Two presets, so the preset page is measured with rows rather than with its empty state.
     *
     * <p>Only when the store is empty: a developer who already has presets gets their own audited,
     * which is strictly better data. The two that are written here go through the ordinary store and
     * therefore land in the dev run's config, so {@link #clearPresets} takes them back out.</p>
     */
    private static void seedPresets() {
        if (PresetStore.get().count(PresetKind.LOADOUT) == 0
                && PresetStore.get().put(PresetKind.LOADOUT, SEED_LOADOUT,
                Loadout.defaultDuel().encode())) {
            seeded = true;
        }
        if (PresetStore.get().count(PresetKind.RULES) == 0
                && PresetStore.get().put(PresetKind.RULES, SEED_RULES,
                DuelRules.defaults().encode())) {
            seeded = true;
        }
    }

    private static void clearPresets() {
        if (!seeded) {
            return;
        }
        seeded = false;
        PresetStore.get().remove(PresetKind.LOADOUT, SEED_LOADOUT);
        PresetStore.get().remove(PresetKind.RULES, SEED_RULES);
    }
}
