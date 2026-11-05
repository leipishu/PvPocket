package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;

import java.util.Optional;

/**
 * One selectable arena map: a vanilla structure template plus the size it needs.
 *
 * <p>Maps are stored as ordinary structure templates under {@code data/pvpocket/structure/}, which
 * buys two things a bespoke format would not: {@code /place template} works for eyeballing them in
 * game, and the same file can be produced by hand with a structure block for anything under 48³.</p>
 *
 * <p>{@link #sizeX} and friends are declared here rather than read from the template at runtime so
 * the rule panel can lay itself out without touching the server. {@link #paste} re-checks them
 * against the loaded template and logs a warning on a mismatch instead of trusting the constant.</p>
 */
public final class ArenaMap {

    /**
     * The generated flat plot: a platform of the requested size, floating in the void.
     *
     * <p>Not a template, because its size comes from the rules rather than from a file. It is the
     * one arena that has to build its own ground — see {@code ArenaManager#buildPlatform}.</p>
     */
    public static final ArenaMap FLAT = new ArenaMap("flat", "平坦", null, 0, 0, 0);

    public final String id;
    /** Shown in the rule panel's cycle control, so it has to stay short. */
    public final String label;
    public final Identifier templateId;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;

    /**
     * True when the build's two sides are separated along Z rather than X.
     *
     * <p>Not cosmetic: {@link ArenaRegion#spawnX} puts the two players a quarter of the width either
     * side of the centre, and if the play axis is Z that lands them beside each other in the middle
     * of the corridor instead of at opposite ends. Two of the three shipped maps are built this way
     * — a 47x95 cavern and a 102x99 bridge whose source XML spawns players at {@code x=0}.</p>
     *
     * <p>The honest fix is a per-map play rectangle (see the design doc), which would make this
     * flag unnecessary. Until then this is the smallest thing that gets the spawns right.</p>
     */
    public final boolean alongZ;

    public ArenaMap(String id, String label, Identifier templateId, int sizeX, int sizeY, int sizeZ) {
        this(id, label, templateId, sizeX, sizeY, sizeZ, false);
    }

    public ArenaMap(String id, String label, Identifier templateId, int sizeX, int sizeY, int sizeZ,
                    boolean alongZ) {
        this.id = id;
        this.label = label;
        this.templateId = templateId;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.alongZ = alongZ;
    }

    public boolean isFlat() {
        return templateId == null;
    }

    /**
     * Stamps the template onto a freshly cleared plot.
     *
     * <p>Entities are ignored and neighbour updates are off: a downloaded map can carry item frames,
     * armour stands and chests, and the mod's contract is that the arena contains blocks only. The
     * neighbour flag matters for the same reason — 72k block updates fanning out through the world
     * would take seconds and would also re-trigger the plot's own change tracking.</p>
     */
    public boolean paste(ServerWorld world, ArenaRegion region) {
        if (isFlat()) {
            return false;
        }
        Optional<StructureTemplate> loaded =
                world.getServer().getStructureTemplateManager().getTemplate(templateId);
        if (loaded.isEmpty()) {
            PvPocket.LOGGER.error("Arena map {} is selected but {} is not in the jar",
                    id, templateId);
            return false;
        }
        StructureTemplate template = loaded.get();
        Vec3i size = template.getSize();
        if (size.getX() != sizeX || size.getY() != sizeY || size.getZ() != sizeZ) {
            PvPocket.LOGGER.warn("Arena map {} declares {}x{}x{} but {} is {}x{}x{}",
                    id, sizeX, sizeY, sizeZ, templateId,
                    size.getX(), size.getY(), size.getZ());
        }
        BlockPos origin = new BlockPos(region.minX, ArenaRegion.SURFACE_Y, region.minZ);
        StructurePlacementData settings = new StructurePlacementData()
                .setIgnoreEntities(true)
                .setUpdateNeighbors(false)
                .setInitializeMobs(false);
        long started = System.nanoTime();
        boolean placed = template.place(world, origin, origin, settings,
                Random.create(0L), Block.NOTIFY_LISTENERS);
        PvPocket.LOGGER.info("Pasted {} at {},{} ({}) in {} ms", id, origin.getX(), origin.getZ(),
                placed ? "ok" : "no-op", (System.nanoTime() - started) / 1_000_000L);
        return placed;
    }

    @Override
    public String toString() {
        return id;
    }
}
