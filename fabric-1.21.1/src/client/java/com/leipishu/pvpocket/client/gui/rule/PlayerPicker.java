package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;

import java.util.List;
import java.util.UUID;

/**
 * The player picker, drawn on the screen's modal layer.
 *
 * <p>It covers the whole viewport rather than sitting inside the panel: the scrim is what says "this
 * is a question you have to answer", and a dialog that only dims its own corner reads as another
 * region of the panel instead of as a step on top of it. The screen gives it the mouse before any
 * root, so a click that lands on the scrim cannot also press the card behind it.</p>
 *
 * <p>The candidate list arrives from the server already filtered — online, not spectating, not
 * already in a duel or holding an invite. Offering a name and then refusing it is worse than never
 * offering it, and only the server knows which of those is true.</p>
 */
public class PlayerPicker extends PvWidget {

    /** Called with the picked player; the screen is responsible for closing the modal. */
    public interface Listener {
        void onPick(String name, String uuid);

        void onCancel();
    }

    private static final int PANEL_W = 260;
    private static final int ROW_H = 20;
    private static final int HEAD_H = 40;
    private static final int FOOT_H = 24;
    private static final int MAX_ROWS = 7;

    private final List<String[]> candidates;
    private final String titleKey;
    private final int teamNumber;
    private Listener listener;
    private int scroll;
    private float appearT;

    /** @param candidates {@code name|uuid} strings, in the order the server sent them */
    public PlayerPicker(List<String[]> candidates, int teamNumber) {
        this.candidates = candidates;
        this.titleKey = "pvpocket.gui.roster.picker_title";
        this.teamNumber = teamNumber;
    }

    public void listener(Listener listener) {
        this.listener = listener;
    }

    private int rowsVisible() {
        return Math.min(MAX_ROWS, Math.max(1, candidates.size()));
    }

    private int panelH() {
        return HEAD_H + rowsVisible() * ROW_H + FOOT_H;
    }

    private int panelX() {
        return (width - PANEL_W) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int maxScroll() {
        return Math.max(0, candidates.size() - MAX_ROWS);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        appearT = PvAnim.damp(appearT, 1F, d, 0.05F);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        // Darker than the screen's own backdrop: the modal has to be above a panel that is already
        // sitting on a 77% scrim, and equal darkness would leave the two reading as one surface.
        ctx.fill(0, 0, width, height, PvTheme.alpha(0xA0000000, appearT));

        int px = panelX();
        int py = panelY();
        int ph = panelH();
        PvDraw.shadow(ctx, px, py, PANEL_W, ph, PvTheme.RADIUS_PANEL);
        PvDraw.roundPanel(ctx, px, py, PANEL_W, ph, PvTheme.RADIUS_PANEL,
                PvTheme.alpha(PvTheme.BORDER_STRONG, appearT),
                PvTheme.alpha(PvTheme.PANEL, appearT));
        PvDraw.hLine(ctx, px + PvTheme.RADIUS_PANEL + 1, py + 1,
                PANEL_W - PvTheme.RADIUS_PANEL * 2 - 2, PvTheme.alpha(PvTheme.HIGHLIGHT, appearT));

        PvDraw.text(ctx, tr, PvLang.str(titleKey, teamNumber), px + 12, py + 10, PvTheme.TEXT);
        String count = PvLang.str("pvpocket.gui.roster.picker_count", candidates.size());
        PvDraw.textRight(ctx, tr, count, px + PANEL_W - 12, py + 10, PvTheme.TEXT_MUTED);
        PvDraw.hLine(ctx, px + 12, py + 26, PANEL_W - 24, PvTheme.DIVIDER);

        if (candidates.isEmpty()) {
            PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.roster.picker_empty"),
                    px + 12, py + HEAD_H, PvTheme.TEXT_MUTED);
        } else {
            int listTop = py + HEAD_H;
            int listBottom = listTop + rowsVisible() * ROW_H;
            for (int i = 0; i < rowsVisible(); i++) {
                int index = scroll + i;
                if (index >= candidates.size()) {
                    break;
                }
                String[] entry = candidates.get(index);
                int ry = listTop + i * ROW_H;
                boolean hot = mouseX >= px + 6 && mouseX < px + PANEL_W - 6
                        && mouseY >= ry && mouseY < ry + ROW_H;
                if (hot) {
                    PvDraw.roundRect(ctx, px + 6, ry, PANEL_W - 12, ROW_H - 2, 3,
                            PvTheme.CARD_HOVER);
                }
                PlayerSkinDrawer.draw(ctx, skinOf(entry[1]), px + 12, ry + (ROW_H - 14) / 2, 14);
                PvDraw.text(ctx, tr, PvDraw.fit(tr, entry[0], PANEL_W - 90),
                        px + 32, ry + (ROW_H - 8) / 2, PvTheme.TEXT);
                PvDraw.textRight(ctx, tr, PvLang.str("pvpocket.gui.roster.picker_add"),
                        px + PANEL_W - 14, ry + (ROW_H - 8) / 2,
                        hot ? PvTheme.ACCENT : PvTheme.TEXT_MUTED);
            }
            if (maxScroll() > 0) {
                // A scrollbar rather than a "more" arrow: the list is short and the wheel already
                // works, so the only thing missing is a hint that there is more below.
                int trackH = rowsVisible() * ROW_H - 4;
                int thumbH = Math.max(10, trackH * rowsVisible() / candidates.size());
                int thumbY = listTop + 2 + (trackH - thumbH) * scroll / maxScroll();
                PvDraw.roundRect(ctx, px + PANEL_W - 5, listTop + 2, 2, trackH, 1, PvTheme.SUNKEN);
                PvDraw.roundRect(ctx, px + PANEL_W - 5, thumbY, 2, thumbH, 1, PvTheme.BORDER_STRONG);
            }
            PvDraw.hLine(ctx, px + 12, py + ph - FOOT_H, PANEL_W - 24, PvTheme.DIVIDER);
        }

        String hint = PvLang.str("pvpocket.gui.roster.picker_hint");
        PvDraw.text(ctx, tr, PvDraw.fit(tr, hint, PANEL_W - 24),
                px + 12, py + ph - FOOT_H + 8, PvTheme.TEXT_MUTED);
    }

    private static SkinTextures skinOf(String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return DefaultSkinHelper.getSkinTextures(new UUID(0L, 0L));
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
                if (player.getUuid().equals(id)) {
                    return player.getSkinTextures();
                }
            }
        }
        return DefaultSkinHelper.getSkinTextures(id);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || button != 0) {
            return false;
        }
        int px = panelX();
        int py = panelY();
        if (mx < px || mx >= px + PANEL_W || my < py || my >= py + panelH()) {
            // Clicking the scrim dismisses. Consumed either way — the screen treats a modal click as
            // handled, which is what stops it reaching the card underneath.
            if (listener != null) {
                listener.onCancel();
            }
            return true;
        }
        int listTop = py + HEAD_H;
        int index = (int) ((my - listTop) / ROW_H) + scroll;
        if (my >= listTop && my < listTop + rowsVisible() * ROW_H
                && index >= 0 && index < candidates.size()) {
            String[] entry = candidates.get(index);
            if (listener != null) {
                listener.onPick(entry[0], entry[1]);
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!visible) {
            return false;
        }
        int next = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(amount)));
        if (next != scroll) {
            scroll = next;
            return true;
        }
        return false;
    }

    @Override
    public boolean focusable() {
        return false;
    }
}
