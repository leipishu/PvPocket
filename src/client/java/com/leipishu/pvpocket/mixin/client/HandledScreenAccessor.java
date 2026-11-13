package com.leipishu.pvpocket.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the inventory panel's origin.
 *
 * <p>{@code AbstractContainerScreen} keeps {@code leftPos} and {@code topPos} protected with no
 * getters. They are the only way to place a button beside the panel instead of at a fixed screen
 * coordinate.
 */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {

    @Accessor("leftPos")
    int pvpocket$panelX();

    @Accessor("topPos")
    int pvpocket$panelY();
}
