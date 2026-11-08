package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.ClientPrefs;
import com.leipishu.pvpocket.client.gui.PvBadge;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvIconButton;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvScrollPane;
import com.leipishu.pvpocket.client.gui.PvStepper;
import com.leipishu.pvpocket.client.gui.PvTabs;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvToggle;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.client.gui.match.MatchListScreen;
import com.leipishu.pvpocket.client.preset.PresetStore;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.HomeStatePayload;
import com.leipishu.pvpocket.network.SpectateRequestPayload;
import com.leipishu.pvpocket.preset.Preset;
import com.leipishu.pvpocket.preset.PresetKind;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mod's home screen: the one panel the player opens by themselves.
 *
 * <p>Everything else in this mod is opened <em>for</em> the player — an invite arrives, a match list is
 * pushed, a loadout editor is requested by the server. This screen is the other direction: it is the
 * client asking, which is why it is the first screen here that owns a request payload.</p>
 *
 * <p>The shell is the same chrome-plus-well arrangement as the rule panel — one {@code SIDEBAR}
 * surface across the top and down the left, one {@code PANEL} well laid over it — so the home screen
 * does not read as a different product. What is new is the avatar row above the tabs: five tabs plus a
 * 34px identity block is the one arrangement that fits the sidebar budget at both canvas sizes without
 * falling back to the compact layout.</p>
 *
 * <p>Data arrives asynchronously. The screen paints local state immediately (presets, preferences,
 * the player's own head) and fills the server-owned numbers when the snapshot lands, because a hotkey
 * that shows an empty panel for a round trip reads as a hotkey that did not work.</p>
 */
public class HomeScreen extends PvScreen {

    public static final int PAGE_OVERVIEW = 0;
    public static final int PAGE_STATS = 1;
    public static final int PAGE_HISTORY = 2;
    public static final int PAGE_PRESETS = 3;
    public static final int PAGE_SETTINGS = 4;
    private static final int PAGE_COUNT = 5;

    private static final int MAX_W = 640;
    private static final int MAX_H = 400;
    private static final int HEADER_H = 30;
    /** Avatar plus its breathing room, above the tab strip. */
    private static final int AVATAR_H = 34;
    private static final int AVATAR = 22;
    private static final int SIDEBAR_MIN = 84;
    private static final int SIDEBAR_MAX = 112;
    private static final int CLOSE = 16;
    /** Horizontal inset of the content well's contents. */
    private static final int PAD = 14;
    /** Band at the bottom of the history page holding the range and the pager. */
    private static final int FOOTER_H = 22;
    /** Square size of the two pager arrows. */
    private static final int PAGER_BTN = 16;

    private int page;
    /**
     * Which history page is on screen, zero-based.
     *
     * <p>A field rather than something derived from the scroll offset: the list is paginated
     * precisely so that the pane does <em>not</em> scroll, and the page has to survive a window
     * resize — {@code init()} runs again on resize and rebuilds the page from scratch.</p>
     */
    private int historyPage;
    private PvTabs tabs;
    private PvScrollPane pane;
    /** Widgets belonging to the current page, removed on every rebuild. */
    private final List<PvWidget> pageWidgets = new ArrayList<>();
    private PvButton primary;
    private int seenVersion = -1;
    private boolean requested;

    /** Chosen once per launch: a quote that changes every time the panel opens looks like a bug. */
    private static final int QUOTE = ThreadLocalRandom.current().nextInt(1, 9);

    public HomeScreen() {
        super(PvLang.t("pvpocket.gui.home.title"));
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        // init() also runs on resize, so the open tab has to be carried over: losing the page because
        // the window changed size is the kind of bug that only shows up on a second monitor.
        int keep = tabs == null ? page : tabs.selected();
        page = keep;
        roots.clear();
        pageWidgets.clear();
        primary = null;

        layoutPanel(MAX_W, MAX_H);
        chromeH = HEADER_H;
        chromeW = sidebarWidth();

        tabs = new PvTabs(tabLabels(), keep);
        tabs.x = panelX;
        tabs.y = panelY + HEADER_H + AVATAR_H + 8;
        tabs.width = chromeW;
        tabs.fitInto(panelH - HEADER_H - AVATAR_H - 8 - 2);
        tabs.onChange(() -> {
            page = tabs.selected();
            buildPage();
        });
        roots.add(tabs);

        pane = new PvScrollPane();
        pane.gap = 4;
        roots.add(pane);

        PvIconButton close = new PvIconButton(PvIconButton.Glyph.CROSS, CLOSE, this::close);
        close.x = panelX + panelW - 8 - CLOSE;
        close.y = panelY + (HEADER_H - CLOSE) / 2;
        close.tooltip = PvLang.str("pvpocket.gui.home.close");
        roots.add(close);

        buildPage();
        if (!requested) {
            requested = true;
            HomeClient.request();
        }
    }

    private List<String> tabLabels() {
        List<String> labels = new ArrayList<>(PAGE_COUNT);
        labels.add(PvLang.str("pvpocket.gui.home.tab.overview"));
        labels.add(PvLang.str("pvpocket.gui.home.tab.stats"));
        labels.add(PvLang.str("pvpocket.gui.home.tab.history"));
        labels.add(PvLang.str("pvpocket.gui.home.tab.presets"));
        labels.add(PvLang.str("pvpocket.gui.home.tab.settings"));
        return labels;
    }

    /**
     * Width of the sidebar: the widest tab label plus its insets, and enough for the avatar row.
     *
     * <p>Same derivation as the rule panel — a fixed width is either wasteful in Chinese or truncating
     * in English — with one addition: the avatar row needs {@code 22 + 6 + name} on one line, and the
     * name may be truncated, so the row only sets a floor rather than a hard requirement.</p>
     */
    private int sidebarWidth() {
        TextRenderer tr = font();
        int widest = 0;
        for (String label : tabLabels()) {
            widest = Math.max(widest, tr.getWidth(label));
        }
        // 34 = two 6px insets + 10px text inset + 12px for the dirty-dot column the strip reserves.
        int fromTabs = widest + 34;
        int fromAvatar = AVATAR + 6 + 44 + 12;
        return Math.max(SIDEBAR_MIN, Math.min(SIDEBAR_MAX, Math.max(fromTabs, fromAvatar)));
    }

    private int contentX() {
        return panelX + chromeW + PAD;
    }

    private int contentY() {
        return panelY + HEADER_H + 12;
    }

    private int contentW() {
        return panelW - chromeW - PAD * 2;
    }

    private int contentH() {
        return panelH - HEADER_H - 24;
    }

    /**
     * Height of the history list: the well minus the footer band, when there is a footer.
     *
     * <p>Empty history draws a centred message instead of a list, and a message with a pager under
     * it would offer to turn a page of nothing. So the band is reserved only when there is something
     * to paginate — decided from whether the list is empty, never from whether it currently spans
     * more than one page, because that would make the pane height depend on the page size and the
     * page size depend on the pane height.</p>
     */
    private int historyListH() {
        return contentH() - (HomeState.history().isEmpty() ? 0 : FOOTER_H);
    }

    private int footerY() {
        return contentY() + historyListH();
    }

    // ------------------------------------------------------------------ pages

    private void buildPage() {
        if (pane == null) {
            return;
        }
        // Recorded first, and on every path: a page that returns early (the overview and the stats
        // page do not scroll) would otherwise leave the marker stale and be rebuilt every frame.
        seenVersion = HomeState.version();
        for (PvWidget widget : pageWidgets) {
            roots.remove(widget);
        }
        pageWidgets.clear();
        primary = null;
        pane.clear();

        boolean scrolls = page == PAGE_HISTORY || page == PAGE_PRESETS || page == PAGE_SETTINGS;
        pane.visible = scrolls;
        if (!scrolls) {
            if (page == PAGE_OVERVIEW) {
                buildOverview();
            }
            validateFocus();
            return;
        }
        pane.x = contentX();
        pane.y = contentY();
        pane.width = contentW();
        pane.height = page == PAGE_HISTORY ? historyListH() : contentH();
        switch (page) {
            case PAGE_HISTORY -> buildHistory();
            case PAGE_PRESETS -> buildPresets();
            default -> buildSettings();
        }
        pane.layout();
        validateFocus();
    }

    private void add(PvWidget widget) {
        roots.add(widget);
        pageWidgets.add(widget);
    }

    /**
     * Overview: greeting, one primary action, and whatever is being fought right now.
     *
     * <p>No "recent match" card. It was in the first draft and it duplicated the history page one
     * click away; the space it wanted is what lets two live matches fit, and a live match is the only
     * thing here the player cannot get anywhere else without a command.</p>
     */
    private void buildOverview() {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        boolean busy = HomeState.has(HomeStatePayload.FLAG_IN_DUEL);
        boolean watching = HomeState.has(HomeStatePayload.FLAG_SPECTATING);
        boolean draft = HomeState.has(HomeStatePayload.FLAG_DRAFT);
        boolean online = HomeClient.supported();

        String label;
        PvButton.Style style = PvButton.Style.PRIMARY;
        Runnable action;
        if (busy) {
            label = PvLang.str("pvpocket.gui.home.action.surrender");
            style = PvButton.Style.DANGER;
            action = this::surrender;
        } else if (watching) {
            label = PvLang.str("pvpocket.gui.home.action.leave_spectate");
            style = PvButton.Style.NORMAL;
            action = this::leaveSpectate;
        } else if (draft) {
            label = PvLang.str("pvpocket.gui.home.action.resume_draft");
            action = this::startDuel;
        } else {
            label = PvLang.str("pvpocket.gui.home.action.duel");
            action = this::startDuel;
        }

        primary = new PvButton(label, style, action);
        primary.size(Math.min(176, w), 26);
        primary.x = x;
        primary.y = y + 38;
        primary.enabled = online;
        if (!online) {
            primary.tooltip = PvLang.str("pvpocket.gui.home.tooltip.offline");
        } else if (busy) {
            primary.tooltip = PvLang.str("pvpocket.gui.home.tooltip.surrender");
        }
        add(primary);

        // The way into each live match is created here, with the rest of the page. Building it while
        // drawing would add another copy every frame; the audit found three of them stacked.
        List<MatchListScreen.Entry> matches = HomeState.matches();
        int rows = liveRows();
        for (int i = 0; i < rows && i < matches.size(); i++) {
            add(liveButton(matches.get(i), x, liveTop() + i * (LIVE_ROW_H + 4), w, LIVE_ROW_H));
        }
    }

    /** Height of one live-match row. Shared by the layout and the drawing of the row behind it. */
    private static final int LIVE_ROW_H = 30;

    /** Where the live-match rows start: lower when the content well is tall enough for a heading. */
    private int liveTop() {
        return contentH() < 130 ? contentY() + 70 : contentY() + 94;
    }

    /** How many live rows fit. Two is the cap: the third would push the page past its own bottom. */
    private int liveRows() {
        return Math.max(0, Math.min(2, (contentY() + contentH() - liveTop()) / (LIVE_ROW_H + 4)));
    }

    /**
     * The button on one live-match row.
     *
     * <p>A real widget rather than a hit rectangle drawn into the row, so hover, focus and keyboard
     * activation behave as they do everywhere else — the same choice the match list makes.</p>
     */
    private PvButton liveButton(MatchListScreen.Entry entry, int x, int y, int w, int h) {
        int btnW = 52;
        PvButton button;
        if (entry.participant()) {
            button = new PvButton(PvLang.str("pvpocket.gui.home.live.playing"),
                    PvButton.Style.GHOST, null);
            button.enabled = false;
        } else if (entry.watching()) {
            button = new PvButton(PvLang.str("pvpocket.gui.home.live.leave"),
                    PvButton.Style.DANGER, this::leaveSpectate);
        } else {
            button = new PvButton(PvLang.str("pvpocket.gui.home.live.watch"),
                    PvButton.Style.NORMAL, () -> spectate(entry.id()));
            button.enabled = entry.spectatable();
        }
        button.size(btnW, 20);
        button.x = x + w - 8 - btnW;
        button.y = y + (h - 20) / 2;
        return button;
    }

    /**
     * The current page of the history list, plus the two arrows that move between pages.
     *
     * <p>The page size is exactly what the setting says, not "whatever fits": a page that silently
     * showed seven rows when ten were asked for would make the setting look broken, and the pane
     * scrolls a page taller than the viewport the same way any long list does. What the setting
     * controls is how many records one page holds, and that has to stay true or the number in the
     * settings page means nothing.</p>
     */
    private void buildHistory() {
        List<HomeState.History> records = HomeState.history();
        int size = ClientPrefs.historyPageSize();
        int pages = pagesOf(records.size(), size);
        // Clamped here rather than at every caller: a new match, a smaller page size and a window
        // resize can each leave the stored page past the end, and a blank page with live arrows is
        // the worst of the three ways to find out.
        historyPage = Math.max(0, Math.min(historyPage, pages - 1));

        int from = historyPage * size;
        int to = Math.min(records.size(), from + size);
        for (int i = from; i < to; i++) {
            HomeState.History record = records.get(i);
            pane.add(new HistoryRow(record, () -> openDetail(record)));
        }
        if (pages <= 1) {
            return;
        }
        // Two root widgets rather than a hit rectangle drawn into the footer: they are what the
        // audit measures, what Tab reaches, and what the shared hover/press animation drives.
        PvIconButton next = new PvIconButton(PvIconButton.Glyph.CHEVRON, PAGER_BTN,
                () -> turnPage(1));
        next.direction = 1;
        next.x = contentX() + contentW() - PAGER_BTN;
        next.y = footerY() + (FOOTER_H - PAGER_BTN) / 2;
        next.enabled = historyPage < pages - 1;
        next.tooltip = PvLang.str("pvpocket.gui.home.history.next");
        add(next);

        PvIconButton prev = new PvIconButton(PvIconButton.Glyph.CHEVRON, PAGER_BTN,
                () -> turnPage(-1));
        prev.direction = -1;
        prev.x = next.x - 4 - PAGER_BTN;
        prev.y = next.y;
        prev.enabled = historyPage > 0;
        prev.tooltip = PvLang.str("pvpocket.gui.home.history.prev");
        add(prev);
    }

    private static int pagesOf(int total, int size) {
        return Math.max(1, (total + Math.max(1, size) - 1) / Math.max(1, size));
    }

    private void turnPage(int delta) {
        historyPage += delta;
        // A rebuild, not a scroll: the rows of the new page do not exist until the pane is filled,
        // and rebuild() already resets the pane's scroll offset, which is what "turn to page 2"
        // means for a list whose first row has to be at the top.
        buildPage();
    }

    private void buildPresets() {
        addPresetSection(PresetKind.LOADOUT);
        addPresetSection(PresetKind.RULES);
    }

    private void addPresetSection(PresetKind kind) {
        List<Preset> presets = PresetStore.get().of(kind);
        pane.add(new SettingRow(kind.label() + " · " + presets.size()));
        if (presets.isEmpty()) {
            pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.preset.empty")));
        }
        for (Preset preset : presets) {
            pane.add(new PresetRow(preset, new PresetRow.Listener() {
                @Override
                public void onStart(Preset preset) {
                    startFromPreset(preset);
                }

                @Override
                public void onMenu(Preset preset) {
                    setOverlay(new PresetActionOverlay(preset, HomeScreen.this::closePresetDialog));
                }
            }));
        }
    }

    private void buildSettings() {
        MinecraftClient client = MinecraftClient.getInstance();
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.about")));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.version"), null, version()));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.license"), null, "LGPL-3.0-only"));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.config"), null, "config/pvpocket"));

        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.appearance")));
        PvToggle light = new PvToggle(PvTheme.current() == PvTheme.Theme.LIGHT);
        light.onChange(value -> ClientPrefs.setTheme(value == 1
                ? PvTheme.Theme.LIGHT : PvTheme.Theme.DARK));
        // Tagged, not hidden: the light palette is complete enough to use and young enough to change.
        // A badge says that without a second line of prose nobody reads.
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.light"), light, null,
                new PvBadge(PvLang.str("pvpocket.gui.home.beta"))));

        PvToggle motion = new PvToggle(ClientPrefs.motion());
        motion.onChange(value -> ClientPrefs.setMotion(value == 1));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.motion"), motion, null));

        // Its own section rather than a third line under "appearance": how many records a page holds
        // is about the data, and burying it under a heading about colours is how a setting ends up
        // existing without anyone finding it.
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.tab.history")));
        PvStepper perPage = new PvStepper(ClientPrefs.HISTORY_PAGE_MIN,
                ClientPrefs.HISTORY_PAGE_MAX, ClientPrefs.HISTORY_PAGE_STEP,
                ClientPrefs.historyPageSize());
        perPage.onChange(value -> {
            ClientPrefs.setHistoryPageSize(value);
            // Back to the first page: the rows the old page started at no longer line up with the
            // new page boundaries, and landing halfway through a list after changing its page size
            // reads as the list having lost its place.
            historyPage = 0;
        });
        perPage.tooltip = PvLang.str("pvpocket.gui.home.settings.history_page_hint");
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.history_page"), perPage,
                null));

        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.keys")));
        if (HomeClient.homeKey() != null) {
            pane.add(new KeyBindRow(PvLang.str("pvpocket.gui.home.settings.key_home"),
                    HomeClient.homeKey()));
        }
        for (net.minecraft.client.option.KeyBinding binding : HomeClient.extraKeys()) {
            pane.add(new KeyBindRow(binding.getTranslationKey() == null ? "?"
                    : Text.translatable(binding.getTranslationKey()).getString(), binding));
        }
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.key_hint")));

        if (client.player != null) {
            pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.presets"), null,
                    Integer.toString(PresetStore.get().count(PresetKind.LOADOUT)
                            + PresetStore.get().count(PresetKind.RULES))));
        }
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer("pvpocket")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // A snapshot that lands after the panel opened has to be *laid out*, not merely painted: the
        // live rows and their buttons only exist once the data does. Rebuilding here is safe because
        // it runs before the root widgets are rendered this frame — the page is stale for zero
        // frames, and the alternative (painting an empty page until the player changes tabs) reads
        // as a request that never arrived.
        if (seenVersion != HomeState.version()) {
            buildPage();
        }
        TextRenderer tr = font();
        drawHeader(ctx, tr);
        drawIdentity(ctx, tr);

        switch (page) {
            case PAGE_OVERVIEW -> drawOverview(ctx, tr, mouseX, mouseY);
            case PAGE_STATS -> drawStats(ctx, tr);
            case PAGE_HISTORY -> {
                List<HomeState.History> records = HomeState.history();
                if (records.isEmpty()) {
                    drawEmpty(ctx, tr, PvLang.str(HomeState.loaded()
                            ? "pvpocket.gui.home.history.empty"
                            : "pvpocket.gui.home.loading"));
                } else {
                    drawHistoryFooter(ctx, tr, records.size());
                }
            }
            default -> {
            }
        }
    }

    private void drawHeader(DrawContext ctx, TextRenderer tr) {
        PvDraw.title(ctx, tr, PvLang.str("pvpocket.gui.home.title"), panelX + 16, panelY + 11,
                PvTheme.TEXT);
        // The clock lives in the header rather than on the overview page: it is ambient information,
        // and on a 166px-tall content well a line of its own costs a sixth of the page.
        String when = HomeText.date() + " · " + HomeText.clock();
        int right = panelX + panelW - 8 - CLOSE - 10;
        PvDraw.textRight(ctx, tr, PvDraw.fit(tr, when, right - panelX - 140), right,
                panelY + 11, PvTheme.TEXT_MUTED);
    }

    private void drawIdentity(DrawContext ctx, TextRenderer tr) {
        int x = panelX + 6;
        int y = panelY + HEADER_H + 6;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;

        if (player != null) {
            PlayerSkinDrawer.draw(ctx, client.getSkinProvider().getSkinTextures(player.getGameProfile()),
                    x, y, AVATAR);
        } else {
            PvDraw.roundRect(ctx, x, y, AVATAR, AVATAR, 3, PvTheme.CARD_ACTIVE);
            PvDraw.textCenter(ctx, tr, "?", x + AVATAR / 2, y + (AVATAR - 8) / 2 + 1,
                    PvTheme.TEXT_DIM);
        }

        int nameX = x + AVATAR + 6;
        int nameRoom = panelX + chromeW - 6 - nameX;
        String name = player == null ? PvLang.str("pvpocket.gui.home.offline")
                : player.getGameProfile().getName();
        PvDraw.text(ctx, tr, PvDraw.fit(tr, name, nameRoom), nameX, y + 2, PvTheme.TEXT);

        String state = HomeState.has(HomeStatePayload.FLAG_IN_DUEL)
                ? PvLang.str("pvpocket.gui.home.state.duel")
                : HomeState.has(HomeStatePayload.FLAG_SPECTATING)
                ? PvLang.str("pvpocket.gui.home.state.watching")
                : PvLang.str("pvpocket.gui.home.state.idle");
        PvDraw.text(ctx, tr, PvDraw.fit(tr, state, nameRoom), nameX, y + 12, PvTheme.TEXT_MUTED);
    }

    private void drawOverview(DrawContext ctx, TextRenderer tr, int mouseX, int mouseY) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        PvDraw.text(ctx, tr, PvDraw.fit(tr, greeting(), w), x, y, PvTheme.TEXT);
        PvDraw.text(ctx, tr, PvDraw.fit(tr, PvLang.str("pvpocket.gui.home.quote." + QUOTE), w),
                x, y + 14, PvTheme.TEXT_DIM);

        List<MatchListScreen.Entry> matches = HomeState.matches();
        boolean tight = h < 130;
        int top = liveTop();
        if (!tight) {
            PvDraw.hLine(ctx, x, y + 76, w, PvTheme.DIVIDER);
            PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.home.live"), x, y + 84, PvTheme.TEXT_MUTED);
            PvDraw.textRight(ctx, tr, matches.isEmpty()
                            ? PvLang.str("pvpocket.gui.home.live.none")
                            : PvLang.str("pvpocket.gui.home.live.count", matches.size()),
                    x + w, y + 84, PvTheme.TEXT_MUTED);
        }

        int rows = liveRows();
        if (matches.isEmpty()) {
            PvDraw.text(ctx, tr, PvDraw.fit(tr, PvLang.str(HomeState.loaded()
                            ? "pvpocket.gui.home.live.empty" : "pvpocket.gui.home.loading"), w),
                    x, top + 6, PvTheme.TEXT_MUTED);
            return;
        }
        for (int i = 0; i < rows && i < matches.size(); i++) {
            drawMatchRow(ctx, tr, matches.get(i), x, top + i * (LIVE_ROW_H + 4), w, LIVE_ROW_H,
                    mouseX, mouseY);
        }
        if (matches.size() > rows && rows > 0) {
            PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.home.live.more", matches.size() - rows),
                    x, top + rows * (LIVE_ROW_H + 4) + 2, PvTheme.TEXT_MUTED);
        }
    }

    /**
     * One live match: map, mode, round and score, with a way in on the right.
     *
     * <p>Only the row itself is drawn here. Its button is a widget created during layout by
     * {@link #liveButton}, which is what keeps a click from being handled by a widget that was
     * rebuilt sixty times a second.</p>
     */
    private void drawMatchRow(DrawContext ctx, TextRenderer tr, MatchListScreen.Entry entry,
                              int x, int y, int w, int h, int mouseX, int mouseY) {
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        PvDraw.roundPanel(ctx, x, y, w, h, PvTheme.RADIUS,
                hot ? PvTheme.BORDER_STRONG : PvTheme.BORDER,
                hot ? PvTheme.CARD_HOVER : PvTheme.CARD);

        int textX = x + 10;
        int line1 = y + 5;
        int line2 = y + 17;
        int btnW = 52;
        int btnX = x + w - 8 - btnW;
        int room = btnX - 8 - textX;

        String score = entry.score();
        PvDraw.text(ctx, tr, entry.mapLabel(), textX, line1, PvTheme.TEXT);
        int chipX = textX + tr.getWidth(PvDraw.fit(tr, entry.mapLabel(), room / 2)) + 6;
        String stage = entry.stageLabel();
        int chipW = tr.getWidth(stage) + 10;
        int stageColor = "ROUND_ACTIVE".equals(entry.stage()) ? PvTheme.LIVE : PvTheme.ACCENT;
        if (chipX + chipW < btnX - 8) {
            PvDraw.roundRect(ctx, chipX, line1 - 2, chipW, 12, 3,
                    PvTheme.alpha(stageColor, 0.14F));
            PvDraw.text(ctx, tr, stage, chipX + 5, line1, stageColor);
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, entry.playersLine(), room), textX, line2,
                PvTheme.TEXT_DIM);
        PvDraw.textRight(ctx, tr, score, btnX - 10, y + (h - 8) / 2 + 1, PvTheme.TEXT);
    }

    private void drawStats(DrawContext ctx, TextRenderer tr) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();
        HomeState.Stats stats = HomeState.stats();
        boolean any = stats.any();

        String[][] cards = {
                {PvLang.str("pvpocket.gui.home.stats.matches"), Integer.toString(stats.matches())},
                {PvLang.str("pvpocket.gui.home.stats.winrate"),
                        HomeText.percent(stats.winRate(), any)},
                {PvLang.str("pvpocket.gui.home.stats.damage"), HomeText.number(stats.damage())},
                {PvLang.str("pvpocket.gui.home.stats.kd"),
                        HomeText.ratio(stats.kd(), any)},
                {PvLang.str("pvpocket.gui.home.stats.rounds"),
                        stats.roundWins() + " / " + stats.rounds()},
                {PvLang.str("pvpocket.gui.home.stats.streak"), Integer.toString(stats.bestStreak())},
        };

        if (w >= 240) {
            int gap = 8;
            int colW = (w - gap) / 2;
            int cardH = 40;
            for (int i = 0; i < cards.length; i++) {
                int cx = x + (i % 2) * (colW + gap);
                int cy = y + (i / 2) * (cardH + 4);
                drawStatCard(ctx, tr, cx, cy, colW, cardH, cards[i][0], cards[i][1],
                        i == 1 && any);
            }
            drawWinStrip(ctx, x, y + 3 * (cardH + 4) + 6, w);
        } else {
            // One column of label/value lines: two columns at this width leave 70px per card, which
            // is narrower than "1,820" plus its label.
            for (int i = 0; i < cards.length; i++) {
                int cy = y + i * 17;
                PvDraw.text(ctx, tr, cards[i][0], x, cy, PvTheme.TEXT_MUTED);
                PvDraw.textRight(ctx, tr, cards[i][1], x + w, cy, PvTheme.TEXT);
            }
        }
        if (!any) {
            PvDraw.text(ctx, tr, PvDraw.fit(tr, PvLang.str("pvpocket.gui.home.stats.empty"), w),
                    x, y + h - 12, PvTheme.TEXT_MUTED);
        }
    }

    private void drawStatCard(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h,
                              String label, String value, boolean accent) {
        PvDraw.roundRect(ctx, x, y, w, h, PvTheme.RADIUS, PvTheme.CARD);
        PvDraw.text(ctx, tr, PvDraw.fit(tr, label, w - 16), x + 8, y + 7, PvTheme.TEXT_MUTED);
        PvDraw.title(ctx, tr, PvDraw.fit(tr, value, w - 16), x + 8, y + 21,
                accent ? PvTheme.ACCENT : PvTheme.TEXT);
    }

    /**
     * The last ten results as squares.
     *
     * <p>The only chart that survives a 166px content well: a line or bar chart needs axes to be
     * readable, and axes need room this panel does not have. Ten squares say "form" at a glance, which
     * is the question the page is asked.</p>
     */
    private void drawWinStrip(DrawContext ctx, int x, int y, int w) {
        List<HomeState.History> records = HomeState.history();
        if (records.isEmpty()) {
            return;
        }
        int shown = Math.min(10, records.size());
        for (int i = 0; i < shown; i++) {
            boolean won = records.get(i).won();
            PvDraw.roundRect(ctx, x + i * 10, y, 8, 8, 2,
                    won ? PvTheme.ACCENT : PvTheme.CARD_ACTIVE);
        }
        if (records.size() > shown) {
            PvDraw.textRight(ctx, font(), PvLang.str("pvpocket.gui.home.stats.recent", shown),
                    x + w, y, PvTheme.TEXT_MUTED);
        }
    }

    private void drawEmpty(DrawContext ctx, TextRenderer tr, String text) {
        PvDraw.textCenter(ctx, tr, PvDraw.fit(tr, text, contentW()), contentX() + contentW() / 2,
                contentY() + 16, PvTheme.TEXT_MUTED);
    }

    /**
     * The band under the history list: which records are on screen, and where the pager is.
     *
     * <p>Only text is drawn here. The arrows are root widgets created by {@link #buildHistory} —
     * a widget rebuilt every frame cannot hold a hover animation, cannot take a click without
     * racing the next rebuild, and is invisible to the layout audit.</p>
     *
     * <p>The range is measured against what the arrows and the page counter leave, rather than
     * being right-aligned against the panel edge: on the smallest canvas the well is 200px wide, and
     * "1–10 of 27" plus "2 / 3" plus two arrows is already most of it.</p>
     */
    private void drawHistoryFooter(DrawContext ctx, TextRenderer tr, int total) {
        int x = contentX();
        int w = contentW();
        int y = footerY();
        int size = ClientPrefs.historyPageSize();
        int pages = pagesOf(total, size);
        int from = historyPage * size;
        int to = Math.min(total, from + size);

        PvDraw.hLine(ctx, x, y, w, PvTheme.DIVIDER);
        int textY = y + (FOOTER_H - 8) / 2 + 1;

        String counter = pages > 1
                ? PvLang.str("pvpocket.gui.home.history.page", historyPage + 1, pages) : null;
        int arrowsW = pages > 1 ? PAGER_BTN * 2 + 4 : 0;
        int counterW = counter == null ? 0 : tr.getWidth(counter) + 10;

        String range = PvLang.str("pvpocket.gui.home.history.range", from + 1, to, total);
        PvDraw.text(ctx, tr, PvDraw.fit(tr, range, w - arrowsW - counterW), x, textY,
                PvTheme.TEXT_MUTED);
        if (counter != null) {
            PvDraw.textRight(ctx, tr, counter, x + w - arrowsW - 8, textY, PvTheme.TEXT_DIM);
        }
    }

    private String greeting() {
        MinecraftClient client = MinecraftClient.getInstance();
        String name = client.player == null ? "" : client.player.getGameProfile().getName();
        int hour = LocalTime.now().getHour();
        String slot = hour < 6 ? "night" : hour < 12 ? "morning" : hour < 18 ? "afternoon" : "evening";
        return PvLang.str("pvpocket.gui.home.hello." + slot, name);
    }

    // ------------------------------------------------------------------ actions

    private void startDuel() {
        HomeClient.send(new com.leipishu.pvpocket.network.HomeActionPayload(
                com.leipishu.pvpocket.network.HomeActionPayload.ACTION_OPEN_DUEL, ""));
    }

    private void startFromPreset(Preset preset) {
        if (preset.kind() == PresetKind.RULES) {
            HomeClient.send(com.leipishu.pvpocket.network.HomeActionPayload
                    .withRules(preset.encoded()));
        } else {
            HomeClient.send(com.leipishu.pvpocket.network.HomeActionPayload
                    .withLoadout(preset.encoded()));
        }
    }

    private void spectate(String sessionId) {
        HomeClient.send(SpectateRequestPayload.join(sessionId));
        MinecraftClient.getInstance().setScreen(null);
    }

    private void leaveSpectate() {
        HomeClient.send(SpectateRequestPayload.leave());
        HomeClient.request();
    }

    private void surrender() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.networkHandler.sendCommand("pvpocket surrender");
        }
    }

    private void openDetail(HomeState.History record) {
        setOverlay(new HistoryDetail(record, this::dismissOverlay));
    }

    /**
     * Closing the preset dialog also rebuilds the list behind it.
     *
     * <p>The dialog can delete a preset and can import one, and both leave the rows underneath
     * stale — a deleted preset that is still listed is the more embarrassing of the two, because it
     * is still clickable. Rebuilding on every close, including a plain cancel, costs one page
     * construction and removes the whole class of "the dialog changed the store" bugs.</p>
     */
    private void closePresetDialog() {
        dismissOverlay();
        buildPage();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // A key capture gets every key, including Escape, before the screen's own bindings: Esc is
        // what a player presses to back out of "press a key", and it must not close the panel.
        if (KeyBindRow.isCapturing()) {
            return KeyBindRow.capture(keyCode, scanCode);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        KeyBindRow.cancelCapture();
        super.close();
    }

    // ------------------------------------------------------------------ audit

    /**
     * One layout per tab, plus one with the history detail dialog open.
     *
     * <p>The dialog is its own arrangement of its own widgets and is not a tab, so walking only the
     * five tabs would leave the most crowded layout on this screen — a card with two columns of
     * rounds, a verdict chip and two buttons — measured by nobody. It is also where the "第 10 局"
     * label used to run into the result beside it, which is precisely the kind of thing an audit is
     * for.</p>
     */
    @Override
    public int auditVariantCount() {
        return PAGE_COUNT + 1;
    }

    @Override
    public void auditVariant(int index) {
        if (tabs == null) {
            return;
        }
        tabs.select(Math.min(index, PAGE_COUNT - 1));
        page = tabs.selected();
        buildPage();
        if (index >= PAGE_COUNT && !HomeState.history().isEmpty()) {
            openDetail(HomeState.history().get(0));
        }
    }
}
