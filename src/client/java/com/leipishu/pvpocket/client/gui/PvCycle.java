package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.rules.RuleDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.function.IntPredicate;

/**
 * Left/right selector for a fixed list of choices.
 *
 * <p>Both arrow zones are 22px wide, the smallest comfortable target at GUI scale 1. The middle is
 * dead space, so a mis-click there does nothing instead of advancing the value.
 *
 * <p>{@link #skip} makes individual options unselectable and the arrows step over them. Only the
 * arena map picker uses it, for a map another duel already occupies. Such a value is marked with
 * colour and a 3px accent bar.
 */
public class PvCycle extends PvValueControl {

    private static final int ARROW = 22;
    /** The 24px control holds two lines of the 8px face between the arrows. */
    private static final int MAX_LINES = 2;

    private final RuleDefinition def;
    /** Values to step over, or null when every choice is available. */
    private IntPredicate skip;

    public PvCycle(RuleDefinition def, int value) {
        this.def = def;
        // Clamped: an out-of-range value (an old session, a hand-edited preset) would otherwise
        // render as the clamped choice but step from the raw one.
        this.value = def.clamp(value);
        this.width = 168;
        this.height = 24;
    }

    /** Marks values as unavailable. They stay visible if the value is already on one, in accent. */
    public PvCycle skip(IntPredicate skip) {
        this.skip = skip;
        return this;
    }

    private boolean blocked(int candidate) {
        return skip != null && skip.test(candidate);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);

        Font tr = Minecraft.getInstance().font;
        int fill = enabled
                ? PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, hoverT)
                : PvTheme.SUNKEN;
        int border = enabled
                ? PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, hoverT)
                : PvTheme.DIVIDER;
        PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS, border, fill);

        boolean overLeft = hovered && enabled && mouseX < x + ARROW;
        boolean overRight = hovered && enabled && mouseX >= x + width - ARROW;
        // The half under the pointer lifts, so the control shows what a click will do. It is drawn
        // inset by one pixel so it never covers the control's border.
        if (overLeft) {
            PvDraw.roundRect(ctx, x + 1, y + 1, ARROW, height - 2, PvTheme.RADIUS - 1,
                    PvTheme.alpha(PvTheme.CARD_ACTIVE, 0.9F));
        } else if (overRight) {
            PvDraw.roundRect(ctx, x + width - ARROW - 1, y + 1, ARROW, height - 2, PvTheme.RADIUS - 1,
                    PvTheme.alpha(PvTheme.CARD_ACTIVE, 0.9F));
        }

        int idle = enabled ? PvTheme.TEXT_MUTED : PvTheme.TEXT_MUTED;
        PvDraw.chevron(ctx, x + ARROW / 2, y + height / 2, -1, 9,
                overLeft ? PvTheme.ACCENT : idle);
        PvDraw.chevron(ctx, x + width - ARROW / 2, y + height / 2, 1, 9,
                overRight ? PvTheme.ACCENT : idle);

        boolean taken = blocked(value);
        String label = def.format(value);
        int maxLabel = width - ARROW * 2 - 8;
        int textColor = !enabled ? PvTheme.TEXT_MUTED : taken ? PvTheme.ACCENT : PvTheme.TEXT;
        // The arrows take fixed room at both ends, so the label shrinks, wraps between them and is
        // cut when two lines do not fit; 22px arrows leave 55px for the label.
        int cx = x + width / 2;
        int labelW = PvDraw.textWrapCenter(ctx, tr, label, cx, y, height, maxLabel,
                MAX_LINES, textColor);
        overflowTip(label, PvDraw.willCut(tr, label, maxLabel, MAX_LINES));

        if (taken && enabled) {
            // A 3px marker in the accent colour: the same channel as the text, so a colour-blind
            // player still sees a shape next to the label.
            PvDraw.roundRect(ctx, cx - labelW / 2 - 9, y + height / 2 - 3, 3, 6, 1, PvTheme.ACCENT);
        }

        drawFocusRing(ctx, PvTheme.RADIUS);
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible || !contains(mouseX, mouseY)) {
            return null;
        }
        if (enabled && blocked(value)) {
            return PvLang.str("pvpocket.gui.rule.map_busy_tooltip");
        }
        return overflow() != null ? overflow() : tooltip;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || !contains(mx, my)) {
            return false;
        }
        if (button == 0) {
            step(mx < x + ARROW ? -1 : 1);
            return true;
        }
        if (button == 1) {
            step(-1);
            return true;
        }
        return false;
    }

    public void step(int direction) {
        int next = def.step(value, direction);
        if (skip != null) {
            // One full lap at most: if every choice is taken there is nowhere to go, and an
            // endless loop would hang the click handler.
            int span = def.max - def.min + 1;
            for (int i = 0; i < span && blocked(next); i++) {
                next = def.step(next, direction);
            }
            if (blocked(next)) {
                return;
            }
        }
        value = next;
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2F));
        emit();
    }

    @Override
    public void activate() {
        step(1);
    }

    @Override
    public void nudge(int direction) {
        step(direction);
    }
}
