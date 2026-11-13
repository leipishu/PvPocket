package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.loadout.LoadoutLock;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses a drop of a locked stack: the Q key inside an open inventory.
 *
 * <p>With a screen open the Q key never reaches {@code dropSelectedItem}; {@code HandledScreen}
 * turns it into a {@code ClickSlotC2SPacket} with {@code THROW}, and the throw happens inside
 * {@code clicked}, which this hooks at HEAD.
 *
 * <p>Only {@code THROW} is vetoed. The condition mirrors vanilla's own guard for the branch
 * ({@code slotIndex >= 0} and an empty cursor), so the hint appears only when a throw would have
 * happened.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ScreenHandlerMixin {

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noThrowLocked(int slotIndex, int button, ClickType actionType,
                                        Player player, CallbackInfo ci) {
        if (actionType != ClickType.THROW || player == null) {
            return;
        }
        AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
        if (!self.getCarried().isEmpty() || slotIndex < 0 || slotIndex >= self.slots.size()) {
            return;
        }
        ItemStack stack = self.slots.get(slotIndex).getItem();
        if (LoadoutLock.isLocked(stack)) {
            SessionManager.reportDropDenial(player);
            ci.cancel();
        }
    }
}
