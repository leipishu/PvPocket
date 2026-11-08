package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertically scrolling container.
 *
 * <p>Three decisions worth keeping:</p>
 * <ul>
 *   <li><b>Children are repositioned, not translated.</b> Each layout pass writes the scrolled
 *       absolute {@code y} back into the child. Hit-testing therefore happens in screen space with
 *       no coordinate conversion, which is where scroll containers usually grow their bugs.</li>
 *   <li><b>Clipping is scissor-based, not visibility-based.</b> A partially visible row is drawn
 *       clipped rather than hidden, so the list does not appear to lose its first row as you
 *       scroll. Rows fully outside the viewport are skipped as an early-out.</li>
 *   <li><b>The wheel moves a target, not the offset.</b> The offset follows the target with a 110ms
 *       time constant, so a fast flick decelerates instead of stopping dead, and the rows move with
 *       the same motion the rest of the interface uses.</li>
 * </ul>
 *
 * <p>The rule panel only ever has a few dozen rows, so this is not virtualised — a viewport check is
 * enough. If a later screen needs thousands of rows, the layout pass is the single place to add
 * windowing.</p>
 */
public class PvScrollPane extends PvWidget {

    public final List<PvWidget> children = new ArrayList<>();
    public int gap;

    private float scroll;
    private int target;
    private int contentHeight;
    private boolean draggingThumb;
    private float barT;
    private float fadeTopT;
    private float fadeBottomT;

    public void add(PvWidget widget) {
        children.add(widget);
    }

    public void clear() {
        children.clear();
        scroll = 0F;
        target = 0;
    }

    public int scroll() {
        return target;
    }

    /**
     * Restores a scroll offset, clamped to the current content.
     *
     * <p>Needed when a caller rebuilds its rows while the player is looking at them: without this the
     * list would jump back to the top on every edit, which reads as the panel losing its place.
     * Restoring is instant on purpose — an animated restore would look like the list scrolling by
     * itself in response to an edit somewhere else.</p>
     */
    public void scrollTo(int offset) {
        target = Math.max(0, Math.min(offset, maxScroll()));
        scroll = target;
    }

    public int maxScroll() {
        return Math.max(0, contentHeight - height);
    }

    /** Recomputes content height and clamps the scroll offset. */
    public void layout() {
        layout(0F);
    }

    private void layout(float d) {
        // Width before height: a row's height can depend on how wide it is (label beside the control
        // when there is room, stacked when there is not), so the widths have to be handed out before
        // anything is totalled. The scrollbar decision therefore uses the previous frame's content
        // height, which is stable in practice — a list that grows past the viewport shows its bar one
        // frame late, and one frame at 60fps is not a state anyone can observe.
        boolean bar = maxScroll() > 0;
        int childWidth = Math.max(0, width - (bar ? PvTheme.SCROLLBAR_W + 8 : 0));
        for (PvWidget child : children) {
            child.x = x;
            child.width = childWidth;
            child.onWidth(childWidth);
        }

        int total = 0;
        for (int i = 0; i < children.size(); i++) {
            total += children.get(i).height;
            if (i < children.size() - 1) {
                total += gap;
            }
        }
        contentHeight = total;
        target = Math.max(0, Math.min(target, maxScroll()));
        scroll = PvAnim.damp(scroll, target, d, 0.037F);
        if (Math.abs(target - scroll) < 0.4F) {
            scroll = target;
        }

        int cy = y - Math.round(scroll);
        for (PvWidget child : children) {
            child.y = cy;
            cy += child.height + gap;
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // A pane with no room is not a pane with a small viewport: a negative width produces a
        // reversed scissor rect, which the renderer does not clamp, so every child would draw
        // unclipped across whatever is next to it. Bailing out is the only safe reading.
        if (!visible || width <= 0 || height <= 0) {
            return;
        }
        float d = dt(delta);
        layout(d);

        ctx.enableScissor(x, y, x + width, y + height);
        // The rows are a surface of their own for the layout audit: what the viewport cuts off is
        // clipped, not overlapping the status line underneath.
        String layer = PvAudit.layer(PvAudit.paneLayer(this));
        for (PvWidget child : children) {
            if (child.y + child.height < y || child.y > y + height) {
                continue;
            }
            child.updateHover(mouseX, mouseY);
            child.render(ctx, mouseX, mouseY, delta);
        }
        PvAudit.layer(layer);
        ctx.disableScissor();

        // Fades on whichever side still has content. Without them a half-scrolled row reads as a
        // rendering glitch; with them the list visibly continues past the edge and the scrollbar
        // stops being the only clue. Both edges animate, so the cue arrives and leaves with the
        // scroll position instead of popping.
        fadeTopT = PvAnim.damp(fadeTopT, scroll > 0.5F ? 1F : 0F, d, 0.08F);
        fadeBottomT = PvAnim.damp(fadeBottomT, scroll < maxScroll() - 0.5F ? 1F : 0F, d, 0.08F);
        if (fadeTopT > 0.01F) {
            fade(ctx, y, true, fadeTopT);
        }
        if (fadeBottomT > 0.01F) {
            fade(ctx, y + height - 10, false, fadeBottomT);
        }

        barT = PvAnim.damp(barT, maxScroll() > 0 ? 1F : 0F, d, 0.1F);
        if (barT > 0.01F) {
            renderScrollbar(ctx, mouseX, mouseY);
        }
    }

    /** 10px gradient drawn as stacked 1px lines, so no shader or gradient API is involved. */
    private void fade(DrawContext ctx, int top, boolean downwards, float strength) {
        int bg = PvTheme.PANEL;
        int w = width - (maxScroll() > 0 ? PvTheme.SCROLLBAR_W + 8 : 0);
        for (int i = 0; i < 10; i++) {
            float t = downwards ? (10 - i) / 10F : i / 10F;
            int alpha = (int) (0xB4 * t * t * strength);
            if (alpha <= 0) {
                continue;
            }
            int color = (alpha << 24) | (bg & 0x00FFFFFF);
            ctx.fill(x, top + i, x + w, top + i + 1, color);
        }
    }

    private void renderScrollbar(DrawContext ctx, int mouseX, int mouseY) {
        int barX = x + width - PvTheme.SCROLLBAR_W;
        boolean hot = overBar(mouseX);
        PvDraw.roundRect(ctx, barX, y + 2, PvTheme.SCROLLBAR_W, height - 4, 3,
                PvTheme.alpha(PvTheme.CARD_ACTIVE, barT));

        int trackH = height - 4;
        int thumbH = Math.max(18, (int) ((float) height * height / Math.max(1, contentHeight)));
        thumbH = Math.min(thumbH, trackH);
        int thumbY = y + 2 + Math.round((float) (trackH - thumbH) * scroll / Math.max(1, maxScroll()));
        int color = draggingThumb ? PvTheme.ACCENT
                : hot ? PvTheme.ACCENT_DIM
                : PvTheme.BORDER_STRONG;
        PvDraw.roundRect(ctx, barX, thumbY, PvTheme.SCROLLBAR_W, thumbH, 3,
                PvTheme.alpha(color, barT));
    }

    private boolean overBar(double mx) {
        return maxScroll() > 0 && mx >= x + width - PvTheme.SCROLLBAR_W - 4 && mx <= x + width;
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
        int thumbH = Math.max(18, (int) ((float) height * height / Math.max(1, contentHeight)));
        thumbH = Math.min(thumbH, trackH);
        float t = (float) ((my - y - 2 - thumbH / 2D) / Math.max(1, trackH - thumbH));
        t = Math.max(0F, Math.min(1F, t));
        target = Math.round(t * maxScroll());
        // Dragging the thumb is direct manipulation: the content has to stay under the pointer, so
        // the offset is not smoothed here even though the wheel is.
        scroll = target;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!visible || !contains(mx, my) || maxScroll() <= 0) {
            return false;
        }
        target = Math.max(0, Math.min(maxScroll(), target - (int) Math.round(amount * 26)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    @Override
    public List<PvWidget> childrenForAudit() {
        return children;
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
