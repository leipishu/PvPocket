package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/**
 * The duel dimension and its regions.
 *
 * <p>Minecraft cannot create dimensions at runtime, so the design is one fixed dimension whose
 * interior is split into regions. S1 only uses region 0; the region abstraction exists so that
 * concurrent sessions do not require a redesign later.</p>
 */
public final class ArenaManager {

    public static final RegistryKey<World> ARENA_WORLD =
            RegistryKey.of(RegistryKeys.WORLD, PvPocket.id("arena"));

    /** Flat generator puts bedrock at y=0 and stone at y=1..2, so y=3 is the walkable surface. */
    public static final int SURFACE_Y = 3;

    private static final BlockPos[] SPAWNS = {
            new BlockPos(0, SURFACE_Y, 0),
            new BlockPos(8, SURFACE_Y, 0)
    };

    /** Half-extent of the usable region, in blocks. Leaving it settles the round. */
    public static final double RADIUS = 40.0D;
    private static final double CENTER_X = 4.0D;
    private static final double CENTER_Z = 0.0D;

    private ArenaManager() {
    }

    public static ServerWorld world(MinecraftServer server) {
        return server.getWorld(ARENA_WORLD);
    }

    public static boolean isReady(MinecraftServer server) {
        return world(server) != null;
    }

    /**
     * Moves a player to their spawn pad. Yaw is set so the two players face each other.
     */
    public static boolean teleportToSpawn(ServerPlayerEntity player, int spawnIndex) {
        ServerWorld world = world(player.getServer());
        if (world == null) {
            return false;
        }
        BlockPos spawn = SPAWNS[Math.floorMod(spawnIndex, SPAWNS.length)];
        float yaw = spawnIndex == 0 ? -90.0F : 90.0F;
        player.stopRiding();
        player.teleport(world, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, yaw, 0.0F);
        player.setVelocity(0.0D, 0.0D, 0.0D);
        return true;
    }

    /** True while the player is inside the arena volume; false means they left and lost. */
    public static boolean isInside(ServerPlayerEntity player) {
        if (!player.getWorld().getRegistryKey().equals(ARENA_WORLD)) {
            return false;
        }
        if (player.getY() < -8.0D) {
            return false;
        }
        double dx = player.getX() - CENTER_X;
        double dz = player.getZ() - CENTER_Z;
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    /**
     * Removes every dropped item and experience orb in the arena.
     *
     * <p>This is a correctness requirement, not housekeeping: a player who dies in the arena drops
     * their survival inventory, then gets it back from the snapshot. If those drops stayed on the
     * ground the player could walk over and collect them, duplicating the entire inventory.</p>
     */
    public static int clearDroppedItems(MinecraftServer server) {
        ServerWorld world = world(server);
        if (world == null) {
            return 0;
        }
        Box box = new Box(
                CENTER_X - RADIUS * 2, world.getBottomY(), CENTER_Z - RADIUS * 2,
                CENTER_X + RADIUS * 2, world.getTopY(), CENTER_Z + RADIUS * 2
        );
        int removed = 0;
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, box, entity -> true)) {
            item.discard();
            removed++;
        }
        for (ExperienceOrbEntity orb : world.getEntitiesByClass(ExperienceOrbEntity.class, box, entity -> true)) {
            orb.discard();
            removed++;
        }
        return removed;
    }
}
