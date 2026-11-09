package com.leipishu.pvpocket.client.gui.loadout;

import com.leipishu.pvpocket.client.gui.PvButton;
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
import com.leipishu.pvpocket.loadout.TridentStyle;
import com.leipishu.pvpocket.network.LoadoutOpenPayload;
import com.leipishu.pvpocket.network.LoadoutSubmitPayload;
import com.leipishu.pvpocket.preset.PresetKind;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The loadout editor: what each side brings into the round.
 *
 * <p>Two regions, not three:</p>
 * <ol>
 *   <li><b>Left</b> — which page: weapons, equipment, consumables, blocks.</li>
 *   <li><b>Right</b> — one list holding what is already on this page and what can be added. Every
 *       line carries the real item icon, because "金苹果" and "附魔金苹果" are the same four characters
 *       to a reader and two very different items to a player.</li>
 * </ol>
 *
 * <p>The item's own settings live in a {@link LoadoutEditor} dialog, opened by 编辑 on an added entry
 * or by picking something from the "can be added" list. They used to be a third column, and at the
 * 426x240 logical canvas that column was 66px wide — narrower than the 104px stepper it had to hold,
 * so the controls drew outside it. A dialog is the same content with room to breathe, and it costs
 * the list the column that was previously empty whenever nothing was selected.</p>
 *
 * <p>The editor never blocks the negotiation: Esc and 返回 go back to the rule panel with the
 * negotiation intact, and only 保存 writes anything. That is deliberate — a player comparing two
 * sword damages should not be able to cancel their own duel by pressing the wrong key.</p>
 */
public class LoadoutScreen extends PvScreen {

    private static final int MAX_W = 680;
    private static final int MAX_H = 392;
    /** One row of text and 16px chips: see {@code RuleScreen} for why the header is not 42px. */
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
     * An empty session id means the personal loadout, not a broken payload.
     *
     * <p>The same screen serves two situations — configuring before challenging anyone, and
     * configuring inside an open negotiation — and the only difference is where 保存 writes. Branching
     * on the id keeps one screen instead of two nearly identical ones.</p>
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
        tabs.onChange(() -> {
            page = PAGES.get(tabs.selected());
            selected = -1;
            rebuildList();
        });
        roots.add(tabs);

        // One column now. It takes everything the sidebar does not, which on a 426x240 canvas is
        // about 300px — enough for a full item name, its summary and both action words.
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
     * Width of the page column: the longest page label plus its insets, clamped.
     *
     * <p>"Consumables" is 64px in the default font, so a fixed 0.19 share of a 406px panel cut it
     * off mid-word. Deriving the column from the label costs the list the few pixels the word needs
     * and nothing more, and the cap keeps the column from growing on a wide window.</p>
     */
    private int sidebarWidth() {
        TextRenderer tr = font();
        int widest = 0;
        for (String label : PAGE_LABELS) {
            widest = Math.max(widest, tr.getWidth(label));
        }
        // 34 = 2 x SIDE_PAD + 10px text inset + 12px for the dirty dot column.
        return PvLayout.clamp(widest + 34, SIDEBAR_MIN, SIDEBAR_MAX);
    }

    private void rebuildList() {
        if (listPane == null) {
            return;
        }
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
            // 编辑 and 删除 are always visible, and the row itself opens the editor as well: the words
            // are the discoverable path, the row is the shortcut for the player who already knows.
            LoadoutRow row = new LoadoutRow(LoadoutRow.Type.ENTRY, entryLabel(entry), entryDetail(entry),
                    iconOf(entry), selected == i,
                    () -> edit(index), () -> edit(index), () -> remove(index));
            row.tooltip(PvLang.str("pvpocket.gui.loadout.tooltip.row"));
            listPane.add(row);
        }
        if (!any) {
            listPane.add(sectionHeader(PvLang.str("pvpocket.gui.loadout.section.empty")));
        }

        listPane.add(sectionHeader(PvLang.str("pvpocket.gui.loadout.section.available")));
        for (ItemKind kind : ItemKind.of(page)) {
            // Adding opens the dialog immediately: a new entry that appears in the list with default
            // numbers and no dialog is a row the player has to be told to go and click again.
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
        // takes it back out, which is what makes "add then cancel" a no-op.
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
                entry.copyFrom(draft);
                selected = index;
                note = PvLang.str(isNew
                        ? "pvpocket.gui.loadout.note.added"
                        : "pvpocket.gui.loadout.note.edited", draft.kind.labelText());
                setOverlay(null);
                rebuildList();
            }

            @Override
            public void onCancel() {
                if (isNew) {
                    // Roll the placeholder row back out; "cancel" on an add means nothing was added.
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
     * 保存, stacked at the foot of the sidebar.
     *
     * <p>It was the rightmost button of a footer row. In a column it becomes the bottom-most item,
     * which is where a form's confirm belongs, and the content well gets back the 34px the footer
     * band used to take.</p>
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
     * Title row and status line.
     *
     * <p>预设 opens a dialog and lives in the header with the digest it saves. 重置 and 返回 are the
     * two ways out of an edit — one reverts the list, one leaves the screen — so they sit at the end
     * of the status line, next to the note that says whether there is anything to revert.</p>
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
                PvButton.Style.GHOST, this::back);
        backButton.size(font().getWidth(backButton.label()) + 16, PvButton.CHIP_H);
        backButton.x = right - backButton.width;
        backButton.y = chipY;
        roots.add(backButton);

        resetButton = new PvButton(PvLang.str("pvpocket.gui.loadout.button.reset"),
                PvButton.Style.NORMAL, this::resetToPreset);
        resetButton.size(font().getWidth(resetButton.label()) + 16, PvButton.CHIP_H);
        resetButton.x = backButton.x - 6 - resetButton.width;
        resetButton.y = chipY;
        roots.add(resetButton);
        statusRight = resetButton.x - 8;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer tr = font();

        String title = PvLang.str("pvpocket.gui.loadout.title");
        PvDraw.text(ctx, tr, title, panelX + 14, panelY + 11, PvTheme.TEXT);
        int titleEnd = panelX + 14 + tr.getWidth(title) + 10;

        String subtitle = isPersonal()
                ? PvLang.str("pvpocket.gui.loadout.subtitle.personal")
                : PvLang.str("pvpocket.gui.loadout.subtitle.duel",
                        payload.otherName().isEmpty()
                                ? PvLang.str("pvpocket.gui.rule.opponent")
                                : payload.otherName());

        String digest = loadout.digest();
        int digestW = tr.getWidth(digest) + 12;
        // The digest is what the other side has to agree to, so it stays; the preset link is what
        // gives up its space when the title is long, by being pushed left of the digest.
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
     * The status line: what the last action did, or the first problem with the loadout.
     *
     * <p>It used to share a 34px footer band with three buttons, which left the message about 144px
     * wide — narrower than a validation sentence. With the buttons moved out, the line has the width
     * of the well minus two chips, and a problem can be stated rather than truncated.</p>
     */
    private void drawStatus(DrawContext ctx, TextRenderer tr) {
        int top = panelY + panelH - STATUS_H;
        int left = panelX + chromeW + 12;
        PvDraw.hLine(ctx, left, top, panelX + panelW - 12 - left, PvTheme.DIVIDER);
        int maxW = Math.max(0, statusRight - left);

        List<Text> problems = loadout.validate();
        String text;
        int color;
        if (!problems.isEmpty()) {
            // Only the first problem fits, but silently hiding the rest would make the player fix one
            // row, press save, and be told about the next one — so the count is part of the message.
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

    /** {@code Identifier.tryParse} returns null on a malformed id, and {@code Registries.ITEM.get}
     * would then throw; an unresolvable variant has to render as "no icon", not as a crash. */
    private static Item itemById(String id) {
        Identifier identifier = Identifier.tryParse(id);
        return identifier == null ? null : Registries.ITEM.get(identifier);
    }

    private static ItemStack iconOf(LoadoutEntry entry) {
        Item item = itemOf(entry.kind, entry);
        return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static ItemStack iconOf(ItemKind kind) {
        Item item = switch (kind) {
            case POTION -> Items.SPLASH_POTION;
            case FOOD -> Items.GOLDEN_CARROT;
            case BLOCK -> Items.WHITE_WOOL;
            default -> kind.resolve(com.leipishu.pvpocket.loadout.Material.IRON);
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
        // The style is part of what the row *is*, not a detail: two tridents with different styles
        // are different weapons, and the list would otherwise show two identical lines.
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
     * The preset dialog, opened over this screen.
     *
     * <p>The snapshot is the live loadout, unsaved changes included — a player who has just moved
     * three swords around and then decides to keep the arrangement expects to keep the arrangement,
     * not the last thing they pressed 保存 on. Loading a preset does not submit anything either: it
     * replaces what is in the editor, and 保存 still decides whether the duel sees it.</p>
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
        List<Text> problems = loadout.validate();
        if (!problems.isEmpty()) {
            note = problems.get(0).getString();
            return;
        }
        loadout.autoAssignSlots();
        if (ClientPlayNetworking.canSend(LoadoutSubmitPayload.ID)) {
            ClientPlayNetworking.send(new LoadoutSubmitPayload(payload.sessionId(), loadout.encode()));
        }
        back();
    }

    /** Returns to the rule panel. Never cancels the negotiation — that is the 取消 button's job. */
    private void back() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void close() {
        back();
    }

    public String sessionId() {
        return payload.sessionId();
    }

    // ------------------------------------------------------------------ audit

    /** Four pages, the item dialog, and the preset dialog. See {@link PvScreen#auditVariantCount()}. */
    @Override
    public int auditVariantCount() {
        return 6;
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
                loadout.entries.add(LoadoutEntry.of(ItemKind.of(page).get(0)));
                rebuildList();
            }
            openEditor(0, false);
            return;
        }
        openPresets();
    }
}
