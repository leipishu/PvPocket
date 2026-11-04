package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

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

    /** Grid shape. 4x4 = 16 concurrent duels, which is far past what an SMP server needs. */
    public static final int COLS = 4;
    public static final int ROWS = 4;

    /** Centre-to-centre spacing of two neighbouring plots, in blocks. */
    public static final int PITCH = 160;
    /** Empty ring kept between the largest allowed plot and its neighbour. */
    public static final int MARGIN = 16;

    /** Largest plot the rule panel may request. Must stay within {@code PITCH - 2*MARGIN}. */
    public static final int MAX_SIZE_XZ = PITCH - MARGIN * 2;
    public static final int MAX_SIZE_Y = 64;

    private static final int SLOTS = COLS * ROWS;

    /** Session id -> leased plot. LinkedHashMap so iteration order is stable for logs. */
    private static final Map<String, ArenaRegion> BY_SESSION = new LinkedHashMap<>();
    private static final boolean[] USED = new boolean[SLOTS];

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
     * Leases a plot sized by the session's rules and wipes it clean.
     *
     * <p>Returns null when the grid is full, which the caller must treat as "cannot start" rather
     * than as a soft failure — silently reusing a busy plot would teleport two duels into each
     * other.</p>
     */
    public static ArenaRegion allocate(MinecraftServer server, String sessionId,
                                       int sizeX, int sizeY, int sizeZ,
                                       boolean protectTerrain, boolean allowBreakOwn) {
        release(sessionId);
        int slot = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (!USED[i]) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return null;
        }
        int sx = clampSize(sizeX, MAX_SIZE_XZ);
        int sz = clampSize(sizeZ, MAX_SIZE_XZ);
        int sy = clampSize(sizeY, MAX_SIZE_Y);
        // Centring the plot inside its cell keeps two different-sized duels visually symmetric and
        // guarantees the margin even when one plot is at the maximum size.
        int originX = (slot % COLS) * PITCH + (PITCH - sx) / 2;
        int originZ = (slot / COLS) * PITCH + (PITCH - sz) / 2;
        ArenaRegion region = new ArenaRegion("arena-" + slot, slot, originX, originZ, sx, sy, sz,
                protectTerrain, allowBreakOwn);
        USED[slot] = true;
        BY_SESSION.put(sessionId, region);

        ServerWorld world = world(server);
        if (world != null) {
            int blocks = suppressRecording(() -> region.reset(world));
            int entities = purge(world, region);
            PvPocket.LOGGER.info("Leased {} to session {} (reset {} blocks, purged {} entities)",
                    region, shortId(sessionId), blocks, entities);
        }
        return region;
    }

    /** Returns a plot to the pool. Idempotent: releasing an unleased session is a no-op. */
    public static ArenaRegion release(String sessionId) {
        ArenaRegion region = BY_SESSION.remove(sessionId);
        if (region != null && region.index >= 0 && region.index < SLOTS) {
            USED[region.index] = false;
        }
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

    public static void clearAll() {
        BY_SESSION.clear();
        java.util.Arrays.fill(USED, false);
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
            player.sendMessage(Text.literal("场地方块受保护，无法破坏。").formatted(Formatting.GRAY), true);
            return false;
        }
        if (!region.allowBreakOwn && placedThisRound) {
            player.sendMessage(Text.literal("本次决斗不允许拆除方块。").formatted(Formatting.GRAY), true);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ teleport / bounds

    public static boolean teleportToSpawn(ServerPlayerEntity player, ArenaRegion region, int spawnIndex) {
        ServerWorld world = world(player.getServer());
        if (world == null || region == null) {
            return false;
        }
        double x = region.spawnX(spawnIndex) + 0.5D;
        double z = region.spawnZ() + 0.5D;
        player.stopRiding();
        player.teleport(world, x, SURFACE_Y, z, region.spawnYaw(spawnIndex), 0.0F);
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
        double x = MathHelper.clamp(player.getX(), region.minX + 1.5D, region.maxX() - 0.5D);
        double z = MathHelper.clamp(player.getZ(), region.minZ + 1.5D, region.maxZ() - 0.5D);
        double y = MathHelper.clamp(player.getY(), SURFACE_Y, SURFACE_Y + region.sizeY - 2.0D);
        player.stopRiding();
        player.teleport(world, x, y, z, player.getYaw(), player.getPitch());
        player.setVelocity(0.0D, 0.0D, 0.0D);
        return true;
    }

    /**
     * Builds the visible boundary: a three-high glass wall on the plot's outermost ring.
     *
     * <p>Written through the normal block path on purpose, so every wall block lands in the plot's
     * deviation set and the same revert that undoes a player's build also takes the wall down. A
     * separate "remember to remove the wall" step is a step that gets forgotten on the error paths.</p>
     *
     * <p>Three high rather than tall enough to be impassable: leaving should be a deliberate act
     * (build up, ender pearl, a broken section) because that is the case the boundary rule's
     * countdown exists for. A wall nobody can cross would make the countdown dead code.</p>
     */
    public static int buildWall(ServerWorld world, ArenaRegion region) {
        if (world == null || region == null) {
            return 0;
        }
        BlockState glass = Blocks.GLASS.getDefaultState();
        int placed = 0;
        for (int y = SURFACE_Y; y < SURFACE_Y + WALL_HEIGHT; y++) {
            for (int x = region.minX; x <= region.maxX(); x++) {
                for (int z = region.minZ; z <= region.maxZ(); z++) {
                    if (x != region.minX && x != region.maxX()
                            && z != region.minZ && z != region.maxZ()) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos) == glass) {
                        continue;
                    }
                    world.setBlockState(pos, glass, Block.NOTIFY_LISTENERS);
                    placed++;
                }
            }
        }
        return placed;
    }

    /** Height of the boundary wall, in blocks above the surface. */
    private static final int WALL_HEIGHT = 3;

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
