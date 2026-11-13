package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvBadge;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * One label and one optional control on a single line, for the settings page.
 *
 * <p>The control keeps its natural size and is placed against the row's right edge. A scroll pane
 * assigns every child the full pane width, so the row positions the control itself.
 *
 * <p>Placement happens in {@code render}, not in {@link #onWidth}: the pane assigns widths before
 * it totals heights, so a child's final {@code y} does not exist yet during {@code onWidth}.
 */
public class SettingRow extends PvWidget {

    /** Lines a hint sentence may take before it is cut: a paragraph would push the page apart. */
    private static final int HINT_LINES = 3;

    private final String label;
    private final PvWidget control;
    private final String value;
    /** Optional status pill drawn right after the label, for example "beta". */
    private final PvBadge badge;
    /** A sentence rather than a heading: it wraps and its height follows the row width. */
    private boolean hint;

    public SettingRow(String label, PvWidget control, String value) {
        this(label, control, value, null);
    }

    public SettingRow(String label, PvWidget control, String value, PvBadge badge) {
        this.label = label;
        this.control = control;
        this.value = value;
        this.badge = badge;
        this.height = 24;
    }

    /** A section header: label only, tighter, no control. */
    public SettingRow(String label) {
        this(label, null, null);
        this.height = 20;
    }

    /**
     * Creates a hint paragraph: one sentence explaining the section above it.
     *
     * <p>The text wraps to at most {@code HINT_LINES} lines and is drawn in dim text.
     */
    public static SettingRow hint(String text) {
        SettingRow row = new SettingRow(text, null, null);
        row.hint = true;
        return row;
    }

    /**
     * Computes a hint row's height from its wrapped line count.
     *
     * <p>The scroll pane calls this between assigning widths and totalling heights, the only point
     * where the width is known.
     */
    @Override
    public void onWidth(int width) {
        if (!hint) {
            return;
        }
        int lines = PvDraw.wrappedLines(Minecraft.getInstance().font, label, width,
                HINT_LINES);
        // A hint is never shorter than a 20px header.
        this.height = Math.max(20, PvDraw.wrappedHeight(lines) + 8);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        Font tr = Minecraft.getInstance().font;
        if (hint) {
            PvDraw.textWrapLeft(ctx, tr, label, x, y, height, width, HINT_LINES, PvTheme.TEXT_DIM);
            // The full sentence is available as the hover tooltip when it is cut.
            overflowTip(label, PvDraw.willCut(tr, label, width, HINT_LINES));
            return;
        }
        int textY = y + (height - 8) / 2 + 1;
        int reserve = 0;
        if (control != null) {
            control.x = x + width - control.width;
            control.y = y + (height - control.height) / 2;
            control.visible = visible;
            control.render(ctx, mouseX, mouseY, delta);
            reserve = control.width + 10;
        }
        if (value != null) {
            // The value sits left of the control, or at the row's right edge when there is none.
            // It is fitted to half the row, a third with a control; the label fits in the rest.
            int valueRoom = Math.max(24, control == null ? width / 2 : width / 3);
            int drawn = PvDraw.fittedWidth(tr, value, valueRoom);
            PvDraw.textFitRight(ctx, tr, value, x + width - reserve, textY, valueRoom,
                    PvTheme.TEXT_DIM);
            reserve += drawn + 10;
        }
        // The badge's room is reserved before the label is fitted, and the badge is then placed
        // against the drawn label width.
        int badgeRoom = badge == null ? 0 : badge.width + 6;
        int labelW = PvDraw.textFitLeft(ctx, tr, label, x, textY, width - reserve - badgeRoom,
                PvTheme.TEXT);
        if (badge != null) {
            badge.x = x + labelW + 6;
            badge.y = y + (height - badge.height) / 2;
            badge.visible = visible;
            badge.render(ctx, mouseX, mouseY, delta);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible) {
            return false;
        }
        if (control != null && control.visible && control.enabled && control.contains(mx, my)) {
            return control.mouseClicked(mx, my, button);
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return control != null && control.mouseReleased(mx, my, button);
    }

    @Override
    public List<PvWidget> childrenForAudit() {
        if (control == null) {
            return badge == null ? List.of() : List.of(badge);
        }
        return badge == null ? List.of(control) : List.of(control, badge);
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible || !contains(mouseX, mouseY)) {
            return null;
        }
        if (control != null && control.visible && control.contains(mouseX, mouseY)) {
            return control.tooltipAt(mouseX, mouseY);
        }
        // The label text, when the row had to cut it.
        return overflow();
    }
}
