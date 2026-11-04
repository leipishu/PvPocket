package com.leipishu.pvpocket.client.gui.loadout;

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
import com.leipishu.pvpocket.client.gui.rule.RuleRow;
import com.leipishu.pvpocket.client.gui.rule.RuleScreen;
import com.leipishu.pvpocket.loadout.ItemKind;
import com.leipishu.pvpocket.loadout.Loadout;
import com.leipishu.pvpocket.loadout.LoadoutCatalog;
import com.leipishu.pvpocket.loadout.LoadoutEntry;
import com.leipishu.pvpocket.network.LoadoutOpenPayload;
import com.leipishu.pvpocket.network.LoadoutSubmitPayload;
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
 * <p>Three columns, each answering one question:</p>
 * <ol>
 *   <li><b>Left</b> — which page: weapons, equipment, consumables, blocks.</li>
 *   <li><b>Middle</b> — what is already in this page, and what can be added. Every line carries the
 *       real item icon, because "金苹果" and "附魔金苹果" are the same four characters to a reader and
 *       two very different items to a player.</li>
 *   <li><b>Right</b> — the selected item's numbers, built out of the same rows the rule panel uses.</li>
 * </ol>
 *
 * <p>The editor never blocks the negotiation: Esc and 返回 go back to the rule panel with the
 * negotiation intact, and only 保存 writes anything. That is deliberate — a player comparing two
 * sword damages should not be able to cancel their own duel by pressing the wrong key.</p>
 */
public class LoadoutScreen extends PvScreen {

    private static final int MAX_W = 640;
    private static final int MAX_H = 360;
    private static final int HEADER_H = 52;
    private static final int FOOTER_H = 36;
    private static final int TABS_W = 88;
    private static final int LIST_W = 196;

    private static final List<ItemKind.Page> PAGES = List.of(
            ItemKind.Page.WEAPON, ItemKind.Page.ARMOR,
            ItemKind.Page.CONSUMABLE, ItemKind.Page.BLOCK);
    private static final List<String> PAGE_LABELS = PAGES.stream().map(p -> p.label).toList();

    private final LoadoutOpenPayload payload;
    private final RuleScreen parent;
    private final Loadout loadout;
    private final Loadout baseline;

    private ItemKind.Page page = ItemKind.Page.WEAPON;
    /** Index into {@link Loadout#entries}, not into the visible page. -1 means nothing selected. */
    private int selected = -1;

    private PvTabs tabs;
    private PvScrollPane listPane;
    private PvScrollPane fieldPane;
    private String note;
    private int footerTextRight;

    public LoadoutScreen(LoadoutOpenPayload payload, RuleScreen parent) {
        super(Text.literal("配装"));
        this.payload = payload;
        this.parent = parent;
        this.loadout = Loadout.decode(payload.loadout());
        this.loadout.autoAssignSlots();
        this.baseline = this.loadout.copy();
        this.note = "在左侧选择分类，中间点「+」添加物品";
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        roots.clear();
        layoutPanel(MAX_W, MAX_H);

        int contentTop = panelY + HEADER_H;
        int contentH = panelH - HEADER_H - FOOTER_H;

        tabs = new PvTabs(PAGE_LABELS, Math.max(0, PAGES.indexOf(page)));
        tabs.x = panelX + 12;
        tabs.y = contentTop;
        tabs.width = TABS_W;
        tabs.fitInto(contentH);
        tabs.onChange(() -> {
            page = PAGES.get(tabs.selected());
            selected = -1;
            rebuildList();
            rebuildFields();
        });
        roots.add(tabs);

        int listX = panelX + 12 + TABS_W + 10;
        listPane = new PvScrollPane();
        listPane.x = listX;
        listPane.y = contentTop;
        listPane.width = LIST_W;
        listPane.height = contentH;
        listPane.gap = 2;
        roots.add(listPane);

        int fieldX = listX + LIST_W + 10;
        fieldPane = new PvScrollPane();
        fieldPane.x = fieldX;
        fieldPane.y = contentTop;
        fieldPane.width = panelX + panelW - 12 - fieldX;
        fieldPane.height = contentH;
        fieldPane.gap = 2;
        roots.add(fieldPane);

        rebuildList();
        rebuildFields();
        buildFooter();
        setFocus(null);
    }

    // ------------------------------------------------------------------ middle column

    private void rebuildList() {
        if (listPane == null) {
            return;
        }
        listPane.clear();
        listPane.add(sectionHeader("已添加"));

        boolean any = false;
        for (int i = 0; i < loadout.entries.size(); i++) {
            LoadoutEntry entry = loadout.entries.get(i);
            if (entry.kind.page != page) {
                continue;
            }
            any = true;
            final int index = i;
            LoadoutRow row = new LoadoutRow(LoadoutRow.Type.ENTRY, entryLabel(entry), entryDetail(entry),
                    iconOf(entry), selected == i,
                    () -> select(index),
                    () -> remove(index));
            row.tooltip("点击编辑属性，右侧 × 移除");
            listPane.add(row);
        }
        if (!any) {
            listPane.add(sectionHeader("（这一页还是空的）"));
        }

        listPane.add(sectionHeader("可添加"));
        for (ItemKind kind : ItemKind.of(page)) {
            listPane.add(new LoadoutRow(LoadoutRow.Type.KIND, kind.label, null, iconOf(kind), false,
                    () -> add(kind), null));
        }
        listPane.layout();
        validateFocus();
    }

    private LoadoutRow sectionHeader(String label) {
        return new LoadoutRow(LoadoutRow.Type.HEADER, label, null, ItemStack.EMPTY, false, null, null);
    }

    private void select(int index) {
        selected = index;
        rebuildList();
        rebuildFields();
    }

    private void add(ItemKind kind) {
        if (loadout.entries.size() >= LoadoutCatalog.MAX_ENTRIES) {
            note = "配装最多 " + LoadoutCatalog.MAX_ENTRIES + " 项，先删掉一些再加。";
            return;
        }
        LoadoutEntry entry = LoadoutEntry.of(kind);
        loadout.entries.add(entry);
        selected = loadout.entries.size() - 1;
        note = "已添加「" + kind.label + "」，右侧调整属性";
        rebuildList();
        rebuildFields();
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
        note = "已移除";
        rebuildList();
        rebuildFields();
    }

    // ------------------------------------------------------------------ right column

    private void rebuildFields() {
        if (fieldPane == null) {
            return;
        }
        fieldPane.clear();
        if (selected < 0 || selected >= loadout.entries.size()) {
            fieldPane.layout();
            validateFocus();
            return;
        }
        LoadoutEntry entry = loadout.entries.get(selected);
        for (LoadoutFields.Field field : LoadoutFields.of(entry)) {
            int value = field.get().getAsInt();
            PvValueControl control = switch (field.def().kind) {
                case TOGGLE -> new PvToggle(value != 0);
                case INT -> new PvSlider(field.def(), value);
                case ENUM -> new PvCycle(field.def(), value);
                case MULTI -> new PvChips(field.def(), value);
                case FIXED -> null;
            };
            if (control != null) {
                control.onChange(v -> {
                    field.set().accept(v);
                    // The middle column shows damage and counts, so it has to follow the edit.
                    rebuildListQuietly();
                });
            }
            fieldPane.add(new RuleRow(field.def(), control, value, true));
        }
        fieldPane.layout();
        validateFocus();
    }

    /**
     * Refreshes the middle column without dropping the selection or the scroll position.
     *
     * <p>Rebuilding the list also rebuilds its rows, which is cheap, but doing it through
     * {@link #select} would reset the right-hand scroll offset on every slider tick — the panel would
     * jump to the top while the player is dragging.</p>
     */
    private void rebuildListQuietly() {
        if (listPane == null) {
            return;
        }
        int scroll = listPane.scroll();
        rebuildList();
        listPane.scrollTo(scroll);
    }

    // ------------------------------------------------------------------ footer

    private void buildFooter() {
        int buttonY = panelY + panelH - FOOTER_H + 8;
        int right = panelX + panelW - 12;

        PvButton save = new PvButton("保存配装", PvButton.Style.PRIMARY, this::submit)
                .size(76, PvTheme.BTN_H);
        right = place(save, right, buttonY);
        right = place(new PvButton("恢复默认", PvButton.Style.NORMAL, this::resetToPreset)
                .size(72, PvTheme.BTN_H), right, buttonY);
        right = place(new PvButton("返回", PvButton.Style.GHOST, this::back)
                .size(48, PvTheme.BTN_H), right, buttonY);
        defaultButton = save;
        footerTextRight = right - 8;
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

        PvDraw.textScaled(ctx, tr, "配装", panelX + 16, panelY + 11, PvTheme.TITLE_SCALE, PvTheme.TEXT);
        int titleEnd = panelX + 16 + PvDraw.scaledWidth(tr, "配装", PvTheme.TITLE_SCALE) + 10;

        String subtitle = "与 " + payload.otherName() + " 的决斗 · 进场时发放";
        int digestW = 76;
        int subtitleMax = panelX + panelW - 12 - digestW - titleEnd;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, subtitle, subtitleMax), titleEnd, panelY + 14, PvTheme.TEXT_DIM);
        PvDraw.textRight(ctx, tr, loadout.digest(), panelX + panelW - 12, panelY + 14, PvTheme.ACCENT);

        PvDraw.hLine(ctx, panelX + 12, panelY + 32, panelW - 24, PvTheme.DIVIDER);
        PvDraw.text(ctx, tr, pageHint(), panelX + 16, panelY + 38, PvTheme.TEXT_MUTED);

        drawRightColumn(ctx, tr);

        PvDraw.hLine(ctx, panelX + 12, panelY + panelH - FOOTER_H, panelW - 24, PvTheme.DIVIDER);
        drawFooterText(ctx, tr);
    }

    /**
     * The right column either shows the selected item's fields or explains why it is empty.
     *
     * <p>An empty pane with no words is indistinguishable from a broken one, so the empty state gets
     * the two sentences that tell the player what to do next.</p>
     */
    private void drawRightColumn(DrawContext ctx, TextRenderer tr) {
        if (selected >= 0 && selected < loadout.entries.size()) {
            return;
        }
        int cx = fieldPane.x + fieldPane.width / 2;
        int cy = fieldPane.y + fieldPane.height / 2 - 18;
        PvDraw.textCenter(ctx, tr, "还没有选中物品", cx, cy, PvTheme.TEXT_DIM);
        PvDraw.textCenter(ctx, tr, "在中间一列点「+」添加，", cx, cy + 14, PvTheme.TEXT_MUTED);
        PvDraw.textCenter(ctx, tr, "点已有物品来调整它的属性", cx, cy + 26, PvTheme.TEXT_MUTED);
    }

    private void drawFooterText(DrawContext ctx, TextRenderer tr) {
        int y = panelY + panelH - FOOTER_H + 14;
        int maxW = footerTextRight - panelX - 16;

        List<String> problems = loadout.validate();
        String text;
        int color;
        if (!problems.isEmpty()) {
            text = problems.get(0);
            color = PvTheme.DANGER;
        } else if (isDirty()) {
            text = note + " · 未保存";
            color = PvTheme.WARN;
        } else {
            text = note;
            color = PvTheme.TEXT_MUTED;
        }
        PvDraw.text(ctx, tr, PvDraw.fit(tr, text, Math.max(0, maxW)), panelX + 16, y, color);
    }

    private String pageHint() {
        return switch (page) {
            case WEAPON -> "武器：剑、斧、弓、弩、三叉戟、重锤、钓鱼竿。材质只影响外观。";
            case ARMOR -> "装备：头盔、胸甲、护腿、靴子、盾牌。护甲值按 1.8 曲线减伤。";
            case CONSUMABLE -> "消耗品：食物与喷溅药水。药水的时长和等级可以自定义。";
            case BLOCK -> "方块：可放置的方块，以及用来处理场地的镐和剪刀。";
        };
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
        StringBuilder sb = new StringBuilder(entry.kind.label);
        if (entry.kind.materialised) {
            sb.append('·').append(entry.material.label);
        }
        if (!entry.variant.isEmpty()) {
            sb.append(' ').append(LoadoutCatalog.labelOf(entry.variant));
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

    private void resetToPreset() {
        Loadout preset = Loadout.defaultDuel();
        preset.autoAssignSlots();
        loadout.entries.clear();
        loadout.entries.addAll(preset.entries);
        loadout.name = preset.name;
        selected = -1;
        note = "已恢复为默认配装";
        rebuildList();
        rebuildFields();
    }

    private void submit() {
        List<String> problems = loadout.validate();
        if (!problems.isEmpty()) {
            note = problems.get(0);
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
}
