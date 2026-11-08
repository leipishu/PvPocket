package com.leipishu.pvpocket.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Base screen for the custom GUI layer.
 *
 * <p>Owns four things that every self-drawn screen needs and that are easy to get wrong once and
 * then wrong everywhere:</p>
 * <ol>
 *   <li><b>The backdrop.</b> A single dim layer instead of the vanilla blur + gradient, so the world
 *       behind stays legible and the panel keeps a stable contrast ratio.</li>
 *   <li><b>The entrance.</b> A short fade from black over the panel rect, not a slide and not a
 *       per-widget stagger: a moving panel would have its drawn position disagree with its hit box
 *       for the duration of the animation, and a click that lands 4px off is a bug report, not a
 *       flourish.</li>
 *   <li><b>Tooltips last.</b> They are drawn after everything else, which is the only way a tooltip
 *       belonging to a row inside a scissor-clipped scroll pane can escape the clip.</li>
 *   <li><b>Keyboard focus.</b> Tab/Shift-Tab traversal, arrows to change a value, Enter to activate,
 *       Esc to close. A self-drawn GUI that only responds to the mouse is unusable for anyone who
 *       does not use one.</li>
 * </ol>
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
     * <p>Both are the same surface, so they are drawn as one L rather than as a card beside a card.
     * The content well is then a second shape laid over the chrome, square where it meets the
     * chrome and rounded at the panel's own bottom-right corner. Zero for either dimension means
     * "this panel has no such band", which is how the match list keeps a header and no sidebar.</p>
     *
     * <p>There is deliberately no border anywhere in this arrangement. A 1px outline around a
     * floating sidebar is what made it read as a card that had been dropped into the panel; with the
     * outline gone the two surfaces have to separate by value alone, which is why
     * {@code PvTheme.SIDEBAR} sits a full step above {@code PvTheme.PANEL}.</p>
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
     * <p>Only one level deep on purpose: every screen in this mod is reachable from the home screen
     * in one step, so a stack would be machinery for a depth that does not exist. A screen pushed by
     * the server (an invite, the match list) leaves this null — closing it returns to the game,
     * because the player did not come from a menu and does not want to land in one.</p>
     */
    protected PvScreen parent;

    /**
     * Modal layer. Drawn after every root, and given the mouse before any of them.
     *
     * <p>Separate from {@link #roots} rather than appended to it because both halves of "on top"
     * have to be enforced, and the list order only gives one of them. Drawing last puts it above;
     * hit-testing first is what stops a click that lands on the modal's backdrop from also pressing
     * the button underneath it. A dialog that is merely drawn last is a dialog that fires the thing
     * behind it.</p>
     */
    protected PvWidget overlay;

    /**
     * Whether any screen currently owns the pointer with a modal layer.
     *
     * <p>Static because the widgets that need to know cannot reach the screen instance: a scroll
     * pane is constructed by whoever builds the page, long before there is a screen to ask, and it
     * re-renders itself inside its own pass rather than through the screen's root loop. The client
     * is single-threaded and only one screen renders at a time, so one flag is enough — it is
     * recomputed at the top of every frame and cleared when the screen closes.</p>
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

    protected PvScreen(Text title) {
        super(title);
    }

    /** The world keeps running behind the panel; a duel should not be pauseable by opening rules. */
    @Override
    public boolean shouldPause() {
        return false;
    }

    /**
     * Closes back to whoever opened this screen.
     *
     * <p>Overridden here rather than in each screen because the default has to be "return to where I
     * came from": a rule panel opened from the home screen that dumped the player into the world
     * would make the home screen a one-way door, and every child screen would have to remember to
     * override this to avoid it.</p>
     */
    @Override
    public void close() {
        modalPointer = false;
        MinecraftClient.getInstance().setScreen(parent);
    }

    /**
     * Clears the modal flag when the screen goes away.
     *
     * <p>Not every screen leaves through {@link #close()} — the server can push a screen over this
     * one, and the game can replace it outright — so the flag is cleared here as well. It is
     * recomputed on the next frame regardless, but between the two the flag is read by panes that
     * belong to the *outgoing* screen, and a frame of "everything is un-hoverable" is cheaper to
     * avoid than to explain.</p>
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

    protected TextRenderer font() {
        return MinecraftClient.getInstance().textRenderer;
    }

    /** Centres a panel of at most {@code maxW × maxH} inside the current GUI viewport. */
    protected void layoutPanel(int maxW, int maxH) {
        panelW = Math.min(width - 20, maxW);
        panelH = Math.min(height - 20, maxH);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    protected void drawBackdrop(DrawContext ctx) {
        ctx.fill(0, 0, width, height, PvTheme.alpha(PvTheme.SCRIM, openProgress()));
    }

    protected void drawPanelFrame(DrawContext ctx) {
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

    /** 0 at the frame the screen opens, 1 once the entrance has finished. */
    private float openProgress() {
        return PvAnim.easeOutCubic(openT / OPEN_TIME);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        cursorX = mouseX;
        cursorY = mouseY;
        // Recomputed every frame rather than tracked at the call sites: setOverlay/dismissOverlay
        // are only two of the ways the layer changes (a dialog can hide itself), and a stale true
        // would leave every scroll pane in the mod permanently un-hoverable.
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
        // Everything drawn from here to the modal layer is one surface: the panel's own text and
        // every root widget are allowed to collide with each other in the audit, and nothing else
        // is compared against them.
        PvAudit.layer(PvAudit.CHROME_LAYER);
        // The pointer as far as the panel is concerned. A modal is a scrim: nothing beneath it is
        // under the cursor, and "nothing" has to be expressed as a coordinate outside every widget,
        // because plenty of widgets work out their own hover from the numbers handed to render()
        // rather than from updateHover() — a card that highlights its row, a stepper that lights the
        // cap under the pointer, a text field that places its caret. Clearing updateHover() alone
        // left all of those lit behind an open dialog.
        int px = overlay == null ? mouseX : -1;
        int py = overlay == null ? mouseY : -1;
        drawContent(ctx, px, py, delta);

        // Item icons live at z = 150 in a depth-tested layer, so a scrim at z = 0 can never cover
        // them (see PvDraw.itemsVisible). While a modal is up the panel underneath is dimmed, and
        // dimmed-with-icons-poking-through is worse than dimmed-and-flat.
        PvDraw.itemsVisible = overlay == null;
        for (PvWidget widget : roots) {
            // Cleared, not frozen. A widget left glowing from the frame before the dialog opened is
            // a widget the player thinks they can still press; freezing the previous state was the
            // same mistake in slower motion.
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
     * <p>Called at the end of {@link #render} on purpose: the scroll panes lay their children out
     * inside their own render pass, so anything measured earlier would be measuring last frame's
     * scroll offset. Off unless the audit harness is running.</p>
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
     * @param laidOut whether this widget was actually rendered this frame. A scroll pane skips the
     *                rows outside its viewport, and a skipped row never positions its inline control
     *                — so descending into one measures a control still sitting at 0,0 at its
     *                constructor size, which reads as "the control escapes the row" five times over.
     *                The row's own box is still measured: an off-screen row that overlaps its
     *                neighbour is a layout bug worth knowing about.
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
        // A scroll pane's children leave the viewport by design — that is what scrolling is — so
        // they are measured against the pane's rect for collisions but not for containment, and
        // they are a surface of their own: a row half-scrolled under the status line is clipped,
        // not overlapping it.
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
     * <p>A screen that only ever audits its opening state hides most of itself: the rule panel has
     * eight pages and two dialogs, and every one of them is a separate arrangement of the same
     * widgets. {@link PvAuditHarness} asks for this count and then calls {@link #auditVariant} for
     * each index, so a page that overlaps something gets reported without a human clicking to it.</p>
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
     * <p>The layer is always the full viewport — every dialog so far centres itself in it and draws
     * its own scrim — so the size is assigned here instead of at each of the call sites, where it
     * would also have to be repeated in {@code init()} to survive a window resize.</p>
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
     * <p>Chosen over animating the alpha of every colour in the panel: a single rounded fill is one
     * draw call, it cannot desynchronise from the widgets underneath, and on a near-black panel the
     * result is indistinguishable from a real cross-fade.</p>
     */
    private void drawEntrance(DrawContext ctx) {
        float k = openProgress();
        if (k >= 1F) {
            return;
        }
        int alpha = Math.round(255F * (1F - k));
        PvDraw.roundRect(ctx, panelX, panelY, panelW, panelH, PvTheme.RADIUS_PANEL,
                alpha << 24);
    }

    /** Hook for the static parts of the panel: title, dividers, footer labels. */
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
    }

    /**
     * Tooltip with a dwell delay and a fade.
     *
     * <p>The delay is what stops the tooltip from strobing while the pointer crosses a column of
     * rows on the way somewhere else; the fade keeps it from appearing as a hard-edged rectangle
     * over a dark panel.</p>
     */
    private void drawTooltip(DrawContext ctx, float d) {
        String tip = null;
        if (hasOverlay()) {
            // The modal is the only thing under the pointer. Walking the roots as well was how a
            // background row's hint appeared on top of an open dialog — the overlay's own tooltip
            // was null, so the loop below found one behind the scrim.
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

        TextRenderer tr = font();
        List<String> lines = PvDraw.wrap(tr, tip, 176);
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, tr.getWidth(line));
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
     * <p>Every input handler below walks this instead of {@link #roots}, because a handler is
     * allowed to change the screen: a button that rebuilds the pane, a card that adds or removes a
     * side, "还原" which tears the whole layout down and runs {@code init()} again. Iterating the
     * live list while that happens throws {@code ConcurrentModificationException} and takes the
     * client down — which is exactly what pressing "+ 添加队伍" used to do.</p>
     */
    private List<PvWidget> snapshot() {
        return new ArrayList<>(roots);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // The modal gets the click first and consumes it whether or not it handled anything: a click
        // on the scrim is "dismiss", not "dismiss and also press whatever was behind the scrim".
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

    @Override
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

    @Override
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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // Escape closes the dialog, not the screen. Closing the whole panel because the player
            // wanted out of a player list would throw away the invite they were building.
            if (hasOverlay()) {
                dismissOverlay();
                return true;
            }
            close();
            return true;
        }
        // A modal gets the keyboard before the panel's own bindings: a text field inside a dialog
        // has to see Backspace and Left/Right, which the screen would otherwise consume as focus
        // traversal and value nudging.
        if (hasOverlay() && overlay.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // Screenshots are worth keeping while a rule set is on screen — players compare loadouts
        // and rules this way. Vanilla loses the F2 binding as soon as a screen opens, so the panel
        // re-implements the one line of it that matters instead of inheriting that gap.
        if (keyCode == GLFW.GLFW_KEY_F2) {
            MinecraftClient client = MinecraftClient.getInstance();
            ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(),
                    text -> client.inGameHud.getChatHud().addMessage(text));
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

    @Override
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
