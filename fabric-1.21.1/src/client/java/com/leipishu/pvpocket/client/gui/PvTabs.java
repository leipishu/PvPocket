package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleGroup;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.List;

/**
 * Vertical tab strip for the rule groups.
 *
 * <p>Vertical rather than horizontal because the labels are short and the panel is wider than it is
 * tall: a vertical strip costs 90px of width and saves 26px of the scarce axis, and it leaves room
 * for all seven groups without a scroll or an overflow menu.</p>
 */
public class PvTabs extends PvWidget {

    private final List<RuleGroup> groups;
    private int selected;
    private Runnable onChange;
    private int tabHeight = PvTheme.TAB_H;

    public PvTabs(List<RuleGroup> groups, int selected) {
        this.groups = groups;
        this.selected = selected;
        this.width = 88;
        this.height = groups.size() * PvTheme.TAB_H + (groups.size() - 1);
    }

    /**
     * Shrinks the tab pitch so every group stays reachable.
     *
     * <p>A tab strip that clips its last entries is worse than a dense one: the hidden groups are
     * not merely off-screen, they are unreachable, because there is nothing to scroll. The panel
     * calls this with whatever vertical space is actually available, so at a small window the tabs
     * compress instead of disappearing.</p>
     */
    public void fitInto(int availableHeight) {
        int n = Math.max(1, groups.size());
        int pitch = (availableHeight - (n - 1)) / n;
        tabHeight = Math.max(13, Math.min(PvTheme.TAB_H, pitch));
        height = groups.size() * tabHeight + (groups.size() - 1);
    }

    public int tabHeight() {
        return tabHeight;
    }

    public int selected() {
        return selected;
    }

    public RuleGroup group() {
        return groups.get(selected);
    }

    public void select(int index) {
        int next = Math.max(0, Math.min(groups.size() - 1, index));
        if (next != selected) {
            selected = next;
            if (onChange != null) {
                onChange.run();
            }
        }
    }

    public void onChange(Runnable callback) {
        this.onChange = callback;
    }

    private int tabY(int index) {
        return y + index * (tabHeight + 1);
    }

    private int tabAt(double my) {
        if (my < y || my >= y + height) {
            return -1;
        }
        int index = (int) ((my - y) / (tabHeight + 1));
        return index >= 0 && index < groups.size() ? index : -1;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int hoverIndex = hovered ? tabAt(mouseY) : -1;

        for (int i = 0; i < groups.size(); i++) {
            RuleGroup group = groups.get(i);
            int ty = tabY(i);
            if (ty + tabHeight > y + height) {
                break;
            }
            boolean active = i == selected;
            boolean hot = i == hoverIndex;

            if (active) {
                PvDraw.roundRect(ctx, x, ty, width, tabHeight, PvTheme.RADIUS, PvTheme.SURFACE_ACTIVE);
                PvDraw.roundRect(ctx, x, ty + 3, 2, tabHeight - 6, 1, PvTheme.ACCENT);
            } else if (hot) {
                PvDraw.roundRect(ctx, x, ty, width, tabHeight, PvTheme.RADIUS, PvTheme.SURFACE_HOVER);
            }

            int textY = ty + (tabHeight - 8) / 2 + 1;
            int textColor = active ? PvTheme.TEXT : (hot ? PvTheme.TEXT : PvTheme.TEXT_DIM);
            PvDraw.text(ctx, tr, group.label, x + 10, textY, textColor);
            PvDraw.textRight(ctx, tr, group.letter, x + width - 8, textY,
                    active ? PvTheme.ACCENT : PvTheme.TEXT_MUTED);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        int index = tabAt(my);
        if (index < 0) {
            return false;
        }
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.1F));
        select(index);
        return true;
    }
}
