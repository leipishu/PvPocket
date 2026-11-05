package com.leipishu.pvpocket.arena;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
 * <p>The <em>baseline</em> of every plot is computable rather than stored — which is what lets this
 * class answer "is this block part of the map or did a player put it there?" without a block
 * snapshot, and lets a round be rolled back by writing the baseline back over the handful of
 * positions that actually changed.</p>
 *
 * <p>For the generated flat plot the baseline is a pure function of y. For a map it is the map: after
 * the template is stamped, {@link #capture} records the non-air blocks and the baseline becomes
 * "look it up, fall back to flat". Only non-air blocks are kept, so a 102x44x99 bridge costs
 * 72k entries rather than 580k, and the fallback covers the air correctly because flat baseline
 * above {@link #SURFACE_Y} is air too.</p>
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

    /** The selected map, or {@link ArenaMap#FLAT}. Never null. */
    public final ArenaMap map;

    /** Positions whose current block differs from the baseline. Packed longs. */
    private final LongOpenHashSet changed = new LongOpenHashSet();

    /**
     * Non-air blocks of the stamped map, keyed by plot-relative position.
     *
     * <p>Empty for the flat plot. Rebuilt on every lease, never persisted: the map is the source of
     * truth and re-reading it is cheaper than keeping a copy in memory between sessions.</p>
     */
    private final Long2ObjectOpenHashMap<BlockState> template = new Long2ObjectOpenHashMap<>();

    /**
     * Resolved spawn pads, filled by {@link #capture}. Until then the nominal guess is used, which
     * is all a flat plot needs.
     */
    private final int[] padX = new int[2];
    private final int[] padY = {SURFACE_Y, SURFACE_Y};
    private final int[] padZ = new int[2];
    private boolean padsResolved;

    private final BlockPos.Mutable cursor = new BlockPos.Mutable();

    ArenaRegion(String id, int index, int minX, int minZ, int sizeX, int sizeY, int sizeZ,
                ArenaMap map, boolean protectTerrain, boolean allowBreakOwn) {
        this.id = id;
        this.index = index;
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.map = map == null ? ArenaMap.FLAT : map;
        this.protectTerrain = protectTerrain;
        this.allowBreakOwn = allowBreakOwn;
    }

    /** What the generator would have put here. Identity comparison against a live state is valid. */
    public static BlockState flatBaseline(int y) {
        if (y == FLOOR_Y) {
            return Blocks.BEDROCK.getDefaultState();
        }
        if (y <= STONE_TO) {
            return Blocks.STONE.getDefaultState();
        }
        return Blocks.AIR.getDefaultState();
    }

    /**
     * What this position is supposed to hold: the map if there is one, the flat generator otherwise.
     *
     * <p>Called once per block write and once per reverted block, so it has to stay a hash lookup.
     * The {@code isEmpty} guard means the flat path costs nothing beyond a branch.</p>
     */
    public BlockState baseline(int x, int y, int z) {
        if (!template.isEmpty()) {
            BlockState state = template.get(BlockPos.asLong(x - minX, y - SURFACE_Y, z - minZ));
            if (state != null) {
                return state;
            }
        }
        return flatBaseline(y);
    }

    public BlockState baseline(BlockPos pos) {
        return baseline(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Records the freshly pasted map as the new baseline.
     *
     * <p>Reading the volume back instead of walking the template's own block list keeps this class
     * free of any assumption about how the template stores its palette — whatever is in the world
     * after the paste is, by definition, what a revert should restore.</p>
     */
    public void capture(ServerWorld world) {
        template.clear();
        int top = SURFACE_Y + sizeY;
        for (int y = SURFACE_Y; y < top; y++) {
            for (int x = minX; x <= maxX(); x++) {
                for (int z = minZ; z <= maxZ(); z++) {
                    BlockState state = world.getBlockState(cursor.set(x, y, z));
                    if (!state.isAir()) {
                        template.put(BlockPos.asLong(x - minX, y - SURFACE_Y, z - minZ), state);
                    }
                }
            }
        }
        resolvePads(world);
    }

    /**
     * Finds a spot each player can actually stand on, near where the nominal pad says.
     *
     * <p>The nominal pad is a guess ("a quarter of the span either side of the centre") and on real
     * maps it lands inside things: measured against the three shipped templates, one pad was
     * underwater and two were inside solid rock with no headroom. Teleporting there means a player
     * starts drowning, suffocating, or stuck in a wall — which reads as a broken duel, not as a
     * misplaced coordinate.</p>
     *
     * <p>So the guess is only a starting point. The search walks outward in rings and takes the
     * first column that has a non-fluid floor with two blocks of air above it. Falling back to the
     * nominal point when nothing is found keeps a pathological map from dropping players at the
     * origin of the dimension.</p>
     */
    private void resolvePads(ServerWorld world) {
        for (int i = 0; i < 2; i++) {
            int[] found = findStand(world, nominalSpawnX(i), nominalSpawnZ(i));
            padX[i] = found[0];
            padY[i] = found[1];
            padZ[i] = found[2];
        }
        padsResolved = true;
    }

    /** Ring search around a target column. Returns {x, feetY, z}; feetY -1 means "nothing found". */
    private int[] findStand(ServerWorld world, int targetX, int targetZ) {
        for (int radius = 0; radius <= 12; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Ring only: the square inside was covered by the previous radius.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = targetX + dx;
                    int z = targetZ + dz;
                    if (x < minX || x > maxX() || z < minZ || z > maxZ()) {
                        continue;
                    }
                    int y = standY(world, x, z);
                    if (y >= 0) {
                        return new int[]{x, y, z};
                    }
                }
            }
        }
        return new int[]{targetX, SURFACE_Y, targetZ};
    }

    /**
     * Feet height of the first standable gap in a column, or -1.
     *
     * <p>A fluid is not a floor: the bridge map's nominal pad sat on top of open water, and
     * treating that as ground put the player in it. Scanning up rather than down matters too — a
     * roofed arena has a solid ceiling well above the pit, and spawning on the roof drops the
     * player into the fight from above.</p>
     */
    private int standY(ServerWorld world, int x, int z) {
        int top = SURFACE_Y + sizeY;
        for (int y = SURFACE_Y; y + 2 < top; y++) {
            BlockState floor = world.getBlockState(cursor.set(x, y, z));
            if (floor.isAir() || !floor.getFluidState().isEmpty()) {
                continue;
            }
            if (world.getBlockState(cursor.set(x, y + 1, z)).isAir()
                    && world.getBlockState(cursor.set(x, y + 2, z)).isAir()) {
                return y + 1;
            }
        }
        return -1;
    }

    public int spawnX(int spawnIndex) {
        int i = clampIndex(spawnIndex);
        return padsResolved ? padX[i] : nominalSpawnX(i);
    }

    public int spawnY(int spawnIndex) {
        int i = clampIndex(spawnIndex);
        return padsResolved ? padY[i] : SURFACE_Y;
    }

    public int spawnZ(int spawnIndex) {
        int i = clampIndex(spawnIndex);
        return padsResolved ? padZ[i] : nominalSpawnZ(i);
    }

    private static int clampIndex(int index) {
        return Math.max(0, Math.min(1, index));
    }

    public int templateBlockCount() {
        return template.size();
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

    /**
     * Spawn pads.
     *
     * <p>The flat plot puts them four blocks inside each end, because the two players start at
     * opposite ends of a bare rectangle. A downloaded map is not a rectangle: its two ends are
     * stands, raised bases, or the far side of a bridge, and spawning there drops the player onto
     * something that is not the floor of the fight. For a map the pads are placed a quarter of the
     * span either side of the centre instead, along whichever axis the build separates its sides
     * on — see {@link ArenaMap#alongZ}.</p>
     */
    private int nominalSpawnX(int spawnIndex) {
        if (!map.isFlat()) {
            if (map.alongZ) {
                return centerX();
            }
            int reach = Math.max(6, sizeX / 4);
            return spawnIndex == 0 ? centerX() - reach : centerX() + reach;
        }
        return spawnIndex == 0 ? minX + 4 : minX + sizeX - 5;
    }

    private int nominalSpawnZ(int spawnIndex) {
        if (!map.isFlat() && map.alongZ) {
            int reach = Math.max(6, sizeZ / 4);
            return spawnIndex == 0 ? centerZ() - reach : centerZ() + reach;
        }
        return centerZ();
    }

    /** Faces the opponent: along X for a flat plot, along whichever axis the map is built on. */
    public float spawnYaw(int spawnIndex) {
        if (!map.isFlat() && map.alongZ) {
            // yaw 0 is +Z (south), 180 is -Z (north).
            return spawnIndex == 0 ? 0.0F : 180.0F;
        }
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
     *
     * <p><b>Compared by block, not by state.</b> Block states are interned per property combination,
     * so a state comparison treats every property drift as a player edit — and a dozen vanilla blocks
     * rewrite their own state on a random tick: fire climbs {@code age} until it burns out, water
     * settles its {@code level}, farmland loses {@code moisture}, leaves age their {@code distance},
     * redstone updates {@code power}, snow gains {@code layers}. In one downloaded map the 253 braziers
     * alone produced 2050 "changes" over one round in which nobody touched a block.</p>
     *
     * <p>The trade-off is that a player who replaces a block with a differently-rotated copy of the
     * same block is not recorded. That is cosmetic — the block is already the one the map wants, and
     * the next lease wipes the plot regardless — whereas the property drift it would cost us is
     * thousands of wasted writes per round.</p>
     */
    public void onBlockChanged(BlockPos pos, BlockState newState) {
        long key = pos.asLong();
        if (newState.getBlock() == baseline(pos).getBlock()) {
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
            world.setBlockState(pos, baseline(pos), Block.NOTIFY_LISTENERS);
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
            // Flat baseline, not the map baseline: the point of a reset is to erase whatever the
            // previous lease left behind, and restoring it to the *current* map would leave the old
            // map's blocks standing wherever the two do not overlap.
            BlockState want = flatBaseline(y);
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
        template.clear();
        padsResolved = false;
        padY[0] = SURFACE_Y;
        padY[1] = SURFACE_Y;
        return restored;
    }

    @Override
    public String toString() {
        return id + "[" + sizeX + "x" + sizeY + "x" + sizeZ + " @" + minX + "," + minZ
                + " " + map.id + "]";
    }
}
