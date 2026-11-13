package com.leipishu.pvpocket.client.gui.loadout;

import com.leipishu.pvpocket.compat.PvCompat;
import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvConfirm;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvLayout;
import com.leipishu.pvpocket.client.gui.PvScreen;
import com.leipishu.pvpocket.client.gui.PvScrollPane;
import com.leipishu.pvpocket.client.gui.PvTabs;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.preset.PresetOverlay;
import com.leipishu.pvpocket.client.gui.rule.RuleScreen;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.ItemKind;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutCatalog;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.loadout.Material;
import com.leipishu.pvpocket.loadout.TridentStyle;
import com.leipishu.pvpocket.network.LoadoutOpenPayload;
import com.leipishu.pvpocket.network.LoadoutSubmitPayload;
import com.leipishu.pvpocket.preset.PresetKind;
import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The loadout editor: what each side brings into the round.
 *
 * <p>Two regions: the left column picks the page and the list on the right holds what is already
 * on this page plus what can be added. Every line carries the real item icon, since two names can
 * read alike while the items differ.
 *
 * <p>An item's settings live in a {@link LoadoutEditor} dialog, opened by editing an added entry.
 *
 * <p>Esc and the back button return to the rule panel with the negotiation intact; only save
 * writes anything.
 */
public class LoadoutScreen extends PvScreen {

    private static final int MAX_W = 680;
    private static final int MAX_H = 392;
    /** Height of the header band: one row of text and 16px chips. */
    private static final int HEADER_H = 30;
    private static final int STATUS_H = 18;
    private static final int ACTION_H = 18;
    private static final int SIDE_PAD = 6;
    private static final int SIDEBAR_MIN = 72;
    private static final int SIDEBAR_MAX = 84;

    private static final List<ItemKind.Page> PAGES = List.of(
            ItemKind.Page.WEAPON, ItemKind.Page.ARMOR,
            ItemKind.Page.CONSUMABLE, ItemKind.Page.BLOCK);
    private static final List<String> PAGE_LABELS = PAGES.stream().map(ItemKind.Page::label).toList();

    private final LoadoutOpenPayload payload;
    private final RuleScreen parent;
    private final Loadout loadout;
    private final Loadout baseline;

    private ItemKind.Page page = ItemKind.Page.WEAPON;
    /** Index into {@link Loadout#entries}, not into the visible page. -1 means nothing selected. */
    private int selected = -1;
    /**
     * Per entry: whether this line differs from the loadout the panel was opened with.
     *
     * <p>Rebuilt by {@link #rebuildList} rather than compared at draw time, since three callers ask
     * the same question and each comparison encodes every entry.
     */
    private boolean[] entryDirty = new boolean[0];

    private PvTabs tabs;
    private PvScrollPane listPane;
    private PvButton presetButton;
    private PvButton saveButton;
    private PvButton resetButton;
    private PvButton backButton;
    /** Right edge of the status text: everything to the right of it belongs to a chip. */
    private int statusRight;
    private String note;

    public LoadoutScreen(LoadoutOpenPayload payload, RuleScreen parent) {
        super(PvLang.t("pvpocket.gui.loadout.title"));
        this.payload = payload;
        this.parent = parent;
        this.loadout = Loadout.decode(payload.loadout());
        this.loadout.autoAssignSlots();
        this.baseline = this.loadout.copy();
        this.note = PvLang.str(isPersonal()
                ? "pvpocket.gui.loadout.note.personal"
                : "pvpocket.gui.loadout.note.hint");
    }

    /**
     * Returns whether this is the personal loadout rather than one bound to a session.
     *
     * <p>An empty session id means the personal loadout. The two cases differ only in where save
     * writes.
     */
    private boolean isPersonal() {
        return payload.sessionId() == null || payload.sessionId().isEmpty();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        layoutPanel(MAX_W, MAX_H);

        chromeH = HEADER_H;
        chromeW = sidebarWidth();
        int paneX = panelX + chromeW + 12;

        tabs = new PvTabs(PAGE_LABELS, Math.max(0, PAGES.indexOf(page)));
        tabs.x = panelX;
        tabs.y = panelY + HEADER_H;
        tabs.width = chromeW;
        // The dot answers "which page changed" without walking all four pages.
        tabs.dirtyTest(this::pageDirty);
        tabs.onChange(() -> {
            page = PAGES.get(tabs.selected());
            selected = -1;
            rebuildList();
        });
        roots.add(tabs);

        // One column, taking everything the sidebar does not: about 300px on a 426x240 canvas,
        // enough for a full item name, its summary and both action words.
        listPane = new PvScrollPane();
        listPane.x = paneX;
        listPane.y = panelY + HEADER_H;
        listPane.width = panelX + panelW - 12 - paneX;
        listPane.height = panelH - HEADER_H - STATUS_H;
        listPane.gap = 2;
        roots.add(listPane);

        buildActions();
        rebuildList();
        buildChips();
        setFocus(null);
    }

    /**
     * Returns the page column width: the longest page label plus its insets, clamped.
     *
     * <p>"Consumables" is 64px in the default font, so a fixed share would cut it off mid-word.
     */
    private int sidebarWidth() {
        Font tr = font();
        int widest = 0;
        for (String label : PAGE_LABELS) {
            widest = Math.max(widest, tr.width(label));
        }
        // 34 = 2 x SIDE_PAD + 10px text inset + 12px for the dirty dot column.
        return PvLayout.clamp(widest + 34, SIDEBAR_MIN, SIDEBAR_MAX);
    }

    private void rebuildList() {
        if (listPane == null) {
            return;
        }
        refreshDirty();
        listPane.clear();
        listPane.add(sectionHeader(PvLang.str("pvpocket.gui.loadout.section.added")));

        boolean any = false;
        for (int i = 0; i < loadout.entries.size(); i++) {
            LoadoutEntry entry = loadout.entries.get(i);
            if (entry.kind.page != page) {
                continue;
            }
            any = true;
            final int index = i;
            // Edit and delete are always visible, and the row itself opens the editor: the words
            // are the discoverable path, the row is the shortcut for a player who knows.
            LoadoutRow row = new LoadoutRow(LoadoutRow.Type.ENTRY, entryLabel(entry), entryDetail(entry),
                    iconOf(entry), selected == i,
                    () -> edit(index), () -> edit(index), () -> remove(index));
            row.dirty(index < entryDirty.length && entryDirty[index]);
            row.tooltip(PvLang.str("pvpocket.gui.loadout.tooltip.row"));
            listPane.add(row);
        }
        if (!any) {
            listPane.add(sectionHeader(PvLang.str("pvpocket.gui.loadout.section.empty")));
        }

        listPane.add(sectionHeader(PvLang.str("pvpocket.gui.loadout.section.available")));
        // Only what this build can actually grant. The list is a whitelist over the item registry,
        // so a kind whose item is absent from the running game is simply not listed.
        for (ItemKind kind : ItemKind.availableOf(page)) {
            // Adding opens the dialog immediately, so a new entry never sits in the list with
            // default numbers.
            listPane.add(new LoadoutRow(LoadoutRow.Type.KIND, kind.label(), null, iconOf(kind),
                    false, () -> add(kind), null));
        }
        listPane.layout();
        validateFocus();
    }

    private LoadoutRow sectionHeader(String label) {
        return new LoadoutRow(LoadoutRow.Type.HEADER, label, null, ItemStack.EMPTY, false, null, null);
    }

    // ------------------------------------------------------------------ actions

    private void add(ItemKind kind) {
        if (loadout.entries.size() >= LoadoutCatalog.MAX_ENTRIES) {
            note = PvLang.str("pvpocket.gui.loadout.note.limit", LoadoutCatalog.MAX_ENTRIES);
            return;
        }
        LoadoutEntry entry = LoadoutEntry.of(kind);
        loadout.entries.add(entry);
        int index = loadout.entries.size() - 1;
        // The entry exists before the dialog opens so the dialog edits a real object; cancelling
        // takes it back out, which makes "add then cancel" a no-op.
        openEditor(index, true);
    }

    private void edit(int index) {
        if (index < 0 || index >= loadout.entries.size()) {
            return;
        }
        openEditor(index, false);
    }

    private void openEditor(int index, boolean isNew) {
        LoadoutEntry entry = loadout.entries.get(index);
        LoadoutEditor editor = new LoadoutEditor(entry);
        editor.x = 0;
        editor.y = 0;
        editor.width = width;
        editor.height = height;
        editor.listener(new LoadoutEditor.Listener() {
            @Override
            public void onConfirm(LoadoutEntry draft) {
                // Compared before the copy, so an edit that was reverted reports no change. The
                // row's accent bar and this sentence have to agree.
                boolean changed = !entry.encode().equals(draft.encode());
                entry.copyFrom(draft);
                selected = index;
                note = PvLang.str(isNew
                                ? "pvpocket.gui.loadout.note.added"
                                : changed
                                        ? "pvpocket.gui.loadout.note.edited"
                                        : "pvpocket.gui.loadout.note.unchanged",
                        draft.kind.labelText());
                setOverlay(null);
                rebuildList();
            }

            @Override
            public void onCancel() {
                if (isNew) {
                    // Roll the placeholder row back out: cancel on an add means nothing was added.
                    loadout.entries.remove(index);
                }
                setOverlay(null);
                rebuildList();
            }
        });
        setOverlay(editor);
    }

    private void remove(int index) {
        if (index < 0 || index >= loadout.entries.size()) {
            return;
        }
        loadout.entries.remove(index);
        if (selected == index) {
            selected = -1;
        } else if (selected > index) {
            selected--;
        }
        note = PvLang.str("pvpocket.gui.loadout.note.removed");
        rebuildList();
    }

    // ------------------------------------------------------------------ chrome

    /**
     * Builds the save button, stacked at the foot of the sidebar.
     */
    private void buildActions() {
        if (saveButton != null) {
            roots.remove(saveButton);
        }
        int y = panelY + panelH - SIDE_PAD;
        saveButton = new PvButton(PvLang.str("pvpocket.gui.loadout.button.save"),
                PvButton.Style.PRIMARY, this::submit);
        saveButton.size(chromeW - SIDE_PAD * 2, ACTION_H);
        saveButton.x = panelX + SIDE_PAD;
        saveButton.y = y - saveButton.height;
        roots.add(saveButton);
        defaultButton = saveButton;

        tabs.fitInto(Math.max(48, saveButton.y - 4 - tabs.y));
    }

    /**
     * Builds the title row chip and the status line chips.
     *
     * <p>The preset chip lives in the header with the digest it saves. Reset and back are the two
     * ways out of an edit, so they sit at the end of the status line.
     */
    private void buildChips() {
        if (presetButton != null) {
            roots.remove(presetButton);
        }
        if (resetButton != null) {
            roots.remove(resetButton);
        }
        if (backButton != null) {
            roots.remove(backButton);
        }

        presetButton = PvButton.chip(PvLang.str("pvpocket.gui.preset.button"), this::openPresets);
        presetButton.x = panelX + panelW - 12 - presetButton.width;
        presetButton.y = panelY + (HEADER_H - PvButton.CHIP_H) / 2;
        roots.add(presetButton);

        int chipY = panelY + panelH - STATUS_H + (STATUS_H - PvButton.CHIP_H) / 2;
        int right = panelX + panelW - 12;
        backButton = new PvButton(PvLang.str("pvpocket.gui.loadout.button.back"),
                PvButton.Style.GHOST, this::requestBack);
        backButton.size(font().width(backButton.label()) + 16, PvButton.CHIP_H);
        backButton.x = right - backButton.width;
        backButton.y = chipY;
        roots.add(backButton);

        resetButton = new PvButton(PvLang.str("pvpocket.gui.loadout.button.reset"),
                PvButton.Style.NORMAL, this::resetToPreset);
        resetButton.size(font().width(resetButton.label()) + 16, PvButton.CHIP_H);
        resetButton.x = backButton.x - 6 - resetButton.width;
        resetButton.y = chipY;
        roots.add(resetButton);
        statusRight = resetButton.x - 8;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        Font tr = font();

        String title = PvLang.str("pvpocket.gui.loadout.title");
        PvDraw.text(ctx, tr, title, panelX + 14, panelY + 11, PvTheme.TEXT);
        int titleEnd = panelX + 14 + tr.width(title) + 10;

        String subtitle = isPersonal()
                ? PvLang.str("pvpocket.gui.loadout.subtitle.personal")
                : PvLang.str("pvpocket.gui.loadout.subtitle.duel",
                        payload.otherName().isEmpty()
                                ? PvLang.str("pvpocket.gui.rule.opponent")
                                : payload.otherName());

        String digest = loadout.digest();
        int digestW = tr.width(digest) + 12;
        // The digest is what the other side has to agree to, so it keeps its room; the preset link
        // gives up its space when the title is long.
        int digestX = presetButton.x - 6 - digestW;
        int chipY = panelY + (HEADER_H - 14) / 2;
        PvDraw.roundRect(ctx, digestX, chipY, digestW, 14, 3, PvTheme.ACCENT_SOFT);
        PvDraw.textCenter(ctx, tr, digest, digestX + digestW / 2, chipY + 3, PvTheme.ACCENT);

        int subtitleMax = digestX - 8 - titleEnd;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, subtitle, subtitleMax), titleEnd, panelY + 11,
                PvTheme.TEXT_DIM);

        drawStatus(ctx, tr);
    }

    /**
     * Draws the status line: what the last action did, or the first problem with the loadout.
     */
    private void drawStatus(GuiGraphics ctx, Font tr) {
        int top = panelY + panelH - STATUS_H;
        int left = panelX + chromeW + 12;
        PvDraw.hLine(ctx, left, top, panelX + panelW - 12 - left, PvTheme.DIVIDER);
        int maxW = Math.max(0, statusRight - left);

        List<Component> problems = loadout.validate();
        String text;
        int color;
        if (!problems.isEmpty()) {
            // Only the first problem fits, so the count is part of the message; hiding the rest
            // would make the player fix one row, save, and be told about the next one.
            text = problems.size() == 1
                    ? problems.get(0).getString()
                    : PvLang.str("pvpocket.gui.loadout.footer.more_problems",
                            problems.get(0), problems.size());
            color = PvTheme.DANGER;
        } else if (isDirty()) {
            text = PvLang.str("pvpocket.gui.loadout.footer.unsaved", note);
            color = PvTheme.ACCENT;
        } else {
            text = note;
            color = PvTheme.TEXT_MUTED;
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, text, maxW), left, top + 5, color);
    }

    // ------------------------------------------------------------------ items

    private static Item itemOf(ItemKind kind, LoadoutEntry entry) {
        return switch (kind) {
            case POTION -> Items.SPLASH_POTION;
            case FOOD, BLOCK -> itemById(entry.variant);
            default -> kind.resolve(entry.material);
        };
    }

    /**
     * Resolves an item id, returning null when it is malformed or unknown.
     *
     * <p>{@code Identifier.tryParse} returns null on a malformed id and {@code Registries.ITEM.get}
     * would then throw, so an unresolvable variant renders as no icon rather than crashing.
     */
    private static Item itemById(String id) {
        ResourceLocation identifier = ResourceLocation.tryParse(id);
        return identifier == null ? null : PvCompat.value(BuiltInRegistries.ITEM, identifier);
    }

    private static ItemStack iconOf(LoadoutEntry entry) {
        Item item = itemOf(entry.kind, entry);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * Returns the icon for a kind that is not in the loadout yet, for the "available" list.
     *
     * <p>A materialised kind shows its cheapest existing tier. A non-materialised one has no tiers:
     * its id is the item id, so {@code materials()} is empty and {@code null} is passed to
     * {@link ItemKind#resolve}.
     */
    private static ItemStack iconOf(ItemKind kind) {
        Item item = switch (kind) {
            case POTION -> Items.SPLASH_POTION;
            case FOOD -> Items.GOLDEN_CARROT;
            case BLOCK -> Items.WHITE_WOOL;
            // The cheapest tier that exists for this kind, not a fixed one, so a kind without its
            // iron variant still shows an icon in the "available" list.
            default -> {
                List<Material> materials = kind.materials();
                yield kind.resolve(materials.isEmpty() ? null : materials.get(0));
            }
        };
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String entryLabel(LoadoutEntry entry) {
        StringBuilder sb = new StringBuilder(entry.kind.label());
        if (entry.kind.materialised) {
            sb.append('·').append(entry.material.label());
        }
        if (!entry.variant.isEmpty()) {
            sb.append(' ').append(LoadoutCatalog.labelOf(entry.variant));
        }
        // The style is part of what the row is: two tridents with different styles are different
        // weapons, and the list would otherwise show two identical lines.
        if (entry.kind == ItemKind.TRIDENT && entry.tridentStyle != TridentStyle.NONE) {
            sb.append(' ').append(entry.tridentStyle.label());
        }
        return sb.toString();
    }

    /** Short right-aligned summary: damage for weapons, stack size for everything else. */
    private static String entryDetail(LoadoutEntry entry) {
        if (entry.damageTenths > 0 && entry.kind.page == ItemKind.Page.WEAPON) {
            return String.format("%.1f", entry.damage());
        }
        if (entry.count > 1) {
            return "×" + entry.count;
        }
        return "";
    }

    // ------------------------------------------------------------------ actions

    private boolean isDirty() {
        return !loadout.encode().equals(baseline.encode());
    }

    /**
     * Recomputes which lines differ from the saved loadout.
     *
     * <p>A bag difference, not an index-by-index compare: adding or removing an entry shifts every
     * later index, so a positional compare would mark the whole list edited after one deletion.
     */
    private void refreshDirty() {
        int size = loadout.entries.size();
        entryDirty = new boolean[size];
        java.util.Map<String, Integer> budget = new java.util.HashMap<>();
        for (LoadoutEntry entry : baseline.entries) {
            budget.merge(entry.encode(), 1, Integer::sum);
        }
        for (int i = 0; i < size; i++) {
            String key = loadout.entries.get(i).encode();
            Integer left = budget.get(key);
            if (left != null && left > 0) {
                budget.put(key, left - 1);
            } else {
                entryDirty[i] = true;
            }
        }
    }

    /** Asked once per tab per frame by {@link PvTabs#dirtyTest}. */
    private boolean pageDirty(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= PAGES.size()) {
            return false;
        }
        ItemKind.Page target = PAGES.get(pageIndex);
        for (int i = 0; i < entryDirty.length && i < loadout.entries.size(); i++) {
            if (entryDirty[i] && loadout.entries.get(i).kind.page == target) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens the preset dialog over this screen.
     *
     * <p>The snapshot is the live loadout, unsaved changes included. Loading a preset replaces what
     * is in the editor without submitting it; save still decides whether the duel sees it.
     */
    private void openPresets() {
        setOverlay(new PresetOverlay(PresetKind.LOADOUT,
                () -> loadout.encode(),
                this::applyPreset,
                () -> setOverlay(null)));
    }

    private void applyPreset(String encoded) {
        Loadout decoded = Loadout.decode(encoded);
        decoded.autoAssignSlots();
        loadout.entries.clear();
        loadout.entries.addAll(decoded.entries);
        loadout.name = decoded.name;
        selected = -1;
        note = PvLang.str("pvpocket.gui.loadout.note.preset_loaded",
                PvLang.str(decoded.displayName()));
        rebuildList();
    }

    private void resetToPreset() {
        Loadout preset = Loadout.defaultDuel();
        preset.autoAssignSlots();
        loadout.entries.clear();
        loadout.entries.addAll(preset.entries);
        loadout.name = preset.name;
        selected = -1;
        note = PvLang.str("pvpocket.gui.loadout.note.reset");
        rebuildList();
    }

    private void submit() {
        List<Component> problems = loadout.validate();
        if (!problems.isEmpty()) {
            note = problems.get(0).getString();
            return;
        }
        loadout.autoAssignSlots();
        if (!PvClientPlatform.get().canSend(LoadoutSubmitPayload.ID)) {
            // Stay on the panel: leaving would look like a save, and the next round would hand out
            // the previous loadout.
            note = PvLang.str("pvpocket.gui.loadout.note.no_channel");
            PvPocket.LOGGER.warn("Loadout submit: channel {} is not open on this client",
                    LoadoutSubmitPayload.ID.id());
            return;
        }
        PvClientPlatform.get().send(new LoadoutSubmitPayload(payload.sessionId(), loadout.encode()));
        PvPocket.LOGGER.info("Loadout submit -> {} ({} items, digest {})",
                isPersonal() ? "personal" : payload.sessionId(), loadout.size(), loadout.digest());
        back();
    }

    /**
     * Leaves the panel, asking first when there are unsaved changes.
     */
    private void requestBack() {
        if (!isDirty()) {
            back();
            return;
        }
        setOverlay(discardConfirm());
    }

    /** The unsaved-changes question, drawn over this panel. */
    private PvConfirm discardConfirm() {
        return new PvConfirm(
                PvLang.str("pvpocket.gui.loadout.discard.title"),
                PvLang.str("pvpocket.gui.loadout.discard.body"),
                PvLang.str("pvpocket.gui.loadout.discard.note"),
                PvTheme.TEXT_MUTED,
                PvLang.str("pvpocket.gui.loadout.discard.save"), PvButton.Style.PRIMARY,
                PvLang.str("pvpocket.gui.loadout.discard.discard"),
                () -> {
                    setOverlay(null);
                    submit();
                },
                () -> {
                    setOverlay(null);
                    back();
                });
    }

    /** Returns to the rule panel. It never cancels the negotiation. */
    private void back() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** Routes Esc through {@link #requestBack}, so only a clean panel leaves immediately. */
    @Override
    public void onClose() {
        requestBack();
    }

    public String sessionId() {
        return payload.sessionId();
    }

    // ------------------------------------------------------------------ audit

    /** Four pages, the item dialog, the preset dialog, and the discard question. */
    @Override
    public int auditVariantCount() {
        return 7;
    }

    @Override
    public void auditVariant(int index) {
        if (tabs == null) {
            return;
        }
        if (index < PAGES.size()) {
            tabs.select(index);
            return;
        }
        if (index == PAGES.size()) {
            if (loadout.entries.isEmpty()) {
                List<ItemKind> kinds = ItemKind.availableOf(page);
                if (kinds.isEmpty()) {
                    return;
                }
                loadout.entries.add(LoadoutEntry.of(kinds.get(0)));
                rebuildList();
            }
            openEditor(0, false);
            return;
        }
        if (index == PAGES.size() + 1) {
            setOverlay(discardConfirm());
            return;
        }
        openPresets();
    }
}
