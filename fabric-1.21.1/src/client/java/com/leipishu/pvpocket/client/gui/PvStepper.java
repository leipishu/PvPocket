package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleDefinition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.function.IntFunction;

/**
 * Numeric stepper: {@code [−]  3 局  [+]}.
 *
 * <p>Chosen over the slider for the two series numbers because both are small, exact counts that a
 * player thinks about as numbers — "7 rounds, first to 4" — and a slider that spans 1..15 makes
 * landing on exactly 4 a matter of pixel aim. A stepper can only produce values on the grid, so
 * there is no snapping step that can disagree with where the handle sits.</p>
 *
 * <p>The whole widget is a hit target for the arrows' row, but only the two end caps change the
 * value: clicking the middle does nothing rather than guessing which way the player meant.</p>
 *
 * <p>Two constructors, one shape: a rule's own bounds and unit, or a raw range with no unit for a
 * client-side setting. The second exists so that a preference that never travels to the server does
 * not have to pretend to be a rule to get a stepper.</p>
 */
public class PvStepper extends PvValueControl {

    private static final int CAP = 16;
    /** Room for the readout between the two caps — {@code "15 局"} fits, {@code "15 rounds"} just. */
    private static final int READOUT = 64;
    private static final int WIDTH = 104;

    private final int min;
    private final int max;
    private final int stepSize;
    private final IntFunction<String> format;
    /** Which cap the pointer is over: -1 none, 0 minus, 1 plus. */
    private int hotCap = -1;

    public PvStepper(RuleDefinition def, int value) {
        this(def.min, def.max, Math.max(1, def.step), def::format, def.clamp(value));
    }

    /**
     * A stepper for a plain integer setting that is not a rule.
     *
     * <p>Same widget rather than a second copy of the cap drawing: the two differ only in where the
     * bounds and the readout text come from, and a duplicated control is a control that gets the
     * next visual fix in one place and not the other. Client preferences (the history page size)
     * are the first caller — they have no {@link RuleDefinition} because they never travel to the
     * server.</p>
     */
    public PvStepper(int min, int max, int step, int value) {
        this(min, max, step, Integer::toString, value);
    }

    private PvStepper(int min, int max, int step, IntFunction<String> format, int value) {
        this.min = min;
        this.max = max;
        this.stepSize = Math.max(1, step);
        this.format = format;
        this.value = Math.max(min, Math.min(max, value));
        this.width = WIDTH;
        this.height = 20;
    }

    private int minusX() {
        return x;
    }

    private int plusX() {
        return x + width - CAP;
    }

    private int capY() {
        return y + (height - CAP) / 2;
    }

    private int capAt(double mx, double my) {
        if (my < capY() || my >= capY() + CAP) {
            return -1;
        }
        if (mx >= minusX() && mx < minusX() + CAP) {
            return 0;
        }
        if (mx >= plusX() && mx < plusX() + CAP) {
            return 1;
        }
        return -1;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        hotCap = enabled && contains(mouseX, mouseY) ? capAt(mouseX, mouseY) : -1;

        drawCap(ctx, minusX(), hotCap == 0, false);
        drawCap(ctx, plusX(), hotCap == 1, true);

        String readout = format.apply(value);
        int centerX = x + width / 2;
        int color = !enabled ? PvTheme.TEXT_MUTED
                : focused ? PvTheme.ACCENT
                : PvTheme.mix(PvTheme.TEXT, 0xFFFFFFFF, hoverT * 0.5F);
        // The readout sits between two fixed caps, so a long unit ("15 rounds" against "15 局")
        // shrinks rather than running under them.
        PvDraw.textFitCenter(ctx, tr, readout, centerX, y + (height - 8) / 2 + 1, READOUT, color);

        drawFocusRing(ctx, PvTheme.RADIUS);
    }

    private void drawCap(DrawContext ctx, int capX, boolean hot, boolean plus) {
        int cy = capY();
        int bg = !enabled ? PvTheme.SUNKEN
                : hot ? PvTheme.CARD_ACTIVE
                : PvTheme.CARD;
        PvDraw.roundRect(ctx, capX, cy, CAP, CAP, PvTheme.RADIUS, bg);
        // Glyphs are drawn rather than typed: the panel uses no symbol characters anywhere, so a
        // font that lacks them degrades to a box rather than to a wrong-looking button.
        int glyph = enabled ? PvTheme.TEXT : PvTheme.TEXT_MUTED;
        int mid = cy + CAP / 2;
        // Filled rects, not a zero-radius roundRect: the rounded path splits the shape into one row
        // per pixel and a 1px-tall shape falls out of it, so the glyph would simply not appear.
        ctx.fill(capX + 5, mid, capX + CAP - 5, mid + 1, glyph);
        if (plus) {
            ctx.fill(capX + CAP / 2, cy + 5, capX + CAP / 2 + 1, cy + CAP - 5, glyph);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        int cap = capAt(mx, my);
        if (cap < 0) {
            return true;
        }
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        step(cap == 1 ? 1 : -1);
        return true;
    }

    @Override
    public void nudge(int direction) {
        if (enabled) {
            step(direction);
        }
    }

    private void step(int direction) {
        int next = Math.max(min, Math.min(max, value + direction * stepSize));
        if (next != value) {
            value = next;
            emit();
        }
    }
}
