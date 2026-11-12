package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.arena.ArenaMap;
import com.leipishu.pvpocket.arena.ArenaMaps;
import com.leipishu.pvpocket.client.gui.PvBadge;
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
import com.leipishu.pvpocket.client.gui.preset.PresetOverlay;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.LoadoutRequestPayload;
import com.leipishu.pvpocket.network.RulesOpenPayload;
import com.leipishu.pvpocket.network.RulesSubmitPayload;
import com.leipishu.pvpocket.preset.PresetKind;
import com.leipishu.pvpocket.rules.DuelRules;
import com.leipishu.pvpocket.rules.RuleCatalog;
import com.leipishu.pvpocket.rules.RuleDefinition;
import com.leipishu.pvpocket.rules.RuleGroup;
import com.leipishu.pvpocket.rules.Rulebook;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

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
     * Chrome metrics. The header is one row of text and nothing else, so 30px is the height of a
     * 16px chip plus 7px of air above and below — the 42px version carried a second line, and a
     * second line is what made the bar read as a thick lid rather than as a title.
     */
    private static final int HEADER_H = 30;
    /** The status line under the well: one 9px line with air, no buttons of its own. */
    private static final int STATUS_H = 18;
    /** Height of a commit button stacked in the sidebar. */
    private static final int ACTION_H = 18;
    private static final int ACTION_GAP = 4;
    /** Inset of the sidebar's contents from the panel edge. */
    private static final int SIDE_PAD = 6;
    private static final int TAB_GAP = 4;
    /** Sidebar width: wide enough for the longest label it holds, and no wider. */
    private static final int SIDEBAR_MIN = 72;
    private static final int SIDEBAR_MAX = 100;
    /** The roster toolbar row that holds "add team", above the cards and outside the scroll pane. */
    private static final int TOOLBAR_H = 24;

    private final RulesOpenPayload payload;
    private final DuelRules incoming;
    private final List<RuleGroup> groups = List.of(RuleGroup.values());

    private DuelRules edited;
    private PvTabs tabs;
    private PvScrollPane pane;
    private RosterBoard board;
    private PvButton addTeamButton;
    /** Title-row chip: the preset dialog. */
    private PvButton presetButton;
    /**
     * 编辑配装, on the 配装 page's toolbar.
     *
     * <p>It used to be a chip in the title row, which made the header carry a link to a whole other
     * screen next to the version and the subtitle. A page-level action belongs on its page: the
     * player reading the 配装 rules is the one who wants to open the editor, and nobody else needs
     * to be told the editor exists from the title band.</p>
     */
    private PvButton loadoutButton;
    /** The commit buttons stacked at the foot of the sidebar, bottom-most first. */
    private final List<PvButton> actionButtons = new ArrayList<>();
    /** 还原, in the status line, present only while something has been edited. */
    private PvButton revertButton;
    /** y of the gap above the topmost commit button; the tab strip ends here. */
    private int actionTop;
    /** Whether the current page has a toolbar band above the scroll pane. */
    private boolean toolbarVisible;
    /** Whether the commit buttons are a row under the well instead of a stack in the sidebar. */
    private boolean compact;
    /** Whether the current stack contains 还原; the stack is rebuilt when this flips. */
    private boolean footerHasRevert;
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

        // Built before the column is measured: the sidebar has to hold the longest commit label as
        // well as the longest tab label, so it cannot be sized until the buttons exist.
        List<PvButton> stack = buildStack();

        chromeH = HEADER_H;
        chromeW = sidebarWidth(stack);
        int paneX = panelX + chromeW + 12;
        int paneW = panelW - chromeW - 24;

        // The roster is tab 0 and the opening tab: the first question of an invite is who is in it,
        // and the rules only mean anything once there is a match to apply them to.
        tabs = PvTabs.ofPanel(groups, PvLang.str("pvpocket.gui.roster.tab"), keepTab);
        tabs.dirtyTest(this::groupDirty);
        tabs.x = panelX;
        tabs.y = panelY + HEADER_H;
        tabs.width = chromeW;
        tabs.onChange(this::buildRows);
        roots.add(tabs);

        pane = new PvScrollPane();
        pane.x = paneX;
        pane.width = paneW;
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

        buildActions(stack);
        buildChips();
        buildRows();
        setFocus(null);
    }

    /**
     * Width of the sidebar column: the longest label it has to hold plus its insets, clamped.
     *
     * <p>Derived from the labels rather than fixed, because the same column carries "Players" in one
     * language and 参与者 in another, and "Accept & start" beside 同意并开始. A fixed width is either
     * wasteful in one or truncating in the other. Both kinds of label count: the tabs and the commit
     * stack share the column, and in English the buttons are the wider of the two.</p>
     */
    private int sidebarWidth(List<PvButton> stack) {
        Font tr = font();
        int widest = tr.width(PvLang.str("pvpocket.gui.roster.tab"));
        for (RuleGroup group : groups) {
            widest = Math.max(widest, tr.width(group.label()));
        }
        // 34 = 2 x SIDE_PAD + 10px text inset + 12px for the dirty dot column.
        int need = widest + 34;
        // A commit button is the column's width minus SIDE_PAD on each side, and its label is
        // fitted to that minus 4 — so 16 is what a label needs beyond its own width. English is the
        // case that binds here: "Accept & start" is 84px against a 42px "Players" tab.
        for (PvButton button : stack) {
            need = Math.max(need, tr.width(button.label()) + 16);
        }
        return PvLayout.clamp(need, SIDEBAR_MIN, SIDEBAR_MAX);
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
        if (loadoutButton != null) {
            roots.remove(loadoutButton);
            loadoutButton = null;
        }
        toolbarVisible = false;
        pane.clear();
        // The content well runs from the header to the status line, at the full height of the
        // panel's right-hand region. A page with a toolbar row above its content gives that row its
        // height back below. Set here rather than in init() because the tabs share one pane
        // instance.
        pane.y = panelY + HEADER_H;
        // The compact stack took a row of the well's height; see buildActions.
        pane.height = panelH - HEADER_H - STATUS_H - (compact ? ACTION_H + ACTION_GAP : 0);

        if (tabs.group() == null) {
            buildRosterPage();
            validateFocus();
            return;
        }
        if (tabs.group() == RuleGroup.D) {
            buildLoadoutToolbar();
        }
        for (RuleDefinition def : RuleCatalog.of(tabs.group())) {
            pane.add(new RuleRow(def, createControl(def), incoming.get(def.id), rowEnabled(def)));
        }
        pane.layout();
        validateFocus();
    }

    /**
     * Whether this rule's control is usable at all, given what the other rules are set to.
     *
     * <p>Two pairs are only meaningful together, and the panel used to leave both halves fully
     * editable — which is how a rule gets the reputation of not working.</p>
     *
     * <ul>
     *   <li>{@code a.roundLimit} and {@code a.timeoutVerdict} describe a clock that only runs when
     *       超时 is one of the accepted win conditions ({@code SessionManager#tickRoundLimit} returns
     *       early otherwise). Setting a 15-minute limit with 超时 unchecked produced no clock at all,
     *       silently.</li>
     *   <li>{@code e.sizeX/Y/Z} only apply to the flat plot. Every other map carries its own
     *       bounding box and {@code ArenaManager#allocate} uses that instead, so the sliders were
     *       moving a number nobody reads.</li>
     * </ul>
     *
     * <p>Greyed rather than hidden: the value is still part of the rule set and still shown in the
     * summary, and a row that disappears makes the panel feel like it lost something. The hint on
     * each row explains the dependency, and the row's label dims to match its control.</p>
     */
    private boolean rowEnabled(RuleDefinition def) {
        switch (def.id) {
            case "a.roundLimit":
            case "a.timeoutVerdict":
                return (edited.get("a.winConditions")
                        & (1 << Rulebook.WinCondition.TIMEOUT.ordinal())) != 0;
            case "e.sizeX":
            case "e.sizeY":
            case "e.sizeZ":
                return ArenaMaps.byIndex(edited.get("e.template")).isFlat();
            default:
                return true;
        }
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

            toolbarVisible = true;
            pane.y += TOOLBAR_H;
            pane.height -= TOOLBAR_H;
        }

        board.visible = true;
        board.height = board.preferredHeight();
        pane.add(board);
        pane.layout();
    }

    /**
     * The 配装 page's toolbar: 编辑配装, above the rules it edits.
     *
     * <p>Same band as the roster's "+ 添加队伍", for the same reason: it is a page-level action that
     * has to stay reachable while the list is scrolled, and it opens something rather than changing
     * a value, so it cannot be a row. It is a normal (outlined) button rather than a ghost chip now
     * that it is the only thing on the band — a bare word over the well would read as a heading.</p>
     */
    private void buildLoadoutToolbar() {
        loadoutButton = new PvButton(PvLang.str("pvpocket.gui.rule.button.edit_loadout"),
                PvButton.Style.NORMAL, this::openLoadout);
        loadoutButton.size(Math.max(76, font().width(loadoutButton.label()) + 16), PvTheme.BTN_H);
        loadoutButton.x = pane.x;
        loadoutButton.y = pane.y + (TOOLBAR_H - PvTheme.BTN_H) / 2;
        roots.add(loadoutButton);

        toolbarVisible = true;
        pane.y += TOOLBAR_H;
        pane.height -= TOOLBAR_H;
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

    /**
     * Whether the roster has reached {@code b.maxPlayers}.
     *
     * <p>Checked here as well as on the server because the server's refusal arrives at "同意并开始",
     * which is the worst possible moment to learn about it: the player has already picked sides and
     * is looking at a button that will not work. The card grid cannot express this on its own —
     * 4 teams x 5 slots is twenty places, and the cap is a separate number — so the footer says it.
     * </p>
     */
    private boolean atPlayerCap() {
        return board != null && board.playerCount() >= edited.get("b.maxPlayers");
    }

    private void openPicker(int team) {
        if (atPlayerCap()) {
            return;
        }
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
                // Rows whose usability depends on this one have to be rebuilt, not patched: the
                // dependency lives in RuleRow.editable, which is fixed at construction. Deferred to
                // the next tick because this callback runs inside the control's own click handler
                // and rebuilding the pane under it would pull the ground out from under the event.
                if ("a.winConditions".equals(def.id)) {
                    Minecraft.getInstance().execute(this::buildRows);
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
            Minecraft.getInstance().execute(this::buildRows);
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
        if (!map.isFlat()) {
            edited.set("e.sizeX", map.sizeX);
            edited.set("e.sizeZ", map.sizeZ);
            edited.set("e.sizeY", map.sizeY);
        }
        // Rebuilt on both branches. The flat branch used to return early, which was fine while the
        // size rows were always editable — now that they are greyed out on every other map, coming
        // back to 平坦 has to re-enable them, and that state is fixed when the row is constructed.
        Minecraft.getInstance().execute(this::buildRows);
    }

    /**
     * Stacks the commit buttons at the foot of the sidebar, primary lowest.
     *
     * <p>They used to be a row along the bottom of the panel, which cost the content area a 34px
     * band and forced the sidebar to stop above it. In a 72px column the same buttons stack, and
     * the primary sits nearest the corner where a form's confirm button belongs.</p>
     *
     * <p>Only the actions that <em>commit</em> the negotiation live here. 还原 and 编辑配装 are not
     * commits — one undoes an edit, the other opens a screen — so they went to the status line and
     * the 配装 page's toolbar, which is also what keeps the stack at three buttons. Five would not
     * fit beside eight tabs in a 190px column at any legible size.</p>
     */
    private void buildActions() {
        buildActions(buildStack());
    }

    /**
     * The commit buttons for this role, unpositioned.
     *
     * <p>Separate from the layout because the sidebar is measured from them: the column has to be
     * wide enough for "Accept & start" as well as for "Sides", and those two disagree by 48px in
     * English. Building the buttons first and the column second is the only order in which the
     * width can be right — the other order truncates the label or lets it hang over the well.</p>
     */
    private List<PvButton> buildStack() {
        PvButton primary;
        List<PvButton> stack = new ArrayList<>();
        if (payload.role() == RulesOpenPayload.ROLE_DRAFT) {
            // Draft: nothing has been sent yet, so the only decisions are "send it" and "drop it".
            // No reject and no revert — there is no earlier revision to go back to.
            primary = new PvButton(PvLang.str("pvpocket.gui.roster.button.send"),
                    PvButton.Style.PRIMARY,
                    () -> submit(RulesSubmitPayload.ACTION_PROPOSE));
            stack.add(primary);
            stack.add(new PvButton(PvLang.str("pvpocket.gui.rule.button.cancel"),
                    PvButton.Style.DANGER, this::onClose));
        } else if (payload.role() == RulesOpenPayload.ROLE_PROPOSER) {
            primary = new PvButton(PvLang.str("pvpocket.gui.rule.button.propose"),
                    PvButton.Style.PRIMARY,
                    () -> submit(RulesSubmitPayload.ACTION_PROPOSE));
            stack.add(primary);
            stack.add(new PvButton(PvLang.str("pvpocket.gui.rule.button.cancel"),
                    PvButton.Style.DANGER, this::onClose));
        } else {
            primary = new PvButton(PvLang.str("pvpocket.gui.rule.button.accept"),
                    PvButton.Style.PRIMARY,
                    () -> submit(RulesSubmitPayload.ACTION_ACCEPT));
            // "Accept" means "this revision, as it stands", and the server takes the revision from
            // its own record rather than from the packet — so a player who moved a slider here and
            // then pressed this button had the edit dropped without a word anywhere. That is the
            // only way this panel could lose work silently, so the button refuses while there is
            // anything unsent and the tooltip names the two ways forward.
            if (changeCount() > 0) {
                primary.enabled = false;
                primary.tooltip = PvLang.str("pvpocket.gui.rule.accept_blocked");
            }
            stack.add(primary);
            stack.add(new PvButton(PvLang.str("pvpocket.gui.rule.button.repropose"),
                    PvButton.Style.NORMAL, () -> submit(RulesSubmitPayload.ACTION_PROPOSE)));
            stack.add(new PvButton(PvLang.str("pvpocket.gui.rule.button.reject"),
                    PvButton.Style.DANGER, () -> submit(RulesSubmitPayload.ACTION_REJECT)));
        }
        defaultButton = primary;
        return stack;
    }

    private void buildActions(List<PvButton> stack) {
        // Rebuild-safe: the stack is rebuilt when the edit count crosses zero (see syncActions), and
        // without this the old buttons would stay in the root list and both sets would be drawn.
        roots.removeAll(actionButtons);
        actionButtons.clear();

        compact = compactStack(stack.size());
        if (compact) {
            // One row across the bottom of the well, above the status line. The column cannot hold
            // the strip and the stack at this panel height, and the well can spare 22px because it
            // scrolls — the sidebar cannot spare anything, because a tab strip that loses entries
            // loses them for good.
            int gaps = ACTION_GAP * (stack.size() - 1);
            int span = panelW - chromeW - 24;
            int wanted = gaps;
            int[] want = new int[stack.size()];
            for (int i = 0; i < stack.size(); i++) {
                // Each button takes the width of its own label when the row can afford it, so 拒绝
                // does not get the same 64px as 同意并开始; when it cannot, they share the span and
                // the label is fitted rather than printed over its neighbour.
                want[i] = font().width(stack.get(i).label()) + 16;
                wanted += want[i];
            }
            int x = panelX + chromeW + 12;
            int y = panelY + panelH - STATUS_H - ACTION_GAP - ACTION_H;
            for (int i = 0; i < stack.size(); i++) {
                PvButton button = stack.get(i);
                button.size(wanted <= span ? want[i] : (span - gaps) / stack.size(), ACTION_H);
                button.x = x;
                button.y = y;
                x += button.width + ACTION_GAP;
                roots.add(button);
                actionButtons.add(button);
            }
            actionTop = panelY + panelH - SIDE_PAD;
        } else {
            int y = panelY + panelH - SIDE_PAD;
            for (PvButton button : stack) {
                button.size(chromeW - SIDE_PAD * 2, ACTION_H);
                button.x = panelX + SIDE_PAD;
                y -= button.height;
                button.y = y;
                y -= ACTION_GAP;
                roots.add(button);
                actionButtons.add(button);
            }
            actionTop = y;
        }

        footerHasRevert = payload.role() != RulesOpenPayload.ROLE_DRAFT && changeCount() > 0;
        if (revertButton != null) {
            roots.remove(revertButton);
            revertButton = null;
        }
        if (footerHasRevert) {
            // Not in a draft: there 还原 would also clear the sides the player has just placed (a
            // draft's roster counts as "received"), and a button that wipes five clicks of setup must
            // not sit next to 发送邀请.
            revertButton = new PvButton(PvLang.str("pvpocket.gui.rule.button.revert"),
                    PvButton.Style.GHOST, this::revert);
            revertButton.size(font().width(revertButton.label()) + 16, PvButton.CHIP_H);
            revertButton.x = panelX + panelW - 12 - revertButton.width;
            revertButton.y = panelY + panelH - STATUS_H + (STATUS_H - PvButton.CHIP_H) / 2;
            roots.add(revertButton);
        }
        layoutTabs();
    }

    /**
     * Whether the sidebar column can hold both the tab strip and the commit stack.
     *
     * <p>Eight entries plus three buttons need 213px of panel height at the shortest legible tab
     * pitch. At 320x180 the panel is 160, so the tabs and the buttons cannot share the column at
     * any size a label can still be read at — one of them has to leave, and the strip cannot: a tab
     * that is not drawn is a page that cannot be reached.</p>
     */
    private boolean compactStack(int stackSize) {
        int needed = HEADER_H + SIDE_PAD + PvTabs.minHeight(groups.size() + 1) + TAB_GAP
                + stackSize * (ACTION_H + ACTION_GAP);
        return panelH < needed;
    }

    /**
     * Fits the tab strip into whatever the commit stack left.
     *
     * <p>A strip that clips its last entries is worse than a dense one: the hidden groups are not
     * merely off-screen, they are unreachable, because there is nothing to scroll. The strip
     * compresses instead, and only the reviewer role — the one with three commit buttons — gets
     * down to the floor.</p>
     */
    private void layoutTabs() {
        if (tabs == null) {
            return;
        }
        int available = actionTop - TAB_GAP - tabs.y;
        tabs.fitInto(Math.max(48, available));
    }

    /**
     * The title row's single chip.
     *
     * <p>Only 预设 is left up here. It opens a dialog over this panel, which is the class of action
     * the title row holds; 编辑配装 opens a whole other screen and moved onto the 配装 page's toolbar,
     * where the player who wants it is already looking. The row is now title, subtitle, version and
     * one link, which is what it can carry at a legible size.</p>
     */
    private void buildChips() {
        if (presetButton != null) {
            roots.remove(presetButton);
        }
        int right = panelX + panelW - 12;
        int chipY = panelY + (HEADER_H - PvButton.CHIP_H) / 2;

        presetButton = PvButton.chip(PvLang.str("pvpocket.gui.preset.button"), this::openPresets);
        presetButton.x = right - presetButton.width;
        presetButton.y = chipY;
        roots.add(presetButton);
    }

    /** Asks the server to open the loadout editor; the server answers with a {@code loadout_open}. */
    private void openLoadout() {
        if (PvClientPlatform.get().canSend(LoadoutRequestPayload.ID)) {
            PvClientPlatform.get().send(new LoadoutRequestPayload());
        }
    }

    /**
     * Adds or removes 还原 as the edit count crosses zero.
     *
     * <p>The stack used to be built only from {@code init()}, so 还原 appeared after a window resize
     * and not when the player actually changed a rule — the one moment it means anything. Rebuilding
     * on the transition instead of every frame keeps the buttons from being torn down and recreated
     * in the middle of a slider drag.</p>
     */
    private void syncActions() {
        boolean want = payload.role() != RulesOpenPayload.ROLE_DRAFT && changeCount() > 0;
        if (want != footerHasRevert) {
            buildActions();
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        // Before anything is drawn, so a commit button that just appeared is laid out this frame.
        syncActions();
        Font tr = font();
        boolean reviewer = payload.role() == RulesOpenPayload.ROLE_REVIEWER;

        String title = PvLang.str("pvpocket.gui.rule.title");
        PvDraw.text(ctx, tr, title, panelX + 14, panelY + 11, PvTheme.TEXT);

        int titleEnd = panelX + 14 + tr.width(title) + 10;
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
        // Drawn inline (right-anchored against the preset button) instead of as a PvBadge widget, but
        // it takes its height and width from PvBadge so the two pills cannot drift apart.
        String version = "v" + payload.revision();
        int versionW = PvBadge.widthOf(version);
        int versionX = presetButton.x - 6 - versionW;
        int chipY = panelY + (HEADER_H - PvBadge.H) / 2;
        PvDraw.roundRect(ctx, versionX, chipY, versionW, PvBadge.H, 3, PvTheme.ACCENT_SOFT);
        PvDraw.textCenter(ctx, tr, version, versionX + versionW / 2, chipY + 3, PvTheme.ACCENT);

        int subtitleMax = versionX - 8 - titleEnd;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, subtitle, subtitleMax), titleEnd, panelY + 11,
                PvTheme.TEXT_DIM);

        // The toolbar is a band of its own; without a rule under it the page action reads as the
        // first row of the list.
        if (toolbarVisible) {
            PvDraw.hLine(ctx, pane.x, pane.y - 1, pane.width, PvTheme.DIVIDER);
        }
        drawStatus(ctx, tr, reviewer);
    }

    /**
     * The status line: what state the draft is in, the loadout tally, and 还原.
     *
     * <p>It replaces the old footer band, which held the same text plus five buttons on a 382px line
     * and ran out of width in the reviewer role — the one role where "3 rules changed" is the fact
     * that decides what to press next. The buttons moved to the sidebar; what is left is one line
     * that finally has room for both halves.</p>
     *
     * <p>还原 sits here rather than in the stack because it is the inverse of the change count it
     * stands next to: the line reads "3 rules changed … 还原", which is the whole sentence.</p>
     */
    private void drawStatus(GuiGraphics ctx, Font tr, boolean reviewer) {
        int top = panelY + panelH - STATUS_H;
        int left = panelX + chromeW + 12;
        int right = panelX + panelW - 12;
        PvDraw.hLine(ctx, left, top, right - left, PvTheme.DIVIDER);
        int textY = top + 5;
        int changes = changeCount();

        // The draft status talks about the roster, not about the other side: there is no other side
        // yet, and "you are proposing to nobody" would be a sentence the player has to decode.
        //
        // On the roster page it names the blocker rather than restating the button. "Send when the
        // sides are set" was the same sentence as the button label, and a line that ends in an
        // ellipsis is not a hint. Naming what is still missing is shorter and is the one thing the
        // reader cannot see for themselves: the cards show who is placed, not how many sides the
        // rules require.
        String status;
        int color = PvTheme.TEXT_MUTED;
        if (rosterEditable()) {
            int teams = board == null ? 0 : board.playingTeams();
            int people = board == null ? 0 : board.playerCount();
            // The cap is named before the generic "send when ready": it is the one blocker that the
            // cards cannot show, because the grid has room for twenty places and the cap is a
            // separate number. Naming it is what stops the player from counting slots.
            status = atPlayerCap()
                    ? PvLang.str("pvpocket.gui.roster.footer.at_cap", edited.get("b.maxPlayers"))
                    : teams < 2
                    ? PvLang.str("pvpocket.gui.roster.footer.need_sides")
                    : people < 2
                    ? PvLang.str("pvpocket.gui.roster.footer.need_people")
                    : PvLang.str("pvpocket.gui.roster.footer");
            if (atPlayerCap() || (teams >= 2 && people >= 2)) {
                color = PvTheme.ACCENT;
            }
        } else {
            status = reviewer
                    ? PvLang.str("pvpocket.gui.rule.footer.reviewer")
                    : PvLang.str("pvpocket.gui.rule.footer.proposer");
            if (changes > 0) {
                status = PvLang.str("pvpocket.gui.rule.footer.changed", changes);
                color = PvTheme.ACCENT;
            }
        }

        // The loadout tally keeps its place on the right: it is the only place the agreement counter
        // appears, and a negotiation where nobody can see "agreed 1/2" is one nobody can act on. It
        // gives way to the status text rather than the other way round, but only down to the point
        // where the status text is still a sentence.
        int cursor = revertButton == null ? right : revertButton.x - 8;
        String loadout = loadoutSummary();
        if (!loadout.isEmpty()) {
            int tagW = tr.width(loadout);
            if (cursor - tagW - 12 - left >= 64) {
                PvDraw.textRight(ctx, tr, loadout, cursor, textY, PvTheme.TEXT_DIM);
                cursor -= tagW + 12;
            } else {
                String agreed = PvLang.str("pvpocket.gui.rule.loadout_agreed",
                        payload.agreed(), payload.total());
                int agreedW = tr.width(agreed);
                if (cursor - agreedW - 12 - left >= 64) {
                    PvDraw.textRight(ctx, tr, agreed, cursor, textY, PvTheme.TEXT_DIM);
                    cursor -= agreedW + 12;
                }
            }
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, status, Math.max(0, cursor - left)), left, textY, color);
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
        // Team index -> members, in the order the server listed them (which is the roster order).
        // Only the grouping matters here: the loadout is one set for the whole match, so no member
        // of a side is "the one whose kit the side fights with" any more.
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

    /**
     * The preset dialog for the rule set.
     *
     * <p>Only {@code edited} travels: a preset is the settings, and the roster is decided in the
     * panel every time. That is also why loading one does not seed or clear the cards — a player who
     * has drawn their sides and then picks a different rule set has not changed their mind about who
     * is playing.</p>
     */
    private void openPresets() {
        setOverlay(new PresetOverlay(PresetKind.RULES,
                () -> edited.encode(),
                this::applyPreset,
                () -> setOverlay(null)));
    }

    private void applyPreset(String encoded) {
        edited = DuelRules.decode(encoded);
        // Through init() rather than buildRows(): the slot count is a rule (b.teamSize), so a preset
        // can change how many players a card holds, and the footer's 还原 has to appear with it.
        roots.clear();
        init();
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
        onClose();
    }

    private void send(int action) {
        if (!PvClientPlatform.get().canSend(RulesSubmitPayload.ID)) {
            return;
        }
        // The roster rides on every submit and is empty once the invite is out: the server ignores
        // it outside the draft, and sending it again from a reviewer would be a second way to
        // rearrange sides that the reviewer has no business having.
        String roster = rosterEditable() ? board.encode() : "";
        PvClientPlatform.get().send(new RulesSubmitPayload(action, edited.encode(), roster));
    }

    /**
     * Closing is a decision too. A proposer who walks away from an unfinished draft would otherwise
     * leave the other side holding an invite that can never be answered, so the dismissal is sent
     * explicitly and the server voids the draft.
     */
    @Override
    public void onClose() {
        if (!submitted) {
            submitted = true;
            send(RulesSubmitPayload.ACTION_DISMISS);
        }
        Minecraft.getInstance().setScreen(null);
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
        Minecraft.getInstance().setScreen(null);
    }

    // ------------------------------------------------------------------ audit

    /**
     * Every arrangement this screen can be in: eight tabs, two dialogs, and the edited state.
     *
     * <p>The pages are separate variants because each one puts a different number of rows of a
     * different height into the same well, and the edited state is separate again because 还原
     * appearing on the status line is the only thing that changes the status line's width budget.
     * See {@link PvScreen#auditVariantCount()}.</p>
     */
    @Override
    public int auditVariantCount() {
        return 11;
    }

    @Override
    public void auditVariant(int index) {
        if (tabs == null) {
            return;
        }
        if (index < groups.size() + 1) {
            tabs.select(index);
            return;
        }
        if (index == 8) {
            openPresets();
            return;
        }
        if (index == 9) {
            openPicker(0);
            return;
        }
        // 10: one rule changed, so the status line grows 还原 and the accept button locks itself —
        // the only state in which the panel draws its widest status line.
        edited.set("a.maxRounds", Math.max(1, edited.get("a.maxRounds") - 1));
        edited.set("a.winsNeeded", Math.max(1, edited.get("a.winsNeeded") - 1));
        syncActions();
    }
}
