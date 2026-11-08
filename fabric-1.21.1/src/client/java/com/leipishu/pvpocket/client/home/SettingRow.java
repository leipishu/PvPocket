package com.leipishu.pvpocket.client.home;

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

    public SettingRow(String label, PvWidget control, String value) {
        this.label = label;
        this.control = control;
        this.value = value;
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
        PvDraw.text(ctx, tr, PvDraw.fit(tr, label, width - reserve), x, textY, PvTheme.TEXT);
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
        return control == null ? List.of() : List.of(control);
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (control != null && control.visible && control.contains(mouseX, mouseY)) {
            return control.tooltipAt(mouseX, mouseY);
        }
        return null;
    }
}
