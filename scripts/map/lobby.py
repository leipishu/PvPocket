#!/usr/bin/env python3
"""Generates the waiting-lobby island, the one build in the duel dimension nobody fights on.

    python scripts/map/lobby.py --out data/pvpocket/structure/lobby.nbt

The lobby is where the result page is read from: the session teleports everybody onto this island
while the countdown runs, and the plot behind them is already being stamped. It is therefore built
to be looked at and not to be fought on - an island, a paved plaza with a lamp, four gates, trees
and lamps along the rim.

Shape (local y, after normalisation): 0..7 the underside cone, 8..9 subsoil, 10 the island surface,
11 the central dais, 12..14 its pillar, 15 the lantern on top. The four arches stand on the surface
at the compass points and reach the same height.

Local coordinates are the template's own; `arena.Lobby` decides where in the world they land.
"""
import argparse
import gzip
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import world2struct as W  # noqa: E402

# Island radius. 27 gives a 55-wide disc: "medium" against the plots, which run up to 224.
R = 27

# Top block of the island, in build coordinates. Normalisation shifts everything by -BOTTOM.
SURFACE = 12
BOTTOM = 2

# Half-width of the central dais, and the radius the four gates stand on.
DAIS = 5
ARCH_R = 21
LAMP_R = 24

# Trees sit between the paths and the lamps, so they frame the plaza instead of hiding it.
TREES = (30, 60, 120, 150, 210, 240, 300, 330)
TREE_R = 18

# Eight lamps, offset half a step from the trees so nothing overlaps.
LAMPS = (22.5, 67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5)

# The cross path: a three-wide run of stone bricks along both axes, ending under the gates.
PATH_HALF = 1


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


def surface_material(x, z, d):
    """One layer of ground: paved where people walk, grass everywhere else."""
    if d <= DAIS:
        # Under the dais: the plaza floor, so the rim of the dais is not floating over grass.
        return "minecraft:smooth_quartz"
    if on_path(x, z):
        return "minecraft:stone_bricks"
    if d >= R - 1:
        # The outermost ring is paved, so the island edge reads as a kerb rather than as a cut.
        return "minecraft:stone_bricks"
    if value_noise(x, z, 6, 1) < 0.16:
        return "minecraft:coarse_dirt"
    if value_noise(x, z, 5, 2) < 0.10:
        return "minecraft:stone"
    return "minecraft:grass_block"


def underside_material(y, x, z):
    """Stone bands by depth, so the cone reads as strata rather than as one grey mass."""
    depth = SURFACE - y
    if depth <= 2:
        return "minecraft:dirt"
    if depth <= 5:
        return "minecraft:stone" if value_noise(x, z, 3, y) > 0.35 else "minecraft:andesite"
    if depth <= 8:
        return "minecraft:tuff" if value_noise(x, z, 3, y) > 0.30 else "minecraft:stone"
    return "minecraft:deepslate" if value_noise(x, z, 3, y) > 0.25 else "minecraft:tuff"


def build():
    blocks = {}
    for x in range(-R - 1, R + 2):
        for z in range(-R - 1, R + 2):
            d = math.hypot(x, z)
            if d > R:
                continue
            put(blocks, x, SURFACE, z, surface_material(x, z, d))
            put(blocks, x, SURFACE - 1, z, "minecraft:dirt")
            put(blocks, x, SURFACE - 2, z, "minecraft:dirt")
            # Underside: a cone that starts one block inside the rim and tapers to a point, so the
            # island has a silhouette from below instead of a flat cut.
            for y in range(BOTTOM, SURFACE - 2):
                rr = R * (y - BOTTOM + 1) / float(SURFACE - 2 - BOTTOM + 1)
                if d <= rr:
                    put(blocks, x, y, z, underside_material(y, x, z))
    plaza(blocks)
    gates(blocks)
    for angle in TREES:
        tree(blocks, angle)
    for angle in LAMPS:
        lamp(blocks, angle)
    return blocks


def plaza(blocks):
    """The central dais and its lamp: the one thing every guest is looking at."""
    for x in range(-DAIS, DAIS + 1):
        for z in range(-DAIS, DAIS + 1):
            if math.hypot(x, z) > DAIS + 0.5:
                continue
            # A paved ring around a plainer centre, so the dais has an edge from eye height.
            border = math.hypot(x, z) > DAIS - 0.5
            put(blocks, x, SURFACE + 1, z,
                "minecraft:chiseled_quartz_block" if border else "minecraft:smooth_quartz")
    for x, z in ((-3, -3), (-3, 3), (3, -3), (3, 3)):
        put(blocks, x, SURFACE + 2, z, "minecraft:lantern", hanging="false")
    put(blocks, 0, SURFACE + 2, 0, "minecraft:quartz_pillar", axis="y")
    put(blocks, 0, SURFACE + 3, 0, "minecraft:quartz_pillar", axis="y")
    put(blocks, 0, SURFACE + 4, 0, "minecraft:sea_lantern")


def gates(blocks):
    """Four arches on the compass points, standing on the ends of the cross path."""
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        cx, cz = dx * ARCH_R, dz * ARCH_R
        # Tangent to the radius, so the opening faces the plaza.
        tx, tz = -dz, dx
        for side in (-2, 2):
            px, pz = cx + tx * side, cz + tz * side
            put(blocks, px, SURFACE + 1, pz, "minecraft:chiseled_stone_bricks")
            for y in range(SURFACE + 2, SURFACE + 5):
                put(blocks, px, y, pz, "minecraft:stone_bricks")
        for side in range(-2, 3):
            px, pz = cx + tx * side, cz + tz * side
            name = ("minecraft:chiseled_stone_bricks" if abs(side) == 2
                    else "minecraft:stone_bricks")
            put(blocks, px, SURFACE + 5, pz, name)
        put(blocks, cx, SURFACE + 4, cz, "minecraft:lantern", hanging="true")


def tree(blocks, angle):
    """A small oak: a trunk of four and a clipped leaf blob, so the island has a canopy."""
    rad = math.radians(angle)
    cx = int(round(math.cos(rad) * TREE_R))
    cz = int(round(math.sin(rad) * TREE_R))
    height = 4 + int(noise(cx, cz, 7) * 2)
    for y in range(SURFACE + 1, SURFACE + 1 + height):
        put(blocks, cx, y, cz, "minecraft:oak_log", axis="y")
    top = SURFACE + height
    for dy in (0, 1):
        radius = 2 if dy == 0 else 1
        for dx in range(-radius, radius + 1):
            for dz in range(-radius, radius + 1):
                if abs(dx) == radius and abs(dz) == radius:
                    continue   # clipped corners, so the canopy is round rather than a cube
                if dx == 0 and dz == 0 and dy == 0:
                    continue   # the trunk is already here
                put(blocks, cx + dx, top + dy, cz + dz, "minecraft:oak_leaves",
                    persistent="true", distance="7")
    put(blocks, cx, top + 2, cz, "minecraft:oak_leaves", persistent="true", distance="7")


def lamp(blocks, angle):
    """A fence post with a lantern on top, one ring inside the rim."""
    rad = math.radians(angle)
    cx = int(round(math.cos(rad) * LAMP_R))
    cz = int(round(math.sin(rad) * LAMP_R))
    if abs(cx) <= PATH_HALF or abs(cz) <= PATH_HALF:
        return   # never stand a lamp in the middle of a path
    put(blocks, cx, SURFACE + 1, cz, "minecraft:oak_fence")
    put(blocks, cx, SURFACE + 2, cz, "minecraft:oak_fence")
    put(blocks, cx, SURFACE + 3, cz, "minecraft:lantern", hanging="false")


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
