package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.compat.PvCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

/**
 * The waiting lobby: one island in the duel dimension, where the result page is read from.
 *
 * <p>Not a plot and not a map. Nothing is fought here and nothing is leased here, so it is not part
 * of the grid: it sits a thousand blocks from the nearest cell, outside both the fog and the
 * client's render distance, and it is rebuilt on every server start instead of being tracked.
 *
 * <p>The session moves every participant here the moment the result page goes up. The island
 * therefore has an edge: a guest who walks off it falls into the void, and the arena dimension has
 * no ground to respawn on. {@code Lobby#contains} also vetoes block breaks, and the session's tick
 * puts a falling guest back on the island.
 */
public final class Lobby {

    /**
     * Centre of the island.
     *
     * <p>The plot grid starts at {@code x = z = 512} and the lobby is 55 wide, so this is roughly
     * 700 blocks clear of the nearest cell corner: past {@code ArenaFog.END}, which means a guest
     * never sees a plot from here and never has one loaded behind it.
     */
    public static final int CENTER_X = -192;
    public static final int CENTER_Z = -192;

    /** The island's top surface block, in absolute y. */
    public static final int FLOOR_Y = 40;

    /**
     * Where a guest stands, as the y of their feet.
     *
     * <p>On the raised dais at the centre, not on the grass: {@code FLOOR_Y + 2} is one block above
     * the dais slab, which itself sits one block above the surface.
     */
    public static final int SPAWN_Y = FLOOR_Y + 2;

    /** Where a guest stands horizontally: four blocks in front of the dais pillar. */
    public static final int SPAWN_X = CENTER_X;
    public static final int SPAWN_Z = CENTER_Z + 4;

    /** Facing the pillar, i.e. north, which is yaw 180. */
    private static final float SPAWN_YAW = 180.0F;

    /**
     * Local y of the island surface inside the template, i.e. where {@link #FLOOR_Y} comes from.
     *
     * <p>Declared rather than derived: the template is a rectangle, so its own y=0 is the tip of
     * the underside, not the surface. {@code scripts/map/lobby.py} prints this number, and a
     * mismatch is logged rather than silently shifting the island.
     */
    private static final int TEMPLATE_SURFACE = 10;

    /** Template footprint, for the block-protection box and the size check. */
    private static final int TEMPLATE_X = 55;
    private static final int TEMPLATE_Y = 17;
    private static final int TEMPLATE_Z = 55;

    /**
     * How far around the island the protection box reaches, in blocks.
     *
     * <p>Wider than the build: a guest can place a block in mid-air beside the island and it would
     * otherwise survive until the next start.
     */
    private static final int PROTECT_MARGIN = 8;

    private static final ResourceLocation TEMPLATE = PvPocket.id("lobby");

    private Lobby() {
    }

    /**
     * Clears the lobby box and stamps the island into it. Called once per server start.
     *
     * <p>Idempotent, and not incremental: the island is small enough that rebuilding it is cheaper
     * than deciding what a guest changed. A start with the template missing leaves whatever was
     * there, which is why that failure is an error rather than a warning.
     */
    public static void build(MinecraftServer server) {
        ServerLevel world = ArenaManager.world(server);
        if (world == null) {
            PvPocket.LOGGER.warn("Duel dimension is not loaded; the lobby was not built");
            return;
        }
        Optional<StructureTemplate> loaded = server.getStructureManager().get(TEMPLATE);
        if (loaded.isEmpty()) {
            PvPocket.LOGGER.error("Lobby template {} is missing from the jar", TEMPLATE);
            return;
        }
        StructureTemplate template = loaded.get();
        Vec3i size = template.getSize();
        if (size.getX() != TEMPLATE_X || size.getY() != TEMPLATE_Y || size.getZ() != TEMPLATE_Z) {
            PvPocket.LOGGER.warn("Lobby template is {}x{}x{}, expected {}x{}x{}; check the constants",
                    size.getX(), size.getY(), size.getZ(), TEMPLATE_X, TEMPLATE_Y, TEMPLATE_Z);
        }
        int originX = CENTER_X - size.getX() / 2;
        int originZ = CENTER_Z - size.getZ() / 2;
        int originY = FLOOR_Y - TEMPLATE_SURFACE;
        long started = System.nanoTime();
        int erased = clear(world, originX, originY, originZ, size);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(false)
                .setFinalizeEntities(false);
        BlockPos origin = new BlockPos(originX, originY, originZ);
        boolean placed = template.placeInWorld(world, origin, origin, settings,
                RandomSource.create(0L), Block.UPDATE_CLIENTS);
        PvPocket.LOGGER.info("Lobby {} at {},{} (surface {}, spawn {}, erased {} blocks) in {} ms",
                placed ? "stamped" : "NOT placed", originX, originZ, FLOOR_Y, SPAWN_Y,
                erased, (System.nanoTime() - started) / 1_000_000L);
    }

    /** Empties the template's box plus a margin, so a restart does not keep what a guest placed. */
    private static int clear(ServerLevel world, int originX, int originY, int originZ, Vec3i size) {
        var air = Blocks.AIR.defaultBlockState();
        int erased = 0;
        for (int x = originX - PROTECT_MARGIN; x < originX + size.getX() + PROTECT_MARGIN; x++) {
            for (int z = originZ - PROTECT_MARGIN; z < originZ + size.getZ() + PROTECT_MARGIN; z++) {
                for (int y = originY - 2; y < originY + size.getY() + 2; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos).isAir()) {
                        continue;
                    }
                    world.setBlock(pos, air, Block.UPDATE_CLIENTS);
                    erased++;
                }
            }
        }
        return erased;
    }

    /**
     * Puts one player on the island.
     *
     * @return false when the duel dimension is not loaded, which the caller journals
     */
    public static boolean teleport(ServerPlayer player) {
        ServerLevel world = ArenaManager.world(PvCompat.server(player));
        if (world == null) {
            return false;
        }
        player.stopRiding();
        PvCompat.teleport(player, world, SPAWN_X + 0.5D, SPAWN_Y, SPAWN_Z + 0.5D, SPAWN_YAW, 0.0F);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.fallDistance = 0.0F;
        return true;
    }

    /**
     * Whether this position belongs to the lobby.
     *
     * <p>Asked by the block-break veto. The lobby is rebuilt at every start, so a broken block is
     * not permanent, but a guest punching a hole in the dais during a countdown is still worth
     * refusing.
     */
    public static boolean contains(Level world, BlockPos pos) {
        if (!ArenaManager.isArena(world)) {
            return false;
        }
        int reachX = TEMPLATE_X / 2 + PROTECT_MARGIN;
        int reachZ = TEMPLATE_Z / 2 + PROTECT_MARGIN;
        return Math.abs(pos.getX() - CENTER_X) <= reachX
                && Math.abs(pos.getZ() - CENTER_Z) <= reachZ
                && pos.getY() >= FLOOR_Y - TEMPLATE_SURFACE - 2
                && pos.getY() <= FLOOR_Y + TEMPLATE_Y;
    }

    /** True when the player has fallen past the island, i.e. is about to leave the world. */
    public static boolean belowIsland(ServerPlayer player) {
        return ArenaManager.isArena(player.level()) && player.getY() < FLOOR_Y - 6;
    }
}
