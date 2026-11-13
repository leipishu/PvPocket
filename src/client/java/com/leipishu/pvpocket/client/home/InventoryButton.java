package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.mixin.client.HandledScreenAccessor;
import com.leipishu.pvpocket.mixin.client.ScreenInvoker;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;

/**
 * Adds a button left of the inventory panel that opens the home screen.
 *
 * <p>Only {@link InventoryScreen} is handled; other container screens are left untouched.
 * The button is a vanilla {@code Button} because it sits on a vanilla screen.
 *
 * <p>Call {@link #register()} once during client init.
 */
public final class InventoryButton {

    private static final int W = 28;
    private static final int H = 20;
    /** Space between the button and the panel's edge. */
    private static final int GAP = 4;

    private InventoryButton() {
    }

    public static void register() {
        // Runs after every screen init: a resize rebuilds the widget list, so the button is added
        // again each time.
        PvClientPlatform.get().onScreenInit((client, screen) -> {
            if (!(screen instanceof InventoryScreen inventory)) {
                return;
            }
            HandledScreenAccessor panel = (HandledScreenAccessor) inventory;
            // Left of the panel, clamped to 2 so a narrow window cannot push the button off screen.
            int bx = Math.max(2, panel.pvpocket$panelX() - W - GAP);
            int by = Math.max(2, panel.pvpocket$panelY() + GAP);

            Button button = Button
                    .builder(PvLang.t("pvpocket.gui.home.inventory"), b -> HomeClient.open())
                    .bounds(bx, by, W, H)
                    .build();
            button.setTooltip(Tooltip.create(PvLang.t("pvpocket.gui.home.inventory.tip")));
            // addRenderableWidget is protected on the vanilla screen, so the widget is added
            // through the @Invoker mixin.
            ((ScreenInvoker) screen).pvpocket$addRenderableWidget(button);
        });
    }
}
