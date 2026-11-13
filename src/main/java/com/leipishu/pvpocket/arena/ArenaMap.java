package com.leipishu.pvpocket.arena;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.i18n.PvLang;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;

import java.util.Optional;

/**
 * One selectable arena map: a vanilla structure template plus the size it needs.
 *
 * <p>Maps are stored as ordinary structure templates under {@code data/pvpocket/structure/}, so
 * {@code /place template} works on them, and a file can also be produced with a structure block
 * for anything under 48x48x48.
 *
 * <p>{@link #sizeX} and friends are declared here rather than read from the template at runtime so
 * the rule panel can lay itself out without touching the server. {@link #paste} re-checks them
 * against the loaded template and logs a warning on a mismatch.
 */
public final class ArenaMap {

    /**
     * The generated flat plot: a platform of the requested size, floating in the void.
     *
     * <p>Has no template, because its size comes from the rules rather than from a file. Built by
     * {@code ArenaManager#buildPlatform}.
     */
    public static final ArenaMap FLAT = new ArenaMap("flat", null, 0, 0, 0);

    public final String id;
    /**
     * Translation key naming the map, {@code pvpocket.map.<id>}.
     *
     * <p>Shown in the rule panel's cycle control, so the text has to stay short. Derived from
     * {@link #id}.
     */
    public final String labelKey;
    public final ResourceLocation templateId;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;

    /**
     * True when the build's two sides are separated along Z rather than X.
     *
     * <p>{@link ArenaRegion#spawnX} puts the two players a quarter of the width either side of the
     * centre, so a map whose play axis is Z needs this flag to land them at opposite ends instead
     * of beside each other in the middle of the corridor. Two of the three shipped maps are built
     * this way: a 47x95 cavern and a 102x99 bridge whose source XML spawns players at {@code x=0}.
     */
    public final boolean alongZ;

    /**
     * How far the fightable rectangle is inset from the plot box, per axis.
     *
     * <p>The plot box is the template's bounding box, which can be larger than the ground the fight
     * happens on: the colosseum box is 147x119, from its outer wall and ten rows of seating, while
     * its sand is 100x80. Maps whose whole box is playable leave both at 0.
     *
     * <p>Inset only shrinks the boundary. The plot box still governs block protection, entity
     * cleanup and the lease.
     */
    public final int pitInsetX;
    public final int pitInsetZ;

    public ArenaMap(String id, ResourceLocation templateId, int sizeX, int sizeY, int sizeZ) {
        this(id, templateId, sizeX, sizeY, sizeZ, false, 0, 0);
    }

    public ArenaMap(String id, ResourceLocation templateId, int sizeX, int sizeY, int sizeZ,
                    boolean alongZ) {
        this(id, templateId, sizeX, sizeY, sizeZ, alongZ, 0, 0);
    }

    public ArenaMap(String id, ResourceLocation templateId, int sizeX, int sizeY, int sizeZ,
                    boolean alongZ, int pitInsetX, int pitInsetZ) {
        this.id = id;
        this.labelKey = "pvpocket.map." + id;
        this.templateId = templateId;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.alongZ = alongZ;
        // Clamped so a typo cannot invert the rectangle: the fight area stays at least 8 wide on
        // each axis, the same floor the rule panel puts on a flat plot's size.
        this.pitInsetX = Math.max(0, Math.min(pitInsetX, (sizeX - 8) / 2));
        this.pitInsetZ = Math.max(0, Math.min(pitInsetZ, (sizeZ - 8) / 2));
    }

    public MutableComponent labelText() {
        return PvLang.t(labelKey);
    }

    /** Display name for the self-drawn GUI and for chat lines composed as strings. */
    public String label() {
        return PvLang.str(labelKey);
    }

    /** True when the fight area is smaller than the plot box, i.e. the boundary is not the wall. */
    public boolean hasPit() {
        return pitInsetX > 0 || pitInsetZ > 0;
    }

    public boolean isFlat() {
        return templateId == null;
    }

    /**
     * Stamps the template onto a freshly cleared plot.
     *
     * <p>Entities are ignored and neighbour updates are off: the arena contract is blocks only, and
     * 72k block updates would be slow and would re-trigger the plot's change tracking.
     *
     * @return true when the template was placed
     */
    public boolean paste(ServerLevel world, ArenaRegion region) {
        if (isFlat()) {
            return false;
        }
        Optional<StructureTemplate> loaded =
                world.getServer().getStructureManager().get(templateId);
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
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(false)
                .setFinalizeEntities(false);
        long started = System.nanoTime();
        boolean placed = template.placeInWorld(world, origin, origin, settings,
                RandomSource.create(0L), Block.UPDATE_CLIENTS);
        PvPocket.LOGGER.info("Pasted {} at {},{} ({}) in {} ms", id, origin.getX(), origin.getZ(),
                placed ? "ok" : "no-op", (System.nanoTime() - started) / 1_000_000L);
        return placed;
    }

    @Override
    public String toString() {
        return id;
    }
}
