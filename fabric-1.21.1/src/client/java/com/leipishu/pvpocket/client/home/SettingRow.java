package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvBadge;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * One label and one control on a single line, for the settings page.
 *
 * <p>A row rather than two siblings because the control has to be positioned against the row's right
 * edge, and a scroll pane assigns every child the full pane width — a 28px switch handed 300px would
 * draw a 300px track. The row keeps the control at its natural size and places it.</p>
 *
 * <p>Placement happens in {@code render}, not in {@link #onWidth}: the pane hands out widths before
 * it totals heights, so a child's final {@code y} does not exist yet when {@code onWidth} runs. Every
 * other container in this mod does the same thing for the same reason.</p>
 */
public class SettingRow extends PvWidget {

    /** Lines a hint sentence may take before it is cut: a paragraph would push the page apart. */
    private static final int HINT_LINES = 3;

    private final String label;
    private final PvWidget control;
    private final String value;
    /** Optional status pill drawn right after the label, e.g. "beta" on a feature that is new. */
    private final PvBadge badge;
    /** A sentence rather than a heading: it wraps, and its height follows from how wide the row is. */
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
     * A hint paragraph: one sentence explaining the section above it.
     *
     * <p>Not a header with a long label. "Changes are written to the vanilla controls too, and can be
     * undone there." is 281px on one line and the settings page is 280px wide at best — as a header it
     * was cut mid-word in both languages, and as a hint it is three lines of dim text that say what
     * they mean.</p>
     */
    public static SettingRow hint(String text) {
        SettingRow row = new SettingRow(text, null, null);
        row.hint = true;
        return row;
    }

    /**
     * A hint's height is a function of its width, so it is computed here rather than in the
     * constructor: the scroll pane hands out widths before it totals heights, and this is the hook
     * that runs in between.
     */
    @Override
    public void onWidth(int width) {
        if (!hint) {
            return;
        }
        int lines = PvDraw.wrappedLines(MinecraftClient.getInstance().textRenderer, label, width,
                HINT_LINES);
        // Never shorter than a header: a hint that fits on one line should not be a tighter row than
        // the section heading above it.
        this.height = Math.max(20, PvDraw.wrappedHeight(lines) + 8);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (hint) {
            PvDraw.textWrapLeft(ctx, tr, label, x, y, height, width, HINT_LINES, PvTheme.TEXT_DIM);
            // Three lines and still not the whole sentence: the rest is one hover away.
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
        } else if (value != null) {
            // A value that is longer than half the row is a value that has outgrown its column —
            // "LGPL-3.0-only" is fine, a path or a version string with a build suffix is not. It
            // shrinks first and takes its reserve from what it actually covers, so the label beside
            // it is measured against the truth.
            int valueRoom = Math.max(24, width / 2);
            reserve = PvDraw.fittedWidth(tr, value, valueRoom) + 10;
            PvDraw.textFitRight(ctx, tr, value, x + width, textY, valueRoom, PvTheme.TEXT_DIM);
        }
        // The badge takes its room out of the label's budget before the label is fitted: a long
        // translated label must truncate rather than run underneath the pill. It is then placed
        // against the *drawn* width, not the natural one, for the same reason.
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
        // The label's own text, when the row had to cut it (a hint sentence at a narrow width).
        return overflow();
    }
}
