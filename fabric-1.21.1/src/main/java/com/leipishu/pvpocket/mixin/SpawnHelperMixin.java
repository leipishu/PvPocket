package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.arena.ArenaManager;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels natural mob spawning inside the duel dimension.
 *
 * <p>The dimension already declares {@code monster_spawn_light_level: 0}, and it is tempting to think
 * that is enough. It is not: that field only raises the light threshold required to spawn, so a
 * covered area, a night cycle, or any block that darkens a pocket still produces mobs. Nothing short
 * of refusing the spawn outright is reliable, and a slime landing in the middle of a duel is not a
 * cosmetic problem — it is a third combatant.</p>
 *
 * <p>{@code SpawnHelper.spawn} is the entry point {@code ServerChunkManager} calls once per loaded
 * chunk per tick for natural spawning, so cancelling here covers monsters, animals, water creatures
 * and ambient spawns in one place. Spawns that arrive by other routes (spawn eggs, spawner blocks,
 * {@code /summon}) are handled by the periodic sweep in {@link ArenaManager#sweepMobs}.</p>
 */
@Mixin(NaturalSpawner.class)
public class SpawnHelperMixin {

    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void pvpocket$suppressNaturalSpawning(ServerLevel world, LevelChunk chunk, NaturalSpawner.SpawnState info,
                                                         boolean spawnAnimals, boolean spawnMonsters, boolean rare,
                                                         CallbackInfo ci) {
        if (ArenaManager.isArena(world)) {
            ci.cancel();
        }
    }

    /**
     * Blocks the passive-mob seeding that runs the first time a chunk is generated.
     *
     * <p>This is a second, separate door: {@code populateEntities} is driven by the biome's spawn
     * list rather than by {@link #pvpocket$suppressNaturalSpawning}, so a freshly generated plot
     * arrives with a handful of cows and sheep already standing on it. Measured on a 48x48 plot:
     * seven animals, which is exactly what a player would have found waiting for them in round one.</p>
     */
    @Inject(method = "spawnMobsForChunkGeneration", at = @At("HEAD"), cancellable = true)
    private static void pvpocket$suppressChunkPopulation(ServerLevelAccessor world, Holder<Biome> biome,
                                                         ChunkPos chunkPos, RandomSource random, CallbackInfo ci) {
        if (world instanceof ServerLevel serverWorld && ArenaManager.isArena(serverWorld)) {
            ci.cancel();
        }
    }
}
