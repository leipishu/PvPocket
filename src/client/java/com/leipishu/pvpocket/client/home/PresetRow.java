package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.preset.Preset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * One saved preset in the preset list: name, saved time, a start action and a more action.
 *
 * <p>The two actions are hit rectangles inside the row, not child widgets, because a scroll pane
 * hands every child the full pane width and stacks children vertically.
 *
 * <p>The row itself is a widget, so the audit measures its box and every string it draws.
 */
public class PresetRow extends PvWidget {

    private static final int H = 36;
    /**
     * Action widths sized to their words: 44 and 36 leave the text lines 10px more room in a 174px
     * row than 48 and 40 do. "Start" is 34px wide and "More" is 30px.
     */
    private static final int BTN_W = 44;
    private static final int BTN_H = 20;
    private static final int MENU_W = 36;

    /** What the row asks the screen to do. Two verbs, so an interface beats two runnables. */
    public interface Listener {
        void onStart(Preset preset);

        void onMenu(Preset preset);
    }

    private final Preset preset;
    private final Listener listener;
    private float hotT;

    public PresetRow(Preset preset, Listener listener) {
        this.preset = preset;
        this.listener = listener;
        this.height = H;
    }

    private int startX() {
        return x + width - 10 - MENU_W - 4 - BTN_W;
    }

    private int menuX() {
        return x + width - 10 - MENU_W;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        hotT = PvAnim.damp(hotT, hovered ? 1F : 0F, d, 0.09F);
        Font tr = Minecraft.getInstance().font;

        int h = H - 4;
        PvDraw.roundPanel(ctx, x, y, width, h, PvTheme.RADIUS,
                PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, hotT),
                PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, hotT));

        int textX = x + 10;
        int line1 = y + 5;
        int line2 = y + 18;
        int textRoom = startX() - 6 - textX;
        // The preset name is typed by the player, so its length is unknown. Both lines shrink
        // before they are cut, and a cut name is shown in full on hover.
        PvDraw.textFitLeft(ctx, tr, preset.name(), textX, line1, textRoom, PvTheme.TEXT);
        PvDraw.textFitLeft(ctx, tr,
                PvLang.str("pvpocket.gui.home.preset.saved", HomeText.relative(preset.savedAt())),
                textX, line2, textRoom, PvTheme.TEXT_MUTED);
        overflowTip(preset.name(), PvDraw.willCut(tr, preset.name(), textRoom, 1));

        boolean hotStart = inRect(mouseX, mouseY, startX(), y + (h - BTN_H) / 2, BTN_W, BTN_H);
        PvDraw.roundPanel(ctx, startX(), y + (h - BTN_H) / 2, BTN_W, BTN_H, PvTheme.RADIUS,
                hotStart ? PvTheme.BORDER_STRONG : PvTheme.BORDER,
                hotStart ? PvTheme.CARD_HOVER : PvTheme.CARD);
        PvDraw.textCenter(ctx, tr, PvLang.str("pvpocket.gui.home.preset.start"),
                startX() + BTN_W / 2, y + (h - BTN_H) / 2 + (BTN_H - 8) / 2 + 1, PvTheme.TEXT);

        boolean hotMenu = inRect(mouseX, mouseY, menuX(), y + (h - BTN_H) / 2, MENU_W, BTN_H);
        PvDraw.textCenter(ctx, tr, PvLang.str("pvpocket.gui.home.preset.more"),
                menuX() + MENU_W / 2, y + (h - BTN_H) / 2 + (BTN_H - 8) / 2 + 1,
                hotMenu ? PvTheme.TEXT : PvTheme.TEXT_MUTED);
    }

    private static boolean inRect(int mx, int my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || button != 0 || !contains(mx, my)) {
            return false;
        }
        int h = H - 4;
        int by = y + (h - BTN_H) / 2;
        if (inRect((int) mx, (int) my, startX(), by, BTN_W, BTN_H)) {
            click();
            listener.onStart(preset);
            return true;
        }
        if (inRect((int) mx, (int) my, menuX(), by, MENU_W, BTN_H)) {
            click();
            listener.onMenu(preset);
            return true;
        }
        return false;
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.1F));
    }
}
