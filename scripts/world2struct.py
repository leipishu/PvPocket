#!/usr/bin/env python3
"""Turn a downloaded Minecraft world save into a vanilla structure template.

Why this exists: the arena presets we want (colosseum builds) are published as *world saves* — a zip
containing `region/*.mca`. PvPocket needs a *structure template* (`data/pvpocket/structure/<id>.nbt`)
that it can paste onto a plot. The obvious conversion route is to open the world in a client and
re-save it, but vanilla's structure block is capped at 48x48x48, so anything larger than that cannot
be captured that way at all. Reading the region files directly has no such limit, and it means the
only thing a human has to do is download the zip.

Two subcommands:

    python scripts/world2struct.py probe <world> [--box x1 y1 z1 x2 y2 z2]
        Reports the bounding box of the non-air blocks, how many blocks that is, the palette size
        and the height histogram. Run this first: it is how we find out whether a build fits a plot
        and where its floor is.

    python scripts/world2struct.py convert <world> --out <file.nbt> [options]
        Writes a structure template. Prints the same report plus the output size.

`<world>` is either a directory that contains `region/`, or the downloaded `.zip` itself.

The output is deliberately *only* blocks: no entities, and no block-entity NBT unless asked. A
downloaded arena can contain chests, hoppers, command blocks and item frames, and every one of those
is either a free-item exploit or a way to run commands inside a duel.
"""
import argparse
import gzip
import io
import json
import os
import struct
import sys
import zipfile
import zlib

# ---------------------------------------------------------------------------- NBT

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

# Values are stored as (tag_type, payload). Compounds are dict[str] -> (type, payload); lists are
# (elem_type, [payload, ...]). Keeping the type alongside every value is what lets the writer emit a
# faithful copy of anything it read, which is what the round-trip test needs.


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def take(self, n):
        chunk = self.data[self.pos:self.pos + n]
        if len(chunk) != n:
            raise ValueError("truncated NBT at offset %d" % self.pos)
        self.pos += n
        return chunk

    def u1(self):
        return self.take(1)[0]

    def i1(self):
        return struct.unpack(">b", self.take(1))[0]

    def i2(self):
        return struct.unpack(">h", self.take(2))[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def i4(self):
        return struct.unpack(">i", self.take(4))[0]

    def i8(self):
        return struct.unpack(">q", self.take(8))[0]

    def f4(self):
        return struct.unpack(">f", self.take(4))[0]

    def f8(self):
        return struct.unpack(">d", self.take(8))[0]

    def string(self):
        return self.take(self.u2()).decode("utf-8", "replace")

    def payload(self, tag):
        if tag == TAG_BYTE:
            return self.i1()
        if tag == TAG_SHORT:
            return self.i2()
        if tag == TAG_INT:
            return self.i4()
        if tag == TAG_LONG:
            return self.i8()
        if tag == TAG_FLOAT:
            return self.f4()
        if tag == TAG_DOUBLE:
            return self.f8()
        if tag == TAG_BYTE_ARRAY:
            return self.take(self.i4())
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            elem = self.u1()
            count = self.i4()
            return (elem, [self.payload(elem) for _ in range(count)])
        if tag == TAG_COMPOUND:
            out = {}
            while True:
                child = self.u1()
                if child == TAG_END:
                    return out
                name = self.string()
                out[name] = (child, self.payload(child))
        if tag == TAG_INT_ARRAY:
            return [self.i4() for _ in range(self.i4())]
        if tag == TAG_LONG_ARRAY:
            return [self.i8() for _ in range(self.i4())]
        raise ValueError("unknown NBT tag %d" % tag)


def read_nbt(raw):
    """Returns (root_name, root_compound). Handles both gzip and bare payloads."""
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    r = Reader(raw)
    tag = r.u1()
    if tag != TAG_COMPOUND:
        raise ValueError("root tag is %d, expected compound" % tag)
    name = r.string()
    return name, r.payload(TAG_COMPOUND)


def write_nbt(root_name, root):
    """Serialises a (type, payload) tree. Uncompressed; the caller decides on gzip."""
    out = io.BytesIO()

    def put_payload(tag, value):
        if tag == TAG_BYTE:
            out.write(struct.pack(">b", value))
        elif tag == TAG_SHORT:
            out.write(struct.pack(">h", value))
        elif tag == TAG_INT:
            out.write(struct.pack(">i", value))
        elif tag == TAG_LONG:
            out.write(struct.pack(">q", value))
        elif tag == TAG_FLOAT:
            out.write(struct.pack(">f", value))
        elif tag == TAG_DOUBLE:
            out.write(struct.pack(">d", value))
        elif tag == TAG_BYTE_ARRAY:
            out.write(struct.pack(">i", len(value)))
            out.write(value)
        elif tag == TAG_STRING:
            encoded = value.encode("utf-8")
            out.write(struct.pack(">H", len(encoded)))
            out.write(encoded)
        elif tag == TAG_LIST:
            elem, items = value
            out.write(struct.pack(">bi", elem, len(items)))
            for item in items:
                put_payload(elem, item)
        elif tag == TAG_COMPOUND:
            for name, (child_tag, child_value) in value.items():
                out.write(struct.pack(">b", child_tag))
                encoded = name.encode("utf-8")
                out.write(struct.pack(">H", len(encoded)))
                out.write(encoded)
                put_payload(child_tag, child_value)
            out.write(b"\x00")
        elif tag == TAG_INT_ARRAY:
            out.write(struct.pack(">i", len(value)))
            for item in value:
                out.write(struct.pack(">i", item))
        elif tag == TAG_LONG_ARRAY:
            out.write(struct.pack(">i", len(value)))
            for item in value:
                out.write(struct.pack(">q", item))
        else:
            raise ValueError("cannot write tag %d" % tag)

    out.write(struct.pack(">b", TAG_COMPOUND))
    encoded = root_name.encode("utf-8")
    out.write(struct.pack(">H", len(encoded)))
    out.write(encoded)
    put_payload(TAG_COMPOUND, root)
    return out.getvalue()


def c(**kwargs):
    """Compound from name=(type, value) pairs, in the order given."""
    return {k: v for k, v in kwargs.items()}


def tag(t, v):
    return (t, v)


def t_int(v):
    return (TAG_INT, v)


def t_string(v):
    return (TAG_STRING, v)


def t_byte(v):
    return (TAG_BYTE, v)


def t_compound(d):
    return (TAG_COMPOUND, d)


def t_list(elem, items):
    return (TAG_LIST, (elem, items))


# ------------------------------------------------------------------- world reading

AIRISH = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}

# Removed from converted structures by default. Each of these is either an exploit or a leftover from
# the map author's own tooling; none of them belong in a fighting pit.
DEFAULT_STRIP = {
    "minecraft:barrier",
    "minecraft:light",
    "minecraft:structure_void",
    "minecraft:structure_block",
    "minecraft:jigsaw",
    "minecraft:command_block",
    "minecraft:chain_command_block",
    "minecraft:repeating_command_block",
}


def _region_bytes(source, name):
    if isinstance(source, zipfile.ZipFile):
        return source.read(name)
    with open(name, "rb") as handle:
        return handle.read()


def iter_region_files(source):
    """Yields (rx, rz, bytes) for every region file the source contains."""
    if isinstance(source, zipfile.ZipFile):
        for info in source.infolist():
            base = os.path.basename(info.filename)
            if "/region/" in info.filename and base.startswith("r.") and base.endswith(".mca"):
                parts = base.split(".")
                yield int(parts[1]), int(parts[2]), source.read(info.filename)
        return
    region_dir = os.path.join(source, "region")
    if not os.path.isdir(region_dir):
        raise SystemExit("no region/ directory in %s" % source)
    for base in sorted(os.listdir(region_dir)):
        if base.startswith("r.") and base.endswith(".mca"):
            parts = base.split(".")
            yield (int(parts[1]), int(parts[2]),
                   open(os.path.join(region_dir, base), "rb").read())


def iter_chunks(region_data):
    """Yields (chunk_x, chunk_z, root_compound) for every present chunk in one .mca file."""
    if len(region_data) < 8192:
        return
    for index in range(1024):
        entry = region_data[index * 4:index * 4 + 4]
        offset = (entry[0] << 16) | (entry[1] << 8) | entry[2]
        sectors = entry[3]
        if offset == 0 or sectors == 0:
            continue
        start = offset * 4096
        header = region_data[start:start + 5]
        if len(header) < 5:
            continue
        length = struct.unpack(">i", header[:4])[0]
        compression = header[4]
        payload = region_data[start + 5:start + 4 + length]
        try:
            if compression == 1:
                raw = gzip.decompress(payload)
            elif compression == 2:
                raw = zlib.decompress(payload)
            elif compression == 3:
                raw = payload
            else:
                continue
        except Exception:
            continue
        try:
            _, root = read_nbt(raw)
        except Exception:
            continue
        # Pre-1.18 worlds nest the chunk under "Level"; 1.18+ put sections at the root.
        if "sections" in root:
            body = root
        elif "Level" in root and "sections" in root["Level"][1]:
            body = root["Level"][1]
        else:
            continue
        yield index % 32, index // 32, body


def section_blocks(section):
    """Yields (local_x, local_y, local_z, name, properties) for every non-air block in a section."""
    states = section.get("block_states")
    if states is None:
        return
    states = states[1]
    palette_entry = states.get("palette")
    if palette_entry is None:
        return
    palette = []
    for entry in palette_entry[1][1]:
        # Each entry is a compound payload: dict[str] -> (tag, value).
        name = entry.get("Name", (TAG_STRING, "minecraft:air"))[1]
        props_entry = entry.get("Properties")
        props = {}
        if props_entry is not None:
            for key, value in props_entry[1].items():
                props[key] = value[1]
        palette.append((name, props))
    if all(name in AIRISH for name, _ in palette):
        return
    data_entry = states.get("data")
    if len(palette) == 1:
        name, props = palette[0]
        for index in range(4096):
            yield index & 15, index >> 8, (index >> 4) & 15, name, props
        return
    if data_entry is None:
        return
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    longs = data_entry[1]
    for index in range(4096):
        long_index = index // per_long
        if long_index >= len(longs):
            break
        value = (longs[long_index] >> ((index % per_long) * bits)) & mask
        if value >= len(palette):
            continue
        name, props = palette[value]
        if name in AIRISH:
            continue
        yield index & 15, index >> 8, (index >> 4) & 15, name, props


def scan(source, box=None, strip_bedrock=False):
    """Walks every chunk and returns (bbox, blocks) where blocks maps (x,y,z) -> (name, props)."""
    blocks = {}
    for rx, rz, data in iter_region_files(source):
        for cx, cz, body in iter_chunks(data):
            base_x = (rx * 32 + cx) * 16
            base_z = (rz * 32 + cz) * 16
            for section in body["sections"][1][1]:
                section_y = section.get("Y", (TAG_BYTE, 0))[1]
                for lx, ly, lz, name, props in section_blocks(section):
                    x = base_x + lx
                    y = section_y * 16 + ly
                    z = base_z + lz
                    if box and not (box[0] <= x <= box[3] and box[1] <= y <= box[4]
                                    and box[2] <= z <= box[5]):
                        continue
                    if name in AIRISH or name in DEFAULT_STRIP:
                        continue
                    if strip_bedrock and name == "minecraft:bedrock":
                        continue
                    blocks[(x, y, z)] = (name, props)
    if not blocks:
        raise SystemExit("no non-air blocks found")
    xs = [p[0] for p in blocks]
    ys = [p[1] for p in blocks]
    zs = [p[2] for p in blocks]
    return (min(xs), min(ys), min(zs), max(xs), max(ys), max(zs)), blocks


def report(bbox, blocks):
    size = (bbox[3] - bbox[0] + 1, bbox[4] - bbox[1] + 1, bbox[5] - bbox[2] + 1)
    names = {}
    for name, _ in blocks.values():
        names[name] = names.get(name, 0) + 1
    top = sorted(names.items(), key=lambda kv: -kv[1])[:12]
    per_y = {}
    for (_, y, _) in blocks:
        per_y[y] = per_y.get(y, 0) + 1
    print("bbox      x %d..%d  y %d..%d  z %d..%d" % (bbox[0], bbox[3], bbox[1], bbox[4],
                                                      bbox[2], bbox[5]))
    print("size      %d x %d x %d  (x, y, z)" % size)
    print("blocks    %d non-air" % len(blocks))
    print("palette   %d distinct block names" % len(names))
    print("top       " + ", ".join("%s x%d" % (n.replace("minecraft:", ""), k) for n, k in top))
    floors = sorted(per_y.items())
    if floors:
        busiest_y, _ = max(per_y.items(), key=lambda kv: kv[1])
        print("height    y=%d has the most blocks (%d)" % (busiest_y, per_y[busiest_y]))
        print("          first non-empty y=%d, last y=%d" % (floors[0][0], floors[-1][0]))
        # The busiest layer is almost always the fighting floor, and its footprint is what the plot
        # size has to be — not the bounding box, which includes the stands.
        at = [(x, z) for (x, y, z) in blocks if y == busiest_y]
        print("          that layer spans x %d..%d  z %d..%d  (%d x %d)"
              % (min(p[0] for p in at), max(p[0] for p in at),
                 min(p[1] for p in at), max(p[1] for p in at),
                 max(p[0] for p in at) - min(p[0] for p in at) + 1,
                 max(p[1] for p in at) - min(p[1] for p in at) + 1))
    return size


def to_structure(bbox, blocks, data_version):
    """Builds a vanilla structure template tree: size + palette + blocks (positions explicit)."""
    palette = []
    palette_index = {}
    # props is a dict, so it cannot go in a set directly; the tuple-of-items form is both hashable
    # and stable, and it is what the block lookup below keys on anyway.
    distinct = {(name, tuple(sorted(props.items()))) for name, props in blocks.values()}
    for name, props_items in sorted(distinct):
        props = dict(props_items)
        palette_index[(name, props_items)] = len(palette)
        entry = {"Name": t_string(name)}
        if props:
            entry["Properties"] = t_compound({k: t_string(v) for k, v in props.items()})
        # Bare payload, not a (tag, value) pair: t_list already knows the element tag.
        palette.append(entry)
    out_blocks = []
    for (x, y, z), (name, props) in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        out_blocks.append({
            "pos": t_list(TAG_INT, [x - bbox[0], y - bbox[1], z - bbox[2]]),
            "state": t_int(palette_index[(name, tuple(sorted(props.items())))]),
        })
    root = {
        "DataVersion": t_int(data_version),
        "size": t_list(TAG_INT, [bbox[3] - bbox[0] + 1, bbox[4] - bbox[1] + 1, bbox[5] - bbox[2] + 1]),
        "palette": t_list(TAG_COMPOUND, palette),
        "blocks": t_list(TAG_COMPOUND, out_blocks),
        "entities": t_list(TAG_COMPOUND, []),
    }
    return root


def open_source(path):
    if path.lower().endswith(".zip"):
        return zipfile.ZipFile(path)
    if not os.path.isdir(path):
        raise SystemExit("%s is neither a directory nor a .zip" % path)
    return path


def parse_box(values):
    if not values:
        return None
    if len(values) != 6:
        raise SystemExit("--box takes 6 numbers: x1 y1 z1 x2 y2 z2")
    x1, y1, z1, x2, y2, z2 = (int(v) for v in values)
    return (min(x1, x2), min(y1, y2), min(z1, z2), max(x1, x2), max(y1, y2), max(z1, z2))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    probe = sub.add_parser("probe", help="report the bounding box and block statistics")
    probe.add_argument("world")
    probe.add_argument("--box", nargs=6)
    probe.add_argument("--strip-bedrock", action="store_true")

    conv = sub.add_parser("convert", help="write a vanilla structure template")
    conv.add_argument("world")
    conv.add_argument("--out", required=True)
    conv.add_argument("--box", nargs=6)
    conv.add_argument("--strip-bedrock", action="store_true")
    conv.add_argument("--data-version", type=int, default=3955,
                      help="DataVersion to stamp (3955 = 1.21.1)")
    conv.add_argument("--no-gzip", action="store_true",
                      help="write uncompressed. Vanilla gzips these files "
                           "(StructureTemplateManager uses NbtIo.readCompressed) and the difference "
                           "is 13x: the test fixture is 178 KB plain, 13.7 KB gzipped")

    args = parser.parse_args(argv)
    source = open_source(args.world)
    box = parse_box(args.box)
    try:
        bbox, blocks = scan(source, box, args.strip_bedrock)
        size = report(bbox, blocks)
        if args.command == "convert":
            root = to_structure(bbox, blocks, args.data_version)
            plain = write_nbt("", root)
            raw = plain if args.no_gzip else gzip.compress(plain, 9)
            out_dir = os.path.dirname(os.path.abspath(args.out))
            os.makedirs(out_dir, exist_ok=True)
            with open(args.out, "wb") as handle:
                handle.write(raw)
            print("wrote     %s (%.1f KB%s)" % (args.out, len(raw) / 1024.0,
                                                "" if args.no_gzip else ", gzipped"))
            # Read it back through the same parser. A converter that cannot read its own output is
            # not a converter, and a silent off-by-one in the palette index would only surface as a
            # scrambled build inside the game.
            _, back = read_nbt(raw)
            back_size = tuple(back["size"][1][1])
            back_blocks = len(back["blocks"][1][1])
            back_palette = len(back["palette"][1][1])
            ok = (back_size == size and back_blocks == len(blocks)
                  and back_palette == len(root["palette"][1][1]))
            print("verify    size=%s blocks=%d palette=%d -> %s"
                  % (back_size, back_blocks, back_palette, "OK" if ok else "MISMATCH"))
            if not ok:
                return 1
    finally:
        if isinstance(source, zipfile.ZipFile):
            source.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
