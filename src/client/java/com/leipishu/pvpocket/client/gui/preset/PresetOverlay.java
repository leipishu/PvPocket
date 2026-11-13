package com.leipishu.pvpocket.client.gui.preset;

import com.leipishu.pvpocket.client.gui.PvAnim;
import com.leipishu.pvpocket.client.gui.PvButton;
import com.leipishu.pvpocket.client.gui.PvDraw;
import com.leipishu.pvpocket.client.gui.PvTextField;
import com.leipishu.pvpocket.client.gui.PvTheme;
import com.leipishu.pvpocket.client.gui.PvWidget;
import com.leipishu.pvpocket.client.preset.PresetShare;
import com.leipishu.pvpocket.client.preset.PresetStore;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.network.PresetShareResultPayload;
import com.leipishu.pvpocket.preset.Preset;
import com.leipishu.pvpocket.preset.PresetKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The preset dialog, shared by the rule panel and the loadout editor.
 *
 * <p>One dialog for both kinds because the four verbs are the same: save what is on screen, load
 * one back, share one, import one. Only the encoded string differs, and that is a parameter.
 *
 * <p>It sits on the screen's modal layer: the panel already carries a tab strip, a scroll pane and
 * a footer, and a preset list inside it would be a scroll inside a scroll.
 *
 * <p>Saving and loading touch only {@link PresetStore}; publishing and importing go through the
 * server, which holds the code for five minutes.
 */
public class PresetOverlay extends PvWidget {

    private static final int CARD_MAX_W = 360;
    private static final int CARD_MAX_H = 232;
    private static final int ROW_H = 20;
    private static final int BTN_W = 48;
    /** Title + divider + two input rows + status line. The list gets whatever is left. */
    private static final int CHROME_H = 94;
    private static final int TITLE_H = 30;

    private final PresetKind kind;
    /** Encodes whatever the host screen is currently editing. */
    private final Supplier<String> snapshot;
    /** Loads a preset string into the host screen. */
    private final Consumer<String> apply;
    private final Runnable onClose;

    private final PvTextField nameField = new PvTextField(120).maxLength(Preset.MAX_NAME);
    private final PvTextField codeField = new PvTextField(120).maxLength(16);
    private final PvButton saveButton;
    private final PvButton importButton;
    private final PvButton closeButton;
    private final List<PvWidget> children = new ArrayList<>();

    private final List<Preset> rows = new ArrayList<>();
    private int scroll;
    private PvWidget focus;
    private float appearT;

    private String statusKey = "";
    private Object[] statusArgs = new Object[0];
    private boolean statusBad;

    public PresetOverlay(PresetKind kind, Supplier<String> snapshot, Consumer<String> apply,
                         Runnable onClose) {
        this.kind = kind;
        this.snapshot = snapshot;
        this.apply = apply;
        this.onClose = onClose;

        saveButton = new PvButton(PvLang.str("pvpocket.gui.preset.save"),
                PvButton.Style.NORMAL, this::saveCurrent).size(BTN_W, PvTheme.BTN_H);
        importButton = new PvButton(PvLang.str("pvpocket.gui.preset.import"),
                PvButton.Style.NORMAL, this::importCode).size(BTN_W, PvTheme.BTN_H);
        closeButton = new PvButton(PvLang.str("pvpocket.gui.preset.close"),
                PvButton.Style.GHOST, this::dismiss).size(44, 18);

        nameField.placeholder(PvLang.str("pvpocket.gui.preset.name_hint"));
        codeField.placeholder(PvLang.str("pvpocket.gui.preset.code_hint"));

        children.add(nameField);
        children.add(codeField);
        children.add(saveButton);
        children.add(importButton);
        children.add(closeButton);
        setFocus(nameField);

        PresetShare.listener(this::onShareResult);
        refresh();
    }

    private void dismiss() {
        PresetShare.listener(null);
        onClose.run();
    }

    @Override
    public List<PvWidget> childrenForAudit() {
        return children;
    }

    /**
     * Drops the share listener when Esc closes the dialog through the screen, so a closed dialog
     * does not receive the next one's answers.
     */
    @Override
    public void dismissed() {
        PresetShare.listener(null);
    }

    // ------------------------------------------------------------------ layout

    private int cardW() {
        return Math.min(CARD_MAX_W, Math.max(220, width - 24));
    }

    private int cardH() {
        return Math.min(CARD_MAX_H, Math.max(150, height - 24));
    }

    private int cardX() {
        return (width - cardW()) / 2;
    }

    private int cardY() {
        return (height - cardH()) / 2;
    }

    private int rowsVisible() {
        return Math.max(1, (cardH() - CHROME_H) / ROW_H);
    }

    private int listTop() {
        return cardY() + TITLE_H;
    }

    private int listBottom() {
        return listTop() + rowsVisible() * ROW_H;
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - rowsVisible());
    }

    /** Positions the five child widgets; the card follows the viewport. */
    private void layout() {
        int px = cardX();
        int py = cardY();
        int pw = cardW();
        Font tr = Minecraft.getInstance().font;
        int labelW = Math.max(tr.width(PvLang.str("pvpocket.gui.preset.name_label")),
                tr.width(PvLang.str("pvpocket.gui.preset.code_label"))) + 6;
        int fieldX = px + 12 + labelW;
        int fieldW = Math.max(60, pw - 12 - labelW - 4 - BTN_W - 12);

        int nameY = listBottom() + 6;
        nameField.x = fieldX;
        nameField.y = nameY;
        nameField.width = fieldW;
        saveButton.x = px + pw - 12 - BTN_W;
        saveButton.y = nameY;

        int codeY = nameY + 24;
        codeField.x = fieldX;
        codeField.y = codeY;
        codeField.width = fieldW;
        importButton.x = px + pw - 12 - BTN_W;
        importButton.y = codeY;

        closeButton.x = px + pw - 12 - closeButton.width;
        closeButton.y = py + 4;
    }

    private void refresh() {
        rows.clear();
        rows.addAll(PresetStore.get().of(kind));
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    // ------------------------------------------------------------------ actions

    private void saveCurrent() {
        String name = Preset.cleanName(nameField.text());
        if (!Preset.isValidName(name)) {
            status("pvpocket.gui.preset.status.need_name", true);
            return;
        }
        if (!PresetStore.get().put(kind, name, snapshot.get())) {
            status("pvpocket.gui.preset.status.save_failed", true);
            return;
        }
        nameField.clear();
        refresh();
        status("pvpocket.gui.preset.status.saved", false, name);
    }

    private void loadPreset(Preset preset) {
        apply.accept(preset.encoded());
        dismiss();
    }

    private void deletePreset(Preset preset) {
        PresetStore.get().remove(kind, preset.name());
        refresh();
        status("pvpocket.gui.preset.status.deleted", false, preset.name());
    }

    private void sharePreset(Preset preset) {
        if (!PresetShare.publish(kind, preset.name(), preset.encoded())) {
            status("pvpocket.gui.preset.status.no_server", true);
        }
    }

    private void importCode() {
        String code = codeField.text().trim();
        if (code.isEmpty()) {
            status("pvpocket.gui.preset.status.need_code", true);
            return;
        }
        if (!PresetShare.fetch(code)) {
            status("pvpocket.gui.preset.status.no_server", true);
        }
    }

    /**
     * Handles the server's answer.
     *
     * <p>On a publish the code goes to the clipboard as well as into the field.
     */
    private void onShareResult(PresetShareResultPayload result) {
        switch (result.status()) {
            case PresetShareResultPayload.STATUS_PUBLISHED -> {
                codeField.text(result.code());
                Minecraft.getInstance().keyboardHandler.setClipboard(result.code());
                status("pvpocket.gui.preset.status.shared", false,
                        result.code(), result.ttlSeconds() / 60);
            }
            case PresetShareResultPayload.STATUS_FETCHED -> {
                PresetKind incoming = PresetKind.byId(result.kind());
                if (incoming != kind) {
                    status("pvpocket.gui.preset.status.wrong_kind", true);
                    return;
                }
                if (!PresetStore.get().put(kind, result.name(), result.encoded())) {
                    status("pvpocket.gui.preset.status.save_failed", true);
                    return;
                }
                refresh();
                status("pvpocket.gui.preset.status.imported", false, result.name());
            }
            case PresetShareResultPayload.STATUS_UNKNOWN_CODE ->
                    status("pvpocket.gui.preset.status.code_unknown", true);
            case PresetShareResultPayload.STATUS_FULL ->
                    status("pvpocket.gui.preset.status.full", true);
            default -> status("pvpocket.gui.preset.status.rejected", true);
        }
    }

    private void status(String key, boolean bad, Object... args) {
        this.statusKey = key;
        this.statusBad = bad;
        this.statusArgs = args;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        float d = dt(delta);
        appearT = PvAnim.damp(appearT, 1F, d, 0.05F);
        layout();
        Font tr = Minecraft.getInstance().font;

        // Darker than the screen's own scrim: this dialog sits on a panel that is already dimmed,
        // and equal darkness would merge the two surfaces.
        ctx.fill(0, 0, width, height, PvTheme.alpha(0xA0000000, appearT));

        int px = cardX();
        int py = cardY();
        int pw = cardW();
        int ph = cardH();
        PvDraw.shadow(ctx, px, py, pw, ph, PvTheme.RADIUS_PANEL);
        PvDraw.roundPanel(ctx, px, py, pw, ph, PvTheme.RADIUS_PANEL,
                PvTheme.alpha(PvTheme.BORDER_STRONG, appearT),
                PvTheme.alpha(PvTheme.PANEL, appearT));

        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.preset.title", kind.label()),
                px + 12, py + 9, PvTheme.TEXT);
        PvDraw.hLine(ctx, px + 12, py + TITLE_H - 4, pw - 24, PvTheme.DIVIDER);

        if (rows.isEmpty()) {
            PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.preset.empty"),
                    px + 12, listTop() + 4, PvTheme.TEXT_MUTED);
        } else {
            for (int i = 0; i < rowsVisible(); i++) {
                int index = scroll + i;
                if (index >= rows.size()) {
                    break;
                }
                drawRow(ctx, tr, rows.get(index), listTop() + i * ROW_H, mouseX, mouseY);
            }
            if (maxScroll() > 0) {
                int trackH = rowsVisible() * ROW_H - 4;
                int thumbH = Math.max(10, trackH * rowsVisible() / rows.size());
                int thumbY = listTop() + 2 + (trackH - thumbH) * scroll / maxScroll();
                PvDraw.roundRect(ctx, px + pw - 5, listTop() + 2, 2, trackH, 1, PvTheme.SUNKEN);
                PvDraw.roundRect(ctx, px + pw - 5, thumbY, 2, thumbH, 1, PvTheme.BORDER_STRONG);
            }
        }

        PvDraw.hLine(ctx, px + 12, listBottom() + 2, pw - 24, PvTheme.DIVIDER);

        // The two labels are drawn rather than being part of the field, so an empty field still
        // says what it wants; a placeholder alone disappears the moment the player types.
        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.preset.name_label"),
                px + 12, nameField.y + (nameField.height - 8) / 2 + 1, PvTheme.TEXT_DIM);
        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.preset.code_label"),
                px + 12, codeField.y + (codeField.height - 8) / 2 + 1, PvTheme.TEXT_DIM);

        if (!statusKey.isEmpty()) {
            PvDraw.text(ctx, tr,
                    PvDraw.fit(tr, PvLang.str(statusKey, statusArgs), pw - 24),
                    px + 12, codeField.y + 26,
                    statusBad ? PvTheme.DANGER : PvTheme.ACCENT);
        }

        for (PvWidget child : children) {
            child.updateHover(mouseX, mouseY);
            child.render(ctx, mouseX, mouseY, delta);
        }
    }

    /** Name on the left, three action words on the right, all measured rather than assumed. */
    private void drawRow(GuiGraphics ctx, Font tr, Preset preset, int ry,
                         int mouseX, int mouseY) {
        int px = cardX();
        int pw = cardW();
        boolean hot = mouseX >= px + 6 && mouseX < px + pw - 6
                && mouseY >= ry && mouseY < ry + ROW_H;
        if (hot) {
            PvDraw.roundRect(ctx, px + 6, ry, pw - 12, ROW_H - 2, 3, PvTheme.CARD_HOVER);
        }
        int textY = ry + (ROW_H - 8) / 2;

        int right = px + pw - 12;
        int deleteX = right - tr.width(actionDelete());
        int shareX = deleteX - 10 - tr.width(actionShare());
        int loadX = shareX - 10 - tr.width(actionLoad());

        PvDraw.text(ctx, tr, actionDelete(), deleteX, textY,
                overWord(mouseX, mouseY, ry, deleteX, actionDelete()) ? PvTheme.DANGER : PvTheme.TEXT_MUTED);
        PvDraw.text(ctx, tr, actionShare(), shareX, textY,
                overWord(mouseX, mouseY, ry, shareX, actionShare()) ? PvTheme.ACCENT : PvTheme.TEXT_MUTED);
        PvDraw.text(ctx, tr, actionLoad(), loadX, textY,
                overWord(mouseX, mouseY, ry, loadX, actionLoad()) ? PvTheme.TEXT : PvTheme.TEXT_DIM);

        int nameMax = loadX - 10 - (px + 12);
        PvDraw.text(ctx, tr, PvDraw.fit(tr, preset.name(), Math.max(0, nameMax)),
                px + 12, textY, PvTheme.TEXT);
    }

    private static boolean overWord(int mouseX, int mouseY, int rowY, int wordX, String word) {
        int w = Minecraft.getInstance().font.width(word);
        return mouseY >= rowY && mouseY < rowY + ROW_H
                && mouseX >= wordX - 3 && mouseX < wordX + w + 3;
    }

    private static String actionLoad() {
        return PvLang.str("pvpocket.gui.preset.action.load");
    }

    private static String actionShare() {
        return PvLang.str("pvpocket.gui.preset.action.share");
    }

    private static String actionDelete() {
        return PvLang.str("pvpocket.gui.preset.action.delete");
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible || button != 0) {
            return false;
        }
        layout();
        // Children first: the fields and buttons sit inside the card, and a click on one of them
        // must not also be read as a click on the row behind it.
        for (PvWidget child : children) {
            if (child.visible && child.enabled && child.mouseClicked(mx, my, button)) {
                setFocus(child);
                return true;
            }
        }
        if (my >= listTop() && my < listBottom() && mx >= cardX() + 6 && mx < cardX() + cardW() - 6) {
            int index = scroll + (int) ((my - listTop()) / ROW_H);
            if (index >= 0 && index < rows.size()) {
                Preset preset = rows.get(index);
                int ry = listTop() + (int) ((my - listTop()) / ROW_H) * ROW_H;
                Font tr = Minecraft.getInstance().font;
                int right = cardX() + cardW() - 12;
                int deleteX = right - tr.width(actionDelete());
                int shareX = deleteX - 10 - tr.width(actionShare());
                int loadX = shareX - 10 - tr.width(actionLoad());
                if (overWord((int) mx, (int) my, ry, deleteX, actionDelete())) {
                    deletePreset(preset);
                } else if (overWord((int) mx, (int) my, ry, shareX, actionShare())) {
                    sharePreset(preset);
                } else if (overWord((int) mx, (int) my, ry, loadX, actionLoad())) {
                    loadPreset(preset);
                }
            }
            return true;
        }
        // A click on the card's empty area is swallowed; a click on the scrim dismisses. Consumed
        // either way, so nothing reaches the panel underneath.
        if (mx < cardX() || mx >= cardX() + cardW()
                || my < cardY() || my >= cardY() + cardH()) {
            dismiss();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        for (PvWidget child : children) {
            child.mouseReleased(mx, my, button);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        for (PvWidget child : children) {
            child.mouseDragged(mx, my, button, dx, dy);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        int next = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(amount)));
        if (next != scroll) {
            scroll = next;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            cycle((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0);
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            // Enter commits whichever half of the dialog has focus, which is what a player who has
            // just typed a code expects.
            if (focus == importButton) {
                importCode();
                return true;
            }
            if (focus == saveButton) {
                saveCurrent();
                return true;
            }
        }
        return focus != null && focus.enabled && focus.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return focus != null && focus.enabled && focus.charTyped(chr, modifiers);
    }

    @Override
    public boolean focusable() {
        return false;
    }

    private void setFocus(PvWidget widget) {
        if (focus != null) {
            focus.focused = false;
        }
        focus = widget;
        if (focus != null) {
            focus.focused = true;
        }
    }

    private void cycle(boolean backwards) {
        int index = children.indexOf(focus);
        int next = index < 0
                ? 0
                : Math.floorMod(index + (backwards ? -1 : 1), children.size());
        setFocus(children.get(next));
    }
}
