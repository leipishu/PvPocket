package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Two or three widgets side by side, so a row can offer more than one action.
 *
 * <p>{@code SettingRow} places a single control against its right edge. A group reports the summed
 * width to the row and lays its children out inside it.
 *
 * <p>It draws nothing itself. Every child keeps its own hover, press and disabled state. Placement
 * happens in {@link #render}, after the pane has assigned widths.
 */
public class PvButtonGroup extends PvWidget {

    /** Room between two neighbouring buttons. Small enough to read as one cluster. */
    private static final int GAP = 4;

    private final PvWidget[] items;

    /**
     * @param height the row height given to every child; all buttons in a group share one size
     */
    public PvButtonGroup(int height, PvWidget... items) {
        this.items = items;
        this.height = height;
        int total = GAP * Math.max(0, items.length - 1);
        for (PvWidget item : items) {
            item.height = height;
            total += item.width;
        }
        this.width = total;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        int at = x;
        for (PvWidget item : items) {
            item.x = at;
            item.y = y;
            item.visible = visible;
            item.render(ctx, mouseX, mouseY, delta);
            at += item.width + GAP;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (PvWidget item : items) {
            if (item.visible && item.enabled && item.contains(mx, my)
                    && item.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Releases to every child, not just the one under the pointer.
     *
     * <p>A press is consumed where it starts and the action fires where it ends, so a drag off the
     * button must still reach the button waiting for the release.
     */
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean consumed = false;
        for (PvWidget item : items) {
            consumed |= item.mouseReleased(mx, my, button);
        }
        return consumed;
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible || !contains(mouseX, mouseY)) {
            return null;
        }
        for (PvWidget item : items) {
            if (item.visible && item.contains(mouseX, mouseY)) {
                String tip = item.tooltipAt(mouseX, mouseY);
                if (tip != null) {
                    return tip;
                }
            }
        }
        return super.tooltipAt(mouseX, mouseY);
    }

    @Override
    public List<PvWidget> childrenForAudit() {
        return List.of(items);
    }
}
