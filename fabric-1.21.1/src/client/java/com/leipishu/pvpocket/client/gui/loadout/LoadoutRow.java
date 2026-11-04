package com.leipishu.pvpocket.client.gui.loadout;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/**
 * One line of the loadout's middle column.
 *
 * <p>Three kinds of line share one widget because they share one list: a section header, an entry
 * that is already in the loadout (selectable, removable), and a kind that can be added. Splitting
 * them into three widgets would have meant three hit-test paths inside one scroll pane for no gain.</p>
 *
 * <p>The icon is the real item rendered by {@code DrawContext.drawItem}, not a glyph or a label. The
 * requirement was explicit about this, and it is also the only way a player can tell a golden apple
 * from an enchanted golden apple at a glance.</p>
 */
public class LoadoutRow extends PvWidget {

    public enum Type {
        HEADER,
        ENTRY,
        KIND
    }

    private static final int ICON = 16;
    private static final int REMOVE_W = 18;

    private final Type type;
    private final String label;
    private final String detail;
    private final ItemStack icon;
    private final boolean selected;
    private final Runnable onClick;
    private final Runnable onRemove;

    private boolean pressed;

    public LoadoutRow(Type type, String label, String detail, ItemStack icon,
                      boolean selected, Runnable onClick, Runnable onRemove) {
        this.type = type;
        this.label = label;
        this.detail = detail;
        this.icon = icon;
        this.selected = selected;
        this.onClick = onClick;
        this.onRemove = onRemove;
        this.height = type == Type.HEADER ? 18 : 22;
        if (type == Type.HEADER) {
            this.enabled = false;
        }
    }

    @Override
    public boolean focusable() {
        return type != Type.HEADER && super.focusable();
    }

    private boolean overRemoveZone(int mouseX) {
        return onRemove != null && hovered && mouseX >= x + width - REMOVE_W;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        if (type == Type.HEADER) {
            PvDraw.text(ctx, tr, label, x + 2, y + 5, PvTheme.TEXT_MUTED);
            PvDraw.hLine(ctx, x + tr.getWidth(label) + 8, y + 9,
                    Math.max(0, width - tr.getWidth(label) - 10), PvTheme.DIVIDER);
            return;
        }

        if (selected) {
            PvDraw.roundRect(ctx, x, y, width, height, PvTheme.RADIUS, PvTheme.SURFACE_ACTIVE);
            PvDraw.roundRect(ctx, x, y + 3, 2, height - 6, 1, PvTheme.ACCENT);
        } else if (hovered) {
            PvDraw.roundRect(ctx, x, y, width, height, PvTheme.RADIUS, PvTheme.SURFACE_HOVER);
        }
        if (focused) {
            PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS, PvTheme.ACCENT, 0);
        }

        if (!icon.isEmpty()) {
            ctx.drawItem(icon, x + 4, y + 3);
        }

        int textY = y + (height - 8) / 2 + 1;
        int reserved = 0;
        if (onRemove != null) {
            reserved = REMOVE_W;
        }
        if (detail != null && !detail.isEmpty()) {
            int detailW = tr.getWidth(detail);
            PvDraw.textRight(ctx, tr, detail, x + width - reserved - 6, textY,
                    selected ? PvTheme.TEXT_DIM : PvTheme.TEXT_MUTED);
            reserved += detailW + 6;
        }
        int labelMax = width - 26 - reserved - 4;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, label, Math.max(0, labelMax)), x + 26, textY,
                type == Type.KIND ? PvTheme.TEXT_DIM : PvTheme.TEXT);

        if (onRemove != null && hovered) {
            boolean hot = overRemoveZone(mouseX);
            PvDraw.textCenter(ctx, tr, "×", x + width - REMOVE_W / 2, textY,
                    hot ? PvTheme.DANGER : PvTheme.TEXT_MUTED);
        } else if (type == Type.KIND) {
            PvDraw.textRight(ctx, tr, "+", x + width - 8, textY,
                    hovered ? PvTheme.ACCENT : PvTheme.TEXT_MUTED);
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
        if (overRemoveZone((int) mx) && onRemove != null) {
            onRemove.run();
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
