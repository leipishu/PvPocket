package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.compat.PvCompat;
import com.leipishu.pvpocket.client.ClientPrefs;
import com.leipishu.pvpocket.client.gui.PvBadge;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvButtonGroup;
import com.leipishu.pvpocket.client.gui.PvConfirm;
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
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mod's home screen: the panel the player opens on their own.
 *
 * <p>Every other screen is opened for the player by an invite, a pushed match list or a server
 * request; this one starts with a client request.
 *
 * <p>The shell is a chrome sidebar plus a content well, as on the rule panel. An avatar row sits
 * above the tab strip, where five tabs and a 34px identity block fit the sidebar budget.
 *
 * <p>Local state paints immediately; server-owned numbers fill in with the snapshot.
 */
public class HomeScreen extends PvScreen {

    public static final int PAGE_OVERVIEW = 0;
    public static final int PAGE_STATS = 1;
    public static final int PAGE_HISTORY = 2;
    public static final int PAGE_PRESETS = 3;
    /**
     * The operator page: arena layout, copies, rebuild and the stray-player guard.
     *
     * <p>Drawn only when the snapshot carries {@link HomeStatePayload#FLAG_OPERATOR}, which the
     * server sets at operator level 3, the level the commands behind these buttons require.
     *
     * <p>These constants are page ids, not tab indices. This is the only page that comes and goes,
     * so the tab strip is a filtered list of ids; see {@link #visiblePages()}.
     */
    public static final int PAGE_ADMIN = 4;
    public static final int PAGE_SETTINGS = 5;
    private static final int PAGE_COUNT = 6;

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
     * <p>A field rather than a value derived from the scroll offset: the list is paginated so the
     * pane does not scroll, and the page has to survive a resize, which runs {@code init()} again.
     */
    private int historyPage;
    private PvTabs tabs;
    private PvScrollPane pane;
    /**
     * The page ids the current tab strip was built from.
     *
     * <p>Lets a snapshot arriving while the screen is open tell a number change from a panel shape
     * change. The operator page is the only conditional page, and its first snapshot lands after
     * {@code init()} has built a strip without it.
     */
    private List<Integer> shownPages = List.of();
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
        // init() also runs on resize, so the open tab is carried over.
        List<Integer> pages = visiblePages();
        // Carried over as a tab position, not a page id: the operator page can disappear between
        // two inits, and holding the position keeps the player on the same tab when it does.
        int keep = tabs == null ? pages.indexOf(page) : tabs.selected();
        if (keep < 0) {
            keep = 0;
        }
        keep = Math.min(keep, pages.size() - 1);
        page = pages.get(keep);
        roots.clear();
        pageWidgets.clear();
        primary = null;

        layoutPanel(MAX_W, MAX_H);
        chromeH = HEADER_H;
        buildTabs(pages);

        pane = new PvScrollPane();
        pane.gap = 4;
        roots.add(pane);

        PvIconButton close = new PvIconButton(PvIconButton.Glyph.CROSS, CLOSE, this::onClose);
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

    /**
     * Builds the tab strip for {@code pages}, replacing the current one.
     *
     * <p>{@link PvTabs} fixes its labels at construction, so the strip is rebuilt when the page set
     * changes while the screen is open.
     *
     * <p>Safe to call from {@code drawContent}: {@link PvScreen#render} draws the content before it
     * walks {@code roots}, so the rebuilt strip is the one painted this frame.
     */
    private void buildTabs(List<Integer> pages) {
        if (tabs != null) {
            roots.remove(tabs);
        }
        // Recomputed here: the sidebar is sized from the labels, and the operator label can be the
        // widest one in a given language.
        chromeW = sidebarWidth(pages);
        tabs = new PvTabs(labelsFor(pages), pages.indexOf(page));
        tabs.x = panelX;
        tabs.y = panelY + HEADER_H + AVATAR_H + 8;
        tabs.width = chromeW;
        tabs.fitInto(panelH - HEADER_H - AVATAR_H - 8 - 2);
        tabs.onChange(() -> {
            page = visiblePages().get(tabs.selected());
            buildPage();
        });
        // Inserted at index 0 so the root order stays tab strip, content well, close button.
        roots.add(0, tabs);
        shownPages = pages;
    }

    /**
     * Brings the strip and the page in line with a snapshot that arrived after the screen opened.
     *
     * <p>Most snapshots only change numbers, and rebuilding the page is enough. The first one also
     * decides whether the operator page exists, which changes the shape of the panel and requires a
     * new strip.
     */
    private void syncPages() {
        List<Integer> pages = visiblePages();
        if (!pages.equals(shownPages)) {
            if (!pages.contains(page)) {
                page = PAGE_OVERVIEW;
            }
            buildTabs(pages);
        }
        buildPage();
    }

    /**
     * Returns the pages this client may open, in strip order.
     *
     * <p>The operator page is conditional, so the strip and every page lookup go through this list.
     */
    private List<Integer> visiblePages() {
        List<Integer> pages = new ArrayList<>(PAGE_COUNT);
        pages.add(PAGE_OVERVIEW);
        pages.add(PAGE_STATS);
        pages.add(PAGE_HISTORY);
        pages.add(PAGE_PRESETS);
        if (HomeState.has(HomeStatePayload.FLAG_OPERATOR)) {
            pages.add(PAGE_ADMIN);
        }
        pages.add(PAGE_SETTINGS);
        return pages;
    }

    private List<String> labelsFor(List<Integer> pages) {
        List<String> labels = new ArrayList<>(pages.size());
        for (int id : pages) {
            labels.add(PvLang.str(labelKey(id)));
        }
        return labels;
    }

    private static String labelKey(int id) {
        return switch (id) {
            case PAGE_OVERVIEW -> "pvpocket.gui.home.tab.overview";
            case PAGE_STATS -> "pvpocket.gui.home.tab.stats";
            case PAGE_HISTORY -> "pvpocket.gui.home.tab.history";
            case PAGE_PRESETS -> "pvpocket.gui.home.tab.presets";
            case PAGE_ADMIN -> "pvpocket.gui.home.tab.admin";
            default -> "pvpocket.gui.home.tab.settings";
        };
    }

    /**
     * Returns the sidebar width: the widest tab label plus its insets, and the avatar row.
     *
     * <p>The avatar row needs {@code 22 + 6 + name} on one line, and the name may be truncated, so
     * the row sets a floor rather than a hard requirement.
     */
    private int sidebarWidth(List<Integer> pages) {
        Font tr = font();
        int widest = 0;
        for (String label : labelsFor(pages)) {
            widest = Math.max(widest, tr.width(label));
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
     * Returns the height of the history list: the well minus the footer band, when one is drawn.
     *
     * <p>The band is reserved only when the history is non-empty, never based on the current page
     * count, which would make the pane height depend on the page size and the reverse.
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
        // Recorded on every path: a page that returns early would otherwise leave the marker stale
        // and be rebuilt every frame.
        seenVersion = HomeState.version();
        for (PvWidget widget : pageWidgets) {
            roots.remove(widget);
        }
        pageWidgets.clear();
        primary = null;
        pane.clear();

        boolean scrolls = page == PAGE_HISTORY || page == PAGE_PRESETS || page == PAGE_SETTINGS
                || page == PAGE_ADMIN;
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
            case PAGE_ADMIN -> buildAdmin();
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
     * Builds the overview page: greeting, one primary action, and the live matches.
     *
     * <p>The space fits two live matches. A live match is the only thing here that needs no command
     * to reach.
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

        // Each live-match button is created here with the rest of the page. Building it while
        // drawing would add a copy every frame.
        List<MatchListScreen.Entry> matches = HomeState.matches();
        int rows = liveRows();
        for (int i = 0; i < rows && i < matches.size(); i++) {
            add(liveButton(matches.get(i), x, liveTop() + i * (LIVE_ROW_H + 4), w, LIVE_ROW_H));
        }
    }

    /** Height of one live-match row. Shared by the layout and the drawing of the row behind it. */
    private static final int LIVE_ROW_H = 30;

    /**
     * Least width a map name keeps before the row drops what qualifies it.
     *
     * <p>48px is "Colosseum" at the legibility floor. The stage chip, the round number and the
     * strip are all measured against it.
     */
    private static final int MIN_MAP_ROOM = 48;

    /** Where the live-match rows start: lower when the well is tall enough for a heading. */
    private int liveTop() {
        return contentH() < 130 ? contentY() + 70 : contentY() + 94;
    }

    /** Returns how many live rows fit, capped at 2. A third would pass the page bottom. */
    private int liveRows() {
        return Math.max(0, Math.min(2, (contentY() + contentH() - liveTop()) / (LIVE_ROW_H + 4)));
    }

    /**
     * Returns the button for one live-match row.
     *
     * <p>A real widget rather than a hit rectangle, so hover, focus and keyboard activation behave
     * as they do elsewhere.
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
     * Builds the current page of the history list, plus the two pager arrows.
     *
     * <p>The page size is the value from the settings, not whatever fits: a page that showed fewer
     * rows would make the setting look broken. A page taller than the viewport scrolls.
     */
    private void buildHistory() {
        List<HomeState.History> records = HomeState.history();
        int size = ClientPrefs.historyPageSize();
        int pages = pagesOf(records.size(), size);
        // Clamped here: a new match, a smaller page size or a resize can each leave the stored page
        // past the end.
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
        // Two root widgets: they are what the audit measures, what Tab reaches, and what the shared
        // hover animation drives.
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
        // A rebuild rather than a scroll: the new page's rows do not exist until the pane is
        // filled, and a rebuild resets the scroll offset.
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
        Minecraft client = Minecraft.getInstance();
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.about")));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.version"), null, version()));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.license"), null, "LGPL-3.0-only"));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.config"), null, "config/pvpocket"));

        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.appearance")));
        PvToggle light = new PvToggle(PvTheme.current() == PvTheme.Theme.LIGHT);
        light.onChange(value -> ClientPrefs.setTheme(value == 1
                ? PvTheme.Theme.LIGHT : PvTheme.Theme.DARK));
        // The light palette is usable but still changing, so it carries a badge.
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.light"), light, null,
                new PvBadge(PvLang.str("pvpocket.gui.home.beta"))));

        PvToggle motion = new PvToggle(ClientPrefs.motion());
        motion.onChange(value -> ClientPrefs.setMotion(value == 1));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.motion"), motion, null));

        // The scoreboard is drawn by the HUD over the fight, so its scale has no visible effect
        // here. Four steps do not need a screen of their own.
        PvStepper hudScale = new PvStepper(ClientPrefs.HUD_SCALE_MIN, ClientPrefs.HUD_SCALE_MAX, 1,
                step -> ClientPrefs.hudScalePercent(step) + "%", ClientPrefs.hudScale());
        hudScale.onChange(ClientPrefs::setHudScale);
        hudScale.tooltip = PvLang.str("pvpocket.gui.home.settings.hud_scale_hint");
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.hud_scale"), hudScale, null));

        // Its own section: how many records a page holds is about the data, not about colours.
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.match")));
        PvToggle resultCard = new PvToggle(ClientPrefs.resultScreen());
        resultCard.onChange(value -> ClientPrefs.setResultScreen(value == 1));
        resultCard.tooltip = PvLang.str("pvpocket.gui.home.settings.result_hint");
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.result"), resultCard, null));

        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.tab.history")));
        PvStepper perPage = new PvStepper(ClientPrefs.HISTORY_PAGE_MIN,
                ClientPrefs.HISTORY_PAGE_MAX, ClientPrefs.HISTORY_PAGE_STEP,
                ClientPrefs.historyPageSize());
        perPage.onChange(value -> {
            ClientPrefs.setHistoryPageSize(value);
            // Back to the first page: the row boundaries move with the page size, so the stored
            // page would land partway through the list.
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
        for (net.minecraft.client.KeyMapping binding : HomeClient.extraKeys()) {
            pane.add(new KeyBindRow(binding.getName() == null ? "?"
                    : Component.translatable(binding.getName()).getString(), binding));
        }
        pane.add(SettingRow.hint(PvLang.str("pvpocket.gui.home.settings.key_hint")));

        if (client.player != null) {
            pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.settings.presets"), null,
                    Integer.toString(PresetStore.get().count(PresetKind.LOADOUT)
                            + PresetStore.get().count(PresetKind.RULES))));
        }
    }

    private static String version() {
        // The version string comes from the loader, since each one has its own metadata format.
        return PvClientPlatform.get().modVersion();
    }

    /**
     * Builds the operator page: arena layout, copies, rebuild and the stray-player guard.
     *
     * <p>Every row is rebuilt from the snapshot, so nothing here holds its own state: an action
     * lands, the server answers with a fresh snapshot, and the numbers move. The guard switch asks
     * the server for the same reason.
     */
    private void buildAdmin() {
        HomeState.Arena arena = HomeState.arena();
        boolean online = HomeClient.supported();

        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.admin.arena")));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.admin.summary"),
                null, PvLang.str("pvpocket.gui.home.admin.summary_value",
                        arena.stamped(), arena.slots(), arena.leased())));

        // 44 fits both labels: "Remove" is 35px, the widest translated one is 16px, and a button
        // shrinks its label only past 40. Both share the width so they read as one control.
        String copyLabel = PvLang.str("pvpocket.gui.home.admin.copy");
        String removeLabel = PvLang.str("pvpocket.gui.home.admin.remove");

        for (HomeState.MapRow row : arena.maps()) {
            // Remove comes first and copy last: the destructive half of the pair is the one reached
            // past.
            PvButton remove = new PvButton(removeLabel,
                    PvButton.Style.DANGER, () -> removeMap(row.id())).size(44, 18);
            // Both refusals belong to the server: nothing left to take, or every cell leased. The
            // tooltip says so before the click.
            remove.enabled = online && row.copies() > 0 && row.leased() < row.copies();
            if (!online) {
                remove.tooltip = PvLang.str("pvpocket.gui.home.tooltip.offline");
            } else if (row.copies() <= 0) {
                remove.tooltip = PvLang.str("pvpocket.gui.home.admin.remove_none");
            } else if (row.leased() >= row.copies()) {
                remove.tooltip = PvLang.str("pvpocket.gui.home.admin.remove_busy");
            }

            PvButton add = new PvButton(copyLabel,
                    PvButton.Style.NORMAL, () -> copyMap(row.id())).size(44, 18);
            // Two reasons to be unavailable, two messages: a full grid can be acted on, being
            // offline cannot.
            add.enabled = online && !arena.gridFull();
            if (!online) {
                add.tooltip = PvLang.str("pvpocket.gui.home.tooltip.offline");
            } else if (arena.gridFull()) {
                add.tooltip = PvLang.str("pvpocket.gui.home.admin.copy_full");
            }
            pane.add(new SettingRow(row.label(), new PvButtonGroup(18, remove, add),
                    PvLang.str("pvpocket.gui.home.admin.map_value", row.copies(), row.leased())));
        }

        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.admin.maintenance")));
        PvButton reset = new PvButton(PvLang.str("pvpocket.gui.home.admin.reset"),
                PvButton.Style.DANGER, this::askReset).size(56, 18);
        // A rebuild in progress is the one state where the button stays off, since the server
        // refuses a second one.
        reset.enabled = online && !arena.building();
        if (arena.building()) {
            reset.tooltip = PvLang.str("pvpocket.gui.home.admin.building");
        } else if (!online) {
            reset.tooltip = PvLang.str("pvpocket.gui.home.tooltip.offline");
        }
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.admin.reset_label"), reset, null));
        pane.add(SettingRow.hint(PvLang.str("pvpocket.gui.home.admin.reset_hint")));

        PvToggle guard = new PvToggle(arena.guard());
        guard.enabled = online;
        guard.onChange(value -> HomeClient.send(
                com.leipishu.pvpocket.network.HomeActionPayload.guard(value == 1)));
        pane.add(new SettingRow(PvLang.str("pvpocket.gui.home.admin.guard"), guard, null));
        pane.add(SettingRow.hint(PvLang.str("pvpocket.gui.home.admin.guard_hint")));
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        // A snapshot landing after the panel opened must be laid out, not only painted: the live
        // rows exist only once the data does. syncPages also rebuilds the strip when needed.
        if (seenVersion != HomeState.version()) {
            syncPages();
        }
        Font tr = font();
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

    private void drawHeader(GuiGraphics ctx, Font tr) {
        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.home.title"), panelX + 16, panelY + 11,
                PvTheme.TEXT);
        // The clock sits in the header as ambient information; a line of its own would cost a sixth
        // of the 166px content well.
        String when = HomeText.date() + " · " + HomeText.clock();
        int right = panelX + panelW - 8 - CLOSE - 10;
        PvDraw.textRight(ctx, tr, PvDraw.fit(tr, when, right - panelX - 140), right,
                panelY + 11, PvTheme.TEXT_MUTED);
    }

    private void drawIdentity(GuiGraphics ctx, Font tr) {
        int x = panelX + 6;
        int y = panelY + HEADER_H + 6;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;

        if (player != null) {
            PlayerFaceRenderer.draw(ctx, player.getSkin(), x, y, AVATAR);
        } else {
            PvDraw.roundRect(ctx, x, y, AVATAR, AVATAR, 3, PvTheme.CARD_ACTIVE);
            PvDraw.textCenter(ctx, tr, "?", x + AVATAR / 2, y + (AVATAR - 8) / 2 + 1,
                    PvTheme.TEXT_DIM);
        }

        int nameX = x + AVATAR + 6;
        int nameRoom = panelX + chromeW - 6 - nameX;
        String name = player == null ? PvLang.str("pvpocket.gui.home.offline")
                : PvCompat.profileName(player.getGameProfile());
        PvDraw.text(ctx, tr, PvDraw.fit(tr, name, nameRoom), nameX, y + 2, PvTheme.TEXT);

        String state = HomeState.has(HomeStatePayload.FLAG_IN_DUEL)
                ? PvLang.str("pvpocket.gui.home.state.duel")
                : HomeState.has(HomeStatePayload.FLAG_SPECTATING)
                ? PvLang.str("pvpocket.gui.home.state.watching")
                : PvLang.str("pvpocket.gui.home.state.idle");
        PvDraw.text(ctx, tr, PvDraw.fit(tr, state, nameRoom), nameX, y + 12, PvTheme.TEXT_MUTED);
    }

    private void drawOverview(GuiGraphics ctx, Font tr, int mouseX, int mouseY) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        // The greeting carries the player name, so its length is unknown, and the quote is a whole
        // sentence. Both shrink before they are cut.
        PvDraw.textFitLeft(ctx, tr, greeting(), x, y, w, PvTheme.TEXT);
        PvDraw.textFitLeft(ctx, tr, PvLang.str("pvpocket.gui.home.quote." + QUOTE), x, y + 14, w,
                PvTheme.TEXT_DIM);

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
     * Draws one live match row: map, mode, round and score, with a way in on the right.
     *
     * <p>The button itself is a widget created during layout by {@link #liveButton}.
     */
    private void drawMatchRow(GuiGraphics ctx, Font tr, MatchListScreen.Entry entry,
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
        // The stage chip gives way before the map name: it qualifies the map, the name identifies
        // the row. It leaves the name 26px on a 130px card, so below that the chip is dropped.
        String stage = entry.stageLabel();
        int chipW = tr.width(stage) + 10;
        boolean chip = room - 6 - chipW >= MIN_MAP_ROOM;
        // The run reports the width it covered, so the chip is anchored to the drawn map width.
        int mapW = PvDraw.textFitLeft(ctx, tr, entry.mapLabel(), textX, line1,
                chip ? room - 6 - chipW : room, PvTheme.TEXT);
        int chipX = textX + mapW + 6;
        int stageColor = "ROUND_ACTIVE".equals(entry.stage()) ? PvTheme.LIVE : PvTheme.ACCENT;
        if (chip && chipX + chipW < btnX - 8) {
            PvDraw.roundRect(ctx, chipX, line1 - 2, chipW, 12, 3,
                    PvTheme.alpha(stageColor, 0.14F));
            PvDraw.text(ctx, tr, stage, chipX + 5, line1, stageColor);
        }
        PvDraw.textFitLeft(ctx, tr, entry.playersLine(), textX, line2, room, PvTheme.TEXT_DIM);
        PvDraw.textFitRight(ctx, tr, score, btnX - 10, y + (h - 8) / 2 + 1, 64, PvTheme.TEXT);
    }

    private void drawStats(GuiGraphics ctx, Font tr) {
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
            // One column of label and value lines: two columns at this width leave 70px per card,
            // narrower than "1,820" plus its label.
            for (int i = 0; i < cards.length; i++) {
                int cy = y + i * 17;
                int valueW = PvDraw.fittedWidth(tr, cards[i][1], w / 2);
                PvDraw.textFitLeft(ctx, tr, cards[i][0], x, cy, w - valueW - 8,
                        PvTheme.TEXT_MUTED);
                PvDraw.textFitRight(ctx, tr, cards[i][1], x + w, cy, w / 2, PvTheme.TEXT);
            }
        }
        if (!any) {
            PvDraw.text(ctx, tr, PvDraw.fit(tr, PvLang.str("pvpocket.gui.home.stats.empty"), w),
                    x, y + h - 12, PvTheme.TEXT_MUTED);
        }
    }

    private void drawStatCard(GuiGraphics ctx, Font tr, int x, int y, int w, int h,
                              String label, String value, boolean accent) {
        PvDraw.roundRect(ctx, x, y, w, h, PvTheme.RADIUS, PvTheme.CARD);
        PvDraw.textFitLeft(ctx, tr, label, x + 8, y + 7, w - 16, PvTheme.TEXT_MUTED);
        PvDraw.textFitLeft(ctx, tr, value, x + 8, y + 21, w - 16,
                accent ? PvTheme.ACCENT : PvTheme.TEXT);
    }

    /**
     * Draws the last ten results as squares.
     *
     * <p>A line or bar chart needs axes to be readable, and axes need room this 166px panel lacks.
     */
    private void drawWinStrip(GuiGraphics ctx, int x, int y, int w) {
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

    private void drawEmpty(GuiGraphics ctx, Font tr, String text) {
        PvDraw.textCenter(ctx, tr, PvDraw.fit(tr, text, contentW()), contentX() + contentW() / 2,
                contentY() + 16, PvTheme.TEXT_MUTED);
    }

    /**
     * Draws the band under the history list: which records are on screen, and the pager position.
     *
     * <p>Only text is drawn here; the arrows are root widgets created by {@link #buildHistory}.
     *
     * <p>The range is measured against what the arrows and the counter leave. On the smallest
     * canvas the well is 200px wide.
     */
    private void drawHistoryFooter(GuiGraphics ctx, Font tr, int total) {
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
        int counterW = counter == null ? 0 : tr.width(counter) + 10;

        String range = PvLang.str("pvpocket.gui.home.history.range", from + 1, to, total);
        PvDraw.text(ctx, tr, PvDraw.fit(tr, range, w - arrowsW - counterW), x, textY,
                PvTheme.TEXT_MUTED);
        if (counter != null) {
            PvDraw.textRight(ctx, tr, counter, x + w - arrowsW - 8, textY, PvTheme.TEXT_DIM);
        }
    }

    private String greeting() {
        Minecraft client = Minecraft.getInstance();
        String name = client.player == null ? ""
                : PvCompat.profileName(client.player.getGameProfile());
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
        Minecraft.getInstance().setScreen(null);
    }

    private void leaveSpectate() {
        HomeClient.send(SpectateRequestPayload.leave());
        HomeClient.request();
    }

    private void surrender() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.connection.sendCommand("pvpocket surrender");
        }
    }

    private void openDetail(HomeState.History record) {
        setOverlay(new HistoryDetail(record, this::dismissOverlay));
    }

    /** One more plot for a map. The answer is a fresh snapshot, not a local counter bump. */
    private void copyMap(String mapId) {
        HomeClient.send(com.leipishu.pvpocket.network.HomeActionPayload.arenaCopy(mapId));
    }

    /**
     * Removes one plot from a map, wipes it and returns it to the grid.
     *
     * <p>No confirmation: it ends no duels, since the server refuses a leased cell and the
     * button is already disabled, and the copy button beside it undoes it.
     */
    private void removeMap(String mapId) {
        HomeClient.send(com.leipishu.pvpocket.network.HomeActionPayload.arenaRemove(mapId));
    }

    /**
     * Asks the rebuild question before anything is sent.
     *
     * <p>The body is built from the snapshot so the operator sees how much the rebuild ends: "0
     * duels" is a different decision from "3 duels".
     */
    private void askReset() {
        HomeState.Arena arena = HomeState.arena();
        setOverlay(new PvConfirm(
                PvLang.str("pvpocket.gui.home.admin.reset_confirm_title"),
                PvLang.str("pvpocket.gui.home.admin.reset_confirm_body", arena.leased()),
                PvLang.str("pvpocket.gui.home.admin.reset_confirm_note"),
                PvTheme.DANGER,
                PvLang.str("pvpocket.gui.home.admin.reset"),
                PvButton.Style.DANGER,
                PvLang.str("pvpocket.gui.home.admin.cancel"),
                this::doReset, this::dismissOverlay));
    }

    private void doReset() {
        dismissOverlay();
        HomeClient.send(com.leipishu.pvpocket.network.HomeActionPayload.arenaReset());
    }

    /**
     * Closes the preset dialog and rebuilds the list behind it.
     *
     * <p>The dialog can delete or import a preset, and both leave the rows underneath stale.
     * Rebuilding on every close, a plain cancel included, costs one page construction.
     */
    private void closePresetDialog() {
        dismissOverlay();
        buildPage();
    }

//? if >=1.21.11 {
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return keyPressed(event.key(), event.scancode(), event.modifiers());
    }
//?}
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // A key capture gets every key, Escape included, before the screen bindings: Esc backs out
        // "press a key" and must not close the panel.
        if (KeyBindRow.isCapturing()) {
            return KeyBindRow.capture(keyCode, scanCode);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        KeyBindRow.cancelCapture();
        super.onClose();
    }

    // ------------------------------------------------------------------ audit

    /**
     * Returns one layout per tab, plus the two dialogs the screen can open.
     *
     * <p>The history dialog is not a tab, and walking only the tabs would leave the most crowded
     * layout on this screen unmeasured.
     *
     * <p>The confirmation dialog is walked over the operator page, the only page that opens it.
     */
    @Override
    public int auditVariantCount() {
        return visiblePages().size() + 2;
    }

    @Override
    public void auditVariant(int index) {
        if (tabs == null) {
            return;
        }
        List<Integer> pages = visiblePages();
        int tabsCount = pages.size();
        // The two dialog variants are drawn over the page that opens them: the history detail over
        // the history page, the confirmation over the operator page.
        int target = index < tabsCount ? pages.get(index)
                : index == tabsCount ? PAGE_HISTORY : PAGE_ADMIN;
        int tab = pages.indexOf(target);
        if (tab < 0) {
            return;
        }
        tabs.select(tab);
        page = target;
        buildPage();
        if (index == tabsCount) {
            if (!HomeState.history().isEmpty()) {
                openDetail(HomeState.history().get(0));
            }
        } else if (index > tabsCount) {
            askReset();
        }
    }
}
