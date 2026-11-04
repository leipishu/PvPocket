package com.leipishu.pvpocket.arena;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * One duel plot inside the shared arena dimension.
 *
 * <p>The dimension's flat generator is fixed: bedrock at y=0, stone at y=1..2, air above. That makes
 * the <em>baseline</em> of every plot computable rather than stored — which is what lets this class
 * answer "is this block part of the map or did a player put it there?" without a block snapshot, and
 * lets a round be rolled back by writing the baseline back over the handful of positions that
 * actually changed.</p>
 *
 * <p>Regions are laid out on a grid by {@link ArenaManager}; a region never overlaps another one, so
 * the boundary test of one plot cannot accidentally accept a player standing in the next plot.</p>
 */
public final class ArenaRegion {

    /** y=0 is bedrock, y=1..2 stone, y=3 and above is the walkable surface. */
    public static final int FLOOR_Y = 0;
    public static final int STONE_TO = 2;
    public static final int SURFACE_Y = 3;

    /**
     * Upper bound on tracked deviations. A 128x128 plot stacked to the ceiling is 1M blocks, which is
     * far beyond anything a duel needs; the cap exists so a griefing client cannot exhaust memory by
     * spamming placement packets.
     */
    private static final int MAX_CHANGES = 200_000;

    public final String id;
    public final int index;
    /** Minimum corner of the plot, inclusive. */
    public final int minX;
    public final int minZ;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;

    /** Copied from the session's rule set when the plot is leased, so protection needs no lookup. */
    public final boolean protectTerrain;
    public final boolean allowBreakOwn;

    /** Positions whose current block differs from {@link #baseline(int)}. Packed longs. */
    private final LongOpenHashSet changed = new LongOpenHashSet();

    ArenaRegion(String id, int index, int minX, int minZ, int sizeX, int sizeY, int sizeZ,
                boolean protectTerrain, boolean allowBreakOwn) {
        this.id = id;
        this.index = index;
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.protectTerrain = protectTerrain;
        this.allowBreakOwn = allowBreakOwn;
    }

    /** What the generator would have put here. Identity comparison against a live state is valid. */
    public static BlockState baseline(int y) {
        if (y == FLOOR_Y) {
            return Blocks.BEDROCK.getDefaultState();
        }
        if (y <= STONE_TO) {
            return Blocks.STONE.getDefaultState();
        }
        return Blocks.AIR.getDefaultState();
    }

    public int maxX() {
        return minX + sizeX - 1;
    }

    public int maxZ() {
        return minZ + sizeZ - 1;
    }

    public int centerX() {
        return minX + sizeX / 2;
    }

    public int centerZ() {
        return minZ + sizeZ / 2;
    }

    /** Spawn pads sit four blocks inside each end so a player never starts against the boundary. */
    public int spawnX(int spawnIndex) {
        return spawnIndex == 0 ? minX + 4 : minX + sizeX - 5;
    }

    public int spawnZ() {
        return centerZ();
    }

    public float spawnYaw(int spawnIndex) {
        return spawnIndex == 0 ? -90.0F : 90.0F;
    }

    /** Horizontal containment. Vertical is only checked downwards — see {@link #contains(BlockPos)}. */
    public boolean contains(double x, double z) {
        return x >= minX && x <= maxX() + 1.0D && z >= minZ && z <= maxZ() + 1.0D;
    }

    /**
     * Full containment used for block edits: a block at the plot edge still belongs to the plot.
     *
     * <p>There is no ceiling test on purpose. A player above the nominal build height is not doing
     * anything the round needs to punish, and cancelling their placement because they drifted one
     * block too high would be indistinguishable from a bug.</p>
     */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX()
                && pos.getZ() >= minZ && pos.getZ() <= maxZ()
                && pos.getY() >= FLOOR_Y;
    }

    /**
     * True when the player is still on the plot. Falling below the floor counts as leaving: the only
     * way down is through bedrock, which means something already went wrong.
     */
    public boolean isInside(double x, double y, double z) {
        return y >= FLOOR_Y - 24.0D && contains(x, z);
    }

    public Box entityBox(int bottomY, int topY) {
        return new Box(minX, bottomY, minZ, maxX() + 1.0D, topY, maxZ() + 1.0D);
    }

    /** True when this position currently deviates from the map baseline, i.e. a player changed it. */
    public boolean isChanged(BlockPos pos) {
        return changed.contains(pos.asLong());
    }

    public int changeCount() {
        return changed.size();
    }

    public boolean changeLimitReached() {
        return changed.size() >= MAX_CHANGES;
    }

    /**
     * Called for every block write inside the plot.
     *
     * <p>Storing "differs from baseline" rather than "old state" is what keeps this cheap: reverting
     * is a baseline write, not a replay of a history, so a block broken and then replaced leaves no
     * trace instead of leaving two entries.</p>
     */
    public void onBlockChanged(BlockPos pos, BlockState newState) {
        long key = pos.asLong();
        if (newState == baseline(pos.getY())) {
            changed.remove(key);
        } else if (changed.size() < MAX_CHANGES) {
            changed.add(key);
        }
    }

    /**
     * Writes the baseline back over every deviation, newest state irrelevant.
     *
     * <p>Iterating the set while mutating it is safe because {@code writeBaseline} is called with
     * recording suppressed — see {@link ArenaManager#suppressRecording()}.</p>
     */
    public int revert(ServerWorld world) {
        if (changed.isEmpty()) {
            return 0;
        }
        int reverted = 0;
        long[] keys = changed.toLongArray();
        changed.clear();
        for (long key : keys) {
            BlockPos pos = BlockPos.fromLong(key);
            world.setBlockState(pos, baseline(pos.getY()), Block.NOTIFY_LISTENERS);
            reverted++;
        }
        return reverted;
    }

    /**
     * Belt-and-braces cleanup used when a plot is handed to a new session.
     *
     * <p>Reverting at settle already restores the plot, so this only matters after a crash left a
     * round half-finished: the region's in-memory deviation set died with the process, and without a
     * scan the debris would become part of the next duel's map.</p>
     *
     * <p>Scanned height is capped at the plot's own build height plus a small margin. Debris placed
     * above the ceiling of a <em>shorter</em> later duel can therefore survive a crash; that is a
     * cosmetic leak in an unused volume, and the alternative (scanning all 256 layers) costs an order
     * of magnitude more for no gameplay benefit.</p>
     */
    public int reset(ServerWorld world) {
        int top = Math.min(SURFACE_Y + sizeY + 4, world.getTopY() - 1);
        int restored = 0;
        for (int y = FLOOR_Y; y <= top; y++) {
            BlockState want = baseline(y);
            for (int x = minX; x <= maxX(); x++) {
                for (int z = minZ; z <= maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos) != want) {
                        world.setBlockState(pos, want, Block.NOTIFY_LISTENERS);
                        restored++;
                    }
                }
            }
        }
        changed.clear();
        return restored;
    }

    @Override
    public String toString() {
        return id + "[" + sizeX + "x" + sizeY + "x" + sizeZ + " @" + minX + "," + minZ + "]";
    }
}
