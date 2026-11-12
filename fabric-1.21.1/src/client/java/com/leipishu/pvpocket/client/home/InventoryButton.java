package com.leipishu.pvpocket.client.home;

import com.leipishu.pvpocket.client.platform.PvClientPlatform;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.mixin.client.HandledScreenAccessor;
import com.leipishu.pvpocket.mixin.client.ScreenInvoker;
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
        // 每次 init 都要加，不是只加一次：窗口大小一变，界面就重建自己的控件列表，只加过一次的
        // 按钮会在第一次改变窗口大小时消失。平台的钩子承诺的正是「每次 init 之后」。
        PvClientPlatform.get().onScreenInit((client, screen) -> {
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
            // 原版把 addRenderableWidget 设成 protected，公开的 children() 又是只读视图。这里走
            // 一个 @Invoker，而不是像 Fabric 那样把那个列表强转回可写形态——强转的那一行正是
            // 客户端搬不到别的加载器上的直接原因。
            ((ScreenInvoker) screen).pvpocket$addRenderableWidget(button);
        });
    }
}
