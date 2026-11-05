#!/usr/bin/env python3
"""Render a top-down footprint of the duel dimension.

Why this exists: the arena is one dimension carved into cells, and the failure mode when the layout
is wrong is visual -- every map stamped into the same cell, piled on top of each other. The server
log tells you where each map *claims* to be; only a picture tells you whether the maps are actually
apart.

    python scripts/arena-map.py [world_dir] [out.png]

`world_dir` defaults to fabric-1.21.1/run/world. The script reads
`dimensions/pvpocket/arena/region/*.mca`, decodes the paletted block states itself (anvil-parser2's
per-block accessor is far too slow for a whole dimension), and writes a 1-pixel-per-block PNG plus
an ASCII overview at 16 blocks per character.
"""

from __future__ import annotations

import glob
import os
import struct
import sys
import zlib

import numpy as np
import anvil

EMPTY = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def _val(tag):
    """Unwrap an nbt tag. anvil-parser2 hands back TAG_* wrappers, which do not compare or int()."""
    return getattr(tag, "value", tag)

# Coarse material buckets. Order matters: the specific names go before the generic ones, so
# "dark_oak_planks" is not caught by "stone" and "sandstone" is not caught by "sand".
CATEGORIES = [
    ("water", (58, 110, 190)),
    ("lava", (222, 120, 40)),
    ("leaves", (74, 140, 70)),
    ("grass_block", (118, 172, 92)),
    ("moss", (96, 140, 84)),
    ("log", (122, 92, 56)),
    ("planks", (172, 132, 82)),
    ("sandstone", (216, 200, 150)),
    ("sand", (222, 206, 152)),
    ("gravel", (150, 143, 138)),
    ("deepslate", (72, 72, 78)),
    ("blackstone", (48, 42, 46)),
    ("basalt", (86, 86, 92)),
    ("netherrack", (122, 52, 52)),
    ("quartz", (232, 226, 214)),
    ("snow", (240, 245, 250)),
    ("ice", (170, 205, 235)),
    ("glass", (196, 220, 230)),
    ("terracotta", (170, 110, 88)),
    ("wool", (206, 186, 196)),
    ("cobble", (112, 112, 112)),
    ("stone", (132, 132, 132)),
    ("brick", (168, 108, 96)),
    ("iron", (198, 198, 204)),
    ("gold", (232, 200, 92)),
    ("copper", (200, 130, 100)),
]
DEFAULT_COLOR = (196, 190, 176)
BACKGROUND = (250, 250, 248)


def color_for(name):
    for needle, color in CATEGORIES:
        if needle in name:
            return color
    return DEFAULT_COLOR


def decode_section(section):
    """(palette, 4096-entry index array in y*256 + z*16 + x order) for one section.

    A palette of length 1 means the section is uniform and stores no data array at all -- the common
    case here, since everything above a build is plain air.
    """
    bs = section.get("block_states")
    if bs is None:
        return None, None
    palette = [_val(entry["Name"]) for entry in bs["palette"]]
    if len(palette) == 1:
        return palette, np.zeros(4096, dtype=np.int32)

    data = bs.get("data")
    if data is None:
        return palette, np.zeros(4096, dtype=np.int32)

    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    raw = np.asarray(_val(data), dtype=np.uint64)
    shifts = np.arange(per_long, dtype=np.uint64) * np.uint64(bits)
    mask = np.uint64((1 << bits) - 1)
    # Entries never straddle a long boundary since 1.16, so this is a plain shift-and-mask.
    indices = (raw[:, None] >> shifts[None, :]) & mask
    flat = indices.reshape(-1)[:4096].astype(np.int32)
    # A palette shorter than 2^bits leaves unused slots; clamp so indexing cannot overflow.
    np.clip(flat, 0, len(palette) - 1, out=flat)
    return palette, flat


def chunk_columns(chunk):
    """Top non-empty block per column: (16x16 array of y, 16x16 array of block name)."""
    top_y = np.full((16, 16), -1, dtype=np.int32)
    top_name = np.full((16, 16), "", dtype=object)

    sections = chunk.data.get("sections") or chunk.data.get("Sections")
    if not sections:
        return top_y, top_name

    row = np.arange(16)
    zz, xx = np.meshgrid(row, row, indexing="ij")

    # nbt tags do not compare, so the sort key has to go through _val.
    for section in sorted(sections, key=lambda s: _val(s.get("Y", 0)), reverse=True):
        palette, flat = decode_section(section)
        if palette is None:
            continue
        solid = np.array([name not in EMPTY for name in palette], dtype=bool)
        if not solid.any():
            continue

        grid = flat.reshape(16, 16, 16)  # y, z, x
        occupied = solid[grid]
        if not occupied.any():
            continue

        base = int(_val(section.get("Y", 0))) * 16
        # argmax over the reversed y axis is "how far down from the top of this section".
        flipped = occupied[::-1, :, :]
        has = flipped.any(axis=0)
        height = base + 15 - flipped.argmax(axis=0)

        fill = (top_y < 0) & has
        if not fill.any():
            continue
        top_y[fill] = height[fill]
        local = np.clip(height - base, 0, 15)
        winner = np.asarray(palette, dtype=object)[grid[local, zz, xx]]
        top_name[fill] = winner[fill]

    return top_y, top_name


def build(world_dir):
    region_dir = os.path.join(world_dir, "dimensions", "pvpocket", "arena", "region")
    paths = sorted(glob.glob(os.path.join(region_dir, "*.mca")))
    if not paths:
        raise SystemExit(f"no region files under {region_dir}")

    chunks = []
    for path in paths:
        # r.<regionX>.<regionZ>.mca -- the region coordinates come from the file name, and the
        # header is a 32x32 table of (sector offset, sector count) with (0, 0) meaning "absent".
        stem = os.path.basename(path)[2:-4]
        region_x, region_z = (int(part) for part in stem.split("."))
        region = anvil.Region.from_file(path)
        for local_z in range(32):
            for local_x in range(32):
                chunk_x = region_x * 32 + local_x
                chunk_z = region_z * 32 + local_z
                if region.chunk_location(chunk_x, chunk_z) == (0, 0):
                    continue
                chunks.append(region.get_chunk(chunk_x, chunk_z))
    if not chunks:
        raise SystemExit("region files contain no chunks")

    xs = [c.x * 16 for c in chunks]
    zs = [c.z * 16 for c in chunks]
    min_x, max_x = min(xs), max(xs) + 16
    min_z, max_z = min(zs), max(zs) + 16

    height = np.full((max_z - min_z, max_x - min_x), -1, dtype=np.int32)
    names = np.full((max_z - min_z, max_x - min_x), "", dtype="U64")

    for chunk in chunks:
        top_y, top_name = chunk_columns(chunk)
        ox = chunk.x * 16 - min_x
        oz = chunk.z * 16 - min_z
        height[oz:oz + 16, ox:ox + 16] = top_y
        names[oz:oz + 16, ox:ox + 16] = top_name

    return height, names, (min_x, min_z)


def colorize(height, names):
    rgb = np.zeros((*height.shape, 3), dtype=np.uint8)
    rgb[:, :] = BACKGROUND

    solid = height >= 0
    if not solid.any():
        return rgb

    lo, hi = int(height[solid].min()), int(height[solid].max())
    span = max(1, hi - lo)
    # Base colour by material, brightness by altitude: a flat colour map hides the terraces and
    # stands that make a build readable from above.
    shades = (0.55 + 0.45 * (height.astype(np.float32) - lo) / span)[..., None]

    # One lookup per distinct block name instead of one per pixel: a dimension is ~1M columns and
    # only a few hundred distinct blocks.
    unique, inverse = np.unique(names, return_inverse=True)
    lut = np.array([color_for(name) for name in unique], dtype=np.float32)
    base = lut[inverse].reshape(*names.shape, 3)

    rgb[solid] = np.clip(base[solid] * shades[solid], 0, 255).astype(np.uint8)
    return rgb


def write_png(path, rgb):
    """Minimal truecolour PNG writer; Pillow is not a project dependency."""
    h, w, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[y].tobytes() for y in range(h))

    def chunk(tag, payload):
        body = tag + payload
        return (struct.pack(">I", len(payload)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 6))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as handle:
        handle.write(png)


def overview(height, origin, step=16):
    """ASCII occupancy map, one character per `step` blocks. '#' has blocks, '.' is bare air."""
    h, w = height.shape
    rows = [f"z\\x  {origin[0]:<6}" + "".join("|" if c % 8 == 0 else " "
                                              for c in range(0, w // step))]
    for r in range(0, h // step):
        band = height[r * step:(r + 1) * step]
        cells = []
        for c in range(0, w // step):
            block = band[:, c * step:(c + 1) * step]
            cells.append("#" if (block >= 4).any() else ".")
        rows.append(f"{origin[1] + r * step:>5}" + "".join(cells))
    return "\n".join(rows)


def main():
    world_dir = sys.argv[1] if len(sys.argv) > 1 else "fabric-1.21.1/run/world"
    out = sys.argv[2] if len(sys.argv) > 2 else "arena-map.png"

    height, names, origin = build(world_dir)
    solid = height >= 0
    print(f"region bbox: x {origin[0]}..{origin[0] + height.shape[1] - 1}, "
          f"z {origin[1]}..{origin[1] + height.shape[0] - 1}")
    print(f"columns with blocks: {int(solid.sum())}")
    if solid.any():
        ys = height[solid]
        print(f"height range: {int(ys.min())} .. {int(ys.max())}")

    write_png(out, colorize(height, names))
    print(f"wrote {out} ({height.shape[1]}x{height.shape[0]})")
    print()
    print(overview(height, origin))


if __name__ == "__main__":
    main()
