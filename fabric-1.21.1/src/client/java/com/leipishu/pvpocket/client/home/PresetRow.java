package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.preset.Preset;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/**
 * One saved preset in the preset list.
 *
 * <p>The two actions are hit rectangles inside the row rather than child widgets. A scroll pane hands
 * every child the full pane width and stacks children vertically, so a 48px button cannot be a sibling
 * of the row it sits on; and a row that owns its own hit regions is the pattern {@code RuleRow} already
 * established for chips. The row itself is a widget, so the audit still measures its box and every
 * string it draws.</p>
 */
public class PresetRow extends PvWidget {

    private static final int H = 36;
    private static final int BTN_W = 48;
    private static final int BTN_H = 20;
    private static final int MENU_W = 40;

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
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        hotT = PvAnim.damp(hotT, hovered ? 1F : 0F, d, 0.09F);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        int h = H - 4;
        PvDraw.roundPanel(ctx, x, y, width, h, PvTheme.RADIUS,
                PvTheme.mix(PvTheme.BORDER, PvTheme.BORDER_STRONG, hotT),
                PvTheme.mix(PvTheme.CARD, PvTheme.CARD_HOVER, hotT));

        int textX = x + 10;
        int line1 = y + 5;
        int line2 = y + 18;
        int textRoom = startX() - 8 - textX;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, preset.name(), textRoom), textX, line1, PvTheme.TEXT);
        PvDraw.text(ctx, tr,
                PvDraw.fit(tr, PvLang.str("pvpocket.gui.home.preset.saved", HomeText.relative(preset.savedAt())),
                        textRoom),
                textX, line2, PvTheme.TEXT_MUTED);

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
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.1F));
    }
}
