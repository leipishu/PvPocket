#!/usr/bin/env python3
"""Generates the waiting-lobby island, the one build in the duel dimension nobody fights on.

    python scripts/map/lobby.py --out data/pvpocket/structure/lobby.nbt

The lobby is where the result page is read from: the session teleports everybody onto this island
while the countdown runs, and the plot behind them is already being stamped. Everything here is
sized for looking rather than for fighting - a paved plaza with a sunken fountain, four gates on
the compass points that open onto the rim, trees and lamp posts between them, and a ragged rock
underside so the island reads as a floating rock and not as a cut disc.

Shape, in build coordinates: SURFACE is the ground layer, SURFACE-1 and -2 are subsoil, and the
rock runs from SURFACE-3 down to BOTTOM with a few spurs hanging below that. The plaza floor is
flush with the ground; only the fountain basin is sunk one block into it, so nothing a guest walks
across on the way to the edge is a step they have to jump up.

Local coordinates are the template's own; `arena.Lobby` decides where in the world they land.
"""
import argparse
import gzip
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import world2struct as W  # noqa: E402

# Nominal island radius: 27 is "medium" against the plots, which run up to 224. The rim is pulled
# in by up to 2.2, so the template comes out 53 wide rather than 55.
R = 27

# Top block of the island, in build coordinates. Normalisation shifts the whole build so its
# lowest block sits at y=0, which is why the surface is not at SURFACE in the written file.
SURFACE = 12

# Lowest tip of the rock. Everything the island hangs from is measured off this.
BOTTOM = -2

# Radii, all from the centre of the plaza. The rings are what the plan is made of: water, the rim
# around it, the paved floor, the trees, the gates, the lamps, and the ragged edge last.
POOL = 4.6       # the water surface
BASIN = 5.9      # outer edge of the rim around the water
PLAZA = 9.0      # paved floor
KERB = 8.2       # where the paved floor gives way to the stone edge of the plaza
ARCH_R = 20      # the four gates
TREE_R = 14      # trees, between the plaza and the gates
LAMP_R = 23.5    # the lamp ring, a step inside the rim
BENCH_R = 10.5   # seats on the lawn, facing the fountain

# The cross path is three wide and runs from the fountain to the rim, so each gate opens onto a
# view of the plot being stamped below.
PATH_HALF = 1

# Trees come in pairs either side of each diagonal, so the canopy frames the plaza.
TREES = (30, 60, 120, 150, 210, 240, 300, 330)

# Eight lamps, offset half a step from the trees so nothing overlaps.
LAMPS = (22.5, 67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5)

BENCHES = (45, 135, 225, 315)

# Boulders sit off the axes, where there is grass and no path to block.
BOULDERS = (75, 165, 255, 345)
BOULDER_R = 17.5

# Rock hanging off the underside, at bearings chosen so the spurs are not evenly spaced.
SPURS = (48, 122, 203, 291, 336)


def noise(x, z, salt=0):
    """Deterministic per-cell value in [0, 1), so the ground is mottled without a random seed."""
    h = (x * 73856093) ^ (z * 19349663) ^ (salt * 83492791)
    h &= 0xFFFFFFFF
    h ^= h >> 13
    h = (h * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65536.0


def value_noise(x, z, cell, salt=0):
    """Bilinear value noise in [0, 1): patches with ragged edges rather than a lattice of squares."""
    gx, gz = x / float(cell), z / float(cell)
    x0, z0 = math.floor(gx), math.floor(gz)
    fx, fz = gx - x0, gz - z0
    fx = fx * fx * (3.0 - 2.0 * fx)
    fz = fz * fz * (3.0 - 2.0 * fz)
    a = noise(x0, z0, salt)
    b = noise(x0 + 1, z0, salt)
    c = noise(x0, z0 + 1, salt)
    d = noise(x0 + 1, z0 + 1, salt)
    return (a + (b - a) * fx) * (1.0 - fz) + (c + (d - c) * fx) * fz


def put(blocks, x, y, z, name, **props):
    if name is None:
        blocks.pop((x, y, z), None)
    else:
        blocks[(x, y, z)] = (name, {k: str(v).lower() for k, v in props.items()})


def on_path(x, z):
    return abs(x) <= PATH_HALF or abs(z) <= PATH_HALF


def edge(x, z):
    """Radius of the island at this bearing.

    The noise is sampled on absolute coordinates, so the rim is mirrored in both axes and the
    template stays centred on 0,0. Without that the disc would wobble off-centre and the island
    would land a block or two away from the spawn point that is written down for it.
    """
    return R - 2.2 * value_noise(abs(x), abs(z), 11, 5)


def surface_material(x, z, d, e):
    """One layer of ground: paved where people walk, grass and rock everywhere else."""
    if d <= BASIN:
        # The floor the rim stands on. Hidden from above; only its inner face shows, from the water.
        return "minecraft:smooth_stone"
    if d <= PLAZA:
        # A grey floor with a kerb of the same stone the paths are made of, so a path runs into
        # the plaza instead of stopping at it. The fountain is the only white thing at the centre.
        return ("minecraft:chiseled_stone_bricks" if d > KERB
                else "minecraft:smooth_stone")
    if on_path(x, z):
        if value_noise(x, z, 4, 4) < 0.14:
            return "minecraft:cracked_stone_bricks"
        return "minecraft:stone_bricks"
    if d >= e - 2.2:
        # The rim is paved and mossy, so the drop reads as a kerb rather than as a cut.
        return ("minecraft:mossy_stone_bricks" if value_noise(x, z, 3, 6) < 0.30
                else "minecraft:stone_bricks")
    if value_noise(x, z, 5, 1) < 0.13:
        return "minecraft:moss_block"
    if value_noise(x, z, 4, 2) < 0.12:
        return "minecraft:coarse_dirt"
    if value_noise(x, z, 4, 3) < 0.07:
        return "minecraft:stone"
    return "minecraft:grass_block"


def underside_material(y, x, z):
    """Stone bands by depth, so the rock reads as strata rather than as one grey mass."""
    depth = SURFACE - y
    if depth <= 2:
        return "minecraft:dirt"
    if depth <= 5:
        return "minecraft:stone" if value_noise(x, z, 3, y) > 0.35 else "minecraft:andesite"
    if depth <= 9:
        return "minecraft:tuff" if value_noise(x, z, 3, y) > 0.30 else "minecraft:stone"
    if depth <= 14:
        return "minecraft:deepslate" if value_noise(x, z, 3, y) > 0.25 else "minecraft:tuff"
    return ("minecraft:cobbled_deepslate" if value_noise(x, z, 2, y) > 0.45
            else "minecraft:deepslate")


def cone_radius(x, z, y, e):
    """How wide the rock is at depth y.

    The exponent keeps the flank wide just under the grass and then drops away fast, which is the
    shape that reads as a rock rather than as an inverted cone; the noise makes the face uneven,
    and the inset from `e` leaves the grassy rim overhanging by a block or two.
    """
    span = float(SURFACE - 2 - BOTTOM)
    taper = (y - BOTTOM + 1) / span
    reach = (e - 2.0) * taper ** 1.6
    return reach + 2.0 * value_noise(x, z, 5, y) - 1.0


def build():
    blocks = {}
    for x in range(-R - 1, R + 2):
        for z in range(-R - 1, R + 2):
            d = math.hypot(x, z)
            e = edge(x, z)
            if d > e:
                continue
            if d <= POOL:
                # A basin sunk one block into the plaza, water flush at ground level. The solid
                # ring around it holds the water in, and the stamp sends no neighbour updates.
                put(blocks, x, SURFACE, z, "minecraft:water")
                put(blocks, x, SURFACE - 1, z, "minecraft:smooth_quartz")
                put(blocks, x, SURFACE - 2, z, "minecraft:smooth_quartz")
            else:
                put(blocks, x, SURFACE, z, surface_material(x, z, d, e))
                put(blocks, x, SURFACE - 1, z, "minecraft:dirt")
                put(blocks, x, SURFACE - 2, z, "minecraft:dirt")
            for y in range(BOTTOM, SURFACE - 2):
                if d <= cone_radius(x, z, y, e):
                    put(blocks, x, y, z, underside_material(y, x, z))
    plaza(blocks)
    gates(blocks)
    for angle in BENCHES:
        bench(blocks, angle)
    for angle in TREES:
        tree(blocks, angle)
    for angle in LAMPS:
        lamp(blocks, angle)
    for angle in BOULDERS:
        boulder(blocks, angle)
    spurs(blocks)
    scatter(blocks)
    return blocks


def plaza(blocks):
    """The fountain: a raised quartz rim around a sunken pool, with a lantern standing in it."""
    for x in range(-int(BASIN) - 1, int(BASIN) + 2):
        for z in range(-int(BASIN) - 1, int(BASIN) + 2):
            if POOL < math.hypot(x, z) <= BASIN:
                put(blocks, x, SURFACE + 1, z, "minecraft:chiseled_quartz_block")
    put(blocks, 0, SURFACE - 1, 0, "minecraft:chiseled_quartz_block")
    for y in range(SURFACE, SURFACE + 3):
        put(blocks, 0, y, 0, "minecraft:quartz_pillar", axis="y")
    put(blocks, 0, SURFACE + 3, 0, "minecraft:sea_lantern")


def bench(blocks, angle):
    """A slab seat on a diagonal, with a lamp post behind it, both facing the fountain.

    Stone, not quartz: the fountain is the only white thing on the island, so a white seat on the
    lawn reads as litter rather than as furniture.
    """
    rad = math.radians(angle)
    cx, cz = math.cos(rad) * BENCH_R, math.sin(rad) * BENCH_R
    tx, tz = -math.sin(rad), math.cos(rad)
    for step in (-1, 0, 1):
        put(blocks, int(round(cx + tx * step)), SURFACE + 1, int(round(cz + tz * step)),
            "minecraft:smooth_stone_slab", type="bottom")
    lx = int(round(cx + math.cos(rad) * 1.6))
    lz = int(round(cz + math.sin(rad) * 1.6))
    put(blocks, lx, SURFACE + 1, lz, "minecraft:stone_brick_wall")
    put(blocks, lx, SURFACE + 2, lz, "minecraft:stone_brick_wall")
    put(blocks, lx, SURFACE + 3, lz, "minecraft:lantern", hanging="false")


def gates(blocks):
    """Four arches on the compass points, where the cross path reaches the rim."""
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        cx, cz = dx * ARCH_R, dz * ARCH_R
        # Tangent to the radius, so the opening faces the plaza.
        tx, tz = -dz, dx
        for side in (-2, 2):
            px, pz = cx + tx * side, cz + tz * side
            for y in range(SURFACE + 1, SURFACE + 4):
                put(blocks, px, y, pz, "minecraft:stone_bricks")
            put(blocks, px, SURFACE + 4, pz, "minecraft:chiseled_stone_bricks")
            put(blocks, px, SURFACE + 6, pz, "minecraft:stone_brick_wall")
        for side in range(-2, 3):
            px, pz = cx + tx * side, cz + tz * side
            name = ("minecraft:chiseled_stone_bricks" if side == 0
                    else "minecraft:stone_bricks")
            put(blocks, px, SURFACE + 5, pz, name)
        # Hung from the keystone, high enough that a guest walks under it without ducking.
        put(blocks, cx, SURFACE + 4, cz, "minecraft:lantern", hanging="true")


def tree(blocks, angle):
    """A small oak: a trunk of five or six and a clipped canopy, so the island has a skyline."""
    rad = math.radians(angle)
    cx = int(round(math.cos(rad) * TREE_R))
    cz = int(round(math.sin(rad) * TREE_R))
    height = 5 + (1 if noise(cx, cz, 7) > 0.55 else 0)
    for y in range(SURFACE + 1, SURFACE + height + 1):
        put(blocks, cx, y, cz, "minecraft:oak_log", axis="y")
    top = SURFACE + height
    for dy, radius in ((-1, 2), (0, 2), (1, 1)):
        for dx in range(-radius, radius + 1):
            for dz in range(-radius, radius + 1):
                if dx == 0 and dz == 0:
                    continue   # the trunk is already here
                if radius > 1 and abs(dx) == radius and abs(dz) == radius:
                    if noise(cx + dx, cz + dz, dy + 3) < 0.6:
                        continue   # clipped corners, so the canopy is round rather than a cube
                put(blocks, cx + dx, top + dy, cz + dz, "minecraft:oak_leaves",
                    persistent="true", distance="7")
    put(blocks, cx, top + 2, cz, "minecraft:oak_leaves", persistent="true", distance="7")


def lamp(blocks, angle):
    """A stone post with a lantern on top, one ring inside the rim."""
    rad = math.radians(angle)
    cx = int(round(math.cos(rad) * LAMP_R))
    cz = int(round(math.sin(rad) * LAMP_R))
    if on_path(cx, cz):
        return   # never stand a lamp in the middle of a path
    put(blocks, cx, SURFACE + 1, cz, "minecraft:stone_brick_wall")
    put(blocks, cx, SURFACE + 2, cz, "minecraft:stone_brick_wall")
    put(blocks, cx, SURFACE + 3, cz, "minecraft:lantern", hanging="false")


def boulder(blocks, angle):
    """A low outcrop of stone in the field, so the ground has something to break it up."""
    rad = math.radians(angle)
    cx = math.cos(rad) * BOULDER_R
    cz = math.sin(rad) * BOULDER_R
    reach = 1.6 + noise(int(cx), int(cz), 17) * 1.2
    height = 1 + (1 if noise(int(cx), int(cz), 18) > 0.45 else 0)
    for dx in range(-3, 4):
        for dz in range(-3, 4):
            x, z = int(round(cx)) + dx, int(round(cz)) + dz
            if math.hypot(dx, dz) > reach or on_path(x, z):
                continue
            for step in range(height):
                roll = value_noise(x, z, 2, step)
                name = "minecraft:andesite" if roll > 0.5 else "minecraft:stone"
                if roll > 0.85:
                    name = "minecraft:cobblestone"
                put(blocks, x, SURFACE + 1 + step, z, name)


def spurs(blocks):
    """Rock hanging off the underside, so the silhouette is not one clean taper.

    A spur is anchored at the depth where the cone has already shrunk to the spur's inner edge,
    which is what makes it grow out of the rock instead of hanging under it unattached.
    """
    span = float(SURFACE - 2 - BOTTOM)
    for angle in SPURS:
        rad = math.radians(angle)
        r = R * (0.20 + 0.14 * noise(int(angle), 0, 13))
        cx, cz = math.cos(rad) * r, math.sin(rad) * r
        e = edge(int(round(cx)), int(round(cz)))
        width = 2.2 + noise(int(angle), 2, 15) * 1.4
        # Cone radius at depth y is (e - 2) * taper ** 1.6, so invert it for the anchor depth.
        share = max(0.02, (r - width + 0.5) / max(1.0, e - 2.0))
        attach = int(round(BOTTOM + share ** (1.0 / 1.6) * span - 1))
        depth = 2 + int(noise(int(angle), 1, 14) * 3)
        for step in range(depth + 1):
            y = attach - step
            if y > SURFACE - 3:
                continue
            reach = width * (1.0 - 0.7 * step / float(depth + 1))
            for dx in range(-3, 4):
                for dz in range(-3, 4):
                    if math.hypot(dx, dz) > reach:
                        continue
                    x, z = int(round(cx)) + dx, int(round(cz)) + dz
                    put(blocks, x, y, z, underside_material(y, x, z))


def scatter(blocks):
    """Tufts and the odd flower on the grass, so the field is not a flat green sheet."""
    for (x, y, z), (name, _) in list(blocks.items()):
        if y != SURFACE or name != "minecraft:grass_block":
            continue
        if (x, SURFACE + 1, z) in blocks:
            continue   # a trunk, a seat or a post is already standing here
        roll = noise(x, z, 21)
        if roll < 0.08:
            put(blocks, x, SURFACE + 1, z, "minecraft:short_grass")
        elif roll < 0.085:
            put(blocks, x, SURFACE + 1, z, "minecraft:oxeye_daisy")
        elif roll < 0.089:
            put(blocks, x, SURFACE + 1, z, "minecraft:azure_bluet")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True)
    parser.add_argument("--data-version", type=int, default=3955,
                        help="DataVersion to stamp (3955 = 1.21.1)")
    args = parser.parse_args(argv)

    blocks = build()
    # The same two guards the converter applies: nothing that falls, nothing that cannot be placed.
    bbox, blocks = W.normalise(blocks)
    size = (bbox[3] - bbox[0] + 1, bbox[4] - bbox[1] + 1, bbox[5] - bbox[2] + 1)
    surface_local = SURFACE - bbox[1]

    print("size      %d x %d x %d  (x, y, z)" % size)
    print("surface   local y=%d  (the block a guest stands on top of)" % surface_local)
    print("blocks    %d" % len(blocks))
    if size[0] > 160 or size[2] > 160 or size[1] > 64:
        raise SystemExit("larger than a plot cell (160 x 64 x 160)")
    if bbox[0] != -bbox[3] or bbox[2] != -bbox[5]:
        # `arena.Lobby` centres the template on the island's centre, so an off-centre build would
        # land a block or two away from the spawn point written down for it.
        raise SystemExit("island is not centred: bbox %s" % (bbox,))

    root = W.to_structure(bbox, blocks, args.data_version)
    raw = gzip.compress(W.write_nbt("", root), 9)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as handle:
        handle.write(raw)
    print("wrote     %s (%.1f KB, gzipped)" % (args.out, len(raw) / 1024.0))

    _, back = W.read_nbt(raw)
    back_size = tuple(back["size"][1][1])
    ok = (back_size == size and len(back["blocks"][1][1]) == len(blocks))
    print("verify    size=%s blocks=%d -> %s"
          % (back_size, len(back["blocks"][1][1]), "OK" if ok else "MISMATCH"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
