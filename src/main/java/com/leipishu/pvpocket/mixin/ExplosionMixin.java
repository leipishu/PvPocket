package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code f.allowExplosives}: a detonation that is not allowed does not happen.
 *
 * <p>Hooked twice because an explosion is two steps. {@code explode} finds the blocks and hurts
 * the entities; {@code finalizeExplosion} removes the blocks, drops the items, sets the fire and
 * plays the sound. Cancelling only one leaves either a bang with nothing behind it or the damage
 * alone.
 *
 * <p>Both run on both sides, because {@code finalizeExplosion} is where the client destroys blocks
 * in its own copy of the world.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Shadow
    @Final
    private Level level;

    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noExplosionDamage(CallbackInfo ci) {
        if (!allowed()) {
            ci.cancel();
        }
    }

    @Inject(method = "finalizeExplosion", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noExplosionWorld(boolean particles, CallbackInfo ci) {
        if (!allowed()) {
            ci.cancel();
        }
    }

    private boolean allowed() {
        if (isWindBurst()) {
            // Wind charges are not explosives: they move players without breaking blocks or
            // dealing explosion damage, so {@code f.allowExplosives} leaves them alone.
            return true;
        }
        Vec3 pos = ((Explosion) (Object) this).center();
        return SessionManager.explosivesAllowed(level, pos);
    }

    /**
     * Tells a wind burst from TNT by source entity, falling back to the sound on the client.
     *
     * <p>A server-side explosion carries its source entity. The client's
     * {@code ExplosionS2CPacket} carries only the particles and sound the server chose, so that
     * side tests for the wind charge burst sound. An explosion matching neither is treated as a
     * normal explosion.
     */
    private boolean isWindBurst() {
        Explosion self = (Explosion) (Object) this;
        if (self.getDirectSourceEntity() instanceof AbstractWindCharge) {
            return true;
        }
        return self.getExplosionSound() != null
                && self.getExplosionSound().value() == SoundEvents.WIND_CHARGE_BURST.value();
    }
}
