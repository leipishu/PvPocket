package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.compat.PvCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The waiting lobby: one island in the duel dimension, where the result page is read from.
 *
 * <p>Not a plot and not a map. Nothing is fought here and nothing is leased here, so it is not part
 * of the grid: it sits a thousand blocks from the nearest cell and is rebuilt on every start. The
 * build is third-party artwork (House Island by Mirai_Minecraft; {@code THIRD-PARTY-NOTICES.md}
 * records the permission it ships under).
 *
 * <p>The island has an edge: a guest who walks off it falls into the void, and the arena dimension
 * has no ground to respawn on. {@code Lobby#contains} vetoes block breaks, and the session's tick
 * puts a falling guest back on the island.
 */
public final class Lobby {

    /**
     * Centre of the island.
     *
     * <p>The plot grid starts at {@code x = z = 512} and the lobby is 44 wide, so this is roughly
     * 680 blocks clear of the nearest cell corner: past {@code ArenaFog.END}, which means a guest
     * never sees a plot from here and never has one loaded behind it.
     */
    public static final int CENTER_X = -192;
    public static final int CENTER_Z = -192;

    /** The island's top surface block, in absolute y. */
    public static final int FLOOR_Y = 40;

    /**
     * Local y of the island surface inside the template, i.e. where {@link #FLOOR_Y} comes from.
     *
     * <p>Declared rather than derived: the template is a rectangle, so its own y=0 is the lowest
     * block of the rock, not the surface. A mismatch is logged rather than silently shifting the
     * island.
     */
    private static final int TEMPLATE_SURFACE = 4;

    /** Template footprint, for the protection box, the spawn point and the size check. */
    private static final int TEMPLATE_X = 44;
    private static final int TEMPLATE_Y = 24;
    private static final int TEMPLATE_Z = 31;

    /** The block a guest stands on, in the template's own coordinates: open lawn, house in view. */
    private static final int TEMPLATE_SPAWN_X = 16;
    private static final int TEMPLATE_SPAWN_Z = 14;

    /** Where a guest stands, as the y of their feet: one above the surface block. */
    public static final int SPAWN_Y = FLOOR_Y + 1;

    /** Where a guest stands horizontally: the template's spawn block, mapped into world x and z. */
    public static final int SPAWN_X = CENTER_X - TEMPLATE_X / 2 + TEMPLATE_SPAWN_X;
    public static final int SPAWN_Z = CENTER_Z - TEMPLATE_Z / 2 + TEMPLATE_SPAWN_Z;

    /** Facing the house, which is east, i.e. yaw 270. */
    private static final float SPAWN_YAW = 270.0F;

    private static final ResourceLocation TEMPLATE = PvPocket.id("lobby");

    /**
     * How far the clear box reaches around the island, in blocks.
     *
     * <p>Fixed rather than derived from the template: the box has to erase what a previous version
     * of the lobby left behind, not just what this one is about to stamp. A resized build whose
     * clear box only covered itself would leave the old rock hanging under the new island.
     */
    private static final int CLEAR_REACH = 32;

    /** How far below the surface the clear box reaches, for the same reason. */
    private static final int CLEAR_DEPTH = 20;

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
        int erased = clear(world, originY + size.getY() + 2);
        int swept = clearEntities(world, originY + size.getY() + 2);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(false)
                .setFinalizeEntities(false);
        BlockPos origin = new BlockPos(originX, originY, originZ);
        boolean placed = template.placeInWorld(world, origin, origin, settings,
                RandomSource.create(0L), Block.UPDATE_CLIENTS);
        PvPocket.LOGGER.info("Lobby {} at {},{} (surface {}, spawn {}, erased {} blocks, "
                        + "swept {} entities) in {} ms",
                placed ? "stamped" : "NOT placed", originX, originZ, FLOOR_Y, SPAWN_Y,
                erased, swept, (System.nanoTime() - started) / 1_000_000L);
    }

    /** Empties the island's volume: what a guest placed, and what an older build left. */
    private static int clear(ServerLevel world, int topY) {
        var air = Blocks.AIR.defaultBlockState();
        int erased = 0;
        for (int x = CENTER_X - CLEAR_REACH; x <= CENTER_X + CLEAR_REACH; x++) {
            for (int z = CENTER_Z - CLEAR_REACH; z <= CENTER_Z + CLEAR_REACH; z++) {
                for (int y = FLOOR_Y - CLEAR_DEPTH; y < topY; y++) {
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
     * Removes every entity that is not a player from the island's volume.
     *
     * <p>{@link #clear} empties blocks, not entities. An item dropped by an older build of the
     * island therefore stays in the world save, and the next build stamps the new surface around
     * it: a guest then walks over a lantern that has not been a block for weeks and picks it up.
     */
    private static int clearEntities(ServerLevel world, int topY) {
        return discard(world, topY, entity -> !(entity instanceof Player));
    }

    /** Removes dropped items only, so a session starts on a clean floor. */
    public static int clearDroppedItems(ServerLevel world) {
        if (world == null) {
            return 0;
        }
        return discard(world, FLOOR_Y + TEMPLATE_Y, entity -> entity instanceof ItemEntity);
    }

    private static int discard(ServerLevel world, int topY, Predicate<Entity> which) {
        AABB box = new AABB(CENTER_X - CLEAR_REACH, FLOOR_Y - CLEAR_DEPTH, CENTER_Z - CLEAR_REACH,
                CENTER_X + CLEAR_REACH + 1, topY, CENTER_Z + CLEAR_REACH + 1);
        int removed = 0;
        for (Entity entity : world.getEntitiesOfClass(Entity.class, box, which)) {
            entity.discard();
            removed++;
        }
        return removed;
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
     * not permanent, but a guest punching a hole in the island during a countdown is still worth
     * refusing. The box is the volume {@link #clear} empties, so a block a guest can place is a
     * block the veto covers.
     */
    public static boolean contains(Level world, BlockPos pos) {
        if (!ArenaManager.isArena(world)) {
            return false;
        }
        return Math.abs(pos.getX() - CENTER_X) <= CLEAR_REACH
                && Math.abs(pos.getZ() - CENTER_Z) <= CLEAR_REACH
                && pos.getY() >= FLOOR_Y - CLEAR_DEPTH
                && pos.getY() < FLOOR_Y + TEMPLATE_Y;
    }

    /** True when the player has fallen past the island, i.e. is about to leave the world. */
    public static boolean belowIsland(ServerPlayer player) {
        return ArenaManager.isArena(player.level()) && player.getY() < FLOOR_Y - 6;
    }
}
