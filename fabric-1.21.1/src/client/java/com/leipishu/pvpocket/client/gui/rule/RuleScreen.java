package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.arena.ArenaMap;
import com.leipishu.pvpocket.arena.ArenaMaps;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvChips;
import com.leipishu.pvpocket.client.gui.PvCycle;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvLayout;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvScrollPane;
import com.leipishu.pvpocket.client.gui.PvSlider;
import com.leipishu.pvpocket.client.gui.PvStepper;
import com.leipishu.pvpocket.client.gui.PvTabs;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvToggle;
import com.leipishu.pvpocket.client.gui.PvValueControl;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.LoadoutRequestPayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.RulesSubmitPayload;
import com.leipishu.pvpocket.rules.DuelRules;
import com.leipishu.pvpocket.rules.RuleCatalog;
import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.rules.RuleGroup;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The rule panel: the screen a duel actually starts from.
 *
 * <p>One screen serves both roles. The proposer sees the same layout as the reviewer, with the same
 * labels, the same ranges and the same defaults — only the footer actions differ. Two screens would
 * have drifted apart within a week, and the whole point of the negotiation is that both sides are
 * looking at the same thing.</p>
 *
 * <p>Nothing is committed until a footer button is pressed. Closing the panel sends an explicit
 * dismiss so the server can drop a draft instead of leaving a proposal hanging forever.</p>
 */
public class RuleScreen extends PvScreen {

    private static final int MAX_W = 580;
    private static final int MAX_H = 348;
    /**
     * Header and footer are chrome, and on a 240px canvas 52 + 40 of chrome is 38% of the panel.
     *
     * <p>The header still carries three things (title, opponent/revision, group hint) but they now
     * sit 4px apart instead of 6, which buys 10px of content without moving anything the eye needs.
     * The footer only has to hold a 20px button with air above and below it.</p>
     */
    private static final int HEADER_H = 42;
    private static final int FOOTER_H = 34;
    /** Upper bound for the tab strip; the actual width is a share of the panel (see {@link #tabWidth()}). */
    private static final int TABS_W = 96;
    private static final int MIN_TABS_W = 64;
    /** The roster toolbar row that holds "add team", above the cards and outside the scroll pane. */
    private static final int TOOLBAR_H = 24;
    /** Below this the group hint is no longer a sentence, so the loadout tag shortens instead. */
    private static final int MIN_HINT_W = 130;

    private final RulesOpenPayload payload;
    private final DuelRules incoming;
    private final List<RuleGroup> groups = List.of(RuleGroup.values());

    private DuelRules edited;
    private PvTabs tabs;
    private PvScrollPane pane;
    private RosterBoard board;
    private PvButton addTeamButton;
    /** Footer buttons, kept so the footer can be rebuilt without leaving orphans in {@link #roots}. */
    private final List<PvButton> footerButtons = new ArrayList<>();
    /** Whether the current footer contains 还原; the footer is rebuilt when this flips. */
    private boolean footerHasRevert;
    private int footerTextRight;
    private boolean submitted;

    public RuleScreen(RulesOpenPayload payload) {
        super(PvLang.t("pvpocket.gui.rule.title"));
        this.payload = payload;
        this.incoming = DuelRules.decode(payload.rules());
        this.edited = incoming.copy();
    }

    /** True while the roster can still be edited, i.e. before the invite has been sent. */
    private boolean rosterEditable() {
        return payload.role() == RulesOpenPayload.ROLE_DRAFT;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        // init() also runs on resize, so everything that survives a resize has to be carried over
        // rather than rebuilt: the open tab and the roster the player has been assembling. Losing
        // the cards because the window changed size is the kind of bug that only shows up on a
        // second monitor, which is exactly when nobody is looking for it.
        int keepTab = tabs == null ? 0 : tabs.selected();
        roots.clear();
        layoutPanel(MAX_W, MAX_H);

        int contentTop = panelY + HEADER_H;
        int contentH = panelH - HEADER_H - FOOTER_H;
        int tabsW = tabWidth();
        int paneX = panelX + 12 + tabsW + 8;
        int paneW = panelX + panelW - 12 - paneX;

        // The roster is tab 0 and the opening tab: the first question of an invite is who is in it,
        // and the rules only mean anything once there is a match to apply them to.
        tabs = PvTabs.ofPanel(groups, PvLang.str("pvpocket.gui.roster.tab"), keepTab);
        tabs.dirtyTest(this::groupDirty);
        tabs.x = panelX + 12;
        tabs.y = contentTop;
        tabs.width = tabsW;
        tabs.fitInto(contentH);
        tabs.onChange(this::buildRows);
        roots.add(tabs);

        pane = new PvScrollPane();
        pane.x = paneX;
        pane.y = contentTop;
        pane.width = paneW;
        pane.height = contentH;
        pane.gap = 2;
        roots.add(pane);

        if (board == null) {
            board = new RosterBoard(rosterEditable(), edited.get("b.teamSize"));
            board.listener(new RosterBoard.Listener() {
                @Override
                public void onAddPlayer(int team) {
                    openPicker(team);
                }

                @Override
                public void onRemovePlayer(int team, int index) {
                    board.removePlayer(team, index);
                }

                @Override
                public void onAddTeam() {
                    board.addTeam();
                    buildRows();
                }

                @Override
                public void onRemoveTeam(int team) {
                    board.removeTeam(team);
                    buildRows();
                }
            });
        }

        buildRows();
        buildFooter();
        setFocus(null);
    }

    /** The tab strip takes a share of the panel so the content column keeps most of it. */
    private int tabWidth() {
        return PvLayout.share(panelW, 0.19F, MIN_TABS_W, TABS_W);
    }

    private void buildRows() {
        if (pane == null || tabs == null) {
            return;
        }
        roots.remove(board);
        if (addTeamButton != null) {
            roots.remove(addTeamButton);
            addTeamButton = null;
        }
        pane.clear();
        // The roster page reserves a toolbar row above the cards; the rule pages have no toolbar and
        // take the whole content box. Set here rather than in init() because the two tabs share one
        // pane instance.
        pane.y = panelY + HEADER_H;
        pane.height = panelH - HEADER_H - FOOTER_H;

        if (tabs.group() == null) {
            buildRosterPage();
            validateFocus();
            return;
        }
        for (RuleDefinition def : RuleCatalog.of(tabs.group())) {
            pane.add(new RuleRow(def, createControl(def), incoming.get(def.id), true));
        }
        pane.layout();
        validateFocus();
    }

    /**
     * The roster page: a fixed toolbar with "add team", and the cards scrolling underneath it.
     *
     * <p>The cards used to be a root widget with no clipping, so a fourth side simply drew past the
     * bottom of the panel and over the footer — the content box is 128px tall on a 426x240 canvas and
     * two rows of cards are 190px. Putting them in the scroll pane is the only arrangement that
     * survives an arbitrary number of sides.</p>
     *
     * <p>The add-team button stays outside the pane, and above it: it is a page-level action, it has
     * to remain visible while the list is scrolled to the last card, and it is a Tab stop — the cards'
     * own "+" hotspots are pointer-only.</p>
     */
    private void buildRosterPage() {
        // The slot count is a rule (b.teamSize), so the cards follow the slider on page B.
        board.setSlots(edited.get("b.teamSize"));
        // Seeded exactly once, and "once" is read off the board itself rather than kept in a flag:
        // init() runs again on resize and a flag would have to be reset in the right places, while
        // an empty board can only mean "never seeded" (the board refuses to drop below two cards).
        if (board.teamCount() == 0) {
            seedBoard();
        }

        if (rosterEditable()) {
            addTeamButton = new PvButton(PvLang.str("pvpocket.gui.roster.add_team"),
                    PvButton.Style.NORMAL, () -> {
                board.addTeam();
                buildRows();
            }).size(76, PvTheme.BTN_H);
            addTeamButton.x = pane.x;
            addTeamButton.y = pane.y + (TOOLBAR_H - PvTheme.BTN_H) / 2;
            addTeamButton.enabled = board.teamCount() < 4;
            roots.add(addTeamButton);

            pane.y += TOOLBAR_H;
            pane.height -= TOOLBAR_H;
        }

        board.visible = true;
        board.height = board.preferredHeight();
        pane.add(board);
        pane.layout();
    }

    /** Rebuilds the cards from what the server sent: one card per side, players in team order. */
    private void seedBoard() {
        board.clear();
        java.util.TreeMap<Integer, java.util.List<Slot>> byTeam = new java.util.TreeMap<>();
        int teams = 0;
        for (Slot slot : slots()) {
            byTeam.computeIfAbsent(slot.team(), k -> new java.util.ArrayList<>()).add(slot);
            teams = Math.max(teams, slot.team() + 1);
        }
        // Two cards is the floor even with one player in the list: the draft opens with two empty
        // cards, and a proposal that arrived with a side nobody is on still has to draw that side.
        board.addTeam();
        board.addTeam();
        for (int team = 0; team < Math.max(teams, 2); team++) {
            if (team >= board.teamCount()) {
                board.addTeam();
            }
            java.util.List<Slot> members = byTeam.get(team);
            if (members != null) {
                for (Slot member : members) {
                    board.addPlayer(team, member.name(), member.uuid(), member.state());
                }
            }
        }
    }

    private void openPicker(int team) {
        java.util.List<String[]> candidates = new java.util.ArrayList<>();
        if (!payload.candidates().isEmpty()) {
            for (String pair : payload.candidates().split(",")) {
                String[] id = pair.split("\\|", 2);
                if (id.length == 2) {
                    candidates.add(id);
                }
            }
        }
        // Drop anybody already on a card: the server would refuse them, and a list that offers a
        // name it is about to reject is a list the player stops trusting.
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (java.util.List<RosterBoard.Member> side : board.teams()) {
            for (RosterBoard.Member member : side) {
                taken.add(member.uuid);
            }
        }
        candidates.removeIf(entry -> taken.contains(entry[1]));

        PlayerPicker picker = new PlayerPicker(candidates, team + 1);
        picker.x = 0;
        picker.y = 0;
        picker.width = width;
        picker.height = height;
        picker.listener(new PlayerPicker.Listener() {
            @Override
            public void onPick(String name, String uuid) {
                board.addPlayer(team, name, uuid);
                setOverlay(null);
                buildRows();
            }

            @Override
            public void onCancel() {
                setOverlay(null);
            }
        });
        setOverlay(picker);
    }

    private PvValueControl createControl(RuleDefinition def) {
        int value = edited.get(def.id);
        PvValueControl control = switch (def.kind) {
            case TOGGLE -> new PvToggle(value != 0);
            case INT -> isStepped(def.id) ? new PvStepper(def, value) : new PvSlider(def, value);
            case ENUM -> {
                PvCycle cycle = new PvCycle(def, value);
                if ("e.template".equals(def.id)) {
                    cycle.skip(this::mapTaken);
                }
                yield cycle;
            }
            case MULTI -> new PvChips(def, value);
            case FIXED -> null;
        };
        if (control != null) {
            // One listener, not two: onChange replaces rather than chains, so the "write the value
            // back" step and the "keep the two series numbers consistent" step have to be the same
            // callback or the second silently drops the first.
            control.onChange(v -> {
                edited.set(def.id, v);
                if ("a.maxRounds".equals(def.id)) {
                    clampWinsNeeded();
                }
            });
            if ("e.template".equals(def.id)) {
                control.onChange(this::pickTemplate);
            }
        }
        return control;
    }

    /**
     * The two series numbers are steppers, not sliders.
     *
     * <p>They are exact counts a player thinks in — "7 rounds, first to 4" — and the range is 1..15,
     * so a slider makes landing on exactly 4 a matter of pixel aim. Everything else in the panel
     * stays a slider because everything else is a quantity you feel your way to.</p>
     */
    private static boolean isStepped(String ruleId) {
        return "a.maxRounds".equals(ruleId) || "a.winsNeeded".equals(ruleId);
    }

    /**
     * Keeps "win 4 of at most 3" from being a state the panel can sit in.
     *
     * <p>Lowering the round cap below the win target is a legal thing to do on the way to a new
     * setting, so the win target follows the cap down instead of the cap being refused. The server
     * clamps the same pair on arrival — this is what stops the panel from showing a target that
     * cannot be reached.</p>
     */
    private void clampWinsNeeded() {
        int maxRounds = edited.get("a.maxRounds");
        if (edited.get("a.winsNeeded") > maxRounds) {
            edited.set("a.winsNeeded", maxRounds);
            MinecraftClient.getInstance().execute(this::buildRows);
        }
    }

    /**
     * True when another duel is already standing on this map.
     *
     * <p>The bitmask comes from the server on every panel open, so it is a snapshot: a map can be
     * taken a second after the panel opens. That race is settled at "同意并开始", which re-checks
     * and refuses — this method only stops the player from picking a map that was already gone,
     * which is the common case and the one that would otherwise waste a full negotiation.</p>
     */
    private boolean mapTaken(int index) {
        return index >= 0 && index < Integer.SIZE && (payload.busyMaps() & (1 << index)) != 0;
    }

    /**
     * Selecting a map carries its size with it.
     *
     * <p>The server overrides the size rules with the map's bounding box anyway, so leaving the
     * sliders on 48x48 would be showing the player a number that is not going to be used. The rows
     * are rebuilt instead of patched in place because the size sliders belong to the same pane the
     * click came from, and poking their value mid-callback is how a slider ends up drawing itself
     * from a stale index.</p>
     */
    private void pickTemplate(int choice) {
        edited.set("e.template", choice);
        ArenaMap map = ArenaMaps.byIndex(choice);
        if (map.isFlat()) {
            return;
        }
        edited.set("e.sizeX", map.sizeX);
        edited.set("e.sizeZ", map.sizeZ);
        edited.set("e.sizeY", map.sizeY);
        MinecraftClient.getInstance().execute(this::buildRows);
    }

    private void buildFooter() {
        // Rebuild-safe: the footer is rebuilt when the edit count crosses zero (see syncFooter), and
        // without this the old buttons would stay in the root list and both sets would be drawn.
        roots.removeAll(footerButtons);
        footerButtons.clear();

        int buttonY = panelY + panelH - FOOTER_H + 7;
        int right = panelX + panelW - 12;

        PvButton primary;
        if (payload.role() == RulesOpenPayload.ROLE_DRAFT) {
            // Draft: nothing has been sent yet, so the only decisions are "send it" and "drop it".
            // No reject and no revert — there is no earlier revision to go back to.
            primary = new PvButton(PvLang.str("pvpocket.gui.roster.button.send"),
                    PvButton.Style.PRIMARY,
                    () -> submit(RulesSubmitPayload.ACTION_PROPOSE)).size(88, PvTheme.BTN_H);
            right = place(primary, right, buttonY);
            right = place(new PvButton(PvLang.str("pvpocket.gui.rule.button.cancel"),
                    PvButton.Style.DANGER,
                    this::close).size(56, PvTheme.BTN_H), right, buttonY);
        } else if (payload.role() == RulesOpenPayload.ROLE_PROPOSER) {
            primary = new PvButton(PvLang.str("pvpocket.gui.rule.button.propose"),
                    PvButton.Style.PRIMARY,
                    () -> submit(RulesSubmitPayload.ACTION_PROPOSE)).size(80, PvTheme.BTN_H);
            right = place(primary, right, buttonY);
            right = place(new PvButton(PvLang.str("pvpocket.gui.rule.button.cancel"),
                    PvButton.Style.DANGER,
                    this::close).size(56, PvTheme.BTN_H), right, buttonY);
        } else {
            primary = new PvButton(PvLang.str("pvpocket.gui.rule.button.accept"),
                    PvButton.Style.PRIMARY,
                    () -> submit(RulesSubmitPayload.ACTION_ACCEPT)).size(92, PvTheme.BTN_H);
            right = place(primary, right, buttonY);
            right = place(new PvButton(PvLang.str("pvpocket.gui.rule.button.repropose"),
                    PvButton.Style.NORMAL,
                    () -> submit(RulesSubmitPayload.ACTION_PROPOSE)).size(88, PvTheme.BTN_H), right, buttonY);
            right = place(new PvButton(PvLang.str("pvpocket.gui.rule.button.reject"),
                    PvButton.Style.DANGER,
                    () -> submit(RulesSubmitPayload.ACTION_REJECT)).size(56, PvTheme.BTN_H), right, buttonY);
        }
        defaultButton = primary;

        // Revert lives at the far end of the button row so it never sits where the confirm button
        // is expected, but is still available the moment anything has been touched.
        //
        // Not in a draft: there 还原 would also clear the sides the player has just placed (a draft's
        // roster counts as "received"), and a button that wipes five clicks of setup must not sit
        // next to 发送邀请.
        footerHasRevert = payload.role() != RulesOpenPayload.ROLE_DRAFT && changeCount() > 0;
        if (footerHasRevert) {
            PvButton revert = new PvButton(PvLang.str("pvpocket.gui.rule.button.revert"),
                    PvButton.Style.GHOST, this::revert)
                    .size(50, PvTheme.BTN_H);
            right = place(revert, right, buttonY);
        }
        // The loadout editor sits leftmost, furthest from "send proposal". Configuring items and
        // committing a rule set are two different decisions and should not be neighbours.
        //
        // Labelled "edit loadout" rather than "loadout" because the tab strip already has a loadout
        // tab (rule group D, which is about *rules* for what you bring). Two things with the same
        // name in one panel, one of which opens the editor and one of which shows a rule page, is a
        // coin flip the player should not have to make.
        right = place(new PvButton(PvLang.str("pvpocket.gui.rule.button.edit_loadout"),
                PvButton.Style.NORMAL, this::openLoadout)
                .size(68, PvTheme.BTN_H), right, buttonY);
        footerTextRight = right - 8;
    }

    /** Asks the server to open the loadout editor; the server answers with a {@code loadout_open}. */
    private void openLoadout() {
        if (ClientPlayNetworking.canSend(LoadoutRequestPayload.ID)) {
            ClientPlayNetworking.send(new LoadoutRequestPayload());
        }
    }

    private int place(PvButton button, int right, int y) {
        button.x = right - button.width;
        button.y = y;
        roots.add(button);
        footerButtons.add(button);
        return button.x - 8;
    }

    /**
     * Adds or removes 还原 as the edit count crosses zero.
     *
     * <p>The footer used to be built only from {@code init()}, so the button appeared after a window
     * resize and not when the player actually changed a rule — the one moment it means anything.
     * Rebuilding on the transition instead of every frame keeps the buttons from being torn down and
     * recreated in the middle of a slider drag.</p>
     */
    private void syncFooter() {
        boolean want = payload.role() != RulesOpenPayload.ROLE_DRAFT && changeCount() > 0;
        if (want != footerHasRevert) {
            buildFooter();
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Before anything is drawn, so a footer button that just appeared is laid out this frame.
        syncFooter();
        TextRenderer tr = font();
        boolean reviewer = payload.role() == RulesOpenPayload.ROLE_REVIEWER;

        String title = PvLang.str("pvpocket.gui.rule.title");
        PvDraw.title(ctx, tr, title, panelX + 16, panelY + 10, PvTheme.TEXT);

        int titleEnd = panelX + 16 + PvDraw.titleWidth(tr, title) + 10;
        // A team match names the sides instead of the opponents. "Duel with Dev2, Dev4" answers
        // "who am I fighting", but in a 2v2 the question that actually needs answering before
        // agreeing is "who is on my side" — and the sides are drawn by hand now, so the invite order
        // says nothing about them. It gets its own sentence rather than reusing the "Duel with %s"
        // one, which would read "Duel with 1队 Dev1+Dev3 · 2队 ...".
        String subtitle;
        if (rosterEditable()) {
            // Nothing has been sent yet, so there is no counterpart to name. The subtitle carries the
            // shape of the roster being built instead — the one fact that changes as names land on
            // cards, and the one that decides whether "发送" can go ahead at all.
            subtitle = PvLang.str("pvpocket.gui.roster.subtitle",
                    board == null ? 0 : board.playingTeams(),
                    board == null ? 0 : board.playerCount());
        } else {
            String teams = teamsSummary();
            subtitle = !teams.isEmpty()
                    ? PvLang.str("pvpocket.gui.rule.subtitle.teams", teams)
                    : reviewer
                    ? PvLang.str("pvpocket.gui.rule.subtitle.reviewer", otherLabel())
                    : PvLang.str("pvpocket.gui.rule.subtitle.proposer", otherLabel());
        }

        // The revision is a chip rather than loose accent text: at 12px of amber, a bare "v0" read as
        // part of the subtitle. A chip gives it an edge and tells the eye it is a separate fact.
        String version = "v" + payload.revision();
        int versionW = tr.getWidth(version) + 12;
        int versionX = panelX + panelW - 12 - versionW;
        PvDraw.roundRect(ctx, versionX, panelY + 6, versionW, 14, 3, PvTheme.ACCENT_SOFT);
        PvDraw.textCenter(ctx, tr, version, versionX + versionW / 2, panelY + 9, PvTheme.ACCENT);

        int subtitleMax = versionX - 8 - titleEnd;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, subtitle, subtitleMax), titleEnd, panelY + 10, PvTheme.TEXT_DIM);

        PvDraw.hLine(ctx, panelX + 12, panelY + 27, panelW - 24, PvTheme.DIVIDER);

        // The loadout summary sits on the hint line rather than the title: the title row is already
        // carrying the opponent's name and the revision, and this is the line the eye passes on the
        // way to the tab strip. Seeing "loadout 8 items · agreed 1/2" here is what makes the edit
        // button in the footer discoverable instead of merely present.
        //
        // The summary is assembled here rather than sent pre-formatted: the server knows the counts
        // and the agreement tally, but not the reader's language.
        //
        // The hint starts at pane.x, not at panelX + 12 — the tab strip sits between them. Budgeting
        // the hint against panelW instead of the pane's own width let the two strings overlap.
        String loadout = loadoutSummary();
        int hintRight = panelX + panelW - 12;
        int hintMax = hintRight - pane.x;
        if (!loadout.isEmpty()) {
            int tagW = tr.getWidth(loadout);
            // The summary wins the space, because it is the only place the agreement counter appears
            // and a negotiation where nobody can see "agreed 1/2" is a negotiation nobody can act on.
            // The hint gives way instead — but only down to a point; below it the summary drops to
            // its agreement half and the hint gets its space back.
            if (hintRight - tagW - 12 - pane.x < MIN_HINT_W) {
                loadout = PvLang.str("pvpocket.gui.rule.loadout_agreed",
                        payload.agreed(), payload.total());
                tagW = tr.getWidth(loadout);
            }
            PvDraw.textRight(ctx, tr, loadout, hintRight, panelY + 32, PvTheme.TEXT_DIM);
            hintMax = hintRight - tagW - 12 - pane.x;
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, groupHint(tabs.group()), Math.max(0, hintMax)),
                pane.x, panelY + 32, PvTheme.TEXT_MUTED);
        // The roster toolbar is a band of its own; without a rule under it the add-team button reads
        // as the first row of the first card.
        if (tabs.group() == null && rosterEditable()) {
            PvDraw.hLine(ctx, pane.x, pane.y - 1, pane.width, PvTheme.DIVIDER);
        }
        PvDraw.hLine(ctx, panelX + 12, panelY + panelH - FOOTER_H, panelW - 24, PvTheme.DIVIDER);
        drawFooterText(ctx, tr, reviewer);
    }

    private void drawFooterText(DrawContext ctx, TextRenderer tr, boolean reviewer) {
        int y = panelY + panelH - FOOTER_H + 13;
        int maxW = footerTextRight - panelX - 16;
        int changes = changeCount();

        // The draft footer talks about the roster, not about the other side: there is no other side
        // yet, and "you are proposing to nobody" would be a sentence the player has to decode.
        //
        // On the roster page it names the blocker rather than restating the button. "Send when the
        // sides are set" was the same sentence as the button label, and it did not survive the
        // footer's width — a line that ends in an ellipsis is not a hint. Naming what is still
        // missing is shorter and is the one thing the reader cannot see for themselves: the cards
        // show who is placed, not how many sides the rules require.
        String left;
        int color = PvTheme.TEXT_MUTED;
        if (rosterEditable()) {
            int teams = board == null ? 0 : board.playingTeams();
            int people = board == null ? 0 : board.playerCount();
            left = teams < 2
                    ? PvLang.str("pvpocket.gui.roster.footer.need_sides")
                    : people < 2
                    ? PvLang.str("pvpocket.gui.roster.footer.need_people")
                    : PvLang.str("pvpocket.gui.roster.footer");
            if (teams >= 2 && people >= 2) {
                color = PvTheme.ACCENT;
            }
        } else {
            left = reviewer
                    ? PvLang.str("pvpocket.gui.rule.footer.reviewer")
                    : PvLang.str("pvpocket.gui.rule.footer.proposer");
            if (changes > 0) {
                left = PvLang.str("pvpocket.gui.rule.footer.changed", changes);
                color = PvTheme.ACCENT;
            }
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, left, Math.max(0, maxW)), panelX + 16, y, color);
    }

    /**
     * One player as the server describes them.
     *
     * <p>The wire form is {@code name|uuid:count:team:state}. The uuid rides inside the name field
     * because the payload's participant list was already at the codec's field budget and a fourth
     * field would have cost a new payload; the split is done here once so that nothing downstream
     * has to remember which colon is which.</p>
     */
    private record Slot(String name, String uuid, int count, int team, int state) {
    }

    /** The participant list, parsed. Malformed records are skipped rather than fatal. */
    private java.util.List<Slot> slots() {
        java.util.List<Slot> out = new java.util.ArrayList<>();
        for (String record : payload.loadoutCounts().split(",")) {
            String[] parts = record.split(":");
            if (parts.length < 4) {
                continue;
            }
            String[] id = parts[0].split("\\|", 2);
            if (id.length < 2) {
                continue;
            }
            try {
                out.add(new Slot(id[0], id[1], Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
            } catch (NumberFormatException e) {
                // A record the server did not write in this format is one this panel cannot show;
                // dropping it is better than drawing a row whose numbers came from nothing.
            }
        }
        return out;
    }

    /**
     * The loadout tag, built from the numbers the server sent.
     *
     * <p>{@code Dev1 8 · Dev2 9 · agreed 1/2}, with the participant list encoded as
     * {@code name|uuid:count:team:state}. Assembled on the client so the separator and the words
     * match the reader's language, and so the panel does not have to parse a sentence it did not
     * write.</p>
     *
     * <p>Only the counts appear here. The side each player landed on is a separate question with a
     * separate answer — see {@link #teamsSummary()} — because putting both on one line makes it too
     * wide for four players, and the fallback then drops <em>everything</em> and leaves the panel
     * showing "Agreed 0/4" with no roster at all.</p>
     */
    private String loadoutSummary() {
        StringBuilder counts = new StringBuilder();
        for (Slot slot : slots()) {
            if (counts.length() > 0) {
                counts.append(" · ");
            }
            counts.append(slot.name()).append(' ').append(slot.count());
        }
        if (counts.length() == 0) {
            return "";
        }
        return PvLang.str("pvpocket.gui.rule.loadout_summary",
                counts.toString(), payload.agreed(), payload.total());
    }

    /** The opponent's name, or the reader's word for "opponent" when there is nobody to name. */
    private String otherLabel() {
        return payload.otherName().isEmpty()
                ? PvLang.str("pvpocket.gui.rule.opponent")
                : payload.otherName();
    }

    /**
     * Who is on which side, {@code "1队 Dev1+Dev3 · 2队 Dev2+Dev4"}.
     *
     * <p>Read out of the same records the loadout tag uses, so it costs no extra payload field and
     * can never disagree with it. This is the line that makes the roster verifiable: the sides are
     * drawn by hand now, so without a preview the reviewer is agreeing to a match-up they have never
     * seen.</p>
     *
     * <p>Empty for a one-on-one, where the opponent's name already says everything and a "1队 Dev1 ·
     * 2队 Dev2" prefix would be noise.</p>
     */
    private String teamsSummary() {
        if (payload.total() <= 2) {
            return "";
        }
        // Team index -> members, in the order the server listed them (which is the roster order, so
        // the first name on a side is the one whose loadout that side fights with under "每队统一").
        java.util.Map<Integer, StringBuilder> byTeam = new java.util.TreeMap<>();
        for (Slot slot : slots()) {
            StringBuilder names = byTeam.computeIfAbsent(slot.team(), k -> new StringBuilder());
            if (names.length() > 0) {
                names.append('+');
            }
            names.append(slot.name());
        }
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<Integer, StringBuilder> e : byTeam.entrySet()) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(PvLang.str("pvpocket.gui.rule.team_tag", e.getKey() + 1))
                    .append(' ').append(e.getValue());
        }
        return out.toString();
    }

    /**
     * One short line per group, shown under the title so the tabs do not need to explain themselves.
     *
     * <p>Kept short. The line shares its row with the loadout tag, and a hint that needs truncation
     * has stopped being a hint — the tab label already names the group, so this only has to say what
     * is <em>inside</em> it.</p>
     */
    private static String groupHint(RuleGroup group) {
        // The roster tab has no rule group: it is the page that decides who the rules apply to.
        return group == null
                ? PvLang.str("pvpocket.gui.roster.hint")
                : PvLang.str(group.hintKey);
    }

    // ------------------------------------------------------------------ actions

    private int changeCount() {
        int n = 0;
        for (RuleDefinition def : RuleCatalog.all()) {
            if (edited.get(def.id) != incoming.get(def.id)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Whether any rule on that tab differs from what the server sent — the tab strip's dot.
     *
     * <p>Index 0 is the roster and never gets a dot: the sides are the player's own work, and every
     * card already shows what was placed, so the dot would be lit from the moment the panel opens
     * and would mean nothing.</p>
     */
    private boolean groupDirty(int index) {
        if (index <= 0 || index > groups.size()) {
            return false;
        }
        for (RuleDefinition def : RuleCatalog.of(groups.get(index - 1))) {
            if (edited.get(def.id) != incoming.get(def.id)) {
                return true;
            }
        }
        return false;
    }

    private void revert() {
        edited = incoming.copy();
        // A draft's roster is part of what was "received", so reverting has to take the cards back
        // too — otherwise the button would undo the rules and leave the sides as they were drawn,
        // which is neither the incoming state nor the current one.
        if (rosterEditable() && board != null) {
            board.clear();
        }
        roots.clear();
        init();
    }

    private void submit(int action) {
        if (submitted) {
            return;
        }
        if (rosterEditable()) {
            // A draft stays open after sending. The server validates the roster against who is
            // actually online and against the rules that were just sent, and a refusal has to leave
            // the cards exactly where they are so the player can fix the one thing that is wrong —
            // closing the panel here would throw away an invite that took five clicks to assemble
            // and give the error message nowhere to land. A successful send replaces this screen
            // with the proposal panel a tick later, so nothing is left hanging.
            send(action);
            return;
        }
        submitted = true;
        send(action);
        close();
    }

    private void send(int action) {
        if (!ClientPlayNetworking.canSend(RulesSubmitPayload.ID)) {
            return;
        }
        // The roster rides on every submit and is empty once the invite is out: the server ignores
        // it outside the draft, and sending it again from a reviewer would be a second way to
        // rearrange sides that the reviewer has no business having.
        String roster = rosterEditable() ? board.encode() : "";
        ClientPlayNetworking.send(new RulesSubmitPayload(action, edited.encode(), roster));
    }

    /**
     * Closing is a decision too. A proposer who walks away from an unfinished draft would otherwise
     * leave the other side holding an invite that can never be answered, so the dismissal is sent
     * explicitly and the server voids the draft.
     */
    @Override
    public void close() {
        if (!submitted) {
            submitted = true;
            send(RulesSubmitPayload.ACTION_DISMISS);
        }
        MinecraftClient.getInstance().setScreen(null);
    }

    public String sessionId() {
        return payload.sessionId();
    }

    /**
     * Server-driven close: the negotiation already ended on the authoritative side, so there is
     * nothing to report back and reporting anything would be a lie about a finished session.
     */
    public void closeSilently() {
        submitted = true;
        MinecraftClient.getInstance().setScreen(null);
    }
}
