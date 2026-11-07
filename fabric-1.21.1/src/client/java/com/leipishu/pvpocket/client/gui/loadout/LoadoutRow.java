package com.leipishu.pvpocket.client.gui.loadout;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/**
 * One line of the loadout list.
 *
 * <p>Three kinds of line share one widget because they share one list: a section header, an entry
 * that is already in the loadout (with 编辑 and 删除 at its right end), and a kind that can be added.
 * Splitting them into three widgets would have meant three hit-test paths inside one scroll pane for
 * no gain.</p>
 *
 * <p>The two actions on an added entry are <b>words, not glyphs</b>. A cross is unambiguous; a pencil
 * at 12px is not, and the row already uses a cross for "remove" and a plus for "add" — a third
 * abstract mark would be a guess. Words also survive translation: "编辑" and "Delete" are different
 * lengths, and the row measures them instead of assuming.</p>
 *
 * <p>The icon is the real item rendered by {@code DrawContext.drawItem}, not a glyph or a label. That
 * is the only way a player can tell a golden apple from an enchanted golden apple at a glance.</p>
 */
public class LoadoutRow extends PvWidget {

    public enum Type {
        HEADER,
        ENTRY,
        KIND
    }

    private static final int ICON = 16;
    private static final int ACTION_GAP = 8;

    private final Type type;
    private final String label;
    private final String detail;
    private final ItemStack icon;
    private final boolean selected;
    private final Runnable onClick;
    private final Runnable onEdit;
    private final Runnable onRemove;

    private boolean pressed;

    public LoadoutRow(Type type, String label, String detail, ItemStack icon,
                      boolean selected, Runnable onClick, Runnable onRemove) {
        this(type, label, detail, icon, selected, onClick, null, onRemove);
    }

    public LoadoutRow(Type type, String label, String detail, ItemStack icon, boolean selected,
                      Runnable onClick, Runnable onEdit, Runnable onRemove) {
        this.type = type;
        this.label = label;
        this.detail = detail;
        this.icon = icon;
        this.selected = selected;
        this.onClick = onClick;
        this.onEdit = onEdit;
        this.onRemove = onRemove;
        this.height = type == Type.HEADER ? 18 : PvTheme.ROW_H;
        if (type == Type.HEADER) {
            this.enabled = false;
        }
    }

    @Override
    public boolean focusable() {
        return type != Type.HEADER && super.focusable();
    }

    private static String editLabel() {
        return PvLang.str("pvpocket.gui.loadout.action.edit");
    }

    private static String removeLabel() {
        return PvLang.str("pvpocket.gui.loadout.action.remove");
    }

    /** Left edge of the "remove" word, or -1 when the row has no actions. */
    private int removeX(TextRenderer tr) {
        if (onRemove == null) {
            return -1;
        }
        return x + width - 6 - tr.getWidth(removeLabel());
    }

    /** Left edge of the "edit" word, or -1 when the row cannot be edited. */
    private int editX(TextRenderer tr) {
        if (onEdit == null) {
            return -1;
        }
        int right = onRemove == null ? x + width - 6 : removeX(tr) - ACTION_GAP;
        return right - tr.getWidth(editLabel());
    }

    private boolean overEdit(int mouseX, int mouseY, TextRenderer tr) {
        int ex = editX(tr);
        return ex >= 0 && mouseY >= y && mouseY < y + height
                && mouseX >= ex - 3 && mouseX < ex + tr.getWidth(editLabel()) + 3;
    }

    private boolean overRemove(int mouseX, int mouseY, TextRenderer tr) {
        int rx = removeX(tr);
        return rx >= 0 && mouseY >= y && mouseY < y + height
                && mouseX >= rx - 3 && mouseX < rx + tr.getWidth(removeLabel()) + 3;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);
        pressT = PvAnim.damp(pressT, pressed ? 1F : 0F, d, 0.05F);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        if (type == Type.HEADER) {
            PvDraw.text(ctx, tr, label, x + 2, y + 5, PvTheme.TEXT_MUTED);
            PvDraw.hLine(ctx, x + tr.getWidth(label) + 8, y + 9,
                    Math.max(0, width - tr.getWidth(label) - 10), PvTheme.DIVIDER);
            return;
        }

        if (selected) {
            PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS, PvTheme.BORDER_STRONG,
                    PvTheme.CARD_ACTIVE);
            PvDraw.roundRect(ctx, x + 1, y + 6, 2, height - 12, 1, PvTheme.ACCENT);
        } else if (hoverT > 0.01F) {
            PvDraw.roundRect(ctx, x, y, width, height, PvTheme.RADIUS,
                    PvTheme.alpha(PvTheme.CARD_HOVER, hoverT));
        }
        drawFocusRing(ctx, PvTheme.RADIUS);

        if (!icon.isEmpty() && PvDraw.itemsVisible) {
            ctx.drawItem(icon, x + 6, y + (height - ICON) / 2);
        }

        int textY = y + (height - 8) / 2 + 1;

        // Actions are measured first, from the right, because everything else has to fit to their
        // left. The words are always drawn — a hover-only affordance is invisible to a player who
        // does not happen to run the mouse over the row, and the whole point of this layout is that
        // "I can edit this" is discoverable.
        int actionsLeft = x + width - 6;
        if (onRemove != null) {
            int rx = removeX(tr);
            boolean hot = overRemove(mouseX, mouseY, tr);
            if (hot) {
                PvDraw.roundRect(ctx, rx - 3, y + 3, tr.getWidth(removeLabel()) + 6, height - 6, 3,
                        PvTheme.alpha(PvTheme.DANGER, 0.22F));
            }
            PvDraw.text(ctx, tr, removeLabel(), rx, textY, hot ? PvTheme.DANGER : PvTheme.TEXT_MUTED);
            actionsLeft = rx;
        }
        if (onEdit != null) {
            int ex = editX(tr);
            boolean hot = overEdit(mouseX, mouseY, tr);
            if (hot) {
                PvDraw.roundRect(ctx, ex - 3, y + 3, tr.getWidth(editLabel()) + 6, height - 6, 3,
                        PvTheme.CARD_ACTIVE);
            }
            PvDraw.text(ctx, tr, editLabel(), ex, textY, hot ? PvTheme.TEXT : PvTheme.TEXT_DIM);
            actionsLeft = ex;
        }
        if (onRemove != null || onEdit != null) {
            actionsLeft -= ACTION_GAP;
        }

        int labelLeft = x + 28;
        if (detail != null && !detail.isEmpty()) {
            int detailW = tr.getWidth(detail);
            PvDraw.textRight(ctx, tr, detail, actionsLeft, textY,
                    selected ? PvTheme.TEXT_DIM : PvTheme.TEXT_MUTED);
            actionsLeft -= detailW + 6;
        }
        int labelMax = actionsLeft - labelLeft;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, label, Math.max(0, labelMax)), labelLeft, textY,
                type == Type.KIND ? PvTheme.TEXT_DIM : PvTheme.TEXT);

        if (type == Type.KIND) {
            PvDraw.plus(ctx, x + width - 10, y + height / 2, 3, 1,
                    PvTheme.mix(PvTheme.TEXT_MUTED, PvTheme.ACCENT, 0.35F + hoverT * 0.65F));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        pressed = true;
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!pressed) {
            return false;
        }
        pressed = false;
        if (button != 0 || !contains(mx, my)) {
            return true;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        // The two words are hit-tested before the row: they sit inside it, so the row's own click
        // would otherwise swallow every press on them.
        if (overRemove((int) mx, (int) my, tr) && onRemove != null) {
            onRemove.run();
        } else if (overEdit((int) mx, (int) my, tr) && onEdit != null) {
            onEdit.run();
        } else if (onClick != null) {
            onClick.run();
        }
        return true;
    }

    @Override
    public void activate() {
        if (onClick != null) {
            onClick.run();
        }
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible || !contains(mouseX, mouseY) || tooltip == null) {
            return null;
        }
        return tooltip;
    }

    public void tooltip(String tip) {
        this.tooltip = tip;
    }
}
