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
    private Level world;

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
            // 风弹不是爆炸物。它不改方块、不造成爆炸伤害，只把人推开——`f.allowExplosives` 管的是
            // 「这一局会不会被 TNT 炸成废墟」，让它顺手把风弹也关掉，等于一条规则干两件事，而
            // 玩家只会看到「我配了风弹但它什么都不做」。
            return true;
        }
        Vec3 pos = ((Explosion) (Object) this).center();
        return SessionManager.explosivesAllowed(world, pos);
    }

    /**
     * 风爆与 TNT 的区别只能靠来源实体认，客户端还得退一步靠音效。
     *
     * <p>服务端创建的爆炸带着来源实体（风弹实体）；客户端收到的 {@code ExplosionS2CPacket}
     * 不带实体，只带服务端挑的粒子与音效，所以那一侧用风弹专用的爆发音效作判据。两条路都认不
     * 出来时按「普通爆炸」处理——漏放一个 TNT 比漏关一个风弹严重。</p>
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
