package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code d.allowPickup}: dropped items stay on the ground.
 *
 * <p>{@code onPlayerCollision} is the only place an item moves itself into an inventory — it is what
 * {@code Entity#onPlayerCollision} calls for every player the item overlaps. Cancelling at the head
 * leaves the entity intact, so the drop is still visible and can still be picked up by whoever is
 * allowed to; the alternative of discarding it would make "关闭拾取" look like "清理地面".</p>
 *
 * <p>Deliberately silent. An item that does not move explains itself; a hint every tick while a
 * player stands on it would not.</p>
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noPickup(Player player, CallbackInfo ci) {
        if (!SessionManager.pickupAllowed(player.getUUID())) {
            ci.cancel();
        }
    }
}
