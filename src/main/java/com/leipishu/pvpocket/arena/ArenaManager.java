package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.compat.PvCompat;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.i18n.PvLang;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The duel dimension, its plot grid, and the rules that keep one plot out of the next duel.
 *
 * <p>Minecraft cannot create dimensions at runtime, so the design is one fixed dimension whose
 * interior is carved into a grid of non-overlapping plots. Every session leases one plot for the
 * duration of its round and returns it when it settles.
 *
 * <p>Three invariants hold here: no mobs (natural spawning is cancelled and strays are swept), a
 * plot is returned to its baseline (blocks are tracked as "differs from baseline"), and two
 * sessions never share a plot (leases are keyed by session id).
 */
public final class ArenaManager {

    public static final ResourceKey<Level> ARENA_WORLD =
            ResourceKey.create(Registries.DIMENSION, PvPocket.id("arena"));

    /** Walkable surface; the flat generator puts bedrock at 0 and stone at 1..2. */
    public static final int SURFACE_Y = ArenaRegion.SURFACE_Y;

    /**
     * Grid shape. The first {@code ArenaMaps.ALL.size()} cells are the maps' own addresses; the
     * rest are the pool that {@link #addCopy} hands out when a map needs another cell.
     */
    public static final int COLS = 4;
    public static final int ROWS = 4;

    /**
     * Interior size of one cell.
     *
     * <p>A cell is the unit the dimension is divided into, and the unit that gets cleared when a
     * map is stamped. It is larger than most maps so it can hold the biggest one, the colosseum at
     * 147x119, without its walls crossing into the neighbour.
     *
     * <p>Not the largest plot a duel may ask for; see {@link #MAX_SIZE_XZ}. A plot may spill past
     * its own cell into the gap.
     */
    public static final int CELL = 160;

    /**
     * Empty ring between two neighbouring cells, in blocks.
     *
     * <p>This is what makes two arenas read as two places: 96 leaves a clear band of flat ground
     * between every pair.
     */
    public static final int GAP = 96;

    /** Cell centre-to-centre spacing, in blocks. */
    public static final int PITCH = CELL + GAP;

    /** Distance from the pitch origin to the corner of a cell's interior. */
    public static final int MARGIN = (PITCH - CELL) / 2;

    /**
     * Closest two maximum-size plots may come to each other, in blocks.
     *
     * <p>Both plots' {@link ArenaRegion#BUFFER} shelves (8 each) plus 16 blocks of clear gap. The
     * shelves must not touch: a shared shelf would let a player knocked off one arena walk onto the
     * other's floor. The remaining 16 keeps the two barrier rings from reading as one wall.
     */
    private static final int MIN_ARENA_GAP = 32;

    /**
     * Largest plot the rule panel may request.
     *
     * <p>Not the cell size. A plot is centred in its cell, so a wider plot spills into the gap on
     * both sides, where nothing else is ever stamped. The limit is the neighbour: at
     * {@code PITCH - 32} two maximum plots still leave {@link #MIN_ARENA_GAP} between them.
     *
     * <p>224, so the flat plot's size is not capped by the widest shipped build.
     */
    public static final int MAX_SIZE_XZ = PITCH - MIN_ARENA_GAP;

    /**
     * Tallest plot the rule panel may request.
     *
     * <p>96. The tallest shipped map is 43, and the real ceiling is the dimension height minus the
     * buffer wall, which 96 plus {@code SURFACE_Y + 2} leaves clear.
     */
    public static final int MAX_SIZE_Y = 96;


    private static final int SLOTS = COLS * ROWS;

    /**
     * The stamped plot living in each cell, whether or not it is leased.
     *
     * <p>A cell keeps its map between duels, so selecting the colosseum always goes to the same
     * coordinates, and a second duel on the same map takes a spare cell.
     */
    private static final ArenaRegion[] CELLS = new ArenaRegion[SLOTS];

    /** Session id -> leased plot. LinkedHashMap so iteration order is stable for logs. */
    private static final Map<String, ArenaRegion> BY_SESSION = new LinkedHashMap<>();
    private static final boolean[] USED = new boolean[SLOTS];

    // ------------------------------------------------------------------ copies

    /**
     * How many cells each map gets on a fresh install, its own address included.
     *
     * <p>The flat plot is the default map and the colosseum is the one players ask for by name, so
     * they get four and two cells. Every other map keeps one cell.
     */
    private static final Map<String, Integer> DEFAULT_COPIES = Map.of(
            "flat", 4,
            "arena_colosseum", 2);

    /**
     * The cells handed out beyond a map's own address, keyed by map id, in the order they were
     * added.
     *
     * <p>Slot numbers are stable addresses, so the third copy of the flat plot is always the same
     * coordinates, across restarts and across a reset. Persisted to {@code pvpocket/arena.json}.
     */
    private static final Map<String, List<Integer>> EXTRA = new LinkedHashMap<>();

    /**
     * Maps that have given up their own address, and therefore live only in {@link #EXTRA}.
     *
     * <p>A map's home cell is derived from its index in {@link ArenaMaps#ALL}, so deleting a map's
     * last copy needs somewhere to record that the address is unused. {@link #copiesOf} subtracts
     * one for every id here, and {@link #addCopy} restores the home cell by removing the id.
     */
    private static final java.util.Set<String> NO_HOME = new java.util.LinkedHashSet<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * On-disk shape of {@code arena.json}. A record so Gson needs no reflection on private fields.
     *
     * <p>Version 2 added {@code noHome}. A version 1 file loads with the field null, which reads as
     * "no map has given up its address"; the next save writes version 2.
     */
    private record LayoutFile(int version, Map<String, List<Integer>> extra, List<String> noHome) {
    }

    /**
     * Reads the copy layout, seeding the defaults on a world that has none.
     *
     * <p>Called once per server start, before {@link #beginWarmUp}. A missing file seeds the
     * defaults and is written back so the next start reads the same slots.
     */
    public static void loadLayout(MinecraftServer server) {
        EXTRA.clear();
        NO_HOME.clear();
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
                if (parsed != null && parsed.noHome() != null) {
                    NO_HOME.addAll(parsed.noHome());
                }
            } catch (IOException | RuntimeException e) {
                // Losing this file costs the extra cells, not the maps: every map still has its own
                // address, which is derived from its index. Re-seed and carry on.
                PvPocket.LOGGER.warn("Could not read {}; copy layout falls back to defaults", file, e);
                EXTRA.clear();
                NO_HOME.clear();
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
            // A map whose address was given up has an empty entry, so it is never reseeded; the
            // second test is for a hand-written file that dropped both.
            if (NO_HOME.contains(map.id)) {
                EXTRA.put(map.id, new ArrayList<>());
                changed = true;
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
     * <p>A hand-edited or half-written file must not point two maps at one cell. Returns true when
     * anything was dropped.
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
        // A noHome entry for a map that does not exist would never be read again, and leaving it
        // makes the next operator wonder which map it referred to.
        for (String id : new ArrayList<>(NO_HOME)) {
            boolean known = false;
            for (ArenaMap map : ArenaMaps.ALL) {
                if (map.id.equals(id)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                NO_HOME.remove(id);
                changed = true;
            }
        }
        return changed;
    }

    private static void saveLayout(MinecraftServer server) {
        Path file = layoutFile(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new LayoutFile(2, EXTRA, new ArrayList<>(NO_HOME))),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            PvPocket.LOGGER.warn("Could not write {}; the copy layout will not survive a restart",
                    file, e);
        }
    }

    private static Path layoutFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("pvpocket").resolve("arena.json");
    }

    private static int totalAssigned() {
        int total = 0;
        for (ArenaMap map : ArenaMaps.ALL) {
            total += copiesOf(map);
        }
        return total;
    }

    /**
     * How many cells a map owns, its own address included.
     *
     * <p>Zero is a legal answer. Deleting every copy of a map takes it out of circulation: the rule
     * panel steps over it, and a session that still has it selected is refused at the confirm
     * prompt.
     */
    public static int copiesOf(ArenaMap map) {
        List<Integer> extra = EXTRA.get(map.id);
        int extras = extra == null ? 0 : extra.size();
        return (NO_HOME.contains(map.id) ? 0 : 1) + extras;
    }

    /**
     * Every cell a map owns, its own address first.
     *
     * <p>The address is {@code ArenaMaps.ALL.indexOf(map)} and never moves; the extra copies are
     * whatever {@link #EXTRA} recorded. Empty when the map has been deleted down to nothing.
     *
     * <p>The order matters to {@link #removeCopy}, which takes cells from the end, so dropping the
     * last copy added keeps every other copy at the address it had.
     */
    public static int[] slotsOf(ArenaMap map) {
        List<Integer> extra = EXTRA.get(map.id);
        int extras = extra == null ? 0 : extra.size();
        boolean hasHome = !NO_HOME.contains(map.id);
        int home = hasHome ? homeSlot(map) : -1;
        if (hasHome && home < 0) {
            hasHome = false;
        }
        int[] out = new int[(hasHome ? 1 : 0) + extras];
        int at = 0;
        if (hasHome) {
            out[at++] = home;
        }
        for (int i = 0; i < extras; i++) {
            out[at++] = extra.get(i);
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
     * <p>Operator-only. The cell is recorded before it is built, so a stamp that fails halfway
     * leaves a reserved address rather than a built cell nobody recorded.
     *
     * <p>A flat copy is reserved but not built; its platform is cut to whatever size the rules ask
     * for, so it is stamped by the first lease that lands on it.
     *
     * @return the cell the copy was given, or -1 when the grid has no spare cell left
     */
    public static int addCopy(MinecraftServer server, ArenaMap map) {
        if (map == null) {
            return -1;
        }
        // A map with no cells gets its own address back before it gets a spare one: the address is
        // the coordinates an operator wrote down.
        int home = homeSlot(map);
        if (home >= 0 && NO_HOME.remove(map.id)) {
            saveLayout(server);
            if (!map.isFlat()) {
                stamp(server, home, map, clampSize(map.sizeX, MAX_SIZE_XZ),
                        clampSize(map.sizeY, MAX_SIZE_Y), clampSize(map.sizeZ, MAX_SIZE_XZ),
                        false, false);
            }
            return home;
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

    /** Returned by {@link #removeCopy} when the map already owns no cells. */
    public static final int REMOVE_NONE = -1;
    /** Returned by {@link #removeCopy} when every cell the map owns is leased to a live duel. */
    public static final int REMOVE_BUSY = -2;

    /**
     * Takes one cell away from a map and wipes it, the inverse of {@link #addCopy}.
     *
     * <p>Cells go from the end of {@link #slotsOf}, so the copy added last is removed first and
     * every older copy keeps its address. When the extras run out the map's own address is given
     * up too: {@code /pvpocket arena remove flat 4} leaves the flat plot with no cells.
     *
     * <p>A leased cell is stepped over, since deleting it would leave the duel's players in the air
     * over the void; if every cell is leased the call returns {@link #REMOVE_BUSY}.
     *
     * @return the released cell, {@link #REMOVE_NONE} or {@link #REMOVE_BUSY}
     */
    public static int removeCopy(MinecraftServer server, ArenaMap map) {
        if (map == null) {
            return REMOVE_NONE;
        }
        int[] slots = slotsOf(map);
        if (slots.length == 0) {
            return REMOVE_NONE;
        }
        List<Integer> extra = EXTRA.get(map.id);
        for (int i = slots.length - 1; i >= 0; i--) {
            int slot = slots[i];
            if (USED[slot]) {
                continue;
            }
            if (extra != null && extra.remove(Integer.valueOf(slot))) {
                // An extra cell: the map keeps its address and loses this one.
            } else {
                NO_HOME.add(map.id);
            }
            saveLayout(server);
            vacate(server, slot, map);
            PvPocket.LOGGER.info("Removed a copy of {} at cell {} ({} left)",
                    map.id, slot, copiesOf(map));
            return slot;
        }
        return REMOVE_BUSY;
    }

    /**
     * Empties a cell a map has given up: terrain back to void, entities gone, gallery entry gone.
     *
     * <p>The blocks have to go, not just the record, because nothing else cleans a cell with no
     * owner: the gallery fill only re-stamps a map's first copy, and {@link #wipeUnstampedCells}
     * walks {@link #CELLS}, which has no entry for it.
     *
     * <p>{@link #CELLS} is rebuilt on every start and only holds each map's first copy, so a copy
     * stamped in a previous session may be unknown here. When the region is known its box is used;
     * otherwise a maximum-size box centred in the cell is cleared, which is a superset of anything
     * a plot can occupy in that cell.
     */
    private static void vacate(MinecraftServer server, int slot, ArenaMap map) {
        ServerLevel world = world(server);
        if (world == null) {
            CELLS[slot] = null;
            USED[slot] = false;
            return;
        }
        ArenaRegion region = CELLS[slot];
        if (region == null) {
            int cellX = cellMinX(slot);
            int cellZ = cellMinZ(slot);
            // Centred like a stamped plot, so the box covers the same ground the stamp would have.
            region = new ArenaRegion("arena-" + slot, slot,
                    cellX + (CELL - MAX_SIZE_XZ) / 2, cellZ + (CELL - MAX_SIZE_XZ) / 2,
                    MAX_SIZE_XZ, MAX_SIZE_Y, MAX_SIZE_XZ,
                    cellX, cellZ, CELL, CELL, map, false, false);
        }
        final ArenaRegion cleared = region;
        // Timed like stamp(). Deleting a copy blocks the server thread, and the erased count
        // separates an empty cell from a full arena.
        long started = System.nanoTime();
        int wiped = suppressRecording(() -> cleared.reset(world, cleared.sizeY));
        purge(world, cleared);
        CELLS[slot] = null;
        USED[slot] = false;
        PvPocket.LOGGER.info("Vacated cell {} ({} blocks wide, {} blocks erased) in {} ms",
                slot, cleared.sizeX, wiped, (System.nanoTime() - started) / 1_000_000L);
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
     * <p>Reset and revert both write the baseline back, and recording those writes would re-mark
     * every position as changed.
     */
    private static int recordingSuppressed;

    private ArenaManager() {
    }

    public static ServerLevel world(MinecraftServer server) {
        return server.getLevel(ARENA_WORLD);
    }

    public static boolean isReady(MinecraftServer server) {
        return world(server) != null;
    }

    public static boolean isArena(Level world) {
        return world != null && world.dimension().equals(ARENA_WORLD);
    }

    // ------------------------------------------------------------------ leases

    /**
     * Leases the plot that belongs to the selected map, stamping it if this is the first time.
     *
     * <p>When a map is selected its bounding box wins over {@code sizeX/sizeY/sizeZ}, so the plot
     * covers the whole build.
     *
     * <p>Every map has a fixed address, chosen from its index in {@link ArenaMaps#ALL}, so the
     * colosseum is always at the colosseum's coordinates. A map owns one cell per copy, recorded
     * in {@link #EXTRA}; this takes the first copy nobody is standing in. A cell that already holds
     * the map is reused as-is after reverting the previous round's edits. Returns null when no cell
     * is available, which the caller must treat as "cannot start".
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
     * Clears a cell, stamps a map into it, and registers the result as that cell's occupant.
     *
     * <p>Clearing uses the cell rectangle rather than the map's box and clears to the taller of the
     * outgoing and incoming build heights, so a cell handed from the colosseum to the 45-wide
     * island does not keep the colosseum's outer wall.
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

        ServerLevel world = world(server);
        if (world == null) {
            PvPocket.LOGGER.error("Arena world is not loaded; cannot stamp {}", map.id);
            return null;
        }
        int clearY = previous == null ? sy : Math.max(sy, previous.sizeY);
        // Phase timings: clearing an empty cell is a few million reads that write nothing, while
        // pasting is tens of thousands of writes, so the log names each phase.
        long started = System.nanoTime();
        int cleared = suppressRecording(() -> region.reset(world, clearY));
        long clearedAt = System.nanoTime();
        int floorBlocks = 0;
        int pasted = 0;
        if (map.isFlat()) {
            floorBlocks = suppressRecording(() -> buildPlatform(world, region));
        } else {
            // Paste and capture both run with recording suppressed: otherwise every written block
            // would be filed as a player change and the deviation set would fill up.
            pasted = suppressRecording(() -> map.paste(world, region) ? 1 : 0);
        }
        long builtAt = System.nanoTime();
        // Capture runs for the flat plot too, so its platform is part of the baseline; otherwise
        // the first revert would delete the ground under the players.
        suppressRecording(() -> {
            region.capture(world);
            return 0;
        });
        // After capture, so the barrier shelf is not mistaken for part of the map: it sits one
        // block below the plot floor and outside the plot box.
        int bufferBlocks = suppressRecording(() -> buildBuffer(world, region));
        int entities = purge(world, region);
        long finished = System.nanoTime();
        // Registered here rather than by the caller so both the duel lease and the startup warm-up
        // leave the cell with an occupant.
        CELLS[slot] = region;
        PvPocket.LOGGER.info("Stamped {} into cell {} at {},{},{} (cleared {} blocks, map {} "
                        + "pasted {}, platform {} blocks, buffer {} barrier, {} template blocks, "
                        + "purged {} entities; clear {} ms, build {} ms, capture {} ms)",
                region, slot, originX, SURFACE_Y, originZ, cleared, map.id, pasted == 1,
                floorBlocks, bufferBlocks, region.templateBlockCount(), entities,
                (clearedAt - started) / 1_000_000L, (builtAt - clearedAt) / 1_000_000L,
                (finished - builtAt) / 1_000_000L);
        return region;
    }

    /**
     * Lays the flat arena's floor.
     *
     * <p>The dimension is void, so a flat plot is a platform of the requested size, floating where
     * a pasted map's base would be. It sits at {@link ArenaRegion#PLATFORM_Y}, one below
     * {@link ArenaRegion#SURFACE_Y}.
     *
     * <p>Returns the block count so the caller can log it the same way it logs a paste.
     */
    private static int buildPlatform(ServerLevel world, ArenaRegion region) {
        BlockState floor = Blocks.SMOOTH_STONE.defaultBlockState();
        int placed = 0;
        for (int x = region.minX; x <= region.maxX(); x++) {
            for (int z = region.minZ; z <= region.maxZ(); z++) {
                world.setBlock(new BlockPos(x, ArenaRegion.PLATFORM_Y, z), floor,
                        Block.UPDATE_CLIENTS);
                placed++;
            }
        }
        return placed;
    }

    /**
     * Builds the invisible buffer around a plot: a barrier shelf and a barrier wall at its rim.
     *
     * <p>The shelf covers the whole plot box plus {@link ArenaRegion#BUFFER} on every side, one
     * block below the plot floor. It covers the box as well as the ring because a build's box is
     * not its footprint: the island is a disc inside a square box, so its corners are empty and a
     * ring-only shelf would leave four holes to fall through.
     *
     * <p>The wall stands at the outer rim of the shelf and reaches past the tallest thing the map
     * can hold. Runs after {@link ArenaRegion#capture} so the shelf is not mistaken for map blocks.
     */
    private static int buildBuffer(ServerLevel world, ArenaRegion region) {
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
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
    private static int setIfDifferent(ServerLevel world, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z);
        if (world.getBlockState(pos) == state) {
            return 0;
        }
        world.setBlock(pos, state, Block.UPDATE_CLIENTS);
        return 1;
    }

    // ------------------------------------------------------------------ prepare wall

    /**
     * How far above the higher spawn pad the preparation wall reaches.
     *
     * <p>Eight blocks is enough that nobody jumps it by accident and cheap enough to place and
     * remove every round. It does not stop a player who pillars up, but the preparation window is
     * thirty seconds and the boundary rule waits on the other side.
     */
    private static final int PREPARE_WALL_MARGIN = 8;

    /**
     * Absolute y of the top block of the preparation wall.
     *
     * <p>Measured from the spawn pads rather than from the plot floor, because a map's floor is not
     * where the players are: the colosseum's sand is a few blocks up.
     */
    public static int prepareWallTop(ArenaRegion region) {
        int pads = Math.max(region.spawnY(0), region.spawnY(1));
        return Math.max(ArenaRegion.SURFACE_Y, pads) + PREPARE_WALL_MARGIN;
    }

    /**
     * Builds the solid wall that closes the arena during the preparation window.
     *
     * <p>During preparation nobody can be hit, so the only useful move is to walk out. The wall is
     * {@code minecraft:barrier}: invisible, unbreakable, solid to the engine, and one block outside
     * the fight area, just behind where the boundary curtain is drawn.
     *
     * <p>Only air is written over: on a map with an inset fight area, such as the colosseum, the
     * ring lands on the podium wall, and overwriting it would carve a trench no revert could undo.
     *
     * <p>The screens are built here too; see {@link #prepareScreens}. Recording is suppressed.
     */
    public static int buildPrepareWall(ServerLevel world, ArenaRegion region, int sides) {
        if (world == null || region == null) {
            return 0;
        }
        int ring = suppressRecording(() -> {
            BlockState barrier = Blocks.BARRIER.defaultBlockState();
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
     * <p>Not tracked: the ring's teardown is a barrier scan, which is safe because the converter
     * strips {@code barrier} out of every shipped template. The screens sit further inside the
     * fight area and are tracked by coordinate instead.
     */
    private static int standIfAir(ServerLevel world, int x, int y, int z, BlockState barrier) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.getBlockState(pos).isAir()) {
            return 0;
        }
        world.setBlock(pos, barrier, Block.UPDATE_CLIENTS);
        return 1;
    }

    /**
     * How far in front of a side's pad its screen stands, in blocks.
     *
     * <p>Four leaves the side room to spread out and read the panel, keeps the two screens from
     * meeting in the middle on the smallest legal plot, and leaves the knockback somewhere to go.
     */
    private static final int SCREEN_GAP = 4;

    /**
     * One preparation screen: a vertical plane one block thick, spanning the fight area.
     *
     * <p>Sent to the client verbatim, so the white wall it paints and the barrier blocks the server
     * stands in the world are the same object.
     */
    public record Screen(boolean alongZ, int plane, int from, int to, int floorY, int height) {
    }

    /**
     * Where each side's screen would stand, without touching the world.
     *
     * <p>{@link #buildScreens} stands barrier blocks on these planes and the session sends the same
     * list to the client to paint. The client cannot compute them, because the pads come from a
     * ring search over the stamped map.
     *
     * <p>Sides that cannot be screened are dropped rather than clamped onto the centre line.
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
        // Footed one block below the lowest pad, not at the plot floor: a map floats, so a screen
        // anchored at the floor would hang thousands of blocks into the void.
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
     * <p>Only air is written over. {@link ArenaRegion#clearScreens} writes air back, so overwriting
     * the map would carve a slot through whatever the plane crossed.
     */
    private static int buildScreens(ServerLevel world, ArenaRegion region, int sides) {
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
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
                    world.setBlock(pos, barrier, Block.UPDATE_CLIENTS);
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
     * <p>Only barrier blocks are removed from the ring, so a map with its own barriers at the same
     * coordinates keeps them. Screens are removed from the recorded position list; see
     * {@link ArenaRegion#clearScreens}.
     */
    public static int removePrepareWall(ServerLevel world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        int ring = suppressRecording(() -> {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState barrier = Blocks.BARRIER.defaultBlockState();
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

    private static int clearIfBarrier(ServerLevel world, int x, int y, int z,
                                      BlockState barrier, BlockState air) {
        BlockPos pos = new BlockPos(x, y, z);
        if (world.getBlockState(pos) != barrier) {
            return 0;
        }
        world.setBlock(pos, air, Block.UPDATE_CLIENTS);
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
     * <p>Only the first copy of each map. Extra copies are capacity and are built the first time
     * somebody is sent to one.
     *
     * <p>Spread over ticks because a single stamp is a cell-sized clear (160x160x40 reads) plus
     * tens of thousands of block writes, and the colosseum alone is 109k blocks. Doing eight in a
     * row in {@code SERVER_STARTED} freezes the server long enough for the watchdog to complain.
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
     * Stamps at most one exhibit. Called from the server tick hook; a no-op once the gallery is
     * done.
     *
     * <p>A cell whose map was half-edited when the process died is cleared and restamped, so debris
     * cannot become part of the next round's baseline.
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
        // A map whose address was given up owns no cells, so there is nothing to fill: stamping it
        // would rebuild a build no rule panel can send anybody to.
        if (!NO_HOME.contains(map.id) && !map.isFlat() && !USED[slot] && CELLS[slot] == null) {
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
     * <p>Distinct from {@link #leased()}: the gallery outlives any single duel, so "where is the
     * colosseum" needs the exhibits, not the active leases.
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
     * <p>Counts registered occupants rather than blocks.
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
        // Does not clear CELLS[slot]: the map stays stamped where it is, which gives each map a
        // stable address. The next lease of the same map reuses it.
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
     * Map indices a live duel is standing on, as a bitmask over {@link ArenaMaps#ALL}.
     *
     * <p>A mask rather than a list because it has to cross the wire: the rule panel greys these
     * out, and up to sixteen booleans fit in one integer. {@code exceptSessionId} lets a session
     * re-open its own panel without seeing its own plot as taken.
     *
     * <p>A bit means "pressing this would be refused", not "somebody is playing this map". A map is
     * marked when every copy is leased or when it has no copies at all.
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
            int copies = copiesOf(ArenaMaps.ALL.get(i));
            if (copies == 0 || leased[i] >= copies) {
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
     * <p>{@link #CELLS} is cleared here even though {@link #release} keeps it: a stop destroys the
     * {@code ServerWorld} these regions were stamped into. The blocks are on disk, so the next
     * start re-derives the gallery.
     */
    public static void clearAll() {
        BY_SESSION.clear();
        java.util.Arrays.fill(USED, false);
        java.util.Arrays.fill(CELLS, null);
        warmUpCursor = -1;
    }

    /**
     * Erases the cells the gallery fill will not re-stamp, so a hard reset really empties the
     * dimension.
     *
     * <p>The gallery fill skips the flat plot, which is built at whatever size the rules ask for,
     * any extra copy of a map, and any cell no map owns. Re-stamping an exhibit clears its cell as
     * a side effect, so only the skipped cells need work here.
     *
     * <p>Call before {@link #clearAll()}, while the regions still know how tall their debris is.
     *
     * @return how many blocks were changed
     */
    public static int wipeUnstampedCells(MinecraftServer server) {
        ServerLevel world = world(server);
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
     * <p>Called from a {@code ServerWorld} mixin rather than from player events, because that is
     * the single choke point shared by placement, breaking, explosions, pistons, fluids and fire.
     */
    public static void onBlockChanged(ServerLevel world, BlockPos pos, BlockState newState) {
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
     * <p>The rule distinguishes "map block" from "block a player put here this round", which is
     * what the deviation set answers. It does not know which player placed a block, so "break your
     * own blocks" is enforced as "break blocks placed this round"; in a sealed 1v1 the two readings
     * differ only when a player dismantles the opponent's cover.
     */
    public static boolean allowBreak(Level world, Player player, BlockPos pos) {
        if (!(world instanceof ServerLevel) || !isArena(world)) {
            return true;
        }
        ArenaRegion region = regionAt(pos);
        if (region == null) {
            return true;
        }
        boolean placedThisRound = region.isChanged(pos);
        if (region.protectTerrain && !placedThisRound) {
            PvCompat.overlay(player, PvLang.t("pvpocket.msg.arena.protected").withStyle(ChatFormatting.GRAY));
            return false;
        }
        if (!region.allowBreakOwn && placedThisRound) {
            PvCompat.overlay(player, PvLang.t("pvpocket.msg.arena.no_break").withStyle(ChatFormatting.GRAY));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ teleport / bounds

    public static boolean teleportToSpawn(ServerPlayer player, ArenaRegion region, int spawnIndex) {
        return teleportToSpawn(player, region, spawnIndex, 0, 1);
    }

    /**
     * Teleports one player to their side's pad, fanned out by team-mate index.
     *
     * <p>Team-mates would otherwise land on the same block and be pushed apart by the collision
     * solver. The fan is perpendicular to the way the side faces, two blocks apart, centred on the
     * pad.
     *
     * <p>{@code memberCount == 1} teleports to the pad coordinate exactly, which is every 1v1.
     */
    public static boolean teleportToSpawn(ServerPlayer player, ArenaRegion region, int spawnIndex,
                                          int member, int memberCount) {
        ServerLevel world = world(PvCompat.server(player));
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
            // Right-hand vector of the facing direction; "backwards" is toward the player's own end
            // of the arena. A yaw's forward vector is (-sin, cos), so backwards is (sin, -cos).
            double rightX = Math.cos(rad);
            double rightZ = Math.sin(rad);
            x += rightX * side;
            z += rightZ * side;
            x += Math.sin(rad) * depth;
            z -= Math.cos(rad) * depth;
        }
        player.stopRiding();
        PvCompat.teleport(player, world, x, y, z, yaw, 0.0F);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        return true;
    }

    public static boolean isInside(ServerPlayer player, ArenaRegion region) {
        if (region == null || !isArena(player.level())) {
            return false;
        }
        return region.isInside(player.getX(), player.getY(), player.getZ());
    }

    /**
     * Puts a player back at the nearest point inside the plot instead of at their spawn.
     *
     * <p>Teleporting to spawn would hand the opponent a free reset of position every time somebody
     * clipped the edge.
     *
     * <p>The clamp stops one block short of the wall ring on every side, so the player is not
     * placed inside the boundary blocks.
     */
    public static boolean pullInside(ServerPlayer player, ArenaRegion region) {
        ServerLevel world = world(PvCompat.server(player));
        if (world == null || region == null) {
            return false;
        }
        // Clamped to the fight area, not the plot box: on the colosseum the box includes the
        // seating, so clamping to the box would drop the player on the stands.
        double x = Mth.clamp(player.getX(), region.pitMinX() + 1.5D, region.pitMaxX() - 0.5D);
        double z = Mth.clamp(player.getZ(), region.pitMinZ() + 1.5D, region.pitMaxZ() - 0.5D);
        double y = Mth.clamp(player.getY(), SURFACE_Y, SURFACE_Y + region.sizeY - 2.0D);
        // Below the map base the player is falling out of the void, not being pushed back: clamping
        // Y would put them where there is nothing to stand on, so look for the floor instead.
        if (player.getY() < SURFACE_Y) {
            int stand = region.standY(world, Mth.floor(x), Mth.floor(z));
            if (stand >= 0) {
                y = stand;
            }
        }
        player.stopRiding();
        PvCompat.teleport(player, world, x, y, z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        return true;
    }

    // ------------------------------------------------------------------ spectator flight

    /**
     * How far outside the plot a spectator may fly, in blocks.
     *
     * <p>Measured from the plot box rather than from the boundary curtain. On the colosseum the two
     * differ: the curtain stands on the sand and the box is the outer wall plus ten rows of
     * seating, so measuring from the curtain would pen the spectator inside the stands.
     *
     * <p>Twelve is chosen against the grid: neighbouring cells are {@link #GAP} blocks apart, so a
     * spectator at the limit is still far from the next arena.
     */
    public static final double SPECTATOR_MARGIN = 12.0D;

    /** How far above the plot top a spectator may climb, for watching from overhead. */
    public static final double SPECTATOR_HEADROOM = 10.0D;

    /** How far below the platform a spectator may dip, to look up at the underside of the map. */
    public static final double SPECTATOR_DEPTH = 6.0D;

    /**
     * The box a detached spectator is allowed to fly in.
     *
     * <p>Only detached spectators are clamped. While the camera is attached to a participant the
     * spectator's body is not moving, so there is nothing to constrain.
     */
    public static AABB spectatorBox(ArenaRegion region) {
        return new AABB(
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
     * so the server can only say where the player actually is. The result slides along the limit
     * instead of crossing it.
     *
     * @return true when the player was outside and has been moved back in.
     */
    public static boolean pullSpectatorInside(ServerPlayer player, ArenaRegion region) {
        ServerLevel world = world(PvCompat.server(player));
        if (world == null || region == null) {
            return false;
        }
        AABB box = spectatorBox(region);
        double x = Mth.clamp(player.getX(), box.minX, box.maxX);
        double y = Mth.clamp(player.getY(), box.minY, box.maxY);
        double z = Mth.clamp(player.getZ(), box.minZ, box.maxZ);
        if (x == player.getX() && y == player.getY() && z == player.getZ()) {
            return false;
        }
        PvCompat.teleport(player, world, x, y, z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        return true;
    }

    // ------------------------------------------------------------------ cleanup

    /**
     * Writes a plot back to its baseline and empties its deviation set.
     *
     * <p>Recording is suppressed for the duration, otherwise every restored position would be
     * re-marked as a change by the write that undoes it.
     */
    public static int revertRegion(ServerLevel world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        return suppressRecording(() -> region.revert(world));
    }

    /**
     * Removes every dropped item and experience orb in the plot.
     *
     * <p>A player who dies in the arena drops their survival inventory and then gets it back from
     * the snapshot; if the drops stayed, the player could collect both.
     */
    public static int clearDroppedItems(ServerLevel world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        AABB box = region.entityBox(world.getMinBuildHeight(), world.getMaxBuildHeight());
        int removed = 0;
        for (ItemEntity item : world.getEntitiesOfClass(ItemEntity.class, box, entity -> true)) {
            item.discard();
            removed++;
        }
        for (ExperienceOrb orb : world.getEntitiesOfClass(ExperienceOrb.class, box, entity -> true)) {
            orb.discard();
            removed++;
        }
        return removed;
    }

    /** Removes every entity in the plot except players, when a plot is handed to a new duel. */
    public static int purge(ServerLevel world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        AABB box = region.entityBox(world.getMinBuildHeight(), world.getMaxBuildHeight());
        List<Entity> doomed = new ArrayList<>();
        for (Entity entity : world.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof ServerPlayer))) {
            doomed.add(entity);
        }
        doomed.forEach(Entity::discard);
        return doomed.size();
    }

    /**
     * Removes living non-player entities from every leased plot.
     *
     * <p>Natural spawning is already cancelled in this dimension; this catches spawn eggs, spawner
     * blocks, {@code /summon} and modded spawners without deleting the arrows, splash potions and
     * dropped items a duel produces mid-round.
     */
    public static int sweepMobs(MinecraftServer server) {
        ServerLevel world = world(server);
        if (world == null || BY_SESSION.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (ArenaRegion region : BY_SESSION.values()) {
            AABB box = region.entityBox(world.getMinBuildHeight(), world.getMaxBuildHeight());
            for (LivingEntity entity : world.getEntitiesOfClass(LivingEntity.class, box,
                    e -> !(e instanceof ServerPlayer))) {
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
