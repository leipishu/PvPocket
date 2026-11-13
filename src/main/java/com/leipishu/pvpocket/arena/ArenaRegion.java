package com.leipishu.pvpocket.arena;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * One duel plot inside the shared arena dimension.
 *
 * <p>The baseline of every plot is computed rather than stored, which lets this class tell a map
 * block from a player edit and roll a round back by writing the baseline over changed positions.
 *
 * <p>For a flat plot the baseline is air everywhere except the platform the plot builds. For a map,
 * {@link #capture} records the non-air blocks after the template is stamped; an 88x36x87 town costs
 * 60k entries rather than 275k.
 *
 * <p>Regions are laid out on a grid by {@link ArenaManager}; a region never overlaps another one.
 */
public final class ArenaRegion {

    /**
     * Bottom of the dimension.
     *
     * <p>The arena is a void world and every arena is a structure floating in it, so this is only
     * the lower bound a reset has to sweep.
     */
    public static final int FLOOR_Y = 0;

    /** The y a map's template is pasted at: template-local y=0 lands here. */
    public static final int SURFACE_Y = 3;

    /**
     * The block a player stands on in a flat plot.
     *
     * <p>One below {@link #SURFACE_Y}, so a flat arena's walkable surface matches a pasted map's.
     * The flat arena is the only one that needs a floor built for it.
     */
    public static final int PLATFORM_Y = SURFACE_Y - 1;

    /**
     * How far outside a plot the invisible buffer extends, in blocks.
     *
     * <p>The buffer is a shelf of barrier blocks one below the arena floor, ringed by a barrier
     * wall, so walking off the map lands on something solid instead of falling into the void.
     * Eight blocks catches a knockback and still leaves the shelf outside the boundary curtain.
     */
    public static final int BUFFER = 8;

    /** Spawn pads per plot: one per side, and a match has at most four sides. */
    public static final int PAD_COUNT = 4;

    /**
     * Upper bound on tracked deviations. A 128x128 plot stacked to the ceiling is 1M blocks; the
     * cap stops a griefing client from exhausting memory by spamming placement packets.
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

    /**
     * The cell this plot sits in, which is larger than the plot itself.
     *
     * <p>Reset clears the cell rather than the plot box, so a cell that held a 147-wide map and
     * then gets a 45-wide one leaves nothing of the first map behind.
     */
    public final int cellMinX;
    public final int cellMinZ;
    public final int cellSizeX;
    public final int cellSizeZ;

    /**
     * Copied from the session's rule set on every lease, so protection needs no lookup.
     *
     * <p>Not final: a cell keeps its stamped map between duels, so the same region object is leased
     * again with a different rule set.
     */
    public boolean protectTerrain;
    public boolean allowBreakOwn;

    /** The selected map, or {@link ArenaMap#FLAT}. Never null. */
    public final ArenaMap map;

    /** Positions whose current block differs from the baseline. Packed longs. */
    private final LongOpenHashSet changed = new LongOpenHashSet();

    /**
     * Blocks the preparation screens wrote, so teardown can be exact.
     *
     * <p>The ring around the plot is cleared by scanning its own coordinates for {@code barrier},
     * which is safe because no map contains that block. The screens sit inside the fight area,
     * where a map may use barrier for its own invisible walls, so the same scan would open a hole
     * in the build. These are the positions that were air before the screen was raised.
     *
     * <p>In memory only: the next lease's {@link #reset} clears the cell to air, which removes any
     * orphaned barrier.
     */
    private final LongOpenHashSet screens = new LongOpenHashSet();

    /**
     * Non-air blocks of the stamped map, keyed by plot-relative position.
     *
     * <p>Empty for the flat plot. Rebuilt on every lease and never persisted.
     */
    private final Long2ObjectOpenHashMap<BlockState> template = new Long2ObjectOpenHashMap<>();

    /**
     * Resolved spawn pads, filled by {@link #capture}. Until then the nominal guess is used, which
     * is all a flat plot needs.
     *
     * <p>Four pads, not two: a match can have up to four sides, and sides 2 and 3 sit on the axis
     * the first two do not use.
     */
    private final int[] padX = new int[PAD_COUNT];
    private final int[] padY = {SURFACE_Y, SURFACE_Y, SURFACE_Y, SURFACE_Y};
    private final int[] padZ = new int[PAD_COUNT];
    private boolean padsResolved;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    ArenaRegion(String id, int index, int minX, int minZ, int sizeX, int sizeY, int sizeZ,
                int cellMinX, int cellMinZ, int cellSizeX, int cellSizeZ,
                ArenaMap map, boolean protectTerrain, boolean allowBreakOwn) {
        this.id = id;
        this.index = index;
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cellMinX = cellMinX;
        this.cellMinZ = cellMinZ;
        this.cellSizeX = cellSizeX;
        this.cellSizeZ = cellSizeZ;
        this.map = map == null ? ArenaMap.FLAT : map;
        this.protectTerrain = protectTerrain;
        this.allowBreakOwn = allowBreakOwn;
    }

    /** True when this stamped plot already is exactly what the caller is asking for. */
    public boolean matches(ArenaMap map, int sizeX, int sizeY, int sizeZ) {
        return this.map == map && this.sizeX == sizeX && this.sizeY == sizeY && this.sizeZ == sizeZ;
    }

    /**
     * Hands an already-stamped cell to a new session without touching the terrain.
     *
     * <p>Only the deviation set and the per-duel rules are reset. The map stays where it is, which
     * keeps each map's address stable: the colosseum is always in its own cell.
     */
    public void lease(boolean protectTerrain, boolean allowBreakOwn) {
        this.protectTerrain = protectTerrain;
        this.allowBreakOwn = allowBreakOwn;
        changed.clear();
        screens.clear();
    }

    /** Records one block of a preparation screen, so {@link #clearScreens} can take it back. */
    public void trackScreen(BlockPos pos) {
        screens.add(pos.asLong());
    }

    public int screenCount() {
        return screens.size();
    }

    /**
     * Removes every screen block that is still barrier.
     *
     * <p>Checked against the block rather than assumed, so a position a player broke during the
     * countdown is skipped.
     */
    public int clearScreens(ServerLevel world) {
        if (screens.isEmpty()) {
            return 0;
        }
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        long[] keys = screens.toLongArray();
        screens.clear();
        int removed = 0;
        for (long key : keys) {
            BlockPos pos = BlockPos.of(key);
            if (world.getBlockState(pos) == barrier) {
                world.setBlock(pos, air, Block.UPDATE_CLIENTS);
                removed++;
            }
        }
        return removed;
    }

    /**
     * The generated baseline: air.
     *
     * <p>The duel dimension is a void world, so every position not covered by a template is air.
     */
    public static BlockState flatBaseline(int y) {
        return Blocks.AIR.defaultBlockState();
    }

    /**
     * What this position is supposed to hold: the map if there is one, the flat baseline otherwise.
     *
     * <p>Called once per block write and once per reverted block, so it has to stay a hash lookup.
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
     * <p>Reads the volume back from the world rather than the template's block list, so whatever is
     * in the world after the paste is what a revert restores.
     */
    public void capture(ServerLevel world) {
        template.clear();
        // Starts one below the map base so the flat arena's platform is captured too; otherwise
        // the first revert would delete the ground the players stand on.
        int top = SURFACE_Y + sizeY;
        for (int y = PLATFORM_Y; y < top; y++) {
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
     * <p>The nominal pad is a quarter of the span either side of the centre, which on real maps can
     * land underwater, inside solid rock, or over a void. The search walks outward in rings and
     * takes the first column with a non-fluid floor and two blocks of air above it, falling back to
     * the nominal point when nothing is found.
     */
    private void resolvePads(ServerLevel world) {
        for (int i = 0; i < PAD_COUNT; i++) {
            int[] found = findStand(world, nominalSpawnX(i), nominalSpawnZ(i));
            padX[i] = found[0];
            padY[i] = found[1];
            padZ[i] = found[2];
        }
        padsResolved = true;
    }

    /**
     * How far the pad search may walk before giving up, in blocks.
     *
     * <p>24 covers Avalon, which is 79 deep with its bases at {@code z=+-35}: the nominal pad lands
     * at {@code z=+-19} over the void, and the slabs are found at {@code z=+-33}. The cost is 48x48
     * columns, paid once per lease.
     */
    private static final int MAX_PAD_SEARCH = 24;

    /** Ring search around a target column. Returns {x, feetY, z}; feetY is -1 when none. */
    private int[] findStand(ServerLevel world, int targetX, int targetZ) {
        for (int radius = 0; radius <= MAX_PAD_SEARCH; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Ring only: the square inside was covered by the previous radius.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = targetX + dx;
                    int z = targetZ + dz;
                    // Searched inside the fight area, not the plot box: a pad outside the boundary
                    // would spawn a player judged to have left before they can move.
                    if (x < pitMinX() || x > pitMaxX() || z < pitMinZ() || z > pitMaxZ()) {
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
     * <p>A fluid is not a floor. Scanning up rather than down matters because a roofed arena has a
     * solid ceiling well above the pit, and spawning on the roof drops the player in from above.
     */
    public int standY(ServerLevel world, int x, int z) {
        int top = SURFACE_Y + sizeY;
        for (int y = PLATFORM_Y; y + 2 < top; y++) {
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
        return Math.max(0, Math.min(PAD_COUNT - 1, index));
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

    // ------------------------------------------------------------------ fight area

    /**
     * The rectangle a round is actually fought in, which is the plot box for most maps.
     *
     * <p>Separate from {@link #minX} and {@link #maxX()} because a bounding box is not a footprint:
     * the colosseum's box is its outer wall plus ten rows of seating, and the sand is 100x80 inside
     * it. Boundary decisions belong on the fight area; block protection and entity cleanup stay on
     * the plot box.
     *
     * <p>For a flat plot the two rectangles are identical.
     */
    public int pitMinX() {
        return minX + map.pitInsetX;
    }

    public int pitMaxX() {
        return maxX() - map.pitInsetX;
    }

    public int pitMinZ() {
        return minZ + map.pitInsetZ;
    }

    public int pitMaxZ() {
        return maxZ() - map.pitInsetZ;
    }

    /** Horizontal containment in the fight area, with the outer face included. */
    public boolean inBounds(double x, double z) {
        return x >= pitMinX() && x <= pitMaxX() + 1.0D
                && z >= pitMinZ() && z <= pitMaxZ() + 1.0D;
    }

    /**
     * Nominal spawn X for a side, before the pad search runs.
     *
     * <p>The flat plot puts pads four blocks inside each end. A map places them a quarter of the
     * span either side of the centre, along whichever axis the build separates its sides on; see
     * {@link ArenaMap#alongZ}.
     *
     * <p>Sides 2 and 3 sit on the other axis, halfway between the ends.
     */
    private int nominalSpawnX(int spawnIndex) {
        int reachX = Math.max(6, sizeX / 4);
        if (!map.isFlat()) {
            if (map.alongZ) {
                return switch (spawnIndex) {
                    case 2 -> centerX() - reachX;
                    case 3 -> centerX() + reachX;
                    default -> centerX();
                };
            }
            return switch (spawnIndex) {
                case 0 -> centerX() - reachX;
                case 1 -> centerX() + reachX;
                default -> centerX();
            };
        }
        return switch (spawnIndex) {
            case 0 -> minX + 4;
            case 1 -> minX + sizeX - 5;
            default -> centerX();
        };
    }

    private int nominalSpawnZ(int spawnIndex) {
        int reachZ = Math.max(6, sizeZ / 4);
        if (!map.isFlat() && map.alongZ) {
            return switch (spawnIndex) {
                case 0 -> centerZ() - reachZ;
                case 1 -> centerZ() + reachZ;
                default -> centerZ();
            };
        }
        if (!map.isFlat()) {
            return switch (spawnIndex) {
                case 2 -> centerZ() - reachZ;
                case 3 -> centerZ() + reachZ;
                default -> centerZ();
            };
        }
        if (spawnIndex == 2) {
            return centerZ() - reachZ;
        }
        if (spawnIndex == 3) {
            return centerZ() + reachZ;
        }
        return centerZ();
    }

    /**
     * Faces the opponent: along whichever axis this pad sits on.
     *
     * <p>Per pad, not per map. Sides 0 and 1 sit on the map's own axis and sides 2 and 3 on the
     * perpendicular one, so reusing the map's axis for all four points two of them at the wall
     * behind them.
     */
    public float spawnYaw(int spawnIndex) {
        if (padAlongZ(spawnIndex)) {
            // yaw 0 is +Z (south), 180 is -Z (north).
            return spawnIndex % 2 == 0 ? 0.0F : 180.0F;
        }
        return spawnIndex % 2 == 0 ? -90.0F : 90.0F;
    }

    /**
     * True when this side faces along Z, i.e. the two ends of its axis are north and south.
     *
     * <p>The preparation screen uses the same fact to know which way it should be a plane: a side
     * that faces along Z is screened by a wall at a constant Z spanning X, and vice versa.
     */
    public boolean padAlongZ(int spawnIndex) {
        boolean mapAlongZ = !map.isFlat() && map.alongZ;
        return spawnIndex >= 2 ? !mapAlongZ : mapAlongZ;
    }

    /**
     * Horizontal containment over the plot box. Vertical is checked only downwards, see
     * {@link #contains(BlockPos)}.
     */
    public boolean contains(double x, double z) {
        return x >= minX && x <= maxX() + 1.0D && z >= minZ && z <= maxZ() + 1.0D;
    }

    /**
     * Full containment used for block edits: a block at the plot edge still belongs to the plot.
     *
     * <p>There is no ceiling test. A player above the nominal build height is not doing anything
     * the round needs to punish.
     */
    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX()
                && pos.getZ() >= minZ && pos.getZ() <= maxZ()
                && pos.getY() >= FLOOR_Y;
    }

    /**
     * True when the player is still on the fight area.
     *
     * <p>Falling more than eight blocks below the platform counts as leaving, about one second of
     * falling, so a player who drops out of the world is caught before void damage.
     *
     * <p>Uses {@link #inBounds} rather than {@link #contains(double, double)}: the boundary rule
     * judges the fight area, not the plot box.
     */
    public boolean isInside(double x, double y, double z) {
        return y >= PLATFORM_Y - 8.0D && inBounds(x, z);
    }

    public AABB entityBox(int bottomY, int topY) {
        return new AABB(minX, bottomY, minZ, maxX() + 1.0D, topY, maxZ() + 1.0D);
    }

    /** True when this position deviates from the map baseline, i.e. a player changed it. */
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
     * <p>Storing "differs from baseline" rather than the old state keeps revert cheap: reverting is
     * a baseline write, so a block broken and then replaced leaves no entry.
     *
     * <p>Compared by block, not by state: vanilla blocks rewrite their own state on a random tick,
     * so state drift would read as a player edit; 253 braziers produced 2050 changes in one round.
     *
     * <p>A player who replaces a block with a differently rotated copy of the same block is not
     * recorded.
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
     * Writes the baseline back over every deviation.
     *
     * <p>Iterating the set while mutating it is safe because the write runs with recording
     * suppressed, see {@link ArenaManager#suppressRecording()}.
     */
    public int revert(ServerLevel world) {
        if (changed.isEmpty()) {
            return 0;
        }
        int reverted = 0;
        long[] keys = changed.toLongArray();
        changed.clear();
        for (long key : keys) {
            BlockPos pos = BlockPos.of(key);
            world.setBlock(pos, baseline(pos), Block.UPDATE_CLIENTS);
            reverted++;
        }
        return reverted;
    }

    /**
     * Clears the cell to air when a plot is handed to a new session.
     *
     * <p>Needed after a crash left a round half-finished and the in-memory deviation set died with
     * the process. The volume is the union of the cell and the plot, plus {@link #BUFFER} blocks on
     * every side for the barrier shelf, so a cell that held the 147x119 colosseum and then gets the
     * 45x60 island leaves nothing behind.
     *
     * <p>Scanned height is capped at the requested build height plus a small margin.
     *
     * @param clearSizeY how tall to scan; pass the taller of the outgoing and incoming map
     */
    public int reset(ServerLevel world, int clearSizeY) {
        int top = Math.min(SURFACE_Y + Math.max(clearSizeY, sizeY) + 4, world.getHeight() - 1);
        int restored = 0;
        int fromX = Math.min(cellMinX, minX) - BUFFER;
        int toX = Math.max(cellMinX + cellSizeX, maxX() + 1) + BUFFER;
        int fromZ = Math.min(cellMinZ, minZ) - BUFFER;
        int toZ = Math.max(cellMinZ + cellSizeZ, maxZ() + 1) + BUFFER;
        for (int y = FLOOR_Y; y <= top; y++) {
            // Flat baseline, not the map baseline: a reset erases whatever the previous lease left,
            // and restoring the current map would leave old blocks where the two do not overlap.
            BlockState want = flatBaseline(y);
            for (int x = fromX; x < toX; x++) {
                for (int z = fromZ; z < toZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos) != want) {
                        world.setBlock(pos, want, Block.UPDATE_CLIENTS);
                        restored++;
                    }
                }
            }
        }
        changed.clear();
        screens.clear();
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
