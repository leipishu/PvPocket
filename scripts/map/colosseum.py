#!/usr/bin/env python3
"""Generates the amphitheatre arena template, the one map here that is not third-party art.

    python scripts/map/colosseum.py --out data/pvpocket/structure/arena_colosseum.nbt

Shape (y): -1 sandstone sub-floor, 0 arena sand, 1..3 podium wall with a gate at each end of the
X axis, 4..13 ten seating rows, 13..27 outer wall with cornices and pilasters, 27 parapet walk.
"""
import argparse
import gzip
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import world2struct as W  # noqa: E402

# Semi-axes of the outer wall. The footprint is 2*these, plus one for the centre column.
A_OUT, B_OUT = 72, 58

# Semi-axes of the sand. Spawn pads sit a quarter of the plot width from the centre, so a sand
# that is too small puts both players against the podium wall.
A_ARENA = 50
E_ARENA = A_ARENA / A_OUT

# Radial thickness of the podium and outer walls, in e units. e is an ellipse metric, not a
# distance, so these are converted from the block thickness on the X axis.
PODIUM_E = 1.2 / A_OUT
WALL_E = 0.955

TIERS = 10
FIRST_ROW_Y = 4          # top surface of the lowest seating row
WALL_TOP = 27
CORNICES = (17, 21)      # y of the two projecting bands on the outer wall
PILASTERS = 48           # evenly spaced buttresses around the facade

GATE_HALF_WIDTH = 1      # gates are 3 blocks wide
GATE_HEIGHT = 3
GATE_DEPTH = 3


def radial(x, z, a=A_OUT, b=B_OUT):
    """Normalised elliptical radius: 1.0 is the outer wall, 0 is the centre."""
    return math.hypot(x / a, z / b)


def noise(x, z, salt=0):
    """Deterministic per-cell value in [0, 1), so the sand is mottled without a random seed."""
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


def floor_material(x, z):
    """Sand with patches of grit, so a 100-block circle does not read as a flat disc.

    One channel per material, not a ladder of thresholds on one channel: a ladder paints each
    material as a ring around the previous one.
    """
    if value_noise(x, z, 5, 1) < 0.20:
        return "minecraft:gravel"
    if value_noise(x, z, 4, 2) < 0.13:
        return "minecraft:coarse_dirt"
    return "minecraft:sand"


def gate_span(x, z):
    """True inside one of the two gate tunnels, which are cut on the X axis at both ends."""
    return abs(z) <= GATE_HALF_WIDTH and A_ARENA - 2 <= abs(x) <= A_ARENA + GATE_DEPTH


def put(blocks, x, y, z, name, **props):
    if name is None:
        blocks.pop((x, y, z), None)
    else:
        blocks[(x, y, z)] = (name, {k: str(v).lower() for k, v in props.items()})


def build():
    blocks = {}
    band = WALL_E - (E_ARENA + PODIUM_E)
    step = band / TIERS
    pilaster_step = 2.0 * math.pi / PILASTERS

    for x in range(-A_OUT - 2, A_OUT + 3):
        for z in range(-B_OUT - 2, B_OUT + 3):
            e = radial(x, z)
            # Projecting detail lives outside the wall proper, so it gets its own metric: an
            # offset added in e units would be a different number of blocks at every angle.
            e_out = radial(x, z, A_OUT + 1.5, B_OUT + 1.2)
            angle = math.atan2(z / B_OUT, x / A_OUT)

            if e <= E_ARENA:
                # The sub-floor goes down everywhere, gate included: skipping it under the gate
                # leaves a three-wide notch in the layer that stops the sand from falling.
                put(blocks, x, -1, z, "minecraft:sandstone")
                if not gate_span(x, z):
                    put(blocks, x, 0, z, floor_material(x, z))
                continue

            if e <= E_ARENA + PODIUM_E:
                if gate_span(x, z):
                    # The gate is a hole in the podium wall, not a ramp: floor, then air above it.
                    put(blocks, x, -1, z, "minecraft:sandstone")
                    put(blocks, x, 0, z, "minecraft:smooth_sandstone")
                    continue
                for y in range(-1, GATE_HEIGHT + 1):
                    put(blocks, x, y, z, "minecraft:smooth_sandstone")
                # Cap the podium with a darker course so the wall reads as a wall from the sand.
                put(blocks, x, GATE_HEIGHT + 1, z, "minecraft:chiseled_sandstone")
                continue

            if e <= WALL_E:
                row = int((e - E_ARENA - PODIUM_E) / step)
                row = min(row, TIERS - 1)
                top = FIRST_ROW_Y + row
                for y in range(-1, top + 1):
                    put(blocks, x, y, z, "minecraft:sandstone")
                # Alternating treads: a single colour makes the rows vanish into one slope.
                tread = "minecraft:smooth_sandstone" if row % 2 == 0 else "minecraft:cut_sandstone"
                put(blocks, x, top, z, tread)
                put(blocks, x, top - 1, z, "minecraft:chiseled_sandstone")
                continue

            if e <= 1.0:
                for y in range(-1, WALL_TOP + 1):
                    put(blocks, x, y, z, "minecraft:smooth_sandstone")
                for y in CORNICES:
                    put(blocks, x, y, z, "minecraft:chiseled_sandstone")
                # Base course: the facade sits on a darker plinth rather than on bare sand.
                for y in (0, 1):
                    put(blocks, x, y, z, "minecraft:stone_bricks")
                continue

            # Outside the wall: only the projecting details exist here.
            if e_out > 1.0:
                continue
            if min(abs(angle % pilaster_step),
                   abs(angle % pilaster_step - pilaster_step)) < pilaster_step * 0.12:
                for y in range(2, WALL_TOP + 1):
                    put(blocks, x, y, z, "minecraft:chiseled_sandstone")
                continue
            for y in CORNICES:
                put(blocks, x, y, z, "minecraft:chiseled_sandstone")
            put(blocks, x, WALL_TOP, z, "minecraft:smooth_sandstone")

    carve_gates(blocks)
    light_the_podium(blocks)
    return blocks


def carve_gates(blocks):
    """Opens the two gates and puts a barred back wall behind each one.

    The podium wall is filled solid first, so the tunnel is carved: a single pass would leave the
    arch lintel missing.
    """
    for sign in (-1, 1):
        for x in range(A_ARENA - 2, A_ARENA + GATE_DEPTH + 1):
            for z in range(-GATE_HALF_WIDTH, GATE_HALF_WIDTH + 1):
                sx = sign * x
                for y in range(1, GATE_HEIGHT + 1):
                    put(blocks, sx, y, z, None)
                if x == A_ARENA + GATE_DEPTH:
                    for y in range(1, GATE_HEIGHT + 1):
                        put(blocks, sx, y, z, "minecraft:iron_bars")
                put(blocks, sx, 0, z, "minecraft:smooth_sandstone")
                put(blocks, sx, GATE_HEIGHT + 1, z, "minecraft:chiseled_sandstone")


def light_the_podium(blocks):
    """Lanterns on the podium cap, so the sand is not lit only by the sky straight down.

    The cap is a one-block ring at an elliptical radius, so it has to be sampled by angle:
    rounding a point on an ellipse does not keep it on the ring.
    """
    cap_y = GATE_HEIGHT + 1
    ring = [(math.atan2(z, x), x, z) for (x, y, z), (name, _) in blocks.items()
            if y == cap_y and name == "minecraft:chiseled_sandstone"]
    if not ring:
        return
    ring.sort()
    step = max(1, len(ring) // 16)
    for _, x, z in ring[::step]:
        put(blocks, x, cap_y + 1, z, "minecraft:lantern", hanging="false")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True)
    parser.add_argument("--data-version", type=int, default=3955,
                        help="DataVersion to stamp (3955 = 1.21.1)")
    args = parser.parse_args(argv)

    blocks = build()
    # Same two guards the converter applies: nothing that falls, nothing that cannot be placed.
    # Run before the size check, because dropping a block on the outer edge shrinks the bbox.
    bbox, blocks = W.normalise(blocks)
    size = (bbox[3] - bbox[0] + 1, bbox[4] - bbox[1] + 1, bbox[5] - bbox[2] + 1)

    print("size      %d x %d x %d  (x, y, z)" % size)
    print("blocks    %d" % len(blocks))
    if size[0] > 160 or size[2] > 160 or size[1] > 64:
        raise SystemExit("larger than the rule panel's plot cap (160 x 64 x 160)")

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
