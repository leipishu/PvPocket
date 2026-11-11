package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.rules.RuleDefinition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.function.IntPredicate;

/**
 * Left/right selector for a fixed list of choices.
 *
 * <p>Chosen over a dropdown on purpose: a dropdown needs an overlay layer, an open/closed state and
 * click-outside handling, and in a panel that is itself already modal it reads as a nested dialog.
 * Arrows keep every option one click away and keep the panel flat.</p>
 *
 * <p>Both arrow zones are 22px wide, which is the smallest target that is still comfortable at GUI
 * scale 1. The middle is dead space rather than a third action, so a mis-click does nothing instead
 * of silently advancing the value.</p>
 *
 * <p>An option can be made unselectable with {@link #skip}. The arrows step <em>over</em> it rather
 * than landing on it and refusing, because a control that stops responding on the way to a valid
 * choice reads as broken. Only the arena map picker uses this: a map another duel is standing on
 * cannot host a second one.</p>
 *
 * <p>When the current value is one of those, the control says so with colour and a marker rather
 * than with a suffix. "倒计时判负（使用中）" was 40% longer than the label it decorated, and the
 * middle of a 168px control is exactly where a long label gets truncated.</p>
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
        // Clamped like PvChips does. Without it an out-of-range value (an old session whose map
        // index no longer exists, a hand-edited preset) renders as the clamped choice but steps
        // from the raw one, so the first click jumps somewhere unrelated to what was on screen.
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
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickHover(d);
        tickFocus(d);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int fill = enabled
                ? PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, hoverT)
                : PvTheme.SUNKEN;
        int border = enabled
                ? PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, hoverT)
                : PvTheme.DIVIDER;
        PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS, border, fill);

        boolean overLeft = hovered && enabled && mouseX < x + ARROW;
        boolean overRight = hovered && enabled && mouseX >= x + width - ARROW;
        // The half under the pointer lifts, so the control answers "what will this click do" before
        // the click happens. It is drawn inset by one pixel so it never covers the control's border.
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
        // The arrows take fixed room at both ends, so the label has to give way: shrunk first, then
        // wrapped between them, and cut only when two lines at the floor are not enough. A 107px
        // control with 22px arrows leaves 55px for the label, which is "Golden / carrot" rather
        // than "Golden car…".
        int cx = x + width / 2;
        int labelW = PvDraw.textWrapCenter(ctx, tr, label, cx, y, height, maxLabel,
                MAX_LINES, textColor);
        overflowTip(label, PvDraw.willCut(tr, label, maxLabel, MAX_LINES));

        if (taken && enabled) {
            // A 3px marker in the accent colour: the same channel the text uses, so a colour-blind
            // player still sees a shape where the word "使用中" used to be.
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
            // One full lap at most: if every choice is taken there is nowhere to go, and wrapping
            // forever would be an infinite loop inside a click handler.
            int span = def.max - def.min + 1;
            for (int i = 0; i < span && blocked(next); i++) {
                next = def.step(next, direction);
            }
            if (blocked(next)) {
                return;
            }
        }
        value = next;
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.2F));
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
