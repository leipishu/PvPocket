package com.leipishu.pvpocket.client.home;

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

/**
 * The dialog behind a preset row's "more" button: share, import and delete.
 *
 * <p>One class with three faces (menu, share, import). A share starts in the menu and its code is
 * shown there, so the flow stays in one dialog.
 *
 * <p>{@link PresetShare} holds one listener: this registers on construction and clears it in
 * {@link #dismissed()}, which also runs when the dialog is closed with Escape.
 */
public class PresetActionOverlay extends PvWidget {

    private static final int CARD_W = 300;
    private static final int CARD_H = 148;
    private static final int BTN_H = 20;

    private enum Face { MENU, SHARE, IMPORT }

    private final Preset preset;
    private final Runnable onClose;
    private final PvButton share;
    private final PvButton remove;
    private final PvButton close;
    private final PvButton confirm;
    private final PvButton importButton;
    private final PvTextField codeField = new PvTextField(150).maxLength(16);

    private Face face = Face.MENU;
    /** True once "delete" has been pressed and the button is waiting for the second press. */
    private boolean confirming;
    private String status = "";
    private boolean statusBad;

    public PresetActionOverlay(Preset preset, Runnable onClose) {
        this.preset = preset;
        this.onClose = onClose;

        share = new PvButton(PvLang.str("pvpocket.gui.home.preset.share"),
                PvButton.Style.NORMAL, this::share).size(76, BTN_H);
        remove = new PvButton(PvLang.str("pvpocket.gui.home.preset.delete"),
                PvButton.Style.DANGER, this::delete).size(76, BTN_H);
        close = new PvButton(PvLang.str("pvpocket.gui.home.preset.cancel"),
                PvButton.Style.GHOST, onClose).size(56, BTN_H);
        confirm = new PvButton(PvLang.str("pvpocket.gui.home.preset.import_do"),
                PvButton.Style.PRIMARY, this::importCode).size(64, BTN_H);
        importButton = new PvButton(PvLang.str("pvpocket.gui.home.preset.import"),
                PvButton.Style.NORMAL, this::showImport).size(76, BTN_H);
        codeField.placeholder(PvLang.str("pvpocket.gui.home.preset.code_hint"));

        PresetShare.listener(this::onShareResult);
    }

    @Override
    public void dismissed() {
        PresetShare.listener(null);
    }

    private int cardX() {
        return x + (width - CARD_W) / 2;
    }

    private int cardY() {
        return y + (height - CARD_H) / 2;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        if (!visible) {
            return;
        }
        PvDraw.rect(ctx, x, y, width, height, PvTheme.alpha(PvTheme.SCRIM, 0.85F));
        int cx = cardX();
        int cy = cardY();
        PvDraw.roundPanel(ctx, cx, cy, CARD_W, CARD_H, PvTheme.RADIUS_PANEL,
                PvTheme.BORDER, PvTheme.PANEL);

        Font tr = Minecraft.getInstance().font;
        PvDraw.text(ctx, tr, PvDraw.fit(tr, preset.name(), CARD_W - 32), cx + 16, cy + 12,
                PvTheme.TEXT);
        PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.home.preset.kind", preset.kind().label()),
                cx + 16, cy + 28, PvTheme.TEXT_MUTED);

        int rowY = cy + 52;
        switch (face) {
            case MENU -> {
                share.x = cx + 16;
                share.y = rowY;
                remove.x = cx + 16;
                remove.y = rowY + BTN_H + 6;
                importButton.x = cx + CARD_W - 16 - importButton.width;
                importButton.y = rowY;
                close.x = cx + CARD_W - 16 - close.width;
                close.y = cy + CARD_H - 16 - BTN_H;
                share.visible = true;
                remove.visible = true;
                importButton.visible = true;
                close.visible = true;
                confirm.visible = false;
                codeField.visible = false;
                share.render(ctx, mouseX, mouseY, delta);
                remove.render(ctx, mouseX, mouseY, delta);
                importButton.render(ctx, mouseX, mouseY, delta);
                close.render(ctx, mouseX, mouseY, delta);
                if (confirming) {
                    PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.home.preset.confirm"),
                            cx + 100, rowY + BTN_H + 13, PvTheme.DANGER);
                }
            }
            case SHARE -> {
                codeField.visible = false;
                confirm.visible = false;
                close.visible = true;
                share.visible = false;
                remove.visible = false;
                importButton.visible = false;
                close.x = cx + CARD_W - 16 - close.width;
                close.y = cy + CARD_H - 16 - BTN_H;
                PvDraw.text(ctx, tr, PvLang.str("pvpocket.gui.home.preset.share_hint"),
                        cx + 16, rowY, PvTheme.TEXT_DIM);
                PvDraw.text(ctx, tr, status.isEmpty()
                                ? PvLang.str("pvpocket.gui.home.preset.share_wait") : status,
                        cx + 16, rowY + 18, PvTheme.ACCENT);
                close.render(ctx, mouseX, mouseY, delta);
            }
            case IMPORT -> {
                codeField.x = cx + 16;
                codeField.y = rowY;
                codeField.width = CARD_W - 32;
                codeField.visible = true;
                codeField.render(ctx, mouseX, mouseY, delta);
                confirm.x = cx + 16;
                confirm.y = rowY + 28;
                confirm.visible = true;
                confirm.render(ctx, mouseX, mouseY, delta);
                close.x = cx + CARD_W - 16 - close.width;
                close.y = cy + CARD_H - 16 - BTN_H;
                close.visible = true;
                close.render(ctx, mouseX, mouseY, delta);
                share.visible = false;
                remove.visible = false;
                importButton.visible = false;
            }
        }

        if (statusBad && !status.isEmpty()) {
            PvDraw.text(ctx, tr, PvDraw.fit(tr, status, CARD_W - 32), cx + 16,
                    cy + CARD_H - 38, PvTheme.DANGER);
        }
    }

    private void share() {
        face = Face.SHARE;
        status = "";
        statusBad = false;
        if (!PresetShare.publish(preset.kind(), preset.name(), preset.encoded())) {
            status = PvLang.str("pvpocket.gui.home.preset.share_offline");
            statusBad = true;
        }
    }

    /**
     * Handles both directions of a share, told apart by the payload status.
     *
     * <p>The status names the request, so a publish and an import in flight at once cannot be
     * confused.
     */
    private void onShareResult(PresetShareResultPayload payload) {
        switch (payload.status()) {
            case PresetShareResultPayload.STATUS_PUBLISHED -> {
                status = payload.code();
                statusBad = false;
                // The code also goes on the clipboard so it can be pasted.
                Minecraft.getInstance().keyboardHandler.setClipboard(payload.code());
            }
            case PresetShareResultPayload.STATUS_FETCHED -> receive(payload);
            case PresetShareResultPayload.STATUS_UNKNOWN_CODE -> fail("pvpocket.gui.home.preset.err_unknown");
            case PresetShareResultPayload.STATUS_FULL -> fail("pvpocket.gui.home.preset.err_full");
            case PresetShareResultPayload.STATUS_REJECTED -> fail("pvpocket.gui.home.preset.err_rejected");
            default -> fail("pvpocket.gui.home.preset.share_failed");
        }
    }

    /** Stores the fetched preset under the name its sender chose, then reports what happened. */
    private void receive(PresetShareResultPayload payload) {
        PresetKind kind = PresetKind.byId(payload.kind());
        if (kind == null || !PresetStore.get().put(kind, payload.name(), payload.encoded())) {
            fail("pvpocket.gui.home.preset.import_failed");
            return;
        }
        face = Face.SHARE;
        status = PvLang.str("pvpocket.gui.home.preset.import_ok", payload.name());
        statusBad = false;
    }

    private void fail(String key) {
        status = PvLang.str(key);
        statusBad = true;
    }

    private void delete() {
        if (!confirming) {
            confirming = true;
            remove.style(PvButton.Style.DANGER);
            return;
        }
        PresetStore.get().remove(preset.kind(), preset.name());
        onClose.run();
    }

    private void showImport() {
        face = Face.IMPORT;
        status = "";
        statusBad = false;
    }

    private void importCode() {
        String code = codeField.text().trim();
        if (code.isEmpty()) {
            status = PvLang.str("pvpocket.gui.home.preset.code_empty");
            statusBad = true;
            return;
        }
        if (!PresetShare.fetch(code)) {
            status = PvLang.str("pvpocket.gui.home.preset.share_offline");
            statusBad = true;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (PvWidget widget : children()) {
            if (widget.visible && widget.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        return contains(mx, my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = false;
        for (PvWidget widget : children()) {
            handled |= widget.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (face == Face.IMPORT && codeField.visible && codeField.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return face == Face.IMPORT && codeField.visible && codeField.charTyped(chr, modifiers);
    }

    private List<PvWidget> children() {
        List<PvWidget> out = new ArrayList<>(5);
        out.add(share);
        out.add(remove);
        out.add(importButton);
        out.add(close);
        out.add(confirm);
        out.add(codeField);
        return out;
    }

    @Override
    public List<PvWidget> childrenForAudit() {
        return children();
    }
}
