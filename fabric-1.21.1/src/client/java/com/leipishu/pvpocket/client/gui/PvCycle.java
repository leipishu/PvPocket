package com.leipishu.pvpocket.client.gui;

import com.leipishu.pvpocket.rules.RuleDefinition;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/**
 * Left/right selector for a fixed list of choices.
 *
 * <p>Chosen over a dropdown on purpose: a dropdown needs an overlay layer, an open/closed state and
 * click-outside handling, and in a panel that is itself already modal it reads as a nested dialog.
 * Arrows keep every option one click away and keep the panel flat.</p>
 *
 * <p>Both arrow zones are 20px wide, which is the smallest target that is still comfortable at GUI
 * scale 1. The middle is dead space rather than a third action, so a mis-click does nothing instead
 * of silently advancing the value.</p>
 */
public class PvCycle extends PvValueControl {

    private static final int ARROW = 20;

    private final RuleDefinition def;

    public PvCycle(RuleDefinition def, int value) {
        this.def = def;
        this.value = value;
        this.width = 132;
        this.height = 18;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int border = !enabled ? PvTheme.DIVIDER
                : hovered ? PvTheme.ACCENT_DIM
                : PvTheme.PANEL_BORDER;
        int fill = !enabled ? PvTheme.SURFACE_SUNKEN
                : hovered ? PvTheme.SURFACE_HOVER
                : PvTheme.SURFACE;
        PvDraw.roundPanel(ctx, x, y, width, height, PvTheme.RADIUS, border, fill);

        boolean overLeft = hovered && mouseX < x + ARROW;
        boolean overRight = hovered && mouseX >= x + width - ARROW;
        int arrowIdle = enabled ? PvTheme.TEXT_DIM : PvTheme.TEXT_MUTED;
        PvDraw.textCenter(ctx, tr, "◀", x + ARROW / 2, y + (height - 8) / 2 + 1,
                overLeft ? PvTheme.ACCENT : arrowIdle);
        PvDraw.textCenter(ctx, tr, "▶", x + width - ARROW / 2, y + (height - 8) / 2 + 1,
                overRight ? PvTheme.ACCENT : arrowIdle);

        String label = def.format(value);
        int textColor = enabled ? PvTheme.TEXT : PvTheme.TEXT_MUTED;
        int maxLabel = width - ARROW * 2 - 4;
        PvDraw.textCenter(ctx, tr, PvDraw.fit(tr, label, maxLabel), x + width / 2,
                y + (height - 8) / 2 + 1, textColor);

        if (focused && enabled) {
            PvDraw.roundPanel(ctx, x - 1, y - 1, width + 2, height + 2, PvTheme.RADIUS + 1,
                    PvTheme.ACCENT, fill);
        }
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
        value = def.step(value, direction);
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
