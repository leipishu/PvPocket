package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.mixin.client.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;

/**
 * The second way into the home panel: a button beside the inventory.
 *
 * <p>A hotkey is invisible — nothing in the interface says it exists until somebody is told. The
 * inventory is the one screen every player opens for reasons of its own, so a button there is the
 * only entry point that gets discovered without reading anything.</p>
 *
 * <p>Only {@link InventoryScreen}: the survival inventory is the one every player has, and adding a
 * button to every chest, furnace and anvil would be a mod that redecorates the game. The button is a
 * vanilla {@code ButtonWidget} rather than one of this mod's own widgets, because it sits on a
 * vanilla screen — the custom layer exists to make the mod's panels consistent with each other, not
 * to put a foreign control inside somebody else's screen.</p>
 */
public final class InventoryButton {

    private static final int W = 28;
    private static final int H = 20;
    /** Space between the button and the panel's edge. */
    private static final int GAP = 4;

    private InventoryButton() {
    }

    public static void register() {
        // AFTER_INIT, not once: init() runs again on every resize, and the screen builds a fresh
        // widget list each time — a button added once would vanish the first time the window changed
        // size.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof InventoryScreen inventory)) {
                return;
            }
            HandledScreenAccessor panel = (HandledScreenAccessor) inventory;
            // Left of the panel. Clamped because a narrow window puts the panel close to the edge and
            // a negative x is a button half off the screen — reachable by mouse, invisible.
            int bx = Math.max(2, panel.pvpocket$panelX() - W - GAP);
            int by = Math.max(2, panel.pvpocket$panelY() + GAP);

            Button button = Button
                    .builder(PvLang.t("pvpocket.gui.home.inventory"), b -> HomeClient.open())
                    .bounds(bx, by, W, H)
                    .build();
            button.setTooltip(Tooltip.create(PvLang.t("pvpocket.gui.home.inventory.tip")));
            Screens.getButtons(screen).add(button);
        });
    }
}
