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
 * <p>For rules whose value is a set rather than a scalar: win conditions, environmental hazards,
 * the block blacklist. Equal widths keep the row on the 8px grid whatever the label lengths.
 *
 * <p>Selection is carried by fill and text colour, and each chip crossfades independently over
 * 120ms.
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
     * <p>Used by the hazard rule, where "none" means the absence of the other three. A set that
     * lights "none" and a hazard at once is refused here, so the display cannot disagree with the
     * value.
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

            // No border: a tinted fill with a tinted 1px outline is two amber edges on a 22px tag.
            // The fill and the label colour carry the state on their own.
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
            // Equal-width chips cannot grow, so the label shrinks, wraps onto a second line and is
            // cut when two lines do not fit; "Respawn anchors" is 85px on one line.
            PvDraw.textWrapCenter(ctx, tr, def.choiceLabel(i), cx + cw / 2, y, H, cw - 10,
                    MAX_LINES, textColor);
        }

        // The row is one tab stop, not one per chip: five chips before the next rule would make
        // keyboard navigation slow.
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
        // one was cut.
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
            // Clearing the last ordinary chip means the same as the exclusive chip, so the
            // exclusive one is set instead of leaving the row dark.
            if (exclusive != 0 && (value & ~exclusive) == 0) {
                value = exclusive;
            }
        } else {
            // The exclusive chip cannot be cleared on its own: it already means "none of the
            // others".
            return true;
        }
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2F));
        emit();
        return true;
    }
}
