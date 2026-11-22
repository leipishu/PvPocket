package com.leipishu.pvpocket.client.gui.rule;

import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.session.RosterShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The arrangement editor: one row per participant, one chip per side.
 *
 * <p>A row list rather than draggable cards: everybody appears exactly once, so "is this person
 * placed" is answered by looking rather than by hunting for a card. The player set is fixed, which
 * is why there is no add or remove here, only a side per row.
 *
 * <p>Sides beyond the ones in use are offered as new ones, up to the four the arena has pads for.
 * Capacity is enforced on click, and the whole arrangement is validated again on confirm.
 */
public class RosterEditor extends PvWidget {

    /** Result callback; {@code roster} is the roster panel's own {@code uuid,uuid;uuid} form. */
    public interface Listener {
        void onConfirm(String roster);

        void onCancel();
    }

    private static final int MAX_TEAMS = 4;
    private static final int PAD = 12;
    private static final int HEAD_H = 26;
    private static final int FOOT_H = 30;
    private static final int ROW_H = 16;
    private static final int CHIP_H = 13;

    private final List<String> uuids = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final int[] team;
    private final int slots;
    private Listener listener;
    private String error;
    private int top;

    public RosterEditor(List<String> uuids, List<String> names, List<Integer> teams, int slots) {
        this.slots = slots;
        this.team = new int[uuids.size()];
        this.uuids.addAll(uuids);
        this.names.addAll(names);
        for (int i = 0; i < uuids.size(); i++) {
            this.team[i] = i < teams.size() ? teams.get(i) : 0;
        }
        this.height = 200;
    }

    public void listener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------ geometry

    private int panelW() {
        return Math.min(width - 20, 380);
    }

    private int panelH() {
        return Math.min(height - 20, 300);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelY() {
        return (height - panelH()) / 2;
    }

    private int listTop() {
        return panelY() + HEAD_H;
    }

    private int listBottom() {
        return panelY() + panelH() - FOOT_H;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ROW_H);
    }

    private int nameW() {
        return Math.min(96, panelW() / 3);
    }

    private int chipW() {
        int span = panelW() - PAD * 2 - nameW() - 6 * (MAX_TEAMS - 1);
        return Math.max(30, span / MAX_TEAMS);
    }

    private int chipX(int column) {
        return panelX() + PAD + nameW() + 6 + column * (chipW() + 6);
    }

    private int rowY(int index) {
        return listTop() + (index - top) * ROW_H;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        Font tr = Minecraft.getInstance().font;
        int px = panelX();
        int py = panelY();
        int pw = panelW();
        int ph = panelH();

        ctx.fill(0, 0, width, height, PvTheme.SCRIM);
        PvDraw.roundRect(ctx, px, py, pw, ph, PvTheme.RADIUS_PANEL, PvTheme.PANEL);
        PvDraw.ring(ctx, px, py, pw, ph, PvTheme.RADIUS_PANEL, 1, PvTheme.BORDER_STRONG);
        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.roster.editor.title"), px + PAD, py + 8,
                PvTheme.TEXT);
        PvDraw.textFitLeft(ctx, tr, PvLang.str("pvpocket.gui.roster.editor.hint"),
                px + PAD, py + 18, pw - PAD * 2, PvTheme.TEXT_MUTED);

        ctx.enableScissor(px + 1, listTop(), px + pw - 1, listBottom());
        for (int i = top; i < Math.min(uuids.size(), top + visibleRows() + 1); i++) {
            drawRow(ctx, tr, i, mouseX, mouseY);
        }
        ctx.disableScissor();

        drawFooter(ctx, tr, mouseX, mouseY);
    }

    private void drawRow(GuiGraphics ctx, Font tr, int index, int mouseX, int mouseY) {
        int ry = rowY(index);
        PvDraw.textFitLeft(ctx, tr, names.get(index), panelX() + PAD, ry + 3, nameW(),
                PvTheme.TEXT);
        for (int column = 0; column < MAX_TEAMS; column++) {
            int cx = chipX(column);
            boolean current = team[index] == column;
            boolean full = !current && occupancy(column) >= slots;
            boolean hot = !full && mouseX >= cx && mouseX < cx + chipW()
                    && mouseY >= ry && mouseY < ry + CHIP_H;
            int fill = current ? PvTheme.ACCENT_SOFT : hot ? PvTheme.CARD_HOVER : PvTheme.CARD;
            int border = current ? PvTheme.ACCENT_DIM : PvTheme.BORDER;
            int ink = current ? PvTheme.ACCENT : full ? PvTheme.TEXT_MUTED : PvTheme.TEXT_DIM;
            PvDraw.roundPanel(ctx, cx, ry, chipW(), CHIP_H, 3, border, fill);
            PvDraw.textCenter(ctx, tr, PvLang.str("pvpocket.gui.roster.editor.team",
                            column + 1, occupancy(column), slots),
                    cx + chipW() / 2, ry + 3, ink);
        }
    }

    private void drawFooter(GuiGraphics ctx, Font tr, int mouseX, int mouseY) {
        int py = panelY() + panelH() - FOOT_H;
        if (error != null) {
            PvDraw.textFitLeft(ctx, tr, error, panelX() + PAD, py + 2, panelW() - PAD * 2,
                    PvTheme.DANGER);
        }
        int buttonW = 56;
        int confirmX = panelX() + panelW() - PAD - buttonW;
        int cancelX = confirmX - 8 - buttonW;
        boolean confirmHot = mouseX >= confirmX && mouseX < confirmX + buttonW
                && mouseY >= py + 12 && mouseY < py + 12 + 16;
        PvDraw.roundPanel(ctx, confirmX, py + 12, buttonW, 16, 3, PvTheme.ACCENT_DIM,
                confirmHot ? PvTheme.ACCENT_HOVER : PvTheme.ACCENT);
        PvDraw.textCenter(ctx, tr, PvLang.str("pvpocket.gui.roster.editor.confirm"),
                confirmX + buttonW / 2, py + 16, PvTheme.ON_ACCENT);
        boolean cancelHot = mouseX >= cancelX && mouseX < cancelX + buttonW
                && mouseY >= py + 12 && mouseY < py + 12 + 16;
        PvDraw.roundPanel(ctx, cancelX, py + 12, buttonW, 16, 3, PvTheme.BORDER,
                cancelHot ? PvTheme.CARD_HOVER : PvTheme.CARD);
        PvDraw.textCenter(ctx, tr, PvLang.str("pvpocket.gui.roster.editor.cancel"),
                cancelX + buttonW / 2, py + 16, PvTheme.TEXT_DIM);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || !enabled) {
            return false;
        }
        int py = panelY() + panelH() - FOOT_H;
        int buttonW = 56;
        int confirmX = panelX() + panelW() - PAD - buttonW;
        int cancelX = confirmX - 8 - buttonW;
        if (my >= py + 12 && my < py + 28) {
            if (mx >= confirmX && mx < confirmX + buttonW) {
                confirm();
                return true;
            }
            if (mx >= cancelX && mx < cancelX + buttonW) {
                if (listener != null) {
                    listener.onCancel();
                }
                return true;
            }
        }
        if (my < listTop() || my >= listBottom()) {
            return true;
        }
        int index = top + (int) ((my - listTop()) / ROW_H);
        if (index < 0 || index >= uuids.size()) {
            return true;
        }
        for (int column = 0; column < MAX_TEAMS; column++) {
            int cx = chipX(column);
            if (mx >= cx && mx < cx + chipW()) {
                if (team[index] != column && occupancy(column) < slots) {
                    team[index] = column;
                    error = null;
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        int max = Math.max(0, uuids.size() - visibleRows());
        top = Math.max(0, Math.min(max, top - (int) Math.signum(amount)));
        return true;
    }

    @Override
    public boolean focusable() {
        return false;
    }

    private int occupancy(int column) {
        int count = 0;
        for (int value : team) {
            if (value == column) {
                count++;
            }
        }
        return count;
    }

    private void confirm() {
        List<List<String>> sides = new ArrayList<>();
        for (int column = 0; column < MAX_TEAMS; column++) {
            List<String> side = new ArrayList<>();
            for (int i = 0; i < uuids.size(); i++) {
                if (team[i] == column) {
                    side.add(uuids.get(i));
                }
            }
            if (!side.isEmpty()) {
                sides.add(side);
            }
        }
        if (sides.size() < 2) {
            error = PvLang.str("pvpocket.gui.roster.editor.need_sides");
            return;
        }
        if (listener != null) {
            listener.onConfirm(RosterShape.encode(sides));
        }
    }
}
