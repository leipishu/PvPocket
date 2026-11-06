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

    protected PvWidget focused;
    /** Activated by Enter when focus is not on a button. The panel's "yes, do it" action. */
    protected PvButton defaultButton;
    protected int cursorX;
    protected int cursorY;

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
        PvDraw.roundPanel(ctx, panelX, panelY, panelW, panelH, PvTheme.RADIUS_PANEL,
                PvTheme.BORDER, PvTheme.PANEL);
        // The only light source in the system: one hairline along the inside of the top edge. It is
        // what separates a black panel from a hole in the screen.
        PvDraw.hLine(ctx, panelX + PvTheme.RADIUS_PANEL + 1, panelY + 1,
                panelW - PvTheme.RADIUS_PANEL * 2 - 2, PvTheme.HIGHLIGHT);
    }

    /** 0 at the frame the screen opens, 1 once the entrance has finished. */
    private float openProgress() {
        return PvAnim.easeOutCubic(openT / OPEN_TIME);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        cursorX = mouseX;
        cursorY = mouseY;
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
        drawContent(ctx, mouseX, mouseY, delta);

        for (PvWidget widget : roots) {
            widget.updateHover(mouseX, mouseY);
            widget.render(ctx, mouseX, mouseY, delta);
        }
        if (overlay != null) {
            // Roots keep their hover state frozen while a modal is up: a button glowing underneath a
            // dialog is a button the player thinks they can press.
            overlay.updateHover(mouseX, mouseY);
            overlay.render(ctx, mouseX, mouseY, delta);
        }
        drawEntrance(ctx);
        drawTooltip(ctx, d);
    }

    /** Puts a widget on the modal layer, or clears it with {@code null}. */
    protected void setOverlay(PvWidget widget) {
        overlay = widget;
        if (widget != null) {
            setFocus(null);
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
            tip = overlay.tooltipAt(cursorX, cursorY);
        }
        for (PvWidget widget : roots) {
            if (tip != null) {
                break;
            }
            tip = widget.tooltipAt(cursorX, cursorY);
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
        for (int i = 0; i < lines.size(); i++) {
            PvDraw.text(ctx, tr, lines.get(i), tx + 8, ty + 6 + i * 10,
                    PvTheme.alpha(PvTheme.TEXT_DIM, tipT));
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // The modal gets the click first and consumes it whether or not it handled anything: a click
        // on the scrim is "dismiss", not "dismiss and also press whatever was behind the scrim".
        if (hasOverlay()) {
            overlay.mouseClicked(mx, my, button);
            return true;
        }
        for (PvWidget widget : roots) {
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
        for (PvWidget widget : roots) {
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
        for (PvWidget widget : roots) {
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
        for (PvWidget widget : roots) {
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
                setOverlay(null);
                return true;
            }
            close();
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
