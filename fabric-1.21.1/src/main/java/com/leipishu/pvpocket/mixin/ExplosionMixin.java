package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code f.allowExplosives}: a detonation that is not allowed simply does not happen.
 *
 * <p>Two injections because the explosion is two steps. {@code collectBlocksAndDamageEntities} is
 * what finds the blocks and hurts the entities; {@code affectWorld} is what removes the blocks,
 * drops the items, sets the fire and plays the sound. Cancelling only the first leaves a bang with
 * nothing behind it, and cancelling only the second leaves the damage — both of them are the rule.</p>
 *
 * <p>Both run on both sides. That is not redundant: {@code affectWorld} is where the client destroys
 * blocks in its own copy of the world, so a client that did not know about the rule would dig a
 * crater the server never confirms.</p>
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Shadow
    @Final
    private World world;

    @Inject(method = "collectBlocksAndDamageEntities", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noExplosionDamage(CallbackInfo ci) {
        if (!allowed()) {
            ci.cancel();
        }
    }

    @Inject(method = "affectWorld", at = @At("HEAD"), cancellable = true)
    private void pvpocket$noExplosionWorld(boolean particles, CallbackInfo ci) {
        if (!allowed()) {
            ci.cancel();
        }
    }

    private boolean allowed() {
        Vec3d pos = ((Explosion) (Object) this).getPosition();
        return SessionManager.explosivesAllowed(world, pos);
    }
}
