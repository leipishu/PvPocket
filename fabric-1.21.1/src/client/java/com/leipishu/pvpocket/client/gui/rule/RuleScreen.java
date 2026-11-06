package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.arena.ArenaMap;
import com.leipishu.pvpocket.arena.ArenaMaps;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvChips;
import com.leipishu.pvpocket.client.gui.PvCycle;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvScrollPane;
import com.leipishu.pvpocket.client.gui.PvSlider;
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
    private static final int HEADER_H = 52;
    private static final int FOOTER_H = 40;
    private static final int TABS_W = 96;
    /** Below this the group hint is no longer a sentence, so the loadout tag shortens instead. */
    private static final int MIN_HINT_W = 150;

    private final RulesOpenPayload payload;
    private final DuelRules incoming;
    private final List<RuleGroup> groups = List.of(RuleGroup.values());

    private DuelRules edited;
    private PvTabs tabs;
    private PvScrollPane pane;
    private int footerTextRight;
    private boolean submitted;

    public RuleScreen(RulesOpenPayload payload) {
        super(PvLang.t("pvpocket.gui.rule.title"));
        this.payload = payload;
        this.incoming = DuelRules.decode(payload.rules());
        this.edited = incoming.copy();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        layoutPanel(MAX_W, MAX_H);

        int contentTop = panelY + HEADER_H;
        int contentH = panelH - HEADER_H - FOOTER_H;
        int paneX = panelX + 12 + TABS_W + 10;
        int paneW = panelW - (paneX - panelX) - 12;

        tabs = PvTabs.ofRules(groups, 0);
        tabs.x = panelX + 12;
        tabs.y = contentTop;
        tabs.width = TABS_W;
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

        buildRows();
        buildFooter();
        setFocus(null);
    }

    private void buildRows() {
        if (pane == null || tabs == null) {
            return;
        }
        pane.clear();
        for (RuleDefinition def : RuleCatalog.of(tabs.group())) {
            pane.add(new RuleRow(def, createControl(def), incoming.get(def.id), true));
        }
        pane.layout();
        validateFocus();
    }

    private PvValueControl createControl(RuleDefinition def) {
        int value = edited.get(def.id);
        PvValueControl control = switch (def.kind) {
            case TOGGLE -> new PvToggle(value != 0);
            case INT -> new PvSlider(def, value);
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
            control.onChange(v -> edited.set(def.id, v));
            if ("e.template".equals(def.id)) {
                control.onChange(this::pickTemplate);
            }
        }
        return control;
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
        int buttonY = panelY + panelH - FOOTER_H + 8;
        int right = panelX + panelW - 12;

        PvButton primary;
        if (payload.role() == RulesOpenPayload.ROLE_PROPOSER) {
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
        if (changeCount() > 0) {
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
        return button.x - 8;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer tr = font();
        boolean reviewer = payload.role() == RulesOpenPayload.ROLE_REVIEWER;

        String title = PvLang.str("pvpocket.gui.rule.title");
        PvDraw.title(ctx, tr, title, panelX + 16, panelY + 13, PvTheme.TEXT);

        int titleEnd = panelX + 16 + PvDraw.titleWidth(tr, title) + 10;
        String other = payload.otherName().isEmpty()
                ? PvLang.str("pvpocket.gui.rule.opponent")
                : payload.otherName();
        String subtitle = reviewer
                ? PvLang.str("pvpocket.gui.rule.subtitle.reviewer", other)
                : PvLang.str("pvpocket.gui.rule.subtitle.proposer", other);

        // The revision is a chip rather than loose accent text: at 12px of amber, a bare "v0" read as
        // part of the subtitle. A chip gives it an edge and tells the eye it is a separate fact.
        String version = "v" + payload.revision();
        int versionW = tr.getWidth(version) + 12;
        int versionX = panelX + panelW - 12 - versionW;
        PvDraw.roundRect(ctx, versionX, panelY + 9, versionW, 14, 3, PvTheme.ACCENT_SOFT);
        PvDraw.textCenter(ctx, tr, version, versionX + versionW / 2, panelY + 12, PvTheme.ACCENT);

        int subtitleMax = versionX - 8 - titleEnd;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, subtitle, subtitleMax), titleEnd, panelY + 13, PvTheme.TEXT_DIM);

        PvDraw.hLine(ctx, panelX + 12, panelY + 34, panelW - 24, PvTheme.DIVIDER);

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
            PvDraw.textRight(ctx, tr, loadout, hintRight, panelY + 40, PvTheme.TEXT_DIM);
            hintMax = hintRight - tagW - 12 - pane.x;
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, groupHint(tabs.group()), Math.max(0, hintMax)),
                pane.x, panelY + 40, PvTheme.TEXT_MUTED);

        PvDraw.hLine(ctx, panelX + 12, panelY + panelH - FOOTER_H, panelW - 24, PvTheme.DIVIDER);
        drawFooterText(ctx, tr, reviewer);
    }

    private void drawFooterText(DrawContext ctx, TextRenderer tr, boolean reviewer) {
        int y = panelY + panelH - FOOTER_H + 16;
        int maxW = footerTextRight - panelX - 16;
        int changes = changeCount();

        String left = reviewer
                ? PvLang.str("pvpocket.gui.rule.footer.reviewer")
                : PvLang.str("pvpocket.gui.rule.footer.proposer");
        int color = PvTheme.TEXT_MUTED;
        if (changes > 0) {
            left = PvLang.str("pvpocket.gui.rule.footer.changed", changes);
            color = PvTheme.ACCENT;
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, left, Math.max(0, maxW)), panelX + 16, y, color);
    }

    /**
     * The loadout tag, built from the numbers the server sent.
     *
     * <p>{@code Dev1 8 · Dev2 9 · agreed 1/2}, with the participant list encoded as
     * {@code name:count} pairs. Assembled on the client so the separator and the words match the
     * reader's language, and so the panel does not have to parse a sentence it did not write.</p>
     */
    private String loadoutSummary() {
        StringBuilder counts = new StringBuilder();
        for (String pair : payload.loadoutCounts().split(",")) {
            int colon = pair.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (counts.length() > 0) {
                counts.append(" · ");
            }
            counts.append(pair, 0, colon).append(' ').append(pair.substring(colon + 1));
        }
        if (counts.length() == 0) {
            return "";
        }
        return PvLang.str("pvpocket.gui.rule.loadout_summary",
                counts.toString(), payload.agreed(), payload.total());
    }

    /**
     * One short line per group, shown under the title so the tabs do not need to explain themselves.
     *
     * <p>Kept short. The line shares its row with the loadout tag, and a hint that needs truncation
     * has stopped being a hint — the tab label already names the group, so this only has to say what
     * is <em>inside</em> it.</p>
     */
    private static String groupHint(RuleGroup group) {
        return PvLang.str(group.hintKey);
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

    private void revert() {
        edited = incoming.copy();
        int tab = tabs == null ? 0 : tabs.selected();
        roots.clear();
        init();
        if (tab > 0) {
            tabs.select(tab);
        }
    }

    private void submit(int action) {
        if (submitted) {
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
        ClientPlayNetworking.send(new RulesSubmitPayload(action, edited.encode()));
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
