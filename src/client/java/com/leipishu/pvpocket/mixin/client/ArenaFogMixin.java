package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.client.render.ArenaFog;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21.11 {
import net.minecraft.client.renderer.fog.FogRenderer;
//?} else {
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}
//? if >=26.1 {
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} elif >=1.21.11 {
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
//?}

/**
 * Pulls the terrain fog in inside the arena dimension, so a plot's neighbours stay out of sight.
 *
 * <p>Every version reaches the same two numbers, {@link ArenaFog#START} and {@link ArenaFog#END},
 * but each needs its own hook: 26.1 and later return a mutable {@code FogData}, 1.21.11 uploads the
 * values inside {@code setupFog} before returning only the colour, and 1.21.1 writes them straight
 * into {@code RenderSystem}. Only the render-distance pair is touched, so water, lava and the sky
 * keep the fog vanilla gave them.
 *
 * <p>Nothing here is a wall. The arena is void, so what the player loses is empty distance.
 */
@Mixin(FogRenderer.class)
public abstract class ArenaFogMixin {

//? if >=26.1 {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void pvpocket$arenaFog(Camera camera, int renderDistance, DeltaTracker delta,
                                   float partialTick, ClientLevel level,
                                   CallbackInfoReturnable<FogData> cir) {
        if (!ArenaFog.inArena(level)) {
            return;
        }
        FogData data = cir.getReturnValue();
        data.renderDistanceStart = Math.min(data.renderDistanceStart, ArenaFog.START);
        data.renderDistanceEnd = Math.min(data.renderDistanceEnd, ArenaFog.END);
    }
//?} elif >=1.21.11 {
    @ModifyArgs(method = "setupFog",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/fog/FogRenderer;"
                            + "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void pvpocket$arenaFog(Args args) {
        if (!ArenaFog.inArena()) {
            return;
        }
        // Slots 5 and 6 are the render-distance pair; 3/4 are environmental, 7/8 sky and cloud.
        args.set(5, Math.min(args.<Float>get(5), ArenaFog.START));
        args.set(6, Math.min(args.<Float>get(6), ArenaFog.END));
    }
//?} else {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void pvpocket$arenaFog(Camera camera, FogRenderer.FogMode mode, float tickDelta,
                                   boolean thickFog, float fov, CallbackInfo ci) {
        // The sky pass runs first and terrain last, and the shader keeps the last pair written.
        if (mode == FogRenderer.FogMode.FOG_TERRAIN) {
            ArenaFog.limitShaderFog();
        }
    }
//?}
}
