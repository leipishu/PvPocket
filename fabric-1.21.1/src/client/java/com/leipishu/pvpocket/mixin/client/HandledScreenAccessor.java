package com.leipishu.pvpocket.mixin.client;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the inventory panel's origin.
 *
 * <p>{@code HandledScreen} keeps {@code x} and {@code y} protected with no getters, and they are the
 * only way to place a button beside the panel rather than at a fixed screen coordinate. An accessor
 * is the smallest possible coupling: no behaviour changes, and if the field is ever renamed the
 * build fails instead of the button landing somewhere arbitrary.</p>
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {

    @Accessor("x")
    int pvpocket$panelX();

    @Accessor("y")
    int pvpocket$panelY();
}
