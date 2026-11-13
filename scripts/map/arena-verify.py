#!/usr/bin/env python3
"""Checks a converted arena template against an *independent* reading of its source.

The reference is always a third-party implementation of the source format, never the reader in
this repository:

    .litematic      -> litemapy        (independent Python implementation of Litematica)
    world save      -> anvil-parser2   (independent .mca reader)

`.schem` has no third-party reader wired up here, so that path is refused.

Two modes:

    # offline: compare the .nbt against the source, no server needed
    python scripts/map/arena-verify.py --source <world dir> --nbt <file.nbt>

    # in game: /place the template and probe it through RCON
    python scripts/map/arena-verify.py --source <world dir> --template arena_thistle

The in-game mode also proves the paste itself (palette resolution, rejected block states, gravity
blocks that fall), which the offline comparison cannot.
"""
import argparse
import glob
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402
import world2struct as W  # noqa: E402

import nbtlib  # noqa: E402

DIMENSION = "pvpocket:arena"


# --------------------------------------------------------------------- source readers

def read_source(path):
    """Independent reading of the source, as {(world_x, world_y, world_z): (name, props)}."""
    if os.path.isdir(path):
        return read_source_world(path)
    if path.lower().endswith(".litematic"):
        return read_source_litemapy(path)
    if path.lower().endswith(".schem"):
        raise SystemExit(
            "no independent reader for .schem is wired up, so this comparison would be "
            "self-referential. Use the in-game mode with a hand-checked sample instead.")
    raise SystemExit("unknown source type: %s" % path)


def read_source_litemapy(path):
    from litemapy import Schematic

    raw = open(path, "rb").read()
    nbt = nbtlib.File.load(path, gzipped=raw[:2] == b"\x1f\x8b")
    schem = Schematic.from_nbt(nbt)
    truth = {}
    for region in schem.regions.values():
        for x in _axis(region.x, region.width):
            for y in _axis(region.y, region.height):
                for z in _axis(region.z, region.length):
                    state = region[x - region.x, y - region.y, z - region.z]
                    if state.id not in W.AIRISH and state.id not in W.DEFAULT_STRIP:
                        truth[(x, y, z)] = (state.id, dict(state.properties()))
    return truth


def _axis(pos, size):
    """World coordinates a region covers on one axis; a negative size runs backwards from `pos`."""
    low = pos + (size + 1 if size < 0 else 0)
    high = pos + (0 if size < 0 else size - 1)
    return range(low, high + 1)


def chunk_sections(chunk):
    """Sections of a chunk as ``[(section_nbt, section_y), ...]``, for either save layout.

    Modern saves keep ``sections`` at the root of the chunk compound; 1.8 keeps ``Sections`` under
    ``Level``, which anvil-parser2 has already unwrapped into ``chunk.data``.
    """
    for key in ("sections", "Sections"):
        found = chunk.data.get(key)
        if found is None:
            continue
        out = []
        for section in found:
            raw = section.get("Y", 0)
            out.append((section, int(getattr(raw, "value", raw))))
        return out
    return []


def read_source_world(path):
    """Reads every chunk of a world save through anvil-parser2.

    Two block layouts have to be handled, because the source maps come from both eras:

    * 1.21: a per-section ``block_states`` palette. ``chunk.get_block`` wants a global Y.
    * 1.8: flat ``Blocks`` / ``Data`` / ``Add`` byte arrays and numeric ids. ``chunk.get_block``
      returns an ``OldBlock`` with no name, and wants a section-local Y when the section is passed
      explicitly. ``OldBlock.convert()`` is anvil-parser2's own flattening table.
    """
    import anvil

    truth = {}
    files = sorted(glob.glob(os.path.join(path, "region", "*.mca")))
    if not files:
        raise SystemExit("no region/*.mca under %s" % path)
    for path_mca in files:
        region = anvil.Region.from_file(path_mca)
        # `get_chunk` takes absolute chunk coordinates and masks them to a region slot, so walking
        # 0..31 per file covers all 32x32 slots of *that* file and `chunk.x/z` report the truth.
        for cz in range(32):
            for cx in range(32):
                try:
                    chunk = region.get_chunk(cx, cz)
                except Exception:
                    continue
                base_x, base_z = chunk.x * 16, chunk.z * 16
                for section, sy in chunk_sections(chunk):
                    legacy = "Palette" not in section and "block_states" not in section
                    for ly in range(16):
                        y = sy * 16 + ly
                        for lz in range(16):
                            for lx in range(16):
                                if legacy:
                                    old = chunk.get_block(lx, ly, lz, section=section)
                                    try:
                                        block = old.convert()
                                    except KeyError:
                                        # anvil-parser2's table has holes the converter's does
                                        # not; mirror the converter's fallback to metadata 0.
                                        block = anvil.OldBlock(old.id, 0).convert()
                                else:
                                    block = chunk.get_block(lx, y, lz)
                                name = block.name()
                                if name in W.AIRISH or name in W.DEFAULT_STRIP:
                                    continue
                                truth[(base_x + lx, y, base_z + lz)] = (
                                    name, dict(block.properties or {}))
    if not truth:
        raise SystemExit("read no blocks out of %s — the reader is wrong, not the map" % path)
    return truth


def read_template(path):
    """Reads a structure template as (size, {(x, y, z): (name, props)}) in template coordinates."""
    nbt = nbtlib.load(path)
    size = tuple(int(v) for v in nbt["size"])
    palette = []
    for entry in nbt["palette"]:
        name = str(entry["Name"])
        props = {str(k): str(v) for k, v in dict(entry.get("Properties", {})).items()}
        palette.append((name, props))
    blocks = {}
    for entry in nbt["blocks"]:
        pos = tuple(int(v) for v in entry["pos"])
        blocks[pos] = palette[int(entry["state"])]
    return size, blocks


# --------------------------------------------------------------------- comparison

def prop_text(value):
    """Renders a block-state value the way it is written in a palette.

    `OldBlock.convert()` puts real Python ``True``/``False`` into ``properties``, and ``str(True)``
    is ``"True"``, while a block state string is always lowercase.
    """
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def cross_check(source_path, nbt_path):
    """Compares the template against an independent reading of the source. Returns failure count."""
    truth = read_source(source_path)
    # The converter renames ids 1.21.1 does not have and drops blocks that cannot be placed.
    # Apply the same transform to the reference so the comparison is about reading, not tables.
    _, truth = W.normalise(dict(truth))
    # anvil-parser2 and nbtlib hand back their own tag types, which do not compare equal to the
    # plain strings the template reader produces; coerce both sides before comparing.
    truth = {pos: (str(name), {str(k): prop_text(v) for k, v in props.items()})
             for pos, (name, props) in truth.items()}

    size, blocks = read_template(nbt_path)

    xs = [p[0] for p in truth]
    ys = [p[1] for p in truth]
    zs = [p[2] for p in truth]
    origin = (min(xs), min(ys), min(zs))
    span = (max(xs) - min(xs) + 1, max(ys) - min(ys) + 1, max(zs) - min(zs) + 1)
    print("source    %s  %dx%dx%d  %d blocks (after strip/normalise)"
          % (os.path.basename(os.path.normpath(source_path)), span[0], span[1], span[2],
             len(truth)))
    print("template  %s  %dx%dx%d  %d blocks"
          % (os.path.basename(nbt_path), size[0], size[1], size[2], len(blocks)))

    if span != size:
        print("SIZE MISMATCH  source %s vs template %s" % (span, size))
        return 1

    # Rebase the reference onto the template's own coordinate system.
    rebased = {(x - origin[0], y - origin[1], z - origin[2]): (name, props)
               for (x, y, z), (name, props) in truth.items()}

    wrong = extra = missing = 0
    for pos, got in blocks.items():
        want = rebased.get(pos)
        if want is None:
            extra += 1
            if extra <= 10:
                print("  EXTRA   %s is %s, source has air" % (pos, _fmt(got)))
        elif want != got:
            wrong += 1
            if wrong <= 10:
                print("  WRONG   %s is %s, source says %s" % (pos, _fmt(got), _fmt(want)))
    for pos, want in rebased.items():
        if pos not in blocks:
            missing += 1
            if missing <= 10:
                print("  MISSING %s should be %s" % (pos, _fmt(want)))

    print("result    wrong=%d missing=%d extra=%d" % (wrong, missing, extra))
    return 0 if wrong == 0 and missing == 0 and extra == 0 else 1


def _fmt(state):
    """`name[k=v,...]`, with the properties sorted so two runs diff cleanly."""
    name, props = state
    if not props:
        return name
    inner = ",".join("%s=%s" % (k, props[k]) for k in sorted(props))
    return "%s[%s]" % (name, inner)


# --------------------------------------------------------------------- in-game mode

def in_game(source_path, template, origin, samples, seed, no_place):
    truth = read_source(source_path)
    _, truth = W.normalise(dict(truth))
    truth = {pos: name for pos, (name, _) in truth.items()}

    ox, oy, oz = origin
    xs = [p[0] for p in truth]
    ys = [p[1] for p in truth]
    zs = [p[2] for p in truth]
    size = (max(xs) - min(xs) + 1, max(ys) - min(ys) + 1, max(zs) - min(zs) + 1)
    print("source    %s  %dx%dx%d  %d blocks (after strip/normalise)"
          % (os.path.basename(os.path.normpath(source_path)), size[0], size[1], size[2],
             len(truth)))

    # A world save is built wherever the author felt like it, so its bounding box rarely starts at
    # 0; the converter rebases the template. Sampling has to happen in template coordinates.
    low = (min(xs), min(ys), min(zs))
    truth = {(x - low[0], y - low[1], z - low[2]): name for (x, y, z), name in truth.items()}
    xs = [p[0] for p in truth]
    ys = [p[1] for p in truth]
    zs = [p[2] for p in truth]

    sock = rcon.connect("127.0.0.1", 25575, "pvpocket")
    try:
        if not no_place:
            rcon.command(sock, "execute in %s run forceload add %d %d %d %d"
                         % (DIMENSION, ox - 16, oz - 16, ox + size[0] + 16, oz + size[2] + 16),
                         linger=2.0)
            # One /fill per layer: /fill refuses more than 32768 blocks and the refusal is silent
            # unless the reply is checked.
            for dy in range(size[1] + 1):
                reply = rcon.command(sock, "execute in %s run fill %d %d %d %d %d %d air"
                                     % (DIMENSION, ox, oy + dy, oz, ox + size[0] - 1, oy + dy,
                                        oz + size[2] - 1), linger=0.3)
                if "Too many" in reply or "not loaded" in reply:
                    raise SystemExit("clear failed at y=%d: %s" % (oy + dy, reply.strip()))
            out = rcon.command(sock, "execute in %s run place template pvpocket:%s %d %d %d"
                               % (DIMENSION, template, ox, oy, oz), linger=3.0)
            print("place     %s" % (out.strip() or "(no output)"))

        rng = random.Random(seed)
        solid = list(truth.items())
        rng.shuffle(solid)
        picks = solid[:samples]
        # Air samples are what catch a converter that shifts everything: a wrong offset would leave
        # the build present but displaced, and every "should be air" probe would then fail.
        air = []
        while len(air) < samples // 2:
            pos = (rng.randrange(min(xs), max(xs) + 1), rng.randrange(min(ys), max(ys) + 1),
                   rng.randrange(min(zs), max(zs) + 1))
            if pos not in truth:
                air.append((pos, "minecraft:air"))

        bad = 0
        for (sx, sy, sz), block in picks + air:
            x, y, z = ox + sx, oy + sy, oz + sz
            reply = rcon.command(sock, "execute in %s run execute if block %d %d %d %s"
                                 % (DIMENSION, x, y, z, block), linger=0.15)
            ok = "Test passed" in reply
            bad += 0 if ok else 1
            print("  %s src %-12s world %-14s %s" % ("ok  " if ok else "FAIL",
                                                     "%d,%d,%d" % (sx, sy, sz),
                                                     "%d,%d,%d" % (x, y, z),
                                                     block.replace("minecraft:", "")))
        total = len(picks) + len(air)
        print("result    %d/%d passed" % (total - bad, total))
        return 0 if bad == 0 else 1
    finally:
        sock.close()


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True,
                        help="the world save / .litematic the template came from")
    parser.add_argument("--nbt", help="offline mode: compare this template file")
    parser.add_argument("--template", help="in-game mode: template id, i.e. arena_thistle")
    parser.add_argument("--origin", nargs=3, type=int, default=[2000, 3, 2000])
    parser.add_argument("--samples", type=int, default=40)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--no-place", action="store_true",
                        help="the template is already there (a leased plot); only sample")
    args = parser.parse_args(argv)

    if args.nbt:
        return cross_check(args.source, args.nbt)
    if not args.template:
        parser.error("give either --nbt (offline) or --template (in game)")
    return in_game(args.source, args.template, args.origin, args.samples, args.seed,
                   args.no_place)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
