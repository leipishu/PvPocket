package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.client.compat.PvChat;
import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.LoadoutLock;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client half of the drop lock, and the one place where the client speaks first.
 *
 * <p>Hooks {@code LocalPlayer.drop} at HEAD. A locked item is not dropped and the refusal message
 * is printed locally, because no packet reaches the server on this path. The server keeps its own
 * veto for the paths that do reach it.
 */
@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin {

    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noDropLocked(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (LoadoutLock.isLocked(self.getMainHandItem())) {
            PvChat.chat(PvLang.t("pvpocket.msg.loadout.no_drop"));
            cir.setReturnValue(false);
        }
    }
}
