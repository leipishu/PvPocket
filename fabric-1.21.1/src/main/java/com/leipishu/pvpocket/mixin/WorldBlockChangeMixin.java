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
 * <p>Player events would have been the obvious hook, but they only cover the paths a player takes:
 * explosion damage, piston pushes, fluid spread, fire and end-of-round cleanup all mutate blocks
 * without ever touching a player event. {@code World.setBlockState} is the single funnel every one of
 * them goes through, so recording here means a plot cannot be altered by a path the mod does not know
 * about.</p>
 *
 * <p>The hook is installed on the common {@code World} class because {@code ServerWorld} does not
 * override the method; the {@code instanceof} guard is what keeps it off the client.</p>
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
        // ServerWorld branch at all — the merged instance really is a World subclass at runtime.
        if ((Object) this instanceof ServerLevel serverWorld && ArenaManager.isArena(serverWorld)) {
            ArenaManager.onBlockChanged(serverWorld, pos, state);
        }
    }
}
