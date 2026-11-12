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
    public static final ArenaMap FLAT = new ArenaMap("flat", null, 0, 0, 0);

    public final String id;
    /**
     * Translation key naming the map, {@code pvpocket.map.<id>}.
     *
     * <p>Shown in the rule panel's cycle control, so the words have to stay short. The key is derived
     * from {@link #id} rather than stored as text because the id is what the rule set persists and
     * what the leased region reports — one identifier, one name.</p>
     */
    public final String labelKey;
    public final ResourceLocation templateId;
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

    /**
     * How far the <em>fightable</em> rectangle is inset from the plot box, per axis.
     *
     * <p>The plot box is the template's bounding box, and a bounding box is not a footprint. The
     * colosseum is 147x119 because that is the size of its outer wall and its ten rows of seating;
     * the sand the fight actually happens on is 100x80. Drawing the boundary on the box means the
     * curtain stands behind the top row of seats, which is both wrong to look at and wrong to
     * enforce: a player who climbs the seating has left the fight long before they reach the wall.</p>
     *
     * <p>Two insets rather than a full rectangle because every build here is centred in its box, so
     * a symmetric inset expresses the same thing with two numbers instead of six. Maps whose whole
     * box is playable leave both at 0 — which is the majority, and the reason this is opt-in rather
     * than a required field.</p>
     *
     * <p>Inset only shrinks the <em>boundary</em>. The plot box still governs block protection,
     * entity cleanup and the lease, so a player cannot break the stands by walking past the curtain.</p>
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
        // Clamped so a typo cannot invert the rectangle: the fight area has to stay at least 8 wide
        // on each axis, which is the same floor the rule panel puts on a flat plot's size.
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
     * <p>Entities are ignored and neighbour updates are off: a downloaded map can carry item frames,
     * armour stands and chests, and the mod's contract is that the arena contains blocks only. The
     * neighbour flag matters for the same reason — 72k block updates fanning out through the world
     * would take seconds and would also re-trigger the plot's own change tracking.</p>
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
