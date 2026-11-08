package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.gui.DrawContext;

/**
 * Base class for every custom widget.
 *
 * <p>Deliberately not a {@code ClickableWidget}: that class brings the vanilla skin, the vanilla
 * narration hooks and the vanilla hover tooltip style, and the whole point of this layer is to own
 * all three. What it does keep is the shape of the input contract, so a widget can be handed events
 * without the screen needing to know what kind it is.</p>
 *
 * <p>Coordinates are absolute screen coordinates in GUI space. The scroll pane repositions its
 * children every frame instead of translating the matrix, which keeps hit-testing in the same space
 * as rendering and removes a whole class of off-by-scroll bugs.</p>
 *
 * <p>Interaction state is carried twice: {@link #hovered} is the instantaneous truth used for hit
 * testing, and {@link #hoverT} is its animated shadow, used for drawing. Keeping both is what lets
 * a click land on the frame the mouse arrives while the fill is still 40% of the way in.</p>
 */
public abstract class PvWidget {

    public int x;
    public int y;
    public int width;
    public int height;

    public boolean visible = true;
    public boolean enabled = true;
    public boolean hovered;
    public boolean focused;

    /** Animated hover, press and focus, all in [0,1]. Advanced by each widget's own render pass. */
    protected float hoverT;
    protected float pressT;
    protected float focusT;

    /** Shown on hover by the screen, drawn last so it is never clipped by a scroll pane. */
    public String tooltip;

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    /**
     * Called by a container after it assigns {@link #width}, before it totals up the heights.
     *
     * <p>Some rows need to know how much room they have before they can say how tall they are: a
     * rule row keeps label and control on one line when both fit and stacks them when they do not.
     * Without this hook the container has to total the heights first, which is exactly the number
     * that depends on the width it has not assigned yet.</p>
     */
    public void onWidth(int width) {
    }

    public boolean focusable() {
        return visible && enabled;
    }

    public void updateHover(int mouseX, int mouseY) {
        hovered = visible && contains(mouseX, mouseY);
    }

    /** Frame step in seconds, for widgets that drive their own tweens. */
    protected float dt(float delta) {
        return PvAnim.dt(delta);
    }

    /** Advances the hover tween. Every interactive widget calls this first in its render pass. */
    protected void tickHover(float d) {
        hoverT = PvAnim.damp(hoverT, hovered && enabled ? 1F : 0F, d, 0.09F);
    }

    protected void tickFocus(float d) {
        focusT = PvAnim.damp(focusT, focused && enabled ? 1F : 0F, d, 0.08F);
    }

    /** Draws the accent ring when focus is (partly) on this widget. */
    protected void drawFocusRing(DrawContext ctx, int radius) {
        if (focusT <= 0.02F) {
            return;
        }
        PvDraw.ring(ctx, x - 2, y - 2, width + 4, height + 4, radius + 2, 1,
                PvTheme.alpha(PvTheme.ACCENT, focusT * 0.85F));
    }

    public abstract void render(DrawContext ctx, int mouseX, int mouseY, float delta);

    public boolean mouseClicked(double mx, double my, int button) {
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        return false;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        return false;
    }

    /** Returns true when the widget consumed the key, which stops the screen's own handling. */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    /**
     * One typed character. Only text fields care; everything else declines.
     *
     * <p>Separate from {@link #keyPressed} because vanilla delivers them on separate paths and the
     * distinction matters: Backspace is a key, "a" is a character, and a keyboard layout that puts
     * "a" somewhere else still produces "a".</p>
     */
    public boolean charTyped(char chr, int modifiers) {
        return false;
    }

    /**
     * The tooltip that should be shown for this position, or null.
     *
     * <p>Asked of the tree rather than tracked as "the hovered widget" so that containers can
     * forward the question to their children and the screen only ever needs to walk its roots.</p>
     */
    public String tooltipAt(int mouseX, int mouseY) {
        return visible && tooltip != null && contains(mouseX, mouseY) ? tooltip : null;
    }

    /** Keyboard activation (Enter / Space). No-op for widgets that cannot be activated. */
    public void activate() {
    }

    /**
     * Called when the screen takes this widget off the modal layer, whichever way it left.
     *
     * <p>Esc is handled by {@link PvScreen} itself, so a dialog that only cleaned up inside its own
     * close button would leak whatever it registered on the way in — a stale listener reacting to the
     * next dialog's packet is exactly the failure that hides until somebody opens the panel twice.
     * Widgets that register nothing can leave this empty.</p>
     */
    public void dismissed() {
    }

    /** Keyboard value change (Left / Right). No-op for widgets without a value. */
    public void nudge(int direction) {
    }

    /**
     * Children for the layout audit to descend into.
     *
     * <p>Containers override this; a leaf returns nothing. It exists so {@link PvAudit} can see the
     * whole tree without knowing which classes are containers — the alternative was a chain of
     * {@code instanceof} checks in the audit, which silently stops covering a new container the day
     * one is added.</p>
     */
    public java.util.List<PvWidget> childrenForAudit() {
        return java.util.List.of();
    }
}
