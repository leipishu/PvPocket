package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleCatalog;
import com.leipishu.pvpocket.rules.RuleDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Multi-select control rendered as a row of equal-width chips.
 *
 * <p>Used for the rules whose value is a set rather than a scalar — win conditions, environmental
 * hazards, the block blacklist. Equal widths keep the row on the 8px grid regardless of how long the
 * labels are.</p>
 *
 * <p>Selection is carried by fill, border and text colour at once, and each chip crossfades
 * independently. That is not decoration: with three chips on screen, a chip that changes state on
 * the same frame as its neighbour is easy to miss, and a 120ms fade draws the eye to the one that
 * moved.</p>
 */
public class PvChips extends PvValueControl {

    private static final int GAP = 4;
    private static final int H = 22;
    /** A 22px chip holds two lines of the 8px face; a third would need a taller row. */
    private static final int MAX_LINES = 2;

    private final RuleDefinition def;
    /** Chips that stay on no matter what; see {@link RuleCatalog#lockedBits}. */
    private final int locked;
    /**
     * Chips that exclude every other chip; see {@link RuleCatalog#exclusiveBits}.
     *
     * <p>{@code e.hazard} is the only user: 无 is not a fourth hazard, it is the absence of the other
     * three. Without this mask the row can sit in "无 and 岩浆 both lit", which the reader resolves by
     * letting 无 win — so the panel shows a hazard that is switched off. The state is refused here
     * because the widget is the only place it can be refused without the display disagreeing with
     * the behaviour.</p>
     */
    private final int exclusive;
    private final float[] selT;
    private final float[] hotT;

    public PvChips(RuleDefinition def, int value) {
        this.def = def;
        this.value = def.clamp(value);
        this.locked = RuleCatalog.lockedBits(def.id);
        this.exclusive = RuleCatalog.exclusiveBits(def.id);
        this.height = H;
        this.width = 200;
        this.selT = new float[def.choiceCount()];
        this.hotT = new float[def.choiceCount()];
        for (int i = 0; i < selT.length; i++) {
            selT[i] = (value & (1 << i)) != 0 ? 1F : 0F;
        }
    }

    private int chipWidth() {
        int n = def.choiceCount();
        return Math.max(1, (width - GAP * (n - 1)) / n);
    }

    private int chipAt(double mx) {
        int cw = chipWidth();
        if (mx < x) {
            return -1;
        }
        int index = (int) ((mx - x) / (cw + GAP));
        return index >= 0 && index < def.choiceCount() ? index : -1;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickFocus(d);
        Font tr = Minecraft.getInstance().font;
        int cw = chipWidth();
        int hoverIndex = hovered && enabled ? chipAt(mouseX) : -1;

        for (int i = 0; i < def.choiceCount(); i++) {
            boolean on = (value & (1 << i)) != 0;
            boolean lockedChip = (locked & (1 << i)) != 0;
            selT[i] = PvAnim.damp(selT[i], on ? 1F : 0F, d, 0.05F);
            // A locked chip never lights up on hover: it cannot be pressed, and a highlight would
            // promise otherwise.
            hotT[i] = PvAnim.damp(hotT[i], hoverIndex == i && !lockedChip ? 1F : 0F, d, 0.09F);

            int cx = x + i * (cw + GAP);
            float sel = selT[i];
            float hot = hotT[i];

            // No border. A tinted fill with a tinted 1px outline is two amber edges on a 22px tag,
            // and at that size the outline reads as a darker bar above and below the label rather
            // than as an edge. The fill and the label colour carry the state on their own.
            int fill = PvTheme.blend(
                    PvTheme.mix(PvTheme.CARD_HOVER, PvTheme.CARD_ACTIVE, hot),
                    PvTheme.mix(PvTheme.ACCENT_SOFT, PvTheme.alpha(PvTheme.ACCENT, 0.30F), hot),
                    sel);
            int textColor = PvTheme.mix(PvTheme.mix(PvTheme.TEXT_DIM, PvTheme.TEXT, hot),
                    PvTheme.ACCENT, sel);
            if (!enabled) {
                fill = PvTheme.SUNKEN;
                textColor = PvTheme.TEXT_MUTED;
            }

            PvDraw.roundRect(ctx, cx, y, cw, H, 3, fill);
            // Equal-width chips cannot grow for a longer word, so the label gives way instead:
            // shrunk first, then wrapped onto a second line, and cut only when even two lines at the
            // floor are not enough. "Respawn anchors" is 85px on one line and 33px per line as
            // "Respawn / anchors", which is the difference between a 45px chip and a truncated one.
            PvDraw.textWrapCenter(ctx, tr, def.choiceLabel(i), cx + cw / 2, y, H, cw - 10,
                    MAX_LINES, textColor);
        }

        // The row is one tab stop, not one per chip: tabbing through five chips to reach the next
        // rule would make the panel feel like it is fighting the keyboard.
        drawFocusRing(ctx, 3);
    }

    @Override
    public String tooltipAt(int mouseX, int mouseY) {
        if (!visible || !contains(mouseX, mouseY)) {
            return null;
        }
        int index = chipAt(mouseX);
        if (index < 0) {
            return null;
        }
        // Per chip, not per row: the tooltip names the one under the pointer, and only when that
        // one had to be cut.
        String label = def.choiceLabel(index);
        Font tr = Minecraft.getInstance().font;
        return PvDraw.willCut(tr, label, chipWidth() - 10, MAX_LINES) ? label : null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled || button != 0 || !contains(mx, my)) {
            return false;
        }
        int index = chipAt(mx);
        if (index < 0) {
            return false;
        }
        if ((locked & (1 << index)) != 0) {
            // Consumed but ignored, so the click cannot fall through to the row behind it and the
            // chip still feels like a control that simply refuses.
            return true;
        }
        int bit = 1 << index;
        boolean turningOn = (value & bit) == 0;
        if (turningOn) {
            // Turning an exclusive chip on clears the rest; turning anything else on clears the
            // exclusive one. Either way the row leaves with at most one of the two groups lit.
            value = (exclusive & bit) != 0 ? bit : (value & ~exclusive) | bit;
        } else if ((exclusive & bit) == 0) {
            value &= ~bit;
            // Clearing the last ordinary chip is the same state as the exclusive chip, so the
            // exclusive one is set instead of leaving the row dark. Without this the same meaning
            // has two spellings — 0 and 1 — and the reader cannot tell which one they are looking
            // at.
            if (exclusive != 0 && (value & ~exclusive) == 0) {
                value = exclusive;
            }
        } else {
            // The exclusive chip cannot be cleared on its own: it already means "none of the
            // others", and a dark 无 next to three dark chips would be the fourth spelling of the
            // same state.
            return true;
        }
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2F));
        emit();
        return true;
    }
}
