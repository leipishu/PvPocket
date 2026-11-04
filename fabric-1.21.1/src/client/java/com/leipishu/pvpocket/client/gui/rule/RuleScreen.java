package com.leipishu.pvpocket.client.gui.rule;

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

    private static final int MAX_W = 560;
    private static final int MAX_H = 316;
    private static final int HEADER_H = 52;
    private static final int FOOTER_H = 36;
    /** Below this the group hint is no longer a sentence, so the loadout summary shortens instead. */
    private static final int MIN_HINT_W = 190;

    private final RulesOpenPayload payload;
    private final DuelRules incoming;
    private final List<RuleGroup> groups = List.of(RuleGroup.values());

    private DuelRules edited;
    private PvTabs tabs;
    private PvScrollPane pane;
    private int footerTextRight;
    private boolean submitted;

    public RuleScreen(RulesOpenPayload payload) {
        super(Text.literal("决斗规则"));
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
        int tabsW = 88;
        int paneX = panelX + 12 + tabsW + 10;
        int paneW = panelW - (paneX - panelX) - 12;

        tabs = PvTabs.ofRules(groups, 0);
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
            case ENUM -> new PvCycle(def, value);
            case MULTI -> new PvChips(def, value);
            case FIXED -> null;
        };
        if (control != null) {
            control.onChange(v -> edited.set(def.id, v));
        }
        return control;
    }

    private void buildFooter() {
        int buttonY = panelY + panelH - FOOTER_H + 8;
        int right = panelX + panelW - 12;

        PvButton primary;
        if (payload.role() == RulesOpenPayload.ROLE_PROPOSER) {
            primary = new PvButton("发送征询", PvButton.Style.PRIMARY,
                    () -> submit(RulesSubmitPayload.ACTION_PROPOSE)).size(76, PvTheme.BTN_H);
            right = place(primary, right, buttonY);
            right = place(new PvButton("取消", PvButton.Style.DANGER,
                    this::close).size(54, PvTheme.BTN_H), right, buttonY);
        } else {
            primary = new PvButton("同意并开始", PvButton.Style.PRIMARY,
                    () -> submit(RulesSubmitPayload.ACTION_ACCEPT)).size(88, PvTheme.BTN_H);
            right = place(primary, right, buttonY);
            right = place(new PvButton("修改后征询", PvButton.Style.NORMAL,
                    () -> submit(RulesSubmitPayload.ACTION_PROPOSE)).size(84, PvTheme.BTN_H), right, buttonY);
            right = place(new PvButton("拒绝", PvButton.Style.DANGER,
                    () -> submit(RulesSubmitPayload.ACTION_REJECT)).size(54, PvTheme.BTN_H), right, buttonY);
        }
        defaultButton = primary;

        // Revert lives at the far end of the button row so it never sits where the confirm button
        // is expected, but is still available the moment anything has been touched.
        if (changeCount() > 0) {
            PvButton revert = new PvButton("还原", PvButton.Style.GHOST, this::revert).size(48, PvTheme.BTN_H);
            right = place(revert, right, buttonY);
        }
        // The loadout editor sits leftmost, furthest from 发送征询. Configuring items and committing a
        // rule set are two different decisions and should not be neighbours.
        //
        // Labelled 编辑配装 rather than 配装 because the tab strip already has a 配装 tab (rule group
        // D, which is about *rules* for what you bring). Two things called 配装 in one panel, one of
        // which opens the editor and one of which shows a rule page, is a coin flip the player should
        // not have to make.
        right = place(new PvButton("编辑配装", PvButton.Style.NORMAL, this::openLoadout)
                .size(64, PvTheme.BTN_H), right, buttonY);
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
        return button.x - 6;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer tr = font();
        boolean reviewer = payload.role() == RulesOpenPayload.ROLE_REVIEWER;

        PvDraw.textScaled(ctx, tr, "决斗规则", panelX + 16, panelY + 11,
                PvTheme.TITLE_SCALE, PvTheme.TEXT);

        int titleEnd = panelX + 16 + PvDraw.scaledWidth(tr, "决斗规则", PvTheme.TITLE_SCALE) + 10;
        String subtitle = reviewer
                ? "来自 " + payload.otherName() + " 的征询"
                : "与 " + payload.otherName() + " 的决斗";
        int versionW = 74;
        int subtitleMax = panelX + panelW - 12 - versionW - titleEnd;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, subtitle, subtitleMax), titleEnd, panelY + 14, PvTheme.TEXT_DIM);

        PvDraw.textRight(ctx, tr, "v" + payload.revision(), panelX + panelW - 12, panelY + 14,
                PvTheme.ACCENT);
        PvDraw.hLine(ctx, panelX + 12, panelY + 32, panelW - 24, PvTheme.DIVIDER);

        // The loadout summary sits on the hint line rather than the title: the title row is already
        // carrying the opponent's name and the revision, and this is the line the eye passes on the
        // way to the tab strip. Seeing "配装 8 项 · 3f9c21" here is what makes the 编辑配装 button in
        // the footer discoverable instead of merely present.
        //
        // The hint starts at pane.x, not at panelX + 12 — the tab strip sits between them. Budgeting
        // the hint against panelW instead of the pane's own width let the two strings overlap.
        String loadout = payload.loadoutSummary();
        int hintRight = panelX + panelW - 12;
        int hintMax = hintRight - pane.x;
        if (loadout != null && !loadout.isEmpty()) {
            int tagW = tr.getWidth(loadout);
            // The summary wins the space, because it is the only place the agreement counter appears
            // and a negotiation where nobody can see "同意 1/2" is a negotiation nobody can act on.
            // The hint gives way instead — but only down to a point: cut below roughly a dozen
            // characters it stops being a sentence at all ("一场决斗由几局组成、怎么…"), so at that
            // width the summary drops to its agreement half and the hint gets its space back.
            if (hintRight - tagW - 12 - pane.x < MIN_HINT_W) {
                int tail = loadout.lastIndexOf('·');
                loadout = tail < 0 ? loadout : "配装 ·" + loadout.substring(tail + 1);
                tagW = tr.getWidth(loadout);
            }
            PvDraw.textRight(ctx, tr, loadout, hintRight, panelY + 38, PvTheme.TEXT_DIM);
            hintMax = hintRight - tagW - 12 - pane.x;
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, groupHint(tabs.group()), Math.max(0, hintMax)),
                pane.x, panelY + 38, PvTheme.TEXT_MUTED);

        PvDraw.hLine(ctx, panelX + 12, panelY + panelH - FOOTER_H, panelW - 24, PvTheme.DIVIDER);
        drawFooterText(ctx, tr, reviewer);
    }

    private void drawFooterText(DrawContext ctx, TextRenderer tr, boolean reviewer) {
        int y = panelY + panelH - FOOTER_H + 14;
        int maxW = footerTextRight - panelX - 16;
        int changes = changeCount();

        String left = reviewer
                ? "同意即按当前规则与双方配装开始 · 「编辑配装」调整进场物品"
                : "设置完成后发送给对方 · 「编辑配装」调整进场物品";
        int color = PvTheme.TEXT_MUTED;
        if (changes > 0) {
            left = "已修改 " + changes + " 项";
            color = PvTheme.WARN;
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, left, maxW), panelX + 16, y, color);
    }

    /** One line per group, shown under the title so the tabs do not need to explain themselves. */
    private static String groupHint(RuleGroup group) {
        return switch (group) {
            case A -> "一场决斗由几局组成、怎么算赢、时间怎么走";
            case B -> "几个人、分几队、队友之间会不会互相打到";
            case C -> "战斗模式与生存参数，经典模式按 1.8 规则结算";
            case D -> "进场时发什么、能不能捡东西、能不能合成";
            case E -> "场地大小、越界怎么处理、有哪些环境伤害";
            case F -> "能不能放方块、能放什么、能不能炸";
            case G -> "末影珍珠、投降、血量显示与聊天范围";
        };
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
