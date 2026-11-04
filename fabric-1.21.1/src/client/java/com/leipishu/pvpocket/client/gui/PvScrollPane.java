package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertically scrolling container.
 *
 * <p>Two decisions worth keeping:</p>
 * <ul>
 *   <li><b>Children are repositioned, not translated.</b> Each layout pass writes the scrolled
 *       absolute {@code y} back into the child. Hit-testing therefore happens in screen space with
 *       no coordinate conversion, which is where scroll containers usually grow their bugs.</li>
 *   <li><b>Clipping is scissor-based, not visibility-based.</b> A partially visible row is drawn
 *       clipped rather than hidden, so the list does not appear to lose its first row as you
 *       scroll. Rows fully outside the viewport are skipped as an early-out.</li>
 * </ul>
 *
 * <p>The rule panel only ever has a few dozen rows, so this is not virtualised — a viewport check is
 * enough. If a later screen needs thousands of rows, the layout pass is the single place to add
 * windowing.</p>
 */
public class PvScrollPane extends PvWidget {

    public final List<PvWidget> children = new ArrayList<>();
    public int gap;

    private int scroll;
    private int contentHeight;
    private boolean draggingThumb;

    public void add(PvWidget widget) {
        children.add(widget);
    }

    public void clear() {
        children.clear();
        scroll = 0;
    }

    public int scroll() {
        return scroll;
    }

    /**
     * Restores a scroll offset, clamped to the current content.
     *
     * <p>Needed when a caller rebuilds its rows while the player is looking at them: without this the
     * list would jump back to the top on every edit, which reads as the panel losing its place.</p>
     */
    public void scrollTo(int offset) {
        scroll = Math.max(0, Math.min(offset, maxScroll()));
    }

    public int maxScroll() {
        return Math.max(0, contentHeight - height);
    }

    /** Recomputes content height, clamps the scroll offset and writes child positions. */
    public void layout() {
        int total = 0;
        for (int i = 0; i < children.size(); i++) {
            total += children.get(i).height;
            if (i < children.size() - 1) {
                total += gap;
            }
        }
        contentHeight = total;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));

        boolean bar = maxScroll() > 0;
        int childWidth = width - (bar ? PvTheme.SCROLLBAR_W + 6 : 0);
        int cy = y - scroll;
        for (PvWidget child : children) {
            child.x = x;
            child.width = childWidth;
            child.y = cy;
            cy += child.height + gap;
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        layout();
        ctx.enableScissor(x, y, x + width, y + height);
        for (PvWidget child : children) {
            if (child.y + child.height < y || child.y > y + height) {
                continue;
            }
            child.updateHover(mouseX, mouseY);
            child.render(ctx, mouseX, mouseY, delta);
        }
        ctx.disableScissor();

        // Hard edges at the viewport boundary plus a short fade on whichever side still has
        // content. Without the fade a half-scrolled row reads as a rendering glitch; with it the
        // list visibly continues past the edge and the scrollbar stops being the only clue.
        PvDraw.hLine(ctx, x, y, width, PvTheme.DIVIDER);
        PvDraw.hLine(ctx, x, y + height - 1, width, PvTheme.DIVIDER);
        if (scroll > 0) {
            fade(ctx, y, true);
        }
        if (scroll < maxScroll()) {
            fade(ctx, y + height - 10, false);
        }

        if (maxScroll() > 0) {
            renderScrollbar(ctx);
        }
    }

    /** 10px gradient drawn as stacked 1px lines, so no shader or gradient API is involved. */
    private void fade(DrawContext ctx, int top, boolean downwards) {
        int bg = PvTheme.PANEL_BG;
        for (int i = 0; i < 10; i++) {
            float t = downwards ? (10 - i) / 10F : i / 10F;
            int alpha = (int) (0xB4 * t * t);
            if (alpha <= 0) {
                continue;
            }
            int color = (alpha << 24) | (bg & 0x00FFFFFF);
            ctx.fill(x, top + i, x + width - (maxScroll() > 0 ? PvTheme.SCROLLBAR_W + 6 : 0),
                    top + i + 1, color);
        }
    }

    private void renderScrollbar(DrawContext ctx) {
        int barX = x + width - PvTheme.SCROLLBAR_W;
        PvDraw.roundRect(ctx, barX, y + 2, PvTheme.SCROLLBAR_W, height - 4, 2, PvTheme.SURFACE_SUNKEN);

        int trackH = height - 4;
        int thumbH = Math.max(16, (int) ((float) height * height / Math.max(1, contentHeight)));
        thumbH = Math.min(thumbH, trackH);
        int thumbY = y + 2 + Math.round((float) (trackH - thumbH) * scroll / maxScroll());
        PvDraw.roundRect(ctx, barX, thumbY, PvTheme.SCROLLBAR_W, thumbH, 2,
                draggingThumb ? PvTheme.ACCENT : PvTheme.ACCENT_DIM);
    }

    private boolean overBar(double mx) {
        return maxScroll() > 0 && mx >= x + width - PvTheme.SCROLLBAR_W - 2 && mx <= x + width;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !contains(mx, my)) {
            return false;
        }
        if (button == 0 && overBar(mx)) {
            draggingThumb = true;
            jumpTo(my);
            return true;
        }
        for (PvWidget child : snapshot()) {
            if (child.visible && child.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingThumb = false;
        boolean handled = false;
        for (PvWidget child : snapshot()) {
            handled |= child.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingThumb) {
            jumpTo(my);
            return true;
        }
        boolean handled = false;
        for (PvWidget child : snapshot()) {
            handled |= child.mouseDragged(mx, my, button, dx, dy);
        }
        return handled;
    }

    /**
     * A copy of the child list, for event dispatch.
     *
     * <p>Children are allowed to rebuild this pane from inside their own handlers — that is exactly
     * what a row's "×" does, and what a slider does on every tick through {@code rebuildListQuietly}.
     * Iterating the live list then throws {@link java.util.ConcurrentModificationException} on the
     * client's render thread, which is a crash rather than a glitch: it took both dev clients down
     * the first time a row was removed.</p>
     *
     * <p>The stale widgets in the copy are harmless — they have already been deselected, so their
     * handlers return false.</p>
     */
    private List<PvWidget> snapshot() {
        return children.isEmpty() ? List.of() : new ArrayList<>(children);
    }

    private void jumpTo(double my) {
        int trackH = height - 4;
        int thumbH = Math.max(16, (int) ((float) height * height / Math.max(1, contentHeight)));
        thumbH = Math.min(thumbH, trackH);
        float t = (float) ((my - y - 2 - thumbH / 2D) / Math.max(1, trackH - thumbH));
        t = Math.max(0F, Math.min(1F, t));
        scroll = Math.round(t * maxScroll());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!visible || !contains(mx, my) || maxScroll() <= 0) {
            return false;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(amount * 24)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible || !contains(mouseX, mouseY)) {
            return null;
        }
        for (PvWidget child : children) {
            String tip = child.tooltipAt(mouseX, mouseY);
            if (tip != null) {
                return tip;
            }
        }
        return tooltip;
    }
}
