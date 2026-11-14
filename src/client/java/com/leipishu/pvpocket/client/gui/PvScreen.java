package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}

import java.util.ArrayList;
import java.util.List;

/**
 * Base screen for the custom GUI layer.
 *
 * <p>It owns four things every self-drawn screen needs: a single dim backdrop instead of the
 * vanilla blur, a short fade from black over the panel rect, tooltips drawn after everything else
 * so they escape a scroll pane's clip, and keyboard focus with Tab traversal, arrow nudging, Enter
 * activation and Esc to close.
 */
public abstract class PvScreen extends Screen {

    /** Seconds the panel takes to resolve out of black. */
    private static final float OPEN_TIME = 0.16F;
    /** Seconds the pointer has to rest on a widget before its tooltip is worth showing. */
    private static final float TIP_DELAY = 0.15F;

    protected final List<PvWidget> roots = new ArrayList<>();

    protected int panelX;
    protected int panelY;
    protected int panelW;
    protected int panelH;

    /**
     * The chrome: a title band across the top, optionally continued down the left as a sidebar.
     *
     * <p>Both are the same surface, drawn as one L. The content well is a second shape laid over
     * the chrome, square where it meets the chrome and rounded at the panel's bottom-right corner.
     * Zero for either dimension means this panel has no such band.
     *
     * <p>There is no border anywhere in this arrangement: the two surfaces separate by value, which
     * is why {@code PvTheme.SIDEBAR} sits a full step above {@code PvTheme.PANEL}.
     */
    protected int chromeW;
    protected int chromeH;

    protected PvWidget focused;
    /** Activated by Enter when focus is not on a button. The panel's "yes, do it" action. */
    protected PvButton defaultButton;
    protected int cursorX;
    protected int cursorY;

    /**
     * The screen that opened this one, or {@code null} when it was opened from the game.
     *
     * <p>Only one level deep: every screen in this mod is reachable from the home screen in one
     * step. A screen pushed by the server leaves this null, so closing it returns to the game.
     */
    protected PvScreen parent;

    /**
     * Modal layer. Drawn after every root, and given the mouse before any of them.
     *
     * <p>Separate from {@link #roots} because both halves of "on top" have to be enforced. Drawing
     * last puts it above; hit-testing first stops a click on its backdrop from pressing the button
     * underneath.
     */
    protected PvWidget overlay;

    /**
     * Whether any screen currently owns the pointer with a modal layer.
     *
     * <p>Static because the widgets that need it cannot reach the screen instance. The client is
     * single-threaded and one screen renders at a time, so one flag is enough; it is recomputed at
     * the top of every frame and cleared when the screen closes.
     */
    private static boolean modalPointer;

    /** See {@link #modalPointer}. */
    public static boolean modalPointer() {
        return modalPointer;
    }

    private boolean started;
    private float openT;
    private String tipKey;
    private float tipDelay;
    private float tipT;

    protected PvScreen(Component title) {
        super(title);
    }

    /** The world keeps running behind the panel; a duel is not pauseable by opening rules. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Closes back to whoever opened this screen.
     *
     * <p>Overridden here rather than in each screen, so a child screen cannot strand the player in
     * the world instead of returning to the menu it came from.
     */
    @Override
    public void onClose() {
        modalPointer = false;
        Minecraft.getInstance().setScreen(parent);
    }

    /**
     * Clears the modal flag when the screen goes away.
     *
     * <p>Not every screen leaves through {@link #close()}: the server can push a screen over this
     * one. It is recomputed on the next frame, but panes of the outgoing screen read it first.
     */
    @Override
    public void removed() {
        modalPointer = false;
        super.removed();
    }

    /** Records where {@code close()} should return to. */
    protected void setParent(PvScreen screen) {
        this.parent = screen;
    }

    protected Font font() {
        return Minecraft.getInstance().font;
    }

    /** Centres a panel of at most {@code maxW x maxH} inside the current GUI viewport. */
    protected void layoutPanel(int maxW, int maxH) {
        panelW = Math.min(width - 20, maxW);
        panelH = Math.min(height - 20, maxH);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    protected void drawBackdrop(GuiGraphics ctx) {
        ctx.fill(0, 0, width, height, PvTheme.alpha(PvTheme.SCRIM, openProgress()));
    }

    protected void drawPanelFrame(GuiGraphics ctx) {
        int r = PvTheme.RADIUS_PANEL;
        // Chrome first, edge to edge: the title band and the sidebar are one surface, and the well
        // is the shape cut out of it.
        PvDraw.roundRect(ctx, panelX, panelY, panelW, panelH, r, PvTheme.SIDEBAR);
        if (chromeW > 0 || chromeH > 0) {
            PvDraw.roundRect(ctx, panelX + chromeW, panelY + chromeH,
                    panelW - chromeW, panelH - chromeH, 0, 0, r, 0, PvTheme.PANEL);
        }
        // The only light source in the system: one hairline along the inside of the top edge. It is
        // what separates a black panel from a hole in the screen.
        PvDraw.hLine(ctx, panelX + r + 1, panelY + 1, panelW - r * 2 - 2, PvTheme.HIGHLIGHT);
    }

    /** Returns 0 at the frame the screen opens, 1 once the entrance has finished. */
    private float openProgress() {
        return PvAnim.easeOutCubic(openT / OPEN_TIME);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        cursorX = mouseX;
        cursorY = mouseY;
        // Recomputed every frame rather than tracked at the call sites: a dialog can hide
        // itself, and a stale true would leave every scroll pane un-hoverable.
        modalPointer = hasOverlay();
        // Opens the measurement window before anything is drawn: text is recorded as it is painted,
        // so a window that opened at the end of the frame would only ever see widget boxes.
        PvAudit.begin(getClass().getSimpleName(), width, height);
        float d = PvAnim.dt(delta);
        // Reset on the first frame rather than in init(): init() also runs on resize, and replaying
        // the fade every time the window changes size would look like a flicker.
        if (!started) {
            started = true;
            openT = 0F;
        } else {
            openT = Math.min(1F, openT + d);
        }

        drawBackdrop(ctx);
        drawPanelFrame(ctx);
        // Everything from here to the modal layer is one surface: the panel's text and every root
        // widget may collide with each other in the audit.
        PvAudit.layer(PvAudit.CHROME_LAYER);
        // The pointer as far as the panel is concerned: with a modal up, nothing beneath it is
        // under the cursor, expressed as a coordinate outside every widget.
        int px = overlay == null ? mouseX : -1;
        int py = overlay == null ? mouseY : -1;
        drawContent(ctx, px, py, delta);

        // Item icons live at z = 150 in a depth-tested layer, so a scrim at z = 0 cannot cover
        // them (see PvDraw.itemsVisible).
        PvDraw.itemsVisible = overlay == null;
        for (PvWidget widget : roots) {
            // Cleared, not frozen: a widget left glowing from the frame before the dialog opened
            // is a widget the player thinks they can still press.
            widget.updateHover(px, py);
            widget.render(ctx, px, py, delta);
        }
        PvDraw.itemsVisible = true;
        if (overlay != null) {
            PvAudit.layer(PvAudit.OVERLAY_LAYER);
            overlay.updateHover(mouseX, mouseY);
            overlay.render(ctx, mouseX, mouseY, delta);
            PvAudit.layer(PvAudit.CHROME_LAYER);
        }
        drawEntrance(ctx);
        drawTooltip(ctx, d);
        auditFrame();
    }

    /**
     * Feeds this frame's widget rectangles to {@link PvAudit} and runs the checks.
     *
     * <p>Called at the end of {@link #render}, because the scroll panes lay their children out
     * inside their own render pass. Off unless the audit harness is running.
     */
    private void auditFrame() {
        if (!PvAudit.ON) {
            return;
        }
        for (int i = 0; i < roots.size(); i++) {
            auditWidget(roots.get(i), "root", "r" + i, null, PvAudit.CHROME_LAYER,
                    panelX, panelY, panelW, panelH, true, true);
        }
        if (overlay != null) {
            auditWidget(overlay, "overlay", "o", null, PvAudit.OVERLAY_LAYER,
                    0, 0, width, height, true, true);
        }
        PvAudit.end();
    }

    /**
     * @param laidOut whether this widget was actually rendered this frame; a row skipped by a
     *                scroll pane never positions its inline control, so descending into one
     *                measures a control still at 0,0
     */
    private static void auditWidget(PvWidget widget, String group, String id, String parent,
                                    String layer, int clipX, int clipY, int clipW, int clipH,
                                    boolean clipped, boolean laidOut) {
        if (!widget.visible) {
            return;
        }
        PvAudit.widget(id, widget.getClass().getSimpleName(), group, parent, layer,
                widget.x, widget.y, widget.width, widget.height,
                clipX, clipY, clipW, clipH, clipped);
        if (!laidOut) {
            return;
        }
        // A scroll pane's children leave the viewport by design, so they are measured against the
        // pane's rect for collisions but not for containment.
        boolean scroll = widget instanceof PvScrollPane;
        String childLayer = scroll ? PvAudit.paneLayer(widget) : layer;
        List<PvWidget> children = widget.childrenForAudit();
        for (int i = 0; i < children.size(); i++) {
            PvWidget child = children.get(i);
            boolean drawn = !scroll
                    || (child.y + child.height >= widget.y && child.y <= widget.y + widget.height);
            auditWidget(child, group + '>' + id, id + '.' + i, id, childLayer,
                    widget.x, widget.y, widget.width, widget.height, !scroll, drawn);
        }
    }

    /**
     * How many distinct layouts the audit should visit.
     *
     * <p>A screen that only audits its opening state hides most of itself: the rule panel has eight
     * pages and two dialogs. {@link PvAuditHarness} calls {@link #auditVariant} for each index.
     */
    public int auditVariantCount() {
        return 1;
    }

    /** Puts the screen into one of the layouts counted by {@link #auditVariantCount}. */
    public void auditVariant(int index) {
    }

    /**
     * Puts a widget on the modal layer, or clears it with {@code null}.
     *
     * <p>The layer is always the full viewport, so the size is assigned here rather than at each
     * call site, where it would also have to be repeated in {@code init()} after a resize.
     */
    protected void setOverlay(PvWidget widget) {
        overlay = widget;
        if (widget != null) {
            widget.width = width;
            widget.height = height;
            setFocus(null);
        }
    }

    /** Takes the modal layer down and lets it clean up, however it is being closed. */
    protected void dismissOverlay() {
        PvWidget widget = overlay;
        overlay = null;
        if (widget != null) {
            widget.dismissed();
        }
    }

    protected boolean hasOverlay() {
        return overlay != null && overlay.visible;
    }

    /**
     * Black overlay fading off the panel rect.
     *
     * <p>A single rounded fill is one draw call and cannot desynchronise from the widgets
     * underneath, unlike animating the alpha of every colour in the panel.
     */
    private void drawEntrance(GuiGraphics ctx) {
        float k = openProgress();
        if (k >= 1F) {
            return;
        }
        int alpha = Math.round(255F * (1F - k));
        PvDraw.roundRect(ctx, panelX, panelY, panelW, panelH, PvTheme.RADIUS_PANEL,
                alpha << 24);
    }

    /** Hook for the static parts of the panel: title, dividers, footer labels. */
    protected void drawContent(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
    }

    /**
     * Tooltip with a dwell delay and a fade.
     *
     * <p>The delay stops the tooltip strobing while the pointer crosses a column of rows; the fade
     * keeps it from appearing as a hard-edged rectangle over a dark panel.
     */
    private void drawTooltip(GuiGraphics ctx, float d) {
        String tip = null;
        if (hasOverlay()) {
            // The modal is the only thing under the pointer. Walking the roots as well let a
            // background row's hint appear on top of an open dialog.
            tip = overlay.tooltipAt(cursorX, cursorY);
        } else {
            for (PvWidget widget : roots) {
                if (tip != null) {
                    break;
                }
                tip = widget.tooltipAt(cursorX, cursorY);
            }
        }
        if (tip == null) {
            tipKey = null;
            tipDelay = 0F;
            tipT = 0F;
            return;
        }
        if (!tip.equals(tipKey)) {
            tipKey = tip;
            tipDelay = 0F;
            tipT = 0F;
        }
        tipDelay += d;
        if (tipDelay < TIP_DELAY) {
            return;
        }
        tipT = PvAnim.damp(tipT, 1F, d, 0.05F);

        Font tr = font();
        List<String> lines = PvDraw.wrap(tr, tip, 176);
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, tr.width(line));
        }
        int boxW = textW + 16;
        int boxH = lines.size() * 10 + 10;
        int tx = Math.max(2, Math.min(cursorX + 10, width - boxW - 2));
        int ty = Math.max(2, Math.min(cursorY + 12, height - boxH - 2));

        PvDraw.roundPanel(ctx, tx, ty, boxW, boxH, PvTheme.RADIUS,
                PvTheme.alpha(PvTheme.BORDER_STRONG, tipT),
                PvTheme.alpha(PvTheme.TOOLTIP, tipT));
        String layer = PvAudit.layer(PvAudit.TOOLTIP_LAYER);
        for (int i = 0; i < lines.size(); i++) {
            PvDraw.text(ctx, tr, lines.get(i), tx + 8, ty + 6 + i * 10,
                    PvTheme.alpha(PvTheme.TEXT_DIM, tipT));
        }
        PvAudit.layer(layer);
    }

    // ------------------------------------------------------------------ input

    /**
     * The widget list as it was when the event arrived.
     *
     * <p>Every input handler below walks this instead of {@link #roots}, because a handler may
     * rebuild the screen. Iterating the live list while that happens throws
     * {@code ConcurrentModificationException}.
     */
    private List<PvWidget> snapshot() {
        return new ArrayList<>(roots);
    }

//? if >=1.21.11 {
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return mouseClicked(event.x(), event.y(), event.button());
    }
//?}
    public boolean mouseClicked(double mx, double my, int button) {
        // The modal gets the click first and consumes it whether or not it handled anything: a
        // click on the scrim is "dismiss", not "dismiss and also press what was behind".
        if (hasOverlay()) {
            overlay.mouseClicked(mx, my, button);
            return true;
        }
        for (PvWidget widget : snapshot()) {
            if (widget.visible && widget.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

//? if >=1.21.11 {
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return mouseReleased(event.x(), event.y(), event.button());
    }
//?}
    public boolean mouseReleased(double mx, double my, int button) {
        if (hasOverlay()) {
            overlay.mouseReleased(mx, my, button);
            return true;
        }
        boolean handled = false;
        for (PvWidget widget : snapshot()) {
            handled |= widget.mouseReleased(mx, my, button);
        }
        return handled;
    }

//? if >=1.21.11 {
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return mouseDragged(event.x(), event.y(), event.button(), dx, dy);
    }
//?}
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (hasOverlay()) {
            overlay.mouseDragged(mx, my, button, dx, dy);
            return true;
        }
        boolean handled = false;
        for (PvWidget widget : snapshot()) {
            handled |= widget.mouseDragged(mx, my, button, dx, dy);
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (hasOverlay()) {
            overlay.mouseScrolled(mx, my, vertical);
            return true;
        }
        for (PvWidget widget : snapshot()) {
            if (widget.mouseScrolled(mx, my, vertical)) {
                return true;
            }
        }
        return false;
    }

//? if >=1.21.11 {
    @Override
    public boolean keyPressed(KeyEvent event) {
        return keyPressed(event.key(), event.scancode(), event.modifiers());
    }
//?}
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // Escape closes the dialog, not the screen. Closing the whole panel because the player
            // wanted out of a player list would throw away the invite they were building.
            if (hasOverlay()) {
                dismissOverlay();
                return true;
            }
            onClose();
            return true;
        }
        // A modal gets the keyboard before the panel's own bindings: a text field inside a dialog
        // has to see Backspace and Left/Right.
        if (hasOverlay() && overlay.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // Screenshots are worth keeping while a rule set is on screen, and vanilla loses the F2
        // binding as soon as a screen opens.
        if (keyCode == GLFW.GLFW_KEY_F2) {
            Minecraft client = Minecraft.getInstance();
            Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(),
                    text -> client.gui.getChat().addMessage(text));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            boolean backwards = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            cycleFocus(backwards);
            return true;
        }
        if (focused != null && focused.visible && focused.enabled) {
            if (keyCode == GLFW.GLFW_KEY_SPACE) {
                focused.activate();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                focused.nudge(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                focused.nudge(1);
                return true;
            }
        }
        // Enter is the dialog convention: it confirms. Only a focused button overrides that, so a
        // slider or a toggle does not swallow the key a player expects to commit with.
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (focused instanceof PvButton button) {
                button.activate();
                return true;
            }
            if (defaultButton != null && defaultButton.visible && defaultButton.enabled) {
                defaultButton.activate();
                return true;
            }
        }
        return false;
    }

//? if >=1.21.11 {
    @Override
    public boolean charTyped(CharacterEvent event) {
//? if >=26.1 {
        // CharacterEvent carries the codepoint and nothing else in 26.1; no widget reads the
        // modifier bits of a typed character, so a zero is the honest stand-in.
        return charTyped((char) event.codepoint(), 0);
//?} else {
        return charTyped((char) event.codepoint(), event.modifiers());
//?}
    }
//?}
    public boolean charTyped(char chr, int modifiers) {
        if (hasOverlay()) {
            return overlay.charTyped(chr, modifiers);
        }
        if (focused != null && focused.visible && focused.enabled) {
            return focused.charTyped(chr, modifiers);
        }
        return false;
    }

    protected List<PvWidget> focusables() {
        List<PvWidget> out = new ArrayList<>();
        for (PvWidget widget : roots) {
            collect(widget, out);
        }
        return out;
    }

    private static void collect(PvWidget widget, List<PvWidget> out) {
        if (!widget.visible || !widget.enabled) {
            return;
        }
        if (widget instanceof PvScrollPane pane) {
            if (pane.visible) {
                for (PvWidget child : pane.children) {
                    collect(child, out);
                }
            }
            return;
        }
        if (widget.focusable()) {
            out.add(widget);
        }
    }

    protected void cycleFocus(boolean backwards) {
        List<PvWidget> list = focusables();
        if (list.isEmpty()) {
            return;
        }
        int index = list.indexOf(focused);
        int next = index < 0
                ? (backwards ? list.size() - 1 : 0)
                : Math.floorMod(index + (backwards ? -1 : 1), list.size());
        setFocus(list.get(next));
    }

    protected void setFocus(PvWidget widget) {
        if (focused != null) {
            focused.focused = false;
        }
        focused = widget;
        if (focused != null) {
            focused.focused = true;
        }
    }

    /** Drops focus when the focused control stops existing, e.g. after a tab switch. */
    protected void validateFocus() {
        if (focused != null && !focusables().contains(focused)) {
            setFocus(null);
        }
    }
}
