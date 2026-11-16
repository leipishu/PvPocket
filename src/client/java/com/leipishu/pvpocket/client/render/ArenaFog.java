package com.leipishu.pvpocket.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

//? if <1.21.11 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}

/**
 * The distance past which nothing is drawn inside the arena dimension.
 *
 * <p>Fog rather than a wall: the dimension is void, so the only thing fog can do is fade the far
 * distance, and a fogged-out horizon reads as open air instead of as a painted slab.
 *
 * <p>{@link #END} comes from the widest plot, not from the render distance: a player at the edge
 * of a 224-wide plot is 224 blocks from the opposite edge, so 288 leaves that edge clear and 384
 * stays under the vanilla ceiling of 512, which makes the cap bite at the highest setting.
 */
public final class ArenaFog {

    /** Furthest anything is drawn in the arena, in blocks. */
    public static final float END = 384.0F;

    /** Where the fade into {@link #END} begins, in blocks. */
    public static final float START = 288.0F;

    private static final ResourceKey<Level> ARENA_WORLD =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath("pvpocket", "arena"));

    private ArenaFog() {
    }

    /** Whether the client is currently inside the arena dimension. */
    public static boolean inArena() {
        return inArena(Minecraft.getInstance().level);
    }

    /** Whether the given level is the arena dimension. */
    public static boolean inArena(ClientLevel level) {
        return level != null && level.dimension().equals(ARENA_WORLD);
    }

//? if <1.21.11 {
    /**
     * Tightens the shader fog after vanilla set it.
     *
     * <p>1.21.1 has no fog data object: {@code FogRenderer.setupFog} writes the two numbers into
     * {@code RenderSystem} itself, so the same numbers are written again here. Both are minima of
     * the pair vanilla just wrote, so a render distance shorter than {@link #END} keeps its own
     * fog instead of gaining a wider band.
     */
    public static void limitShaderFog() {
        if (!inArena()) {
            return;
        }
        RenderSystem.setShaderFogStart(Math.min(RenderSystem.getShaderFogStart(), START));
        RenderSystem.setShaderFogEnd(Math.min(RenderSystem.getShaderFogEnd(), END));
    }
//?}
}
