package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleDefinition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

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

    private final RuleDefinition def;
    private final float[] selT;
    private final float[] hotT;

    public PvChips(RuleDefinition def, int value) {
        this.def = def;
        this.value = def.clamp(value);
        this.height = H;
        this.width = 200;
        this.selT = new float[def.choices.length];
        this.hotT = new float[def.choices.length];
        for (int i = 0; i < selT.length; i++) {
            selT[i] = (value & (1 << i)) != 0 ? 1F : 0F;
        }
    }

    private int chipWidth() {
        int n = def.choices.length;
        return Math.max(1, (width - GAP * (n - 1)) / n);
    }

    private int chipAt(double mx) {
        int cw = chipWidth();
        if (mx < x) {
            return -1;
        }
        int index = (int) ((mx - x) / (cw + GAP));
        return index >= 0 && index < def.choices.length ? index : -1;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        tickFocus(d);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int cw = chipWidth();
        int hoverIndex = hovered && enabled ? chipAt(mouseX) : -1;

        for (int i = 0; i < def.choices.length; i++) {
            boolean on = (value & (1 << i)) != 0;
            selT[i] = PvAnim.damp(selT[i], on ? 1F : 0F, d, 0.05F);
            hotT[i] = PvAnim.damp(hotT[i], hoverIndex == i ? 1F : 0F, d, 0.09F);

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
            String label = PvDraw.fit(tr, def.choices[i], cw - 10);
            PvDraw.textCenter(ctx, tr, label, cx + cw / 2, y + (H - 8) / 2 + 1, textColor);
        }

        // The row is one tab stop, not one per chip: tabbing through five chips to reach the next
        // rule would make the panel feel like it is fighting the keyboard.
        drawFocusRing(ctx, 3);
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
        value ^= (1 << index);
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.2F));
        emit();
        return true;
    }
}
