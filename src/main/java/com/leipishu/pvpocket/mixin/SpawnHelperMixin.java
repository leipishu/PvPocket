package com.leipishu.pvpocket.mixin;

import com.leipishu.pvpocket.arena.ArenaManager;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
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

import java.util.List;

/**
 * Cancels natural mob spawning inside the duel dimension.
 *
 * <p>Hooks {@code spawnForChunk} at HEAD, the entry point {@code ServerChunkManager} calls once per
 * loaded chunk per tick, so monsters, animals, water creatures and ambient spawns are all covered.
 * Spawns from other routes (spawn eggs, spawner blocks, {@code /summon}) are removed by the sweep
 * in {@link ArenaManager#sweepMobs}.
 *
 * <p>The dimension declares {@code monster_spawn_light_level: 0}, but that only raises the light
 * threshold, so a covered or dark pocket still produces mobs.
 */
@Mixin(NaturalSpawner.class)
public class SpawnHelperMixin {

    //? if >=1.21.11 {
    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void pvpocket$suppressNaturalSpawning(ServerLevel world, LevelChunk chunk,
                                                         NaturalSpawner.SpawnState info,
                                                         List<MobCategory> categories,
                                                         CallbackInfo ci) {
    //?} else {
    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void pvpocket$suppressNaturalSpawning(ServerLevel world, LevelChunk chunk,
                                                         NaturalSpawner.SpawnState info,
                                                         boolean spawnAnimals, boolean spawnMonsters,
                                                         boolean rare, CallbackInfo ci) {
    //?}
        if (ArenaManager.isArena(world)) {
            ci.cancel();
        }
    }

    /**
     * Blocks the passive-mob seeding that runs the first time a chunk is generated.
     *
     * <p>Hooks {@code spawnMobsForChunkGeneration} at HEAD. That path is driven by the biome's
     * spawn list rather than by {@code spawnForChunk}, so a freshly generated plot would otherwise
     * arrive with animals on it; a 48x48 plot measured seven.
     */
    @Inject(method = "spawnMobsForChunkGeneration", at = @At("HEAD"), cancellable = true)
    private static void pvpocket$suppressChunkPopulation(ServerLevelAccessor world, Holder<Biome> biome,
                                                         ChunkPos chunkPos, RandomSource random, CallbackInfo ci) {
        if (world instanceof ServerLevel serverWorld && ArenaManager.isArena(serverWorld)) {
            ci.cancel();
        }
    }
}
