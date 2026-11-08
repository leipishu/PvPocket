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

    private final String label;
    private final PvWidget control;
    private final String value;
    /** Optional status pill drawn right after the label, e.g. "beta" on a feature that is new. */
    private final PvBadge badge;

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

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int textY = y + (height - 8) / 2 + 1;
        int reserve = 0;
        if (control != null) {
            control.x = x + width - control.width;
            control.y = y + (height - control.height) / 2;
            control.visible = visible;
            control.render(ctx, mouseX, mouseY, delta);
            reserve = control.width + 10;
        } else if (value != null) {
            reserve = tr.getWidth(value) + 10;
            PvDraw.textRight(ctx, tr, value, x + width, textY, PvTheme.TEXT_DIM);
        }
        // The badge takes its room out of the label's budget before the label is fitted: a long
        // translated label must truncate rather than run underneath the pill. It is then placed
        // against the *fitted* width, not the raw one, for the same reason.
        int badgeRoom = badge == null ? 0 : badge.width + 6;
        String shown = PvDraw.fit(tr, label, width - reserve - badgeRoom);
        PvDraw.text(ctx, tr, shown, x, textY, PvTheme.TEXT);
        if (badge != null) {
            badge.x = x + tr.getWidth(shown) + 6;
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
        if (control != null && control.visible && control.contains(mouseX, mouseY)) {
            return control.tooltipAt(mouseX, mouseY);
        }
        return null;
    }
}
