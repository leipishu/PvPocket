package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.arena.ArenaManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every block write that happens inside a leased arena plot.
 *
 * <p>Hooks {@code setBlock} at HEAD, the funnel that explosion damage, piston pushes, fluid spread,
 * fire and end-of-round cleanup all go through, so a plot cannot be altered by a path the mod does
 * not know about.
 *
 * <p>Installed on {@code Level} because {@code ServerLevel} does not override the method; the
 * {@code instanceof} guard keeps it off the client.
 */
@Mixin(Level.class)
public class WorldBlockChangeMixin {

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD")
    )
    private void pvpocket$recordArenaBlockChange(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                                 CallbackInfoReturnable<Boolean> cir) {
        // `this` is typed as the mixin class, so the cast to Object is what lets javac consider the
        // ServerLevel branch at all; the merged instance is a Level subclass at runtime.
        if ((Object) this instanceof ServerLevel serverWorld && ArenaManager.isArena(serverWorld)) {
            ArenaManager.onBlockChanged(serverWorld, pos, state);
        }
    }
}
