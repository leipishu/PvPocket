package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.combat.CombatState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person half of the 1.8 block feedback: four corner brackets around the crosshair.
 *
 * <p>Hooks {@code Gui.renderCrosshair} at TAIL and draws the brackets when the local player is
 * blocking. Drawn in the mod's warm accent.
 */
@Mixin(Gui.class)
public abstract class InGameHudMixin {

    /** Length of each bracket arm, in GUI pixels. */
    private static final int ARM = 4;

    /** Half the box around the 15x15 crosshair. */
    private static final int SPAN = 11;

    /** The accent {@code #F5A524} at 72% alpha, dimmed against the crosshair. */
    private static final int GUARD_COLOR = 0xB8F5A524;

    @Inject(method = "renderCrosshair", at = @At("TAIL"))
    private void pvpocket$blockingGuard(GuiGraphics context, DeltaTracker tickCounter,
                                        CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !CombatState.isBlocking(client.player.getUUID())) {
            return;
        }
        int centerX = context.guiWidth() / 2;
        int centerY = context.guiHeight() / 2;
        int left = centerX - SPAN;
        int top = centerY - SPAN;
        int right = centerX + SPAN;
        int bottom = centerY + SPAN;
        // Eight 1px bars, one horizontal and one vertical arm per corner. fill() is exact at 1px.
        context.fill(left, top, left + ARM, top + 1, GUARD_COLOR);
        context.fill(left, top, left + 1, top + ARM, GUARD_COLOR);
        context.fill(right - ARM, top, right, top + 1, GUARD_COLOR);
        context.fill(right - 1, top, right, top + ARM, GUARD_COLOR);
        context.fill(left, bottom - 1, left + ARM, bottom, GUARD_COLOR);
        context.fill(left, bottom - ARM, left + 1, bottom, GUARD_COLOR);
        context.fill(right - ARM, bottom - 1, right, bottom, GUARD_COLOR);
        context.fill(right - 1, bottom - ARM, right, bottom, GUARD_COLOR);
    }
}
