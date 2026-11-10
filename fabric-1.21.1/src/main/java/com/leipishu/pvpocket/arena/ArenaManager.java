package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.i18n.PvLang;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The duel dimension, its plot grid, and the rules that keep a plot from leaking into the next duel.
 *
 * <p>Minecraft cannot create dimensions at runtime, so the design is one fixed dimension whose
 * interior is carved into a grid of non-overlapping plots. Every session leases one plot for the
 * duration of its round and returns it when it settles.</p>
 *
 * <p>Three invariants hold here and nowhere else:</p>
 * <ol>
 *   <li><b>No mobs, ever.</b> The dimension's {@code monster_spawn_light_level} of 0 only stops
 *       daylight spawning; a dark pocket or a night cycle still spawns. Natural spawning is therefore
 *       cancelled outright in this world, and anything that slips in through another path is swept.</li>
 *   <li><b>A plot is returned to its baseline.</b> Blocks are tracked as "differs from baseline", so
 *       reverting is a baseline write and the map cannot be permanently altered by a duel.</li>
 *   <li><b>Two sessions never share a plot.</b> Leases are keyed by session id, so a double release
 *       cannot free someone else's plot.</li>
 * </ol>
 */
public final class ArenaManager {

    public static final RegistryKey<World> ARENA_WORLD =
            RegistryKey.of(RegistryKeys.WORLD, PvPocket.id("arena"));

    /** Walkable surface; the flat generator puts bedrock at 0 and stone at 1..2. */
    public static final int SURFACE_Y = ArenaRegion.SURFACE_Y;

    /**
     * Grid shape. The first {@code ArenaMaps.ALL.size()} cells are the maps' own addresses; the rest
     * are the pool that {@link #addCopy} hands out when a map needs a second, third or fourth cell.
     */
    public static final int COLS = 4;
    public static final int ROWS = 4;

    /**
     * Interior size of one cell.
     *
     * <p>A cell is the unit the dimension is divided into, and the unit that gets cleared when a
     * map is stamped. It is deliberately larger than most maps: the cell has to be able to hold the
     * biggest one (the colosseum, 147x119) without its walls crossing into the neighbour.</p>
     *
     * <p>It is no longer the largest plot a duel may ask for — see {@link #MAX_SIZE_XZ}. A plot may
     * spill past its own cell into the gap, which is what keeps the flat arena's ceiling from being
     * tied to the colosseum's width.</p>
     */
    public static final int CELL = 160;

    /**
     * Empty ring between two neighbouring cells, in blocks.
     *
     * <p>This is what makes two arenas read as two places. At the original 32 the colosseum's outer
     * wall was close enough to the next cell to look like part of it; 96 leaves a clear band of flat
     * ground between every pair.</p>
     */
    public static final int GAP = 96;

    /** Cell centre-to-centre spacing, in blocks. */
    public static final int PITCH = CELL + GAP;

    /** Distance from the pitch origin to the corner of a cell's interior. */
    public static final int MARGIN = (PITCH - CELL) / 2;

    /**
     * Closest two maximum-size plots may come to each other, in blocks.
     *
     * <p>Exactly both plots' {@link ArenaRegion#BUFFER} shelves (8 each) plus 16 blocks of clear
     * gap. The shelves must not touch: a shared shelf would let a player knocked off one arena walk
     * straight onto the other's floor, which is the whole reason the buffer exists. The remaining 16
     * is what keeps the two barrier rings from reading as one continuous wall. Narrower and the two
     * arenas stop being two places; wider and the flat arena's ceiling drops for no benefit.</p>
     */
    private static final int MIN_ARENA_GAP = 32;

    /**
     * Largest plot the rule panel may request.
     *
     * <p>Not the cell size. A plot is centred in its cell, so a plot wider than the cell spills into
     * the gap on both sides — and the gap is exactly where a plot is allowed to go, because nothing
     * else is ever stamped there. The only hard limit is the neighbour: at {@code PITCH - 32} two
     * maximum plots still leave {@link #MIN_ARENA_GAP} between them.</p>
     *
     * <p>Raised from {@code CELL} so the flat arena is not capped by the colosseum. The flat plot is
     * the one map whose size the players choose, so its ceiling is the grid's, not the widest shipped
     * build's; 224 is 40% more side than the old 160 and the panel's slider now reaches it.</p>
     */
    public static final int MAX_SIZE_XZ = PITCH - MIN_ARENA_GAP;

    /**
     * Tallest plot the rule panel may request.
     *
     * <p>Raised from 64 once the dimension became void: with nothing generated there is no reason
     * to cap a build at 64 blocks, and the tallest shipped map is 43. The real ceiling is the
     * dimension height minus the buffer wall, which is what {@code e.sizeY} 96 plus
     * {@code SURFACE_Y + 2} leaves comfortably clear.</p>
     */
    public static final int MAX_SIZE_Y = 96;


    private static final int SLOTS = COLS * ROWS;

    /**
     * The stamped plot living in each cell, whether or not it is leased.
     *
     * <p>This array is why a map has a fixed address. A cell keeps its map between duels, so
     * selecting the colosseum always means going to the same coordinates — and a second duel on the
     * same map takes a spare cell rather than stamping over the first one.</p>
     */
    private static final ArenaRegion[] CELLS = new ArenaRegion[SLOTS];

    /** Session id -> leased plot. LinkedHashMap so iteration order is stable for logs. */
    private static final Map<String, ArenaRegion> BY_SESSION = new LinkedHashMap<>();
    private static final boolean[] USED = new boolean[SLOTS];

    // ------------------------------------------------------------------ copies

    /**
     * How many cells each map gets on a fresh install, its own address included.
     *
     * <p>Only two maps are here, and both for the same reason: they are the ones a busy server runs
     * out of first. The flat plot is the default map — a server that has not customised anything
     * runs every duel on it — and the colosseum is the one people ask for by name. One copy of each
     * means the second duel on that map has to be turned away; four and two respectively cover an
     * evening of several concurrent rooms without asking an operator to think about it.</p>
     *
     * <p>Every other map keeps one cell. Copies are capacity, not decoration: each one costs a cell
     * of the grid, and the grid is what a server actually runs out of.</p>
     */
    private static final Map<String, Integer> DEFAULT_COPIES = Map.of(
            "flat", 4,
            "arena_colosseum", 2);

    /**
     * The cells handed out beyond a map's own address, keyed by map id, in the order they were added.
     *
     * <p>This map is the record the copies live in: slot numbers are stable addresses, so the third
     * copy of the flat plot is always the same coordinates, across restarts and across a reset. It
     * is persisted to {@code pvpocket/arena.json} for exactly that reason — re-deriving the layout
     * from "the first free cell" would move every copy the moment somebody added one.</p>
     */
    private static final Map<String, List<Integer>> EXTRA = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** On-disk shape of {@code arena.json}. A record so Gson needs no reflection on private fields. */
    private record LayoutFile(int version, Map<String, List<Integer>> extra) {
    }

    /**
     * Reads the copy layout, seeding the defaults on a world that has none.
     *
     * <p>Called once per server start, before {@link #beginWarmUp}. A missing file is the normal
     * first-run case and the normal upgrade case: both should end up with the default copies, and
     * the file is written so the next start reads the same slots instead of allocating them
     * again.</p>
     */
    public static void loadLayout(MinecraftServer server) {
        EXTRA.clear();
        Path file = layoutFile(server);
        boolean rewrite = false;
        if (Files.exists(file)) {
            try {
                LayoutFile parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        LayoutFile.class);
                if (parsed != null && parsed.extra() != null) {
                    for (Map.Entry<String, List<Integer>> entry : parsed.extra().entrySet()) {
                        if (entry.getValue() != null) {
                            EXTRA.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Losing this file costs the extra cells, not the maps: every map still has its own
                // address, which is derived from its index and cannot be lost. Re-seed and carry on.
                PvPocket.LOGGER.warn("Could not read {}; copy layout falls back to defaults", file, e);
                EXTRA.clear();
                rewrite = true;
            }
        } else {
            rewrite = true;
        }
        rewrite |= seedDefaults();
        rewrite |= sanitise();
        if (rewrite) {
            saveLayout(server);
        }
        PvPocket.LOGGER.info("Arena layout: {} cells assigned across {} map(s)",
                totalAssigned(), ArenaMaps.ALL.size());
    }

    /** Gives every map without an entry its default extra cells. Returns true when it changed. */
    private static boolean seedDefaults() {
        boolean changed = false;
        for (ArenaMap map : ArenaMaps.ALL) {
            if (EXTRA.containsKey(map.id)) {
                continue;
            }
            int want = DEFAULT_COPIES.getOrDefault(map.id, 1) - 1;
            List<Integer> slots = new ArrayList<>(Math.max(0, want));
            for (int i = 0; i < want; i++) {
                int slot = spareSlot(slots);
                if (slot < 0) {
                    PvPocket.LOGGER.warn("Grid full; {} gets {} copy/copies instead of {}",
                            map.id, slots.size() + 1, want + 1);
                    break;
                }
                slots.add(slot);
            }
            EXTRA.put(map.id, slots);
            changed = true;
        }
        return changed;
    }

    /**
     * Drops duplicate, out-of-range and home-slot collisions from the loaded layout.
     *
     * <p>A hand-edited or half-written file must not be able to point two maps at one cell — that is
     * two arenas stamped on top of each other, which is the bug the whole slot scheme exists to
     * prevent. Returns true when anything had to be dropped.</p>
     */
    private static boolean sanitise() {
        boolean changed = false;
        boolean[] taken = new boolean[SLOTS];
        // Home addresses are fixed, so they claim their cells first.
        for (int i = 0; i < ArenaMaps.ALL.size() && i < SLOTS; i++) {
            taken[i] = true;
        }
        for (Map.Entry<String, List<Integer>> entry : EXTRA.entrySet()) {
            List<Integer> kept = new ArrayList<>(entry.getValue().size());
            for (Integer slot : entry.getValue()) {
                if (slot == null || slot < ArenaMaps.ALL.size() || slot >= SLOTS || taken[slot]) {
                    changed = true;
                    continue;
                }
                taken[slot] = true;
                kept.add(slot);
            }
            entry.setValue(kept);
        }
        return changed;
    }

    private static void saveLayout(MinecraftServer server) {
        Path file = layoutFile(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new LayoutFile(1, EXTRA)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PvPocket.LOGGER.warn("Could not write {}; the copy layout will not survive a restart",
                    file, e);
        }
    }

    private static Path layoutFile(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("pvpocket").resolve("arena.json");
    }

    private static int totalAssigned() {
        int total = 0;
        for (ArenaMap map : ArenaMaps.ALL) {
            total += copiesOf(map);
        }
        return total;
    }

    /** How many cells a map owns, its own address included. */
    public static int copiesOf(ArenaMap map) {
        List<Integer> extra = EXTRA.get(map.id);
        return 1 + (extra == null ? 0 : extra.size());
    }

    /**
     * Every cell a map owns, its own address first.
     *
     * <p>The address is {@code ArenaMaps.ALL.indexOf(map)} and never moves; the extra copies are
     * whatever {@link #EXTRA} recorded. Returning them as one array is what lets {@link #allocate}
     * stop caring which is which.</p>
     */
    public static int[] slotsOf(ArenaMap map) {
        int home = homeSlot(map);
        if (home < 0) {
            return new int[0];
        }
        List<Integer> extra = EXTRA.get(map.id);
        int n = 1 + (extra == null ? 0 : extra.size());
        int[] out = new int[n];
        out[0] = home;
        for (int i = 1; i < n; i++) {
            out[i] = extra.get(i - 1);
        }
        return out;
    }

    /** The map that owns a cell, or null for a cell no map has been given. */
    private static ArenaMap mapOfSlot(int slot) {
        if (slot < 0) {
            return null;
        }
        if (slot < ArenaMaps.ALL.size()) {
            return ArenaMaps.ALL.get(slot);
        }
        for (Map.Entry<String, List<Integer>> entry : EXTRA.entrySet()) {
            if (entry.getValue().contains(slot)) {
                for (ArenaMap map : ArenaMaps.ALL) {
                    if (map.id.equals(entry.getKey())) {
                        return map;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Hands a map one more cell, persists the layout, and builds it.
     *
     * <p>Operator-only, and the one way to raise the number of duels a server can run at once. The
     * cell is recorded before it is built: a stamp that fails halfway leaves a reserved address,
     * which is recoverable, while a built cell nobody recorded would be erased by the next map that
     * gets it.</p>
     *
     * <p>A flat copy is reserved but not built — its platform is cut to whatever size the rules ask
     * for, so there is nothing to paste yet. It is stamped by the first lease that lands on it.</p>
     *
     * @return the cell the copy was given, or -1 when the grid has no spare cell left
     */
    public static int addCopy(MinecraftServer server, ArenaMap map) {
        if (map == null) {
            return -1;
        }
        int slot = spareSlot(EXTRA.getOrDefault(map.id, List.of()));
        if (slot < 0) {
            return -1;
        }
        EXTRA.computeIfAbsent(map.id, key -> new ArrayList<>()).add(slot);
        saveLayout(server);
        if (!map.isFlat()) {
            stamp(server, slot, map, clampSize(map.sizeX, MAX_SIZE_XZ),
                    clampSize(map.sizeY, MAX_SIZE_Y), clampSize(map.sizeZ, MAX_SIZE_XZ),
                    false, false);
        }
        return slot;
    }

    /** The centre of a cell, for reporting where a copy lives. */
    public static int[] cellCentre(int slot) {
        return new int[]{cellMinX(slot) + CELL / 2, cellMinZ(slot) + CELL / 2};
    }

    /** The plot stamped into a cell, or null when nothing is built there yet. */
    public static ArenaRegion regionAtSlot(int slot) {
        return slot >= 0 && slot < SLOTS ? CELLS[slot] : null;
    }

    /** First cell past the map addresses that no map owns and no session holds. */
    private static int spareSlot(List<Integer> alreadyTaken) {
        for (int slot = ArenaMaps.ALL.size(); slot < SLOTS; slot++) {
            if (USED[slot] || alreadyTaken.contains(slot)) {
                continue;
            }
            if (mapOfSlot(slot) != null) {
                continue;
            }
            return slot;
        }
        return -1;
    }

    /**
     * While non-zero, block writes are not recorded as plot deviations.
     *
     * <p>Reset and revert both write the baseline back; recording those writes would immediately
     * re-mark every position as changed and turn a clean plot into a permanently dirty one.</p>
     */
    private static int recordingSuppressed;

    private ArenaManager() {
    }

    public static ServerWorld world(MinecraftServer server) {
        return server.getWorld(ARENA_WORLD);
    }

    public static boolean isReady(MinecraftServer server) {
        return world(server) != null;
    }

    public static boolean isArena(World world) {
        return world != null && world.getRegistryKey().equals(ARENA_WORLD);
    }

    // ------------------------------------------------------------------ leases

    /**
     * Leases the plot that belongs to the selected map, stamping it if this is the first time.
     *
     * <p>When a map is selected its bounding box wins over {@code sizeX/sizeY/sizeZ}: the plot has to
     * cover the whole build, otherwise the boundary test would declare a player standing on the
     * stands as having left the arena.</p>
     *
     * <p><b>Every map has a fixed address.</b> The first cell of a map is chosen from its index in
     * {@link ArenaMaps#ALL}, not from "whichever cell is free": the first version took the first
     * free slot, so a server that runs one duel at a time pasted every map into slot 0, one on top
     * of the previous. Because the plot is centred by its own size, the new map landed inside the
     * old one and both were visible at once. Taking the map's own cell means the colosseum is always
     * at the colosseum's coordinates.</p>
     *
     * <p><b>Copies are the capacity, not an overflow.</b> A map owns one cell per copy, recorded in
     * {@link #EXTRA}; this takes the first of them that nobody is standing in. When every copy is
     * leased the map is full and the caller must be told so — silently stamping a fifth flat arena
     * into somebody else's cell is how two duels end up in one place. The number of copies is an
     * operator decision ({@code /pvpocket arena copy}), not something a busy evening can raise on
     * its own.</p>
     *
     * <p>A cell that already holds the requested map is reused as-is — the map stays stamped between
     * duels, so a round only has to undo the previous round's edits.</p>
     *
     * <p>Returns null when no cell is available, which the caller must treat as "cannot start"
     * rather than as a soft failure — silently reusing a busy plot would teleport two duels into
     * each other.</p>
     */
    public static ArenaRegion allocate(MinecraftServer server, String sessionId,
                                       int sizeX, int sizeY, int sizeZ, ArenaMap map,
                                       boolean protectTerrain, boolean allowBreakOwn) {
        release(sessionId);
        ArenaMap chosen = map == null ? ArenaMap.FLAT : map;
        int sx = chosen.isFlat() ? clampSize(sizeX, MAX_SIZE_XZ) : clampSize(chosen.sizeX, MAX_SIZE_XZ);
        int sz = chosen.isFlat() ? clampSize(sizeZ, MAX_SIZE_XZ) : clampSize(chosen.sizeZ, MAX_SIZE_XZ);
        int sy = chosen.isFlat() ? clampSize(sizeY, MAX_SIZE_Y) : clampSize(chosen.sizeY, MAX_SIZE_Y);

        int slot = -1;
        for (int candidate : slotsOf(chosen)) {
            if (!USED[candidate]) {
                slot = candidate;
                break;
            }
        }
        if (slot < 0) {
            return null;
        }

        ArenaRegion region = CELLS[slot];
        if (region == null || !region.matches(chosen, sx, sy, sz)) {
            // stamp() registers the result in CELLS itself; that is what makes the cell's occupant
            // visible to cellOf()/gallery() as well as to the next allocate().
            region = stamp(server, slot, chosen, sx, sy, sz, protectTerrain, allowBreakOwn);
            if (region == null) {
                return null;
            }
        } else {
            int reverted = revertRegion(world(server), region);
            region.lease(protectTerrain, allowBreakOwn);
            PvPocket.LOGGER.info("Reusing {} for session {} (reverted {} blocks)",
                    region, shortId(sessionId), reverted);
        }
        USED[slot] = true;
        BY_SESSION.put(sessionId, region);
        return region;
    }

    /**
     * Clears a cell and stamps a map into it, leaving the result registered as that cell's occupant.
     *
     * <p>Clearing uses the cell rectangle rather than the map's box, and clears to the taller of the
     * outgoing and incoming build heights. Both details exist for the same reason: a cell handed
     * from the colosseum to the 45-wide island must not keep the colosseum's outer wall standing
     * around the island.</p>
     */
    private static ArenaRegion stamp(MinecraftServer server, int slot, ArenaMap map,
                                     int sx, int sy, int sz,
                                     boolean protectTerrain, boolean allowBreakOwn) {
        int cellX = cellMinX(slot);
        int cellZ = cellMinZ(slot);
        // Centring inside the cell is the same arithmetic as centring inside the pitch, because the
        // cell is the pitch minus a symmetric margin.
        int originX = cellX + (CELL - sx) / 2;
        int originZ = cellZ + (CELL - sz) / 2;
        ArenaRegion previous = CELLS[slot];
        ArenaRegion region = new ArenaRegion("arena-" + slot, slot, originX, originZ, sx, sy, sz,
                cellX, cellZ, CELL, CELL, map, protectTerrain, allowBreakOwn);

        ServerWorld world = world(server);
        if (world == null) {
            PvPocket.LOGGER.error("Arena world is not loaded; cannot stamp {}", map.id);
            return null;
        }
        int clearY = previous == null ? sy : Math.max(sy, previous.sizeY);
        int cleared = suppressRecording(() -> region.reset(world, clearY));
        int floorBlocks = 0;
        int pasted = 0;
        if (map.isFlat()) {
            floorBlocks = suppressRecording(() -> buildPlatform(world, region));
        } else {
            // Paste and capture both run with recording suppressed. The paste writes tens of
            // thousands of blocks, and every one of them would otherwise be filed as "the player
            // changed this" -- the deviation set would be full before the first round.
            pasted = suppressRecording(() -> map.paste(world, region) ? 1 : 0);
        }
        // Capture runs for the flat plot too. Its platform has to be part of the baseline, or the
        // first revert of a duel would delete the ground out from under the players.
        suppressRecording(() -> {
            region.capture(world);
            return 0;
        });
        // After capture, so the barrier shelf is not mistaken for part of the map. It sits one block
        // below the plot floor and outside the plot box, which is exactly the volume capture skips.
        int bufferBlocks = suppressRecording(() -> buildBuffer(world, region));
        int entities = purge(world, region);
        // Registered here rather than by the caller so both paths -- a duel lease and the startup
        // warm-up -- leave the cell with an occupant. Forgetting it in warm-up meant the gallery was
        // stamped but invisible, and every later lease re-stamped over it.
        CELLS[slot] = region;
        PvPocket.LOGGER.info("Stamped {} into cell {} at {},{},{} (cleared {} blocks, map {} "
                        + "pasted {}, platform {} blocks, buffer {} barrier, {} template blocks, "
                        + "purged {} entities)",
                region, slot, originX, SURFACE_Y, originZ, cleared, map.id, pasted == 1,
                floorBlocks, bufferBlocks, region.templateBlockCount(), entities);
        return region;
    }

    /**
     * Lays the flat arena's floor.
     *
     * <p>The dimension is void, so "flat" no longer means "whatever the generator put there" — there
     * is nothing under anything. A flat plot is a platform of the requested size, floating exactly
     * where a pasted map's base would be, which is why it sits at {@link ArenaRegion#PLATFORM_Y} and
     * not at {@link ArenaRegion#SURFACE_Y}.</p>
     *
     * <p>Returns the block count so the caller can log it the same way it logs a paste.</p>
     */
    private static int buildPlatform(ServerWorld world, ArenaRegion region) {
        BlockState floor = Blocks.SMOOTH_STONE.getDefaultState();
        int placed = 0;
        for (int x = region.minX; x <= region.maxX(); x++) {
            for (int z = region.minZ; z <= region.maxZ(); z++) {
                world.setBlockState(new BlockPos(x, ArenaRegion.PLATFORM_Y, z), floor,
                        Block.NOTIFY_LISTENERS);
                placed++;
            }
        }
        return placed;
    }

    /**
     * Builds the invisible buffer around a plot: a barrier shelf and a barrier wall at its rim.
     *
     * <p>Without it a plot's edge is a drop into nothing, because the dimension is void. Two
     * separate problems, two separate pieces:</p>
     * <ul>
     *   <li><b>The shelf</b> covers the whole plot box plus {@link ArenaRegion#BUFFER} on every side,
     *       one block <em>below</em> the plot floor. It is what catches a player who walks or is
     *       knocked off the map: they land on it instead of dying to the void, and can walk back.
     *       It covers the plot box as well as the ring because a build's box is not its footprint —
     *       the island is a disc inside a square box, so its corners are empty and a ring-only shelf
     *       would leave four holes to fall through.</li>
     *   <li><b>The wall</b> stands at the outer rim of the shelf, from the shelf up past the tallest
     *       thing the map can hold, so the shelf is a place you can be rescued from rather than a
     *       ledge you walk off the far side of.</li>
     * </ul>
     *
     * <p>Barrier rather than glass or stone on purpose: it is invisible, unbreakable, and cannot be
     * obtained, so the buffer adds nothing to look at and nothing to exploit. The boundary curtain
     * is still drawn at the plot box, inside the shelf, which is why the boundary rule keeps its
     * meaning — leaving the plot is still a rule violation, it just is not a death sentence any
     * more.</p>
     *
     * <p>Runs after {@link ArenaRegion#capture} so the shelf cannot be mistaken for map blocks: it
     * is one below the plot floor and outside the plot box, and capture scans neither.</p>
     */
    private static int buildBuffer(ServerWorld world, ArenaRegion region) {
        BlockState barrier = Blocks.BARRIER.getDefaultState();
        int pad = ArenaRegion.BUFFER;
        int shelfY = ArenaRegion.PLATFORM_Y - 1;
        int x0 = region.minX - pad;
        int x1 = region.maxX() + pad;
        int z0 = region.minZ - pad;
        int z1 = region.maxZ() + pad;
        int placed = 0;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                placed += setIfDifferent(world, x, shelfY, z, barrier);
            }
        }

        int wallTop = SURFACE_Y + region.sizeY + 2;
        for (int y = shelfY + 1; y <= wallTop; y++) {
            for (int x = x0; x <= x1; x++) {
                placed += setIfDifferent(world, x, y, z0, barrier);
                placed += setIfDifferent(world, x, y, z1, barrier);
            }
            for (int z = z0 + 1; z < z1; z++) {
                placed += setIfDifferent(world, x0, y, z, barrier);
                placed += setIfDifferent(world, x1, y, z, barrier);
            }
        }
        return placed;
    }

    /** Writes only on a real change, so re-stamping a cell does not rewrite an identical buffer. */
    private static int setIfDifferent(ServerWorld world, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z);
        if (world.getBlockState(pos) == state) {
            return 0;
        }
        world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        return 1;
    }

    // ------------------------------------------------------------------ prepare wall

    /**
     * How far above the higher spawn pad the preparation wall reaches.
     *
     * <p>Eight blocks is enough that nobody jumps it by accident and short enough that it costs
     * almost nothing to place and remove every round. It is not meant to survive a player who
     * deliberately pillars up: the preparation window is thirty seconds of standing still, and the
     * boundary rule is waiting on the other side of it anyway.</p>
     */
    private static final int PREPARE_WALL_MARGIN = 8;

    /**
     * Absolute y of the top block of the preparation wall.
     *
     * <p>Measured from the spawn pads rather than from the plot floor because a map's floor is not
     * where the players are: the colosseum's sand is a few blocks up, and a wall sized from
     * {@code PLATFORM_Y} would end below the players' feet on any raised build.</p>
     */
    public static int prepareWallTop(ArenaRegion region) {
        int pads = Math.max(region.spawnY(0), region.spawnY(1));
        return Math.max(ArenaRegion.SURFACE_Y, pads) + PREPARE_WALL_MARGIN;
    }

    /**
     * Builds the solid wall that closes the arena during the preparation window.
     *
     * <p>This is the one place where the boundary is <em>real</em> rather than a rule. During
     * preparation nobody can be hit, so the only thing a player can usefully do is walk out and be
     * somewhere strange when the round goes live. A rendered curtain does not stop that — the
     * existing curtain is deliberately walk-through, because a boundary you can push against is a
     * boundary the countdown rule can never see. So for the preparation window the wall is made of
     * {@code minecraft:barrier}: invisible, unbreakable, unobtainable, and solid to the engine.</p>
     *
     * <p>It sits one block <em>outside</em> the fight area, which puts it just behind where the
     * boundary curtain is drawn. That is the whole trick: the player bumps into something invisible
     * at the same place the client is painting a wall, so the two agree without the server having to
     * send anything per frame.</p>
     *
     * <p>Barrier rather than glass so nothing has to be cleaned out of the player's inventory and
     * nothing appears in the world to look at once the round starts. {@link #removePrepareWall} only
     * clears blocks that are still barrier, so a map that happens to have its own barrier blocks at
     * the same coordinates keeps them.</p>
     *
     * <p><b>Only air is written over.</b> The ring runs one block outside the fight area, and on a
     * map whose fight area is inset — the colosseum — that line lands on the single block of podium
     * wall between the sand and the seating. Overwriting it put a one-block trench around the whole
     * arena: the barrier is cleared to air afterwards, and because the write happened with recording
     * suppressed, {@link #revertRegion} could not put the map back either. The trench then survived
     * every later round in that cell, which is how it came to look like a hole in the map. A solid
     * map block is already an obstacle, so the ring has nothing to add there.</p>
     *
     * <p>The screens between the sides are built here too, from the same material — see
     * {@link #prepareScreens}. They are the half of this barrier the player can <em>see</em>: the
     * client paints them solid white from the planes this class reports, which is why the blocks
     * themselves can stay invisible.</p>
     *
     * <p>Recording is suppressed: the wall is outside the plot box, but {@code regionAt} matches on
     * the plot box and a wall at {@code pitMinX - 1} can still fall inside it on a map with no
     * inset — and a barrier block filed as "the player placed this" would make the whole ring
     * unbreakable-but-tracked and would be reverted into existence at the next settle.</p>
     */
    public static int buildPrepareWall(ServerWorld world, ArenaRegion region, int sides) {
        if (world == null || region == null) {
            return 0;
        }
        int ring = suppressRecording(() -> {
            BlockState barrier = Blocks.BARRIER.getDefaultState();
            int x0 = region.pitMinX() - 1;
            int x1 = region.pitMaxX() + 1;
            int z0 = region.pitMinZ() - 1;
            int z1 = region.pitMaxZ() + 1;
            int top = prepareWallTop(region);
            int placed = 0;
            for (int y = ArenaRegion.PLATFORM_Y; y <= top; y++) {
                for (int x = x0; x <= x1; x++) {
                    placed += standIfAir(world, x, y, z0, barrier);
                    placed += standIfAir(world, x, y, z1, barrier);
                }
                for (int z = z0 + 1; z < z1; z++) {
                    placed += standIfAir(world, x0, y, z, barrier);
                    placed += standIfAir(world, x1, y, z, barrier);
                }
            }
            return placed;
        });
        return ring + suppressRecording(() -> buildScreens(world, region, sides));
    }

    /**
     * Writes barrier only where there is nothing, so the ring can never carve a map.
     *
     * <p>Not tracked, unlike the screens: the ring's teardown is a barrier scan, which is safe
     * because the converter strips {@code barrier} out of every shipped template. The screens sit
     * further inside the fight area, where a map is free to use that block for its own invisible
     * walls, which is why those are tracked by coordinate instead.</p>
     */
    private static int standIfAir(ServerWorld world, int x, int y, int z, BlockState barrier) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.getBlockState(pos).isAir()) {
            return 0;
        }
        world.setBlockState(pos, barrier, Block.NOTIFY_LISTENERS);
        return 1;
    }

    /**
     * How far in front of a side's pad its screen stands, in blocks.
     *
     * <p>Far enough that the side has room to spread out and read the panel, close enough that the
     * two screens do not meet in the middle on the smallest legal plot. Four also leaves the
     * knockback of the first exchange somewhere to go once the screens come down.</p>
     */
    private static final int SCREEN_GAP = 4;

    /**
     * One preparation screen: a vertical plane one block thick, spanning the fight area.
     *
     * <p>Sent to the client verbatim, so the white wall it paints and the barrier blocks the server
     * stands in the world are the same object. Deriving the plane twice — once here, once on the
     * client from pads and insets — is how a wall ends up drawn a block away from the thing you are
     * bumping into.</p>
     */
    public record Screen(boolean alongZ, int plane, int from, int to, int floorY, int height) {
    }

    /**
     * Where each side's screen would stand, without touching the world.
     *
     * <p>One method for both jobs: {@link #buildScreens} stands barrier blocks on these planes and
     * the session sends the same list to the client to paint. The client cannot compute them itself
     * — the pads come from a ring search over the stamped map — so the server is the only place the
     * answer exists.</p>
     *
     * <p>Sides that cannot be screened are dropped rather than clamped onto the centre line: on a
     * plot barely wider than the two pads there is no room for a screen that is not also a wall in
     * the middle of the fight, and half a screen would be worse than none.</p>
     */
    public static List<Screen> prepareScreens(ArenaRegion region, int sides) {
        if (region == null) {
            return List.of();
        }
        int count = Math.min(sides, ArenaRegion.PAD_COUNT);
        if (count <= 0) {
            return List.of();
        }
        List<Screen> screens = new ArrayList<>(count);
        // Footed one block below the lowest pad, not at the plot floor. The floor is where the flat
        // arena's platform is, but a downloaded map floats: its floor is tens of blocks under the
        // surface, and a screen anchored there would be a wall hanging into the void — thousands of
        // invisible blocks per round, and a white sheet visible from outside the island. One block
        // below the pad keeps the foot at the players' own level, which is the only level that has
        // to be blocked.
        int floor = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            floor = Math.min(floor, region.spawnY(i));
        }
        floor -= 1;
        int height = prepareWallTop(region) + 1 - floor;
        for (int side = 0; side < count; side++) {
            boolean alongZ = region.padAlongZ(side);
            int pad = alongZ ? region.spawnZ(side) : region.spawnX(side);
            int centre = alongZ ? region.centerZ() : region.centerX();
            int dir = pad <= centre ? 1 : -1;
            int plane = pad + dir * SCREEN_GAP;
            // Must stay on this side's half, or the screens of two opposing sides land on each
            // other and the middle of the arena is sealed instead of open.
            if (dir > 0 ? plane > centre - 1 : plane < centre + 1) {
                continue;
            }
            int lo = alongZ ? region.pitMinZ() : region.pitMinX();
            int hi = alongZ ? region.pitMaxZ() : region.pitMaxX();
            if (plane < lo || plane > hi) {
                continue;
            }
            int from = alongZ ? region.pitMinX() : region.pitMinZ();
            int to = alongZ ? region.pitMaxX() : region.pitMaxZ();
            screens.add(new Screen(alongZ, plane, from, to, floor, height));
        }
        return screens;
    }

    /**
     * Stands the barrier blocks a screen needs, on the planes {@link #prepareScreens} reports.
     *
     * <p>Barrier, not glass: the wall the player sees is painted by the client from the same plane,
     * so the block only has to be solid. That also keeps the world looking empty the instant the
     * round goes live, which is when the drawn wall disappears.</p>
     *
     * <p>Only air is written over. Overwriting the map would not be undone by
     * {@link ArenaRegion#clearScreens} writing air, so it would carve a one-block slot through
     * whatever the plane crossed. Skipping non-air leaves the screen solid where it matters — the
     * direct line — and the map untouched.</p>
     */
    private static int buildScreens(ServerWorld world, ArenaRegion region, int sides) {
        BlockState barrier = Blocks.BARRIER.getDefaultState();
        int placed = 0;
        for (Screen screen : prepareScreens(region, sides)) {
            int top = screen.floorY() + screen.height() - 1;
            for (int y = screen.floorY(); y <= top; y++) {
                for (int c = screen.from(); c <= screen.to(); c++) {
                    BlockPos pos = screen.alongZ()
                            ? new BlockPos(c, y, screen.plane())
                            : new BlockPos(screen.plane(), y, c);
                    if (!world.getBlockState(pos).isAir()) {
                        continue;
                    }
                    world.setBlockState(pos, barrier, Block.NOTIFY_LISTENERS);
                    region.trackScreen(pos);
                    placed++;
                }
            }
        }
        return placed;
    }

    /**
     * Tears the preparation barrier down: the ring, then every screen block still standing.
     *
     * <p>Only barrier blocks are removed from the ring, so a map that happens to have its own
     * barriers at the same coordinates keeps them. The screens are removed from the recorded
     * position list instead — see {@link ArenaRegion#clearScreens} for why a scan would be wrong.</p>
     */
    public static int removePrepareWall(ServerWorld world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        int ring = suppressRecording(() -> {
            BlockState air = Blocks.AIR.getDefaultState();
            BlockState barrier = Blocks.BARRIER.getDefaultState();
            int x0 = region.pitMinX() - 1;
            int x1 = region.pitMaxX() + 1;
            int z0 = region.pitMinZ() - 1;
            int z1 = region.pitMaxZ() + 1;
            int top = prepareWallTop(region);
            int removed = 0;
            for (int y = ArenaRegion.PLATFORM_Y; y <= top; y++) {
                for (int x = x0; x <= x1; x++) {
                    removed += clearIfBarrier(world, x, y, z0, barrier, air);
                    removed += clearIfBarrier(world, x, y, z1, barrier, air);
                }
                for (int z = z0 + 1; z < z1; z++) {
                    removed += clearIfBarrier(world, x0, y, z, barrier, air);
                    removed += clearIfBarrier(world, x1, y, z, barrier, air);
                }
            }
            return removed;
        });
        return ring + suppressRecording(() -> region.clearScreens(world));
    }

    private static int clearIfBarrier(ServerWorld world, int x, int y, int z,
                                      BlockState barrier, BlockState air) {
        BlockPos pos = new BlockPos(x, y, z);
        if (world.getBlockState(pos) != barrier) {
            return 0;
        }
        world.setBlockState(pos, air, Block.NOTIFY_LISTENERS);
        return 1;
    }

    /** Next exhibit to stamp during warm-up, or -1 when warm-up is idle or finished. */
    private static int warmUpCursor = -1;
    private static int warmUpBuilt;
    private static long warmUpStarted;

    /**
     * Queues the gallery fill: the first copy of every map gets stamped into its own cell, one per
     * tick.
     *
     * <p>Without this the cells only fill in as maps get played, and an operator flying around
     * before the first duel sees an empty plain — which is what made the stacking bug hard to see in
     * the first place.</p>
     *
     * <p>Only the <em>first</em> copy of each map. Extra copies are capacity: an operator who asked
     * for a second colosseum wants the ability to run two colosseum duels, not two colosseums to
     * look at, and pre-stamping them would double both the startup freeze and the disk the dimension
     * takes. A copy is built the first time somebody is sent to it.</p>
     *
     * <p>Spread over ticks rather than done in one go on purpose. A single stamp is a cell-sized
     * clear (160x160x~40 reads) plus tens of thousands of block writes, and the colosseum alone is
     * 109k blocks; doing eight of them back to back in {@code SERVER_STARTED} freezes the server for
     * long enough that the watchdog starts complaining. One per tick keeps the start responsive, and
     * nobody can be in a duel yet anyway.</p>
     */
    public static void beginWarmUp() {
        warmUpCursor = 0;
        warmUpBuilt = 0;
        warmUpStarted = System.nanoTime();
    }

    public static boolean warmUpPending() {
        return warmUpCursor >= 0;
    }

    /**
     * Stamps at most one exhibit. Called from the server tick hook; a no-op once the gallery is done.
     *
     * <p>It also doubles as crash recovery: a cell whose map was half-edited when the process died
     * is cleared and restamped, so debris cannot become part of the next round's baseline.</p>
     */
    public static void warmUpTick(MinecraftServer server) {
        if (warmUpCursor < 0) {
            return;
        }
        if (world(server) == null) {
            warmUpCursor = -1;
            return;
        }
        int limit = Math.min(SLOTS, ArenaMaps.ALL.size());
        if (warmUpCursor >= limit) {
            finishWarmUp();
            return;
        }

        int slot = warmUpCursor++;
        ArenaMap map = ArenaMaps.ALL.get(slot);
        if (!map.isFlat() && !USED[slot] && CELLS[slot] == null) {
            int sx = clampSize(map.sizeX, MAX_SIZE_XZ);
            int sy = clampSize(map.sizeY, MAX_SIZE_Y);
            int sz = clampSize(map.sizeZ, MAX_SIZE_XZ);
            if (stamp(server, slot, map, sx, sy, sz, false, false) != null) {
                warmUpBuilt++;
            }
        }
        if (warmUpCursor >= limit) {
            finishWarmUp();
        }
    }

    private static void finishWarmUp() {
        warmUpCursor = -1;
        PvPocket.LOGGER.info("Arena gallery ready: {} maps stamped in {} ms",
                warmUpBuilt, (System.nanoTime() - warmUpStarted) / 1_000_000L);
    }

    /** The cell a map permanently occupies, or -1 when the grid has more maps than cells. */
    private static int homeSlot(ArenaMap map) {
        int index = ArenaMaps.ALL.indexOf(map);
        return index >= 0 && index < SLOTS ? index : -1;
    }

    private static int cellMinX(int slot) {
        return (slot % COLS) * PITCH + MARGIN;
    }

    private static int cellMinZ(int slot) {
        return (slot / COLS) * PITCH + MARGIN;
    }

    /** Where a map lives, for logs and for {@code /pvpocket arena}. Null when it has no cell. */
    public static ArenaRegion cellOf(ArenaMap map) {
        int slot = homeSlot(map);
        return slot < 0 ? null : CELLS[slot];
    }

    /**
     * Every cell that currently holds a stamped map, leased or not.
     *
     * <p>Distinct from {@link #leased()} on purpose: the gallery outlives any single duel, so an
     * operator asking "where is the colosseum" needs the exhibits, not the active leases.</p>
     */
    public static List<ArenaRegion> gallery() {
        List<ArenaRegion> out = new ArrayList<>(SLOTS);
        for (ArenaRegion region : CELLS) {
            if (region != null) {
                out.add(region);
            }
        }
        return out;
    }

    /** Cell count of the grid, for "n / total" style reporting. */
    public static int slotCount() {
        return SLOTS;
    }

    /**
     * How many cells currently hold a plot, for the operator panel's "n / total stamped" line.
     *
     * <p>Counts the registered occupants rather than the blocks: a cell whose map was never built
     * and a cell that is empty are the same thing to an operator deciding whether to add a copy.</p>
     */
    public static int stampedCount() {
        int stamped = 0;
        for (ArenaRegion region : CELLS) {
            if (region != null) {
                stamped++;
            }
        }
        return stamped;
    }

    /** Returns a plot to the pool. Idempotent: releasing an unleased session is a no-op. */
    public static ArenaRegion release(String sessionId) {
        ArenaRegion region = BY_SESSION.remove(sessionId);
        if (region != null && region.index >= 0 && region.index < SLOTS) {
            USED[region.index] = false;
        }
        // Deliberately does not clear CELLS[slot]: the map stays stamped where it is, which is what
        // gives each map a stable address. The next lease of the same map reuses it.
        return region;
    }

    public static ArenaRegion region(String sessionId) {
        return BY_SESSION.get(sessionId);
    }

    public static int leasedCount() {
        return BY_SESSION.size();
    }

    /** Read-only snapshot of the current leases, for diagnostics. */
    public static List<Map.Entry<String, ArenaRegion>> leased() {
        return new ArrayList<>(BY_SESSION.entrySet());
    }

    /**
     * Map indices that a live duel is currently standing on, as a bitmask over {@link ArenaMaps#ALL}.
     *
     * <p>A mask rather than a list because it has to cross the wire: the rule panel greys these out,
     * and a set of up to sixteen booleans is one integer. {@code exceptSessionId} lets a session
     * re-open its own panel without seeing its own plot as taken.</p>
     *
     * <p>Keyed on the map, not on the cell, and that is still right: a map with four copies may
     * host four duels at once, but a fifth has to wait. So the bit means "every copy of this map is
     * leased", not "somebody is playing this map" — the panel greys a map out exactly when pressing
     * it would be refused.</p>
     */
    public static int busyMapMask(String exceptSessionId) {
        int[] leased = new int[ArenaMaps.ALL.size()];
        for (Map.Entry<String, ArenaRegion> entry : BY_SESSION.entrySet()) {
            if (entry.getKey().equals(exceptSessionId)) {
                continue;
            }
            int index = ArenaMaps.ALL.indexOf(entry.getValue().map);
            if (index >= 0 && index < leased.length) {
                leased[index]++;
            }
        }
        int mask = 0;
        for (int i = 0; i < leased.length && i < Integer.SIZE; i++) {
            if (leased[i] > 0 && leased[i] >= copiesOf(ArenaMaps.ALL.get(i))) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** True when some other session is already fighting on this map. */
    public static boolean isMapBusy(int mapIndex, String exceptSessionId) {
        return mapIndex >= 0 && (busyMapMask(exceptSessionId) & (1 << mapIndex)) != 0;
    }

    /** Plot containing this block, or null when the position belongs to no live duel. */
    public static ArenaRegion regionAt(BlockPos pos) {
        for (ArenaRegion region : BY_SESSION.values()) {
            if (region.contains(pos)) {
                return region;
            }
        }
        return null;
    }

    /** Plot whose horizontal bounds contain this position, regardless of height. */
    public static ArenaRegion regionAt(double x, double z) {
        for (ArenaRegion region : BY_SESSION.values()) {
            if (region.contains(x, z)) {
                return region;
            }
        }
        return null;
    }

    /**
     * Drops every lease and every remembered exhibit, on server stop.
     *
     * <p>{@link #CELLS} is cleared here even though {@link #release} deliberately keeps it: a stop
     * destroys the {@code ServerWorld} these regions were stamped into, so the objects would be
     * pointing at a world that no longer exists. The blocks themselves are on disk, so the next
     * start re-derives the whole gallery — which is the same path a crash takes.</p>
     */
    public static void clearAll() {
        BY_SESSION.clear();
        java.util.Arrays.fill(USED, false);
        java.util.Arrays.fill(CELLS, null);
        warmUpCursor = -1;
    }

    /**
     * Erases the cells the gallery fill will <em>not</em> re-stamp, so a hard reset really empties
     * the dimension.
     *
     * <p>The gallery fill skips three kinds of cell by design: the flat plot, which is built at
     * whatever size the rules ask for and therefore cannot be pre-stamped, any extra copy of a map
     * (those are built on first use, not at startup), and any cell no map owns. Re-stamping an
     * exhibit clears its cell as a side effect, so those need nothing here — doing them twice would
     * double the cost of the one operation that already blocks the server for several seconds.</p>
     *
     * <p>Called <em>before</em> {@link #clearAll()}: the regions are what know how tall the debris
     * in their cell can be.</p>
     *
     * @return how many blocks were changed
     */
    public static int wipeUnstampedCells(MinecraftServer server) {
        ServerWorld world = world(server);
        if (world == null) {
            return 0;
        }
        int cleared = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            boolean restamped = slot < ArenaMaps.ALL.size() && !ArenaMaps.ALL.get(slot).isFlat();
            if (restamped) {
                continue;
            }
            ArenaRegion region = CELLS[slot];
            if (region == null) {
                continue;
            }
            cleared += suppressRecording(() -> region.reset(world, region.sizeY));
        }
        return cleared;
    }

    // ------------------------------------------------------------------ block tracking

    /**
     * Hook for every block write in the arena dimension.
     *
     * <p>Called from a {@code ServerWorld} mixin rather than from player events on purpose: that is
     * the single choke point shared by placement, breaking, explosions, pistons, fluids and fire, so
     * a plot cannot be altered through a path the mod forgot to listen to.</p>
     */
    public static void onBlockChanged(ServerWorld world, BlockPos pos, BlockState newState) {
        if (recordingSuppressed > 0) {
            return;
        }
        ArenaRegion region = regionAt(pos);
        if (region == null) {
            return;
        }
        region.onBlockChanged(pos, newState);
    }

    private static int suppressRecording(java.util.function.IntSupplier action) {
        recordingSuppressed++;
        try {
            return action.getAsInt();
        } finally {
            recordingSuppressed--;
        }
    }

    /**
     * Vetoes a block break that the plot's own rules forbid.
     *
     * <p>The distinction the rule asks for is "map block" versus "block a player put here this
     * round", and that is exactly what the deviation set answers. It does not know <em>which</em>
     * player placed a block, so "拆除自己放置的方块" is enforced as "拆除本局放置的方块" — for a 1v1 in a
     * sealed plot the two readings only differ if a player deliberately dismantles the opponent's
     * cover, which is a play-style question rather than a correctness one.</p>
     */
    public static boolean allowBreak(World world, PlayerEntity player, BlockPos pos) {
        if (!(world instanceof ServerWorld) || !isArena(world)) {
            return true;
        }
        ArenaRegion region = regionAt(pos);
        if (region == null) {
            return true;
        }
        boolean placedThisRound = region.isChanged(pos);
        if (region.protectTerrain && !placedThisRound) {
            player.sendMessage(PvLang.t("pvpocket.msg.arena.protected").formatted(Formatting.GRAY), true);
            return false;
        }
        if (!region.allowBreakOwn && placedThisRound) {
            player.sendMessage(PvLang.t("pvpocket.msg.arena.no_break").formatted(Formatting.GRAY), true);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ teleport / bounds

    public static boolean teleportToSpawn(ServerPlayerEntity player, ArenaRegion region, int spawnIndex) {
        return teleportToSpawn(player, region, spawnIndex, 0, 1);
    }

    /**
     * Teleports one player to their side's pad, fanned out by team-mate index.
     *
     * <p>Team-mates would otherwise be teleported to the same block and pushed apart by the collision
     * solver, which on a narrow map means somebody lands in the wall or off the edge. The fan is
     * perpendicular to the way the side is facing, two blocks apart, centred on the pad — so a
     * four-player side is a line of four rather than a stack of one.</p>
     *
     * <p>{@code memberCount == 1} keeps the exact old coordinate, which is every 1v1.</p>
     */
    public static boolean teleportToSpawn(ServerPlayerEntity player, ArenaRegion region, int spawnIndex,
                                          int member, int memberCount) {
        ServerWorld world = world(player.getServer());
        if (world == null || region == null) {
            return false;
        }
        double x = region.spawnX(spawnIndex) + 0.5D;
        double z = region.spawnZ(spawnIndex) + 0.5D;
        // Not SURFACE_Y: a map's pit floor sits wherever the build put it, and spawning at the flat
        // surface would bury the player inside the map's ground layer.
        double y = region.spawnY(spawnIndex);
        float yaw = region.spawnYaw(spawnIndex);
        if (memberCount > 1) {
            // Rows of two, so a 4-player side is a 2x2 block rather than a 4-wide line that would
            // not fit across a narrow arena.
            int col = member % 2;
            int row = member / 2;
            int cols = Math.min(2, memberCount);
            double side = (col - (cols - 1) / 2.0D) * 2.0D;
            double depth = row * 2.0D;
            double rad = Math.toRadians(yaw);
            // Right-hand vector of the facing direction, and "backwards" is toward the player's own
            // end of the arena so rows do not walk into the middle of the map.
            //
            // Minecraft's forward vector for a yaw is (-sin, cos), so backwards is (sin, -cos) — the
            // signs here are the whole point. Getting them the wrong way round still produces a
            // legal 2x2, which is why it is worth writing down: the formation ends up two blocks
            // further into the arena instead of two blocks further back, and nobody notices until
            // somebody measures the spawn against the plot edge.
            double rightX = Math.cos(rad);
            double rightZ = Math.sin(rad);
            x += rightX * side;
            z += rightZ * side;
            x += Math.sin(rad) * depth;
            z -= Math.cos(rad) * depth;
        }
        player.stopRiding();
        player.teleport(world, x, y, z, yaw, 0.0F);
        player.setVelocity(0.0D, 0.0D, 0.0D);
        return true;
    }

    public static boolean isInside(ServerPlayerEntity player, ArenaRegion region) {
        if (region == null || !isArena(player.getWorld())) {
            return false;
        }
        return region.isInside(player.getX(), player.getY(), player.getZ());
    }

    /**
     * Puts a player back at the nearest point inside the plot instead of at their spawn.
     *
     * <p>The distinction matters: "拉回" promises to undo the step that took them out, not to undo
     * the whole fight. Teleporting to spawn would hand the opponent a free reset of position every
     * time somebody clipped the edge.</p>
     *
     * <p>The clamp stops one block short of the wall ring on every side, otherwise the player would
     * be placed inside the boundary blocks and suffocate.</p>
     */
    public static boolean pullInside(ServerPlayerEntity player, ArenaRegion region) {
        ServerWorld world = world(player.getServer());
        if (world == null || region == null) {
            return false;
        }
        // Clamped to the fight area, not the plot box. On the colosseum the box includes the
        // seating, so clamping to the box would "pull back" a player who walked out of the sand by
        // dropping them onto the stands — inside the geometry, outside the fight, still out of
        // bounds, and pulled again on the next tick.
        double x = MathHelper.clamp(player.getX(), region.pitMinX() + 1.5D, region.pitMaxX() - 0.5D);
        double z = MathHelper.clamp(player.getZ(), region.pitMinZ() + 1.5D, region.pitMaxZ() - 0.5D);
        double y = MathHelper.clamp(player.getY(), SURFACE_Y, SURFACE_Y + region.sizeY - 2.0D);
        // Below the map base the player is not being pushed back, they are falling out of the void.
        // Clamping their Y to the map base would put them back at a height where there is nothing to
        // stand on, and they would fall straight back out; look for the floor instead.
        if (player.getY() < SURFACE_Y) {
            int stand = region.standY(world, MathHelper.floor(x), MathHelper.floor(z));
            if (stand >= 0) {
                y = stand;
            }
        }
        player.stopRiding();
        player.teleport(world, x, y, z, player.getYaw(), player.getPitch());
        player.setVelocity(0.0D, 0.0D, 0.0D);
        return true;
    }

    // ------------------------------------------------------------------ spectator flight

    /**
     * How far outside the plot a spectator may fly, in blocks.
     *
     * <p>Measured from the plot box rather than from the boundary curtain. On four of the five
     * maps the two are the same rectangle and this is simply "the boundary plus twelve". On the
     * colosseum they are not: the curtain stands on the sand, and the plot box is the outer wall
     * plus ten rows of seating. Measuring from the curtain there would pen the spectator inside the
     * stands, where every view is the inside of a block; measuring from the box lets them circle the
     * building, which is the only way to watch a colosseum fight.</p>
     *
     * <p>Twelve is chosen against the grid: neighbouring cells are {@link #GAP} blocks apart, so a
     * spectator at the limit is still a long way from the next arena and cannot see one duel from
     * inside another.</p>
     */
    public static final double SPECTATOR_MARGIN = 12.0D;

    /** How far above the plot top a spectator may climb. Watching from directly overhead, not orbit. */
    public static final double SPECTATOR_HEADROOM = 10.0D;

    /** How far below the platform a spectator may dip, to look up at the underside of the map. */
    public static final double SPECTATOR_DEPTH = 6.0D;

    /**
     * The box a detached spectator is allowed to fly in.
     *
     * <p>Only detached spectators are clamped. While the camera is attached to a participant the
     * spectator's own body is not moving, so there is nothing to constrain — and teleporting a body
     * nobody is looking at would be one packet per tick for no visible effect.</p>
     */
    public static Box spectatorBox(ArenaRegion region) {
        return new Box(
                region.minX - SPECTATOR_MARGIN,
                ArenaRegion.PLATFORM_Y - SPECTATOR_DEPTH,
                region.minZ - SPECTATOR_MARGIN,
                region.maxX() + 1.0D + SPECTATOR_MARGIN,
                SURFACE_Y + region.sizeY + SPECTATOR_HEADROOM,
                region.maxZ() + 1.0D + SPECTATOR_MARGIN);
    }

    /**
     * Clamps a spectator back into {@link #spectatorBox}.
     *
     * <p>Server-side teleport, like {@link #pullInside}: spectator flight is client-authoritative,
     * so the only lever the server has is to say where the player actually is. The result is a
     * spectator who slides along the limit instead of crossing it, which is the same behaviour the
     * world border gives and reads as a wall rather than as a glitch.</p>
     *
     * @return true when the player was outside and has been moved back in.
     */
    public static boolean pullSpectatorInside(ServerPlayerEntity player, ArenaRegion region) {
        ServerWorld world = world(player.getServer());
        if (world == null || region == null) {
            return false;
        }
        Box box = spectatorBox(region);
        double x = MathHelper.clamp(player.getX(), box.minX, box.maxX);
        double y = MathHelper.clamp(player.getY(), box.minY, box.maxY);
        double z = MathHelper.clamp(player.getZ(), box.minZ, box.maxZ);
        if (x == player.getX() && y == player.getY() && z == player.getZ()) {
            return false;
        }
        player.teleport(world, x, y, z, player.getYaw(), player.getPitch());
        player.setVelocity(0.0D, 0.0D, 0.0D);
        return true;
    }

    // ------------------------------------------------------------------ cleanup

    /**
     * Writes a plot back to its baseline and empties its deviation set.
     *
     * <p>Recording is suppressed for the duration, otherwise every restored position would be
     * re-marked as a change by the very write that undoes it.</p>
     */
    public static int revertRegion(ServerWorld world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        return suppressRecording(() -> region.revert(world));
    }

    /**
     * Removes every dropped item and experience orb in the plot.
     *
     * <p>This is a correctness requirement, not housekeeping: a player who dies in the arena drops
     * their survival inventory, then gets it back from the snapshot. If those drops stayed on the
     * ground the player could walk over and collect them, duplicating the entire inventory.</p>
     */
    public static int clearDroppedItems(ServerWorld world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        Box box = region.entityBox(world.getBottomY(), world.getTopY());
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

    /** Removes every entity in the plot except players. Used when a plot is handed to a new duel. */
    public static int purge(ServerWorld world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        Box box = region.entityBox(world.getBottomY(), world.getTopY());
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : world.getEntitiesByClass(Entity.class, box, e -> !(e instanceof ServerPlayerEntity))) {
            doomed.add(entity);
        }
        doomed.forEach(Entity::discard);
        return doomed.size();
    }

    /**
     * Removes living non-player entities from every leased plot.
     *
     * <p>Natural spawning is already cancelled in this dimension; this catches the remaining doors —
     * spawn eggs, spawner blocks, {@code /summon}, and modded spawners — without also deleting the
     * arrows, splash potions and dropped items a duel legitimately produces mid-round.</p>
     */
    public static int sweepMobs(MinecraftServer server) {
        ServerWorld world = world(server);
        if (world == null || BY_SESSION.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (ArenaRegion region : BY_SESSION.values()) {
            Box box = region.entityBox(world.getBottomY(), world.getTopY());
            for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, box,
                    e -> !(e instanceof ServerPlayerEntity))) {
                entity.discard();
                removed++;
            }
        }
        return removed;
    }

    private static int clampSize(int value, int max) {
        return Math.max(8, Math.min(value, max));
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
