package com.leipishu.pvpocket.mixin.client;

//? if <26.2 {
import com.leipishu.pvpocket.client.hud.CrosshairGuard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person half of the 1.8 block feedback: four corner brackets around the crosshair.
 *
 * <p>Hooks the crosshair pass of {@code Gui} at TAIL. From 26.2 the crosshair moves to {@code Hud},
 * which {@code HudCrosshairMixin} hooks instead.
 */
@Mixin(Gui.class)
public abstract class InGameHudMixin {

//? if >=26.1 {
    @Inject(method = "extractCrosshair", at = @At("TAIL"))
//?} else {
    @Inject(method = "renderCrosshair", at = @At("TAIL"))
//?}
    private void pvpocket$blockingGuard(GuiGraphics context, DeltaTracker tickCounter,
                                        CallbackInfo ci) {
        CrosshairGuard.draw(context);
    }
}
//?}
