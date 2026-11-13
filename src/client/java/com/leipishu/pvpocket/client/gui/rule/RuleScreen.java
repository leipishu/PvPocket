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
 * The rule panel: the screen a duel is proposed from.
 *
 * <p>One screen serves the proposer and the reviewer. Both roles see the same layout, labels,
 * ranges and defaults; only the footer actions differ.
 *
 * <p>Nothing is committed until a footer button is pressed. Closing the panel sends an explicit
 * dismiss so the server can drop the draft.
 */
public class RuleScreen extends PvScreen {

    private static final int MAX_W = 580;
    private static final int MAX_H = 348;
    /**
     * Height of the header band. It holds one row: a 16px chip plus 7px of air above and below.
     */
    private static final int HEADER_H = 30;
    /** Height of the status line under the well: one 9px line with air, no buttons. */
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
    /** The roster toolbar row holding "add team", above the cards and outside the scroll pane. */
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
     * The loadout page toolbar button that opens the loadout editor.
     */
    private PvButton loadoutButton;
    /** The commit buttons stacked at the foot of the sidebar, bottom-most first. */
    private final List<PvButton> actionButtons = new ArrayList<>();
    /** The revert button in the status line; present only while something has been edited. */
    private PvButton revertButton;
    /** Y offset of the gap above the topmost commit button; the tab strip ends here. */
    private int actionTop;
    /** Whether the current page has a toolbar band above the scroll pane. */
    private boolean toolbarVisible;
    /** Whether the commit buttons are a row under the well instead of a stack in the sidebar. */
    private boolean compact;
    /** Whether the stack holds the revert button; the stack is rebuilt when this flips. */
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
        // init() also runs on resize. The open tab and the roster survive it, so carry them over
        // rather than rebuilding them.
        int keepTab = tabs == null ? 0 : tabs.selected();
        roots.clear();
        layoutPanel(MAX_W, MAX_H);

        // Built before the column is measured: the sidebar width depends on the commit labels.
        List<PvButton> stack = buildStack();

        chromeH = HEADER_H;
        chromeW = sidebarWidth(stack);
        int paneX = panelX + chromeW + 12;
        int paneW = panelW - chromeW - 24;

        // Tab 0 is the roster, and it is the tab the panel opens on.
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
     * Returns the sidebar column width: the longest label it holds plus its insets, clamped.
     *
     * <p>Both label kinds count, since the tabs and the commit stack share the column.
     */
    private int sidebarWidth(List<PvButton> stack) {
        Font tr = font();
        int widest = tr.width(PvLang.str("pvpocket.gui.roster.tab"));
        for (RuleGroup group : groups) {
            widest = Math.max(widest, tr.width(group.label()));
        }
        // 34 = 2 x SIDE_PAD + 10px text inset + 12px for the dirty dot column.
        int need = widest + 34;
        // A commit button spans the column minus SIDE_PAD on each side and fits its label to that
        // minus 4, so a label needs its own width plus 16.
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
        // The well runs from the header to the status line. A page with a toolbar row above its
        // content takes that row's height back below. Set here because the tabs share one pane.
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
     * Returns whether this rule's control is usable, given what the other rules are set to.
     *
     * <p>{@code a.roundLimit} and {@code a.timeoutVerdict} need a timeout win condition, otherwise
     * the clock never runs. {@code e.sizeX/Y/Z} apply to the flat plot only; every other map
     * carries its own bounding box.
     *
     * <p>A disabled row is greyed, not hidden: its value is still part of the rule set and still
     * appears in the summary. The row's hint explains the dependency.
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
     * Builds the roster page: a fixed toolbar with the add-team button, and the cards scrolling
     * underneath it.
     *
     * <p>The add-team button stays outside the pane so it stays visible while the list is scrolled,
     * and so it stays a Tab stop; the cards' own "+" hotspots are pointer-only.
     */
    private void buildRosterPage() {
        // The slot count is a rule (b.teamSize), so the cards follow the slider on page B.
        board.setSlots(edited.get("b.teamSize"));
        // Seeded once, read off the board itself: an empty board means "never seeded", since the
        // board never drops below two cards.
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
     * Builds the loadout page toolbar: the edit-loadout button, above the rules it edits.
     *
     * <p>The button stays outside the scroll pane so it stays reachable while the list is scrolled.
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
        // Two cards is the floor even with one player in the list: a proposal that arrived with a
        // side nobody is on still has to draw that side.
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
     * Returns whether the roster has reached {@code b.maxPlayers}.
     *
     * <p>The card grid cannot express this on its own: 4 teams x 5 slots is twenty places, and the
     * cap is a separate number. The footer states it.
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
        // Drop anybody already on a card: the server would refuse them.
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
            // One listener only: onChange replaces rather than chains, so writing the value back
            // and keeping the series numbers consistent have to be the same callback.
            control.onChange(v -> {
                edited.set(def.id, v);
                if ("a.maxRounds".equals(def.id)) {
                    clampWinsNeeded();
                }
                // Rows depending on this one are rebuilt, not patched, since their enabled state is
                // fixed at construction. Deferred a tick so the pane is not rebuilt mid-click.
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
     * Returns whether the rule uses a stepper instead of a slider.
     *
     * <p>The series numbers are exact counts in the range 1..15, so a slider would make landing on
     * a value a matter of pixel aim.
     */
    private static boolean isStepped(String ruleId) {
        return "a.maxRounds".equals(ruleId) || "a.winsNeeded".equals(ruleId);
    }

    /**
     * Lowers {@code a.winsNeeded} to {@code a.maxRounds} when the target exceeds the cap.
     *
     * <p>Lowering the cap is a legal step toward a new setting, so the target follows it down. The
     * server clamps the same pair on arrival.
     */
    private void clampWinsNeeded() {
        int maxRounds = edited.get("a.maxRounds");
        if (edited.get("a.winsNeeded") > maxRounds) {
            edited.set("a.winsNeeded", maxRounds);
            Minecraft.getInstance().execute(this::buildRows);
        }
    }

    /**
     * Returns whether another duel is already standing on the map at this index.
     *
     * <p>The bitmask comes from the server on panel open, so a map can be taken a moment later.
     * The submit re-checks and refuses; this only keeps the player from picking a taken map.
     */
    private boolean mapTaken(int index) {
        return index >= 0 && index < Integer.SIZE && (payload.busyMaps() & (1 << index)) != 0;
    }

    /**
     * Applies a map choice and copies its size into the size rules.
     *
     * <p>The server overrides the size rules with the map's bounding box, so the sliders would
     * otherwise show a number that is not used.
     */
    private void pickTemplate(int choice) {
        edited.set("e.template", choice);
        ArenaMap map = ArenaMaps.byIndex(choice);
        if (!map.isFlat()) {
            edited.set("e.sizeX", map.sizeX);
            edited.set("e.sizeZ", map.sizeZ);
            edited.set("e.sizeY", map.sizeY);
        }
        // Rebuilt on both branches: coming back to the flat map has to re-enable the size rows,
        // whose enabled state is fixed at construction.
        Minecraft.getInstance().execute(this::buildRows);
    }

    /**
     * Stacks the commit buttons at the foot of the sidebar, primary lowest.
     *
     * <p>Only actions that commit the negotiation live here. Revert and the loadout editor are not
     * commits, so they sit on the status line and on the loadout page toolbar.
     */
    private void buildActions() {
        buildActions(buildStack());
    }

    /**
     * Builds the commit buttons for this role, unpositioned.
     *
     * <p>The sidebar width is measured from them, so they are built before the column.
     */
    private List<PvButton> buildStack() {
        PvButton primary;
        List<PvButton> stack = new ArrayList<>();
        if (payload.role() == RulesOpenPayload.ROLE_DRAFT) {
            // Draft: nothing has been sent yet, so the actions are send and cancel.
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
            // "Accept" takes the revision from the server's record, so an unsent edit would be
            // dropped. The button refuses while anything is unsent and the tooltip says why.
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
        // Rebuild-safe: the stack is rebuilt when the edit count crosses zero (see syncActions),
        // and without this the old buttons would stay in the root list.
        roots.removeAll(actionButtons);
        actionButtons.clear();

        compact = compactStack(stack.size());
        if (compact) {
            // One row across the bottom of the well, above the status line: the column cannot hold
            // the strip and the stack at this height, and the well scrolls.
            int gaps = ACTION_GAP * (stack.size() - 1);
            int span = panelW - chromeW - 24;
            int wanted = gaps;
            int[] want = new int[stack.size()];
            for (int i = 0; i < stack.size(); i++) {
                // Each button takes its label width when the row can afford it, and the label is
                // fitted to a shared span when it cannot.
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
            // Not in a draft: there revert would also clear the sides the player has placed, since
            // a draft's roster counts as received.
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
     * Returns whether the sidebar column can hold both the tab strip and the commit stack.
     *
     * <p>Eight entries plus three buttons need 213px of panel height at the shortest legible tab
     * pitch. Below that the stack moves under the well, since a tab that is not drawn cannot be
     * reached.
     */
    private boolean compactStack(int stackSize) {
        int needed = HEADER_H + SIDE_PAD + PvTabs.minHeight(groups.size() + 1) + TAB_GAP
                + stackSize * (ACTION_H + ACTION_GAP);
        return panelH < needed;
    }

    /**
     * Fits the tab strip into the height the commit stack left.
     *
     * <p>A strip that clips its last entries makes those groups unreachable, since there is nothing
     * to scroll.
     */
    private void layoutTabs() {
        if (tabs == null) {
            return;
        }
        int available = actionTop - TAB_GAP - tabs.y;
        tabs.fitInto(Math.max(48, available));
    }

    /**
     * Builds the title row's single chip, which opens the preset dialog.
     *
     * <p>The title row holds the title, the subtitle, the version and this one link.
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

    /** Asks the server for the loadout editor; the server answers with a {@code loadout_open}. */
    private void openLoadout() {
        if (PvClientPlatform.get().canSend(LoadoutRequestPayload.ID)) {
            PvClientPlatform.get().send(new LoadoutRequestPayload());
        }
    }

    /**
     * Adds or removes the revert button as the edit count crosses zero.
     *
     * <p>Rebuilding on the transition rather than every frame keeps the buttons from being torn
     * down in the middle of a slider drag.
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
        // A team match names the sides instead of the opponents: the question before agreeing is
        // who is on the reader's side. It gets its own sentence rather than the "Duel with" one.
        String subtitle;
        if (rosterEditable()) {
            // Nothing has been sent yet, so there is no counterpart to name. The subtitle carries
            // the shape of the roster being built instead.
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

        // The revision is a chip rather than loose accent text, so it reads as a separate fact.
        // Drawn inline but sized from PvBadge, so the two pills cannot drift apart.
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
     * Draws the status line: the draft state, the loadout tally, and the revert button.
     *
     * <p>The revert button sits next to the change count it undoes, so the line reads
     * "3 rules changed ... revert".
     */
    private void drawStatus(GuiGraphics ctx, Font tr, boolean reviewer) {
        int top = panelY + panelH - STATUS_H;
        int left = panelX + chromeW + 12;
        int right = panelX + panelW - 12;
        PvDraw.hLine(ctx, left, top, right - left, PvTheme.DIVIDER);
        int textY = top + 5;
        int changes = changeCount();

        // The draft status talks about the roster, not about the other side: there is no other side
        // yet. On the roster page it names the blocker rather than restating the button label.
        String status;
        int color = PvTheme.TEXT_MUTED;
        if (rosterEditable()) {
            int teams = board == null ? 0 : board.playingTeams();
            int people = board == null ? 0 : board.playerCount();
            // The cap is named before the generic hint: it is the one blocker the cards cannot
            // show, because the grid has twenty places and the cap is a separate number.
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

        // The loadout tally keeps its place on the right: it is the only place the agreement
        // counter appears. It yields width to the status text, which stays a sentence.
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
     * to stay within the codec's field budget; the split happens here once.
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
                // A record in a format this panel cannot read is dropped rather than drawn with
                // numbers that came from nothing.
            }
        }
        return out;
    }

    /**
     * Builds the loadout tag from the counts the server sent, for example
     * {@code Dev1 8, Dev2 9, agreed 1/2}.
     *
     * <p>Only the counts appear here; the side each player landed on is drawn by
     * {@link #teamsSummary()}.
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
     * Returns who is on which side, for example {@code 1 Dev1+Dev3, 2 Dev2+Dev4}.
     *
     * <p>Read from the same records the loadout tag uses, so it costs no extra payload field.
     *
     * <p>Empty for a one-on-one, where the opponent's name already says everything.
     */
    private String teamsSummary() {
        if (payload.total() <= 2) {
            return "";
        }
        // Team index -> members, in the order the server listed them, which is the roster order.
        // The loadout is one set for the whole match, so no member owns the side's kit.
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
     * Returns whether any rule on that tab differs from what the server sent; the tab strip's dot.
     *
     * <p>Index 0 is the roster and never gets a dot: every card already shows what was placed.
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
     * Opens the preset dialog for the rule set.
     *
     * <p>Only {@code edited} travels. The roster is decided in the panel, so loading a preset does
     * not seed or clear the cards.
     */
    private void openPresets() {
        setOverlay(new PresetOverlay(PresetKind.RULES,
                () -> edited.encode(),
                this::applyPreset,
                () -> setOverlay(null)));
    }

    private void applyPreset(String encoded) {
        edited = DuelRules.decode(encoded);
        // Through init() rather than buildRows(): the slot count is a rule (b.teamSize), so a
        // preset can change how many players a card holds.
        roots.clear();
        init();
    }

    private void revert() {
        edited = incoming.copy();
        // A draft's roster is part of what was received, so reverting clears the cards too.
        // Otherwise the button would undo the rules and leave the sides as drawn.
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
            // A draft stays open after sending: a refusal has to leave the cards where they are so
            // the player can fix the problem. A successful send replaces this screen a tick later.
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
        // The roster rides on every submit and is empty once the invite is out; the server ignores
        // it outside the draft.
        String roster = rosterEditable() ? board.encode() : "";
        PvClientPlatform.get().send(new RulesSubmitPayload(action, edited.encode(), roster));
    }

    /**
     * Closes the panel and sends a dismiss unless something was already submitted.
     *
     * <p>A proposer who walks away would otherwise leave an invite that can never be answered.
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
     * Closes the screen without reporting back. The negotiation already ended on the server.
     */
    public void closeSilently() {
        submitted = true;
        Minecraft.getInstance().setScreen(null);
    }

    // ------------------------------------------------------------------ audit

    /**
     * Returns the number of arrangements this screen can be in: eight tabs, two dialogs and the
     * edited state.
     *
     * <p>The pages are separate variants because each puts a different number of rows of a
     * different height into the same well. The edited state is separate because the revert button
     * changes the status line's width budget.
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
        // 10: one rule changed, so the status line grows the revert button and the accept button
        // locks itself; the widest status line the panel draws.
        edited.set("a.maxRounds", Math.max(1, edited.get("a.maxRounds") - 1));
        edited.set("a.winsNeeded", Math.max(1, edited.get("a.winsNeeded") - 1));
        syncActions();
    }
}
