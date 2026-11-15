package com.leipishu.pvpocket.mixin.client;

// 26.2 moved the crosshair pass from Gui to Hud. Below 26.2 this file generates empty:
// InGameHudMixin hooks the Gui pass on those versions.
//? if >=26.2 {
import com.leipishu.pvpocket.client.hud.CrosshairGuard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person half of the 1.8 block feedback: four corner brackets around the crosshair.
 *
 * <p>Hooks {@code Hud.extractCrosshair} at TAIL, which is where 26.2 draws the crosshair. Up to
 * 26.1 the same pass lives on {@code Gui} and {@code InGameHudMixin} covers it.
 */
@Mixin(Hud.class)
public abstract class HudCrosshairMixin {

    @Inject(method = "extractCrosshair", at = @At("TAIL"))
    private void pvpocket$blockingGuard(GuiGraphics context, DeltaTracker tickCounter,
                                        CallbackInfo ci) {
        CrosshairGuard.draw(context);
    }
}
//?}
