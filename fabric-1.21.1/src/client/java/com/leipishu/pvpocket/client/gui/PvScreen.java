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
 * <p>Owns three things that every self-drawn screen needs and that are easy to get wrong once and
 * then wrong everywhere:</p>
 * <ol>
 *   <li><b>The backdrop.</b> A single dim layer instead of the vanilla blur + gradient, so the world
 *       behind stays legible and the panel keeps a stable contrast ratio.</li>
 *   <li><b>Tooltips last.</b> They are drawn after everything else, which is the only way a tooltip
 *       belonging to a row inside a scissor-clipped scroll pane can escape the clip.</li>
 *   <li><b>Keyboard focus.</b> Tab/Shift-Tab traversal, arrows to change a value, Enter to activate,
 *       Esc to close. A self-drawn GUI that only responds to the mouse is unusable for anyone who
 *       does not use one.</li>
 * </ol>
 */
public abstract class PvScreen extends Screen {

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
        ctx.fill(0, 0, width, height, 0x99000000);
    }

    protected void drawPanelFrame(DrawContext ctx) {
        PvDraw.shadow(ctx, panelX, panelY, panelW, panelH, PvTheme.RADIUS, 6);
        PvDraw.roundPanel(ctx, panelX, panelY, panelW, panelH, PvTheme.RADIUS,
                PvTheme.PANEL_BORDER, PvTheme.PANEL_BG);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        cursorX = mouseX;
        cursorY = mouseY;

        drawBackdrop(ctx);
        drawPanelFrame(ctx);
        drawContent(ctx, mouseX, mouseY, delta);

        for (PvWidget widget : roots) {
            widget.updateHover(mouseX, mouseY);
            widget.render(ctx, mouseX, mouseY, delta);
        }
        drawTooltip(ctx);
    }

    /** Hook for the static parts of the panel: title, dividers, footer labels. */
    protected void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
    }

    private void drawTooltip(DrawContext ctx) {
        String tip = null;
        for (PvWidget widget : roots) {
            tip = widget.tooltipAt(cursorX, cursorY);
            if (tip != null) {
                break;
            }
        }
        if (tip == null) {
            return;
        }
        TextRenderer tr = font();
        List<String> lines = PvDraw.wrap(tr, tip, 176);
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, tr.getWidth(line));
        }
        int boxW = textW + 14;
        int boxH = lines.size() * 10 + 8;
        int tx = Math.max(2, Math.min(cursorX + 10, width - boxW - 2));
        int ty = Math.max(2, Math.min(cursorY + 12, height - boxH - 2));

        PvDraw.shadow(ctx, tx, ty, boxW, boxH, PvTheme.RADIUS, 3);
        PvDraw.roundPanel(ctx, tx, ty, boxW, boxH, PvTheme.RADIUS, PvTheme.ACCENT_DIM, 0xF2101319);
        for (int i = 0; i < lines.size(); i++) {
            PvDraw.text(ctx, tr, lines.get(i), tx + 7, ty + 5 + i * 10, PvTheme.TEXT_DIM);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (PvWidget widget : roots) {
            if (widget.visible && widget.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = false;
        for (PvWidget widget : roots) {
            handled |= widget.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        boolean handled = false;
        for (PvWidget widget : roots) {
            handled |= widget.mouseDragged(mx, my, button, dx, dy);
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
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
