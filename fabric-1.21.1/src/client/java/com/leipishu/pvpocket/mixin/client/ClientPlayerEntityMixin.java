package com.leipishu.pvpocket.mixin.client;

import com.leipishu.pvpocket.i18n.PvLang;
import com.leipishu.pvpocket.loadout.LoadoutLock;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client half of 禁止丢弃, and the one place in this mod where the client is the one that speaks.
 *
 * <p>{@code ClientPlayerEntity.dropSelectedItem} empties the local slot <em>before</em> sending the
 * packet. Leaving it alone would mean the item vanishes for a frame and reappears when the server's
 * refusal arrives — a visible flicker on a key that is supposed to do nothing. Cancelling here means
 * no local removal and no packet.</p>
 *
 * <p>Which is exactly why the sentence has to be printed here: the server never hears about the
 * attempt, so it cannot explain anything. This is not the "local guess at the server's reason" that
 * {@code reportUseDenial} warns about — the lock is a component on the stack, and the client reads
 * the same fact the server would. The server keeps its own veto (and its own sentence) for the paths
 * that do reach it, such as the Q key over an open inventory.</p>
 *
 * <p>No throttle: the drop key is edge-triggered, so holding Q produces one attempt, not one per
 * tick.</p>
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noDropLocked(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (LoadoutLock.isLocked(self.getMainHandStack())) {
            self.sendMessage(PvLang.t("pvpocket.msg.loadout.no_drop"), false);
            cir.setReturnValue(false);
        }
    }
}
