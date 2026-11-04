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
 * labels are, and the selected state is carried by both fill and text colour so it survives a
 * colour-blind palette.</p>
 */
public class PvChips extends PvValueControl {

    private static final int GAP = 4;

    private final RuleDefinition def;

    public PvChips(RuleDefinition def, int value) {
        this.def = def;
        this.value = def.clamp(value);
        this.height = 16;
        this.width = 200;
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
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int cw = chipWidth();
        int hoverIndex = hovered ? chipAt(mouseX) : -1;

        for (int i = 0; i < def.choices.length; i++) {
            int cx = x + i * (cw + GAP);
            boolean on = (value & (1 << i)) != 0;
            boolean hot = hoverIndex == i;

            int border;
            int fill;
            int textColor;
            if (!enabled) {
                border = PvTheme.DIVIDER;
                fill = PvTheme.SURFACE_SUNKEN;
                textColor = PvTheme.TEXT_MUTED;
            } else if (on) {
                border = PvTheme.ACCENT;
                fill = hot ? PvTheme.mix(PvTheme.ACCENT_SOFT, PvTheme.ACCENT, 0.25F) : PvTheme.ACCENT_SOFT;
                textColor = PvTheme.TEXT;
            } else {
                border = hot ? PvTheme.ACCENT_DIM : PvTheme.PANEL_BORDER;
                fill = hot ? PvTheme.SURFACE_HOVER : PvTheme.SURFACE;
                textColor = PvTheme.TEXT_DIM;
            }
            PvDraw.roundPanel(ctx, cx, y, cw, height, PvTheme.RADIUS, border, fill);
            String label = PvDraw.fit(tr, def.choices[i], cw - 8);
            PvDraw.textCenter(ctx, tr, label, cx + cw / 2, y + (height - 8) / 2 + 1, textColor);
        }
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
