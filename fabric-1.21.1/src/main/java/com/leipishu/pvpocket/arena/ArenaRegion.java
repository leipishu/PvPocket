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
 * <p>For the generated flat plot the baseline is a pure function of y — and since the dimension is
 * void, that function is "air" everywhere except the platform the plot builds for itself. For a map
 * it is the map: after the template is stamped, {@link #capture} records the non-air blocks and the
 * baseline becomes "look it up, fall back to air". Only non-air blocks are kept, so an 88x36x87 town
 * costs 60k entries rather than 275k, and the fallback covers the empty space correctly because the
 * void baseline is air too.</p>
 *
 * <p>Regions are laid out on a grid by {@link ArenaManager}; a region never overlaps another one, so
 * the boundary test of one plot cannot accidentally accept a player standing in the next plot.</p>
 */
public final class ArenaRegion {

    /**
     * Bottom of the dimension.
     *
     * <p>Nothing is generated here any more: the arena is a void world and every arena is a
     * structure floating in it. This is only the lower bound a reset has to sweep.</p>
     */
    public static final int FLOOR_Y = 0;

    /** The y a map's template is pasted at: template-local y=0 lands here. */
    public static final int SURFACE_Y = 3;

    /**
     * The block a player stands on in a flat plot.
     *
     * <p>One below {@link #SURFACE_Y}, so a flat arena's walkable surface is at the same height as a
     * pasted map's. The flat arena is the only one that needs a floor built for it — every other
     * map arrives with its own.</p>
     */
    public static final int PLATFORM_Y = SURFACE_Y - 1;

    /**
     * How far outside a plot the invisible buffer extends, in blocks.
     *
     * <p>The dimension is void, so a plot's edge used to be a drop into nothing. The buffer is a
     * shelf of barrier blocks one below the arena floor, ringed by a barrier wall: walk off the map
     * and you land on something solid instead of dying to the void, and the wall stops you going
     * further. Eight blocks is wide enough to catch a knockback and narrow enough that the shelf is
     * still outside the boundary curtain, so the boundary rule keeps its meaning.</p>
     */
    public static final int BUFFER = 8;

    /** Spawn pads per plot: one per side, and a match has at most four sides. */
    public static final int PAD_COUNT = 4;

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

    /**
     * The cell this plot sits in, which is larger than the plot itself.
     *
     * <p>Clearing the plot's own bounding box is not enough: a cell that held a 147-wide map and
     * then gets a 45-wide one would keep the first map's walls, because they fall outside the
     * second map's box. That is exactly how the maps ended up piled on top of each other at the
     * origin. Resetting the cell instead means a cell can be handed from one map to another with
     * nothing left behind.</p>
     */
    public final int cellMinX;
    public final int cellMinZ;
    public final int cellSizeX;
    public final int cellSizeZ;

    /**
     * Copied from the session's rule set on every lease, so protection needs no lookup.
     *
     * <p>Not final: a cell keeps its stamped map between duels, so the same region object is leased
     * again with a different rule set rather than rebuilt from scratch.</p>
     */
    public boolean protectTerrain;
    public boolean allowBreakOwn;

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
     *
     * <p>Four pads, not two: a match can have up to four sides, and sides 2 and 3 sit on the axis the
     * first two do not use. Two sides are the common case and keep the old positions exactly.</p>
     */
    private final int[] padX = new int[PAD_COUNT];
    private final int[] padY = {SURFACE_Y, SURFACE_Y, SURFACE_Y, SURFACE_Y};
    private final int[] padZ = new int[PAD_COUNT];
    private boolean padsResolved;

    private final BlockPos.Mutable cursor = new BlockPos.Mutable();

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
     * <p>Only the deviation set and the per-duel rules are reset. The map itself stays where it is,
     * which is what makes each map's address stable: the colosseum is always in its own cell, so
     * "select the colosseum" means "go there", not "stamp a colosseum somewhere".</p>
     */
    public void lease(boolean protectTerrain, boolean allowBreakOwn) {
        this.protectTerrain = protectTerrain;
        this.allowBreakOwn = allowBreakOwn;
        changed.clear();
    }

    /**
     * What the generator would have put here: nothing.
     *
     * <p>The duel dimension is a void world. An arena is a structure floating in it, and a flat arena
     * is a platform floating in it, so the baseline of every position not covered by a template is
     * air. This is what makes "the map sits in the void" literally true rather than "the map sits on
     * a stone plain that is out of shot".</p>
     */
    public static BlockState flatBaseline(int y) {
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
        // Starts one below the map base so the flat arena's platform is captured too. Without that
        // the platform would not be part of the baseline, and the first revert of a duel would
        // delete the ground the players were standing on.
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
     * <p>The nominal pad is a guess ("a quarter of the span either side of the centre") and on real
     * maps it lands inside things: measured against the shipped templates, one pad was underwater,
     * two were inside solid rock with no headroom, and one was over a void. Teleporting there means
     * a player starts drowning, suffocating, or stuck in a wall — which reads as a broken duel, not
     * as a misplaced coordinate.</p>
     *
     * <p>So the guess is only a starting point. The search walks outward in rings and takes the
     * first column that has a non-fluid floor with two blocks of air above it. Falling back to the
     * nominal point when nothing is found keeps a pathological map from dropping players at the
     * origin of the dimension.</p>
     */
    private void resolvePads(ServerWorld world) {
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
     * <p>Twelve was enough for the first three maps and is not enough for a bridge map whose bases
     * sit near the ends: Avalon is 79 deep, its bases are at {@code z=+-35}, and the nominal pad
     * lands at {@code z=+-19} — over the void between them. At radius 12 the search found nothing,
     * fell back to the nominal point, and dropped both players onto the bare plot floor underneath
     * the map. At 24 it finds the slabs at {@code z=+-33}.</p>
     *
     * <p>The cost is bounded and only paid when the small rings are empty: 48x48 columns, once per
     * arena lease, and every map that works today resolves at radius 0 or 1.</p>
     */
    private static final int MAX_PAD_SEARCH = 24;

    /** Ring search around a target column. Returns {x, feetY, z}; feetY -1 means "nothing found". */
    private int[] findStand(ServerWorld world, int targetX, int targetZ) {
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
                    // would spawn a player who is judged to have left before they can move.
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
     * <p>A fluid is not a floor: the bridge map's nominal pad sat on top of open water, and
     * treating that as ground put the player in it. Scanning up rather than down matters too — a
     * roofed arena has a solid ceiling well above the pit, and spawning on the roof drops the
     * player into the fight from above.</p>
     */
    public int standY(ServerWorld world, int x, int z) {
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
     * <p>Separate from {@link #minX}/{@link #maxX()} because a bounding box is not a footprint. The
     * colosseum's box is its outer wall plus ten rows of seating; the sand is 100x80 inside it. Every
     * boundary decision — where the curtain is drawn, where "you left the arena" starts, where a
     * pull-back lands — belongs on the fight area, while block protection and entity cleanup stay on
     * the plot box so walking past the curtain does not put the stands out of reach of the rules.</p>
     *
     * <p>For a flat plot the two rectangles are identical, because the platform is built to the
     * requested size and there is nothing else there.</p>
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
     * Spawn pads.
     *
     * <p>The flat plot puts them four blocks inside each end, because the two players start at
     * opposite ends of a bare rectangle. A downloaded map is not a rectangle: its two ends are
     * stands, raised bases, or the far side of a bridge, and spawning there drops the player onto
     * something that is not the floor of the fight. For a map the pads are placed a quarter of the
     * span either side of the centre instead, along whichever axis the build separates its sides
     * on — see {@link ArenaMap#alongZ}.</p>
     *
     * <p>Sides 2 and 3 sit on the <em>other</em> axis, halfway between the ends: a four-way match
     * spreads around the pit rather than stacking two sides on top of each other. With two sides —
     * every match that does not opt into teams — pads 0 and 1 are byte-identical to what they were
     * before four pads existed.</p>
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
     * Faces the opponent: along whichever axis <em>this pad</em> sits on.
     *
     * <p>Per pad, not per map. Sides 0 and 1 sit on the map's own axis and sides 2 and 3 on the
     * perpendicular one — so reusing the map's axis for all four points two of them at the wall
     * behind them instead of at the enemy. Facing the wrong way is not cosmetic: it costs the first
     * second of every round, which in a four-way match is the second that decides who gets hit
     * first.</p>
     */
    public float spawnYaw(int spawnIndex) {
        boolean mapAlongZ = !map.isFlat() && map.alongZ;
        boolean padAlongZ = spawnIndex >= 2 ? !mapAlongZ : mapAlongZ;
        if (padAlongZ) {
            // yaw 0 is +Z (south), 180 is -Z (north).
            return spawnIndex % 2 == 0 ? 0.0F : 180.0F;
        }
        return spawnIndex % 2 == 0 ? -90.0F : 90.0F;
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
     * True when the player is still on the fight area.
     *
     * <p>Falling below the platform counts as leaving. There is no ground under an arena any more —
     * the dimension is void — so the alternative to catching this within a few blocks is a player
     * who drops out of the world and dies to void damage before the boundary rule ever sees them.
     * Eight blocks is about one second of falling: past that they are not on the map by any reading,
     * and the pull-back puts them back on it.</p>
     *
     * <p>Uses {@link #inBounds} rather than {@link #contains(double, double)}: the boundary rule
     * judges the fight area, not the plot box. On the colosseum that is the difference between
     * "standing on the sand" and "climbed the seating", and only the first is still a fight.</p>
     */
    public boolean isInside(double x, double y, double z) {
        return y >= PLATFORM_Y - 8.0D && inBounds(x, z);
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
     * <p>Scanned height is capped at the requested build height plus a small margin. Debris placed
     * above the ceiling of a <em>shorter</em> later duel can therefore survive a crash; that is a
     * cosmetic leak in an unused volume, and the alternative (scanning all 256 layers) costs an order
     * of magnitude more for no gameplay benefit.</p>
     *
     * <p><b>Clears the cell, not the plot.</b> The plot's own bounding box is the wrong volume to
     * erase: a cell that held the 147x119 colosseum and then gets the 45x60 island would keep every
     * colosseum block outside the island's box, and the two builds would be visible on top of each
     * other. Since cells are the unit that gets handed between maps, cells are the unit that gets
     * cleared.</p>
     *
     * <p>It also clears {@link #BUFFER} blocks past the cell on every side, because that is where
     * the barrier shelf lives — a cell handed to a smaller map would otherwise keep the previous
     * map's shelf sticking out into the gap.</p>
     *
     * @param clearSizeY how tall to scan. Pass the taller of the outgoing and incoming map, or the
     *                   outgoing one's upper floors survive the handover.
     */
    public int reset(ServerWorld world, int clearSizeY) {
        int top = Math.min(SURFACE_Y + Math.max(clearSizeY, sizeY) + 4, world.getTopY() - 1);
        int restored = 0;
        int fromX = cellMinX - BUFFER;
        int toX = cellMinX + cellSizeX + BUFFER;
        int fromZ = cellMinZ - BUFFER;
        int toZ = cellMinZ + cellSizeZ + BUFFER;
        for (int y = FLOOR_Y; y <= top; y++) {
            // Flat baseline, not the map baseline: the point of a reset is to erase whatever the
            // previous lease left behind, and restoring it to the *current* map would leave the old
            // map's blocks standing wherever the two do not overlap.
            BlockState want = flatBaseline(y);
            for (int x = fromX; x < toX; x++) {
                for (int z = fromZ; z < toZ; z++) {
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
