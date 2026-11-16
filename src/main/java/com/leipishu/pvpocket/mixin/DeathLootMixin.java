package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.combat.DeathFx;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces a duel death's item drop with a particle send-off at the fall spot.
 *
 * <p>Hooks {@code LivingEntity#dropAllDeathLoot}, the single point where a death spills loot and
 * experience. Cancelling it leaves the arena clean; the burst is drawn server-side so every player
 * nearby sees it without an extra packet. Deaths outside a live round keep the vanilla drop.
 */
@Mixin(LivingEntity.class)
public abstract class DeathLootMixin {

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void pvpocket$duelDeathBurst(ServerLevel level, DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayer player)) {
            return;
        }
        int tier = SessionManager.deathBurstTier(player);
        if (tier == SessionManager.NO_DUEL) {
            return;
        }
        ci.cancel();
        DeathFx.spawn(level, player.position(), tier);
    }
}
