package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.client.render.LobbyLight;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lights the lobby by the overworld's clock rather than by the round's fixed time.
 *
 * <p>{@code getSkyDarken} is what the lightmap reads, and it is level-wide; {@link LobbyLight}
 * decides when that is the lobby and what the answer should be. Every other place, including the
 * plots, keeps vanilla's value.
 */
@Mixin(Level.class)
public abstract class LobbyLightMixin {

    @Inject(method = "getSkyDarken", at = @At("HEAD"), cancellable = true)
    private void pvpocket$lobbySkyDarken(CallbackInfoReturnable<Integer> cir) {
        int darken = LobbyLight.darken();
        if (darken >= 0) {
            cir.setReturnValue(darken);
        }
    }
}
