package com.leipishu.pvpocket.mixin.client;

//? if >=1.21.11 {

import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code f.allowExplosives} on the client: a detonation the rule forbids leaves no trace.
 *
 * <p>The client never builds an {@code Explosion}; the server's packet is turned straight into a
 * call on {@code ClientLevel}, which destroys the blocks in the client's copy of the world and
 * plays the particles and the sound. That call is the client-side equivalent of the
 * {@code finalizeExplosion} hook the 1.21.1 mixin uses.
 *
 * <p>The packet carries no source entity, only the sound the server chose, so a wind burst is
 * recognised by its sound.
 */
@Mixin(ClientLevel.class)
public abstract class ClientExplosionMixin {

    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noClientExplosion(Entity source, DamageSource damage,
                                            ExplosionDamageCalculator calculator,
                                            double x, double y, double z, float radius,
                                            boolean fire, Level.ExplosionInteraction interaction,
                                            ParticleOptions smallParticles,
                                            ParticleOptions largeParticles,
                                            WeightedList<ExplosionParticleInfo> particleInfo,
                                            Holder<SoundEvent> sound, CallbackInfo ci) {
        // Wind charges are not explosives: they move players without breaking blocks, so
        // {@code f.allowExplosives} leaves them alone.
        boolean windBurst = source instanceof AbstractWindCharge
                || (sound != null && sound.value() == SoundEvents.WIND_CHARGE_BURST.value());
        if (windBurst || SessionManager.explosivesAllowed((Level) (Object) this, new Vec3(x, y, z))) {
            return;
        }
        ci.cancel();
    }
}
//?}
