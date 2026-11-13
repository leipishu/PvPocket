#!/usr/bin/env python3
"""Turns a downloaded Minecraft world save into a vanilla structure template.

Inputs are picked by extension: a world save directory or .zip containing region/, a Litematica
.litematic, or a Sponge .schem (v2/v3). Vanilla's structure block caps at 48x48x48, which is why
region files are read directly instead.

    python scripts/world2struct.py probe <input> [--box x1 y1 z1 x2 y2 z2]
    python scripts/world2struct.py convert <input> --out <file.nbt> [options]

The output holds blocks only: no entities and no block-entity NBT, since chests, hoppers and
command blocks are either an item exploit or a way to run commands inside a duel.
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

# Values are (tag_type, payload). Compounds are dict[str] -> (type, payload); lists are
# (elem_type, [payload, ...]). Keeping the type lets the writer emit a faithful copy.


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


def t_short(v):
    return (TAG_SHORT, v)


def t_string(v):
    return (TAG_STRING, v)


def t_byte(v):
    return (TAG_BYTE, v)


def t_compound(d):
    return (TAG_COMPOUND, d)


def t_list(elem, items):
    return (TAG_LIST, (elem, items))


AIRISH = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}

# Removed from converted structures: each of these is an exploit or a leftover from the author's
# tooling, and none of them belongs in a fighting pit.
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

# Renames between the version these maps are authored in (1.16 era) and 1.21.1. An id that does
# not exist becomes air in game, silently, which is the worst failure mode for an arena floor.
RENAMES = {
    "minecraft:grass_path": "minecraft:dirt_path",
    "minecraft:grass": "minecraft:short_grass",
    "minecraft:sign": "minecraft:oak_sign",
    "minecraft:wall_sign": "minecraft:oak_wall_sign",
}

# Blocks that exist in the map's version but not in 1.21.1, so there is no rename to fall back on.
# Every entry keeps the same blockstate shape, so the silhouette survives.
FALLBACK = {
    "minecraft:waxed_exposed_copper_bars": "minecraft:iron_bars",
    "minecraft:exposed_copper_bars": "minecraft:iron_bars",
    "minecraft:copper_bars": "minecraft:iron_bars",
    "minecraft:weathered_copper_bars": "minecraft:iron_bars",
    "minecraft:oxidized_copper_bars": "minecraft:iron_bars",
    "minecraft:pale_oak_stairs": "minecraft:oak_stairs",
    "minecraft:pale_oak_slab": "minecraft:oak_slab",
    "minecraft:pale_oak_planks": "minecraft:oak_planks",
    "minecraft:pale_oak_fence": "minecraft:oak_fence",
    "minecraft:pale_oak_fence_gate": "minecraft:oak_fence_gate",
    "minecraft:pale_oak_door": "minecraft:oak_door",
    "minecraft:pale_oak_trapdoor": "minecraft:oak_trapdoor",
    "minecraft:pale_oak_log": "minecraft:oak_log",
    "minecraft:pale_oak_wood": "minecraft:oak_wood",
    "minecraft:stripped_pale_oak_log": "minecraft:stripped_oak_log",
    "minecraft:stripped_pale_oak_wood": "minecraft:stripped_oak_wood",
    "minecraft:pale_oak_leaves": "minecraft:oak_leaves",
    "minecraft:pale_oak_sapling": "minecraft:oak_sapling",
    "minecraft:pale_oak_sign": "minecraft:oak_sign",
    "minecraft:pale_oak_wall_sign": "minecraft:oak_wall_sign",
    "minecraft:pale_oak_button": "minecraft:oak_button",
    "minecraft:pale_oak_pressure_plate": "minecraft:oak_pressure_plate",
    "minecraft:creaking_heart": "minecraft:oak_wood",
    "minecraft:pale_moss_block": "minecraft:moss_block",
    "minecraft:pale_moss_carpet": "minecraft:moss_carpet",
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
        # Three layouts in the wild: 1.18+ has lowercase `sections` at the root, 1.13-1.17 nest
        # them under `Level`, and 1.8 chunks use a capitalised `Sections` array.
        body = None
        for candidate in (root, root.get("Level", (None, {}))[1]):
            if not isinstance(candidate, dict):
                continue
            if "sections" in candidate or "Sections" in candidate:
                body = candidate
                break
        if body is None:
            continue
        yield index % 32, index // 32, body


def chunk_sections(body):
    """The section list of a chunk, whichever key its era used."""
    for key in ("sections", "Sections"):
        entry = body.get(key)
        if entry is not None:
            return entry[1][1]
    return []


def section_blocks(section):
    """Yields (local_x, local_y, local_z, name, properties) for every non-air block in a section."""
    states = section.get("block_states")
    if states is None:
        yield from legacy_section_blocks(section)
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


def legacy_section_blocks(section):
    """1.8-era section: numeric block ids in `Blocks`, 4-bit metadata in `Data`.

    Every id+metadata pair has to be flattened to a modern name, and `anvil-parser2` already ships
    Paper's conversion table (`LEGACY_ID_MAP`). Properties are stringified here because everything
    downstream treats them as the strings NBT would store.
    """
    raw = section.get("Blocks")
    if raw is None:
        return
    from anvil.legacy import LEGACY_ID_MAP

    blocks = raw[1]
    data = section.get("Data", (None, b""))[1]
    add = section.get("Add", (None, b""))[1]
    for index in range(min(4096, len(blocks))):
        block_id = blocks[index] & 0xFF
        if add:
            block_id |= ((add[index >> 1] >> (4 * (index & 1))) & 0xF) << 8
        if block_id == 0:
            continue
        meta = (data[index >> 1] >> (4 * (index & 1))) & 0xF if data else 0
        entry = LEGACY_ID_MAP.get("%d:%d" % (block_id, meta))
        if entry is None:
            # An unmapped metadata value is usually a variant that does not matter; metadata 0
            # keeps the block instead of punching a hole in the build.
            entry = LEGACY_ID_MAP.get("%d:0" % block_id)
        if entry is None:
            continue
        name = entry[0]
        if not name.startswith("minecraft:"):
            name = "minecraft:" + name
        props = {}
        for key, value in (entry[1] or {}).items():
            props[key] = ("true" if value is True else "false" if value is False else str(value))
        yield index & 15, index >> 8, (index >> 4) & 15, name, props


def keep(name, props, x, y, z, box, strip_bedrock):
    """The single place that decides whether a block survives into the output."""
    if box and not (box[0] <= x <= box[3] and box[1] <= y <= box[4] and box[2] <= z <= box[5]):
        return False
    if name in AIRISH or name in DEFAULT_STRIP:
        return False
    if strip_bedrock and name == "minecraft:bedrock":
        return False
    return True


def finish(blocks):
    if not blocks:
        raise SystemExit("no non-air blocks found")
    xs = [p[0] for p in blocks]
    ys = [p[1] for p in blocks]
    zs = [p[2] for p in blocks]
    return (min(xs), min(ys), min(zs), max(xs), max(ys), max(zs)), blocks


def scan(source, box=None, strip_bedrock=False):
    """Walks every chunk of a world save and returns (bbox, blocks)."""
    blocks = {}
    for rx, rz, data in iter_region_files(source):
        for cx, cz, body in iter_chunks(data):
            base_x = (rx * 32 + cx) * 16
            base_z = (rz * 32 + cz) * 16
            for section in chunk_sections(body):
                section_y = section.get("Y", (TAG_BYTE, 0))[1]
                for lx, ly, lz, name, props in section_blocks(section):
                    x = base_x + lx
                    y = section_y * 16 + ly
                    z = base_z + lz
                    if keep(name, props, x, y, z, box, strip_bedrock):
                        blocks[(x, y, z)] = (name, props)
    return finish(blocks)


# A .litematic has no chunk or region layer: one flat palette plus one flat long array per
# sub-region. The inner bit packing is not the vanilla section packing; see unpack_longs.


def unpack_longs(longs, bits, count):
    """Litematica's packed long array -> list of `count` values of `bits` bits each.

    This is not the vanilla chunk packing. A chunk section stores floor(64/bits) values per long
    and throws the leftover bits away; Litematica packs a continuous bit stream, so a value that
    starts at bit 61 of one long continues at bit 0 of the next. The two agree only when `bits`
    divides 64, and every arena here needs 5 bits. Cross-checked against litemapy.
    """
    mask = (1 << bits) - 1
    span = (1 << 64) - 1
    out = []
    for index in range(count):
        start = index * bits
        first = start >> 6
        if first >= len(longs):
            break
        offset = start & 63
        value = (longs[first] & span) >> offset
        if offset + bits > 64 and first + 1 < len(longs):
            value |= (longs[first + 1] & span) << (64 - offset)
        out.append(value & mask)
    return out


def palette_entry(entry):
    """Both litematic and Sponge use the structure-template shape: Name + optional Properties."""
    name = entry.get("Name", (TAG_STRING, "minecraft:air"))[1]
    props_entry = entry.get("Properties")
    props = {}
    if props_entry is not None:
        for key, value in props_entry[1].items():
            props[key] = value[1]
    return name, props


def scan_litematic(path, box=None, strip_bedrock=False):
    """Reads a Litematica .litematic. One file may hold several named sub-regions."""
    with open(path, "rb") as handle:
        _, root = read_nbt(handle.read())
    regions_entry = root.get("Regions")
    if regions_entry is None:
        raise SystemExit("%s has no Regions compound -- not a litematic" % path)
    blocks = {}
    for _, region_entry in regions_entry[1].items():
        region = region_entry[1]        # compound dict -> (tag, payload) pairs
        palette_entry_ = region.get("BlockStatePalette")
        states_entry = region.get("BlockStates")
        if palette_entry_ is None or states_entry is None:
            continue
        pos = region["Position"][1]
        size = region["Size"][1]
        px, py, pz = pos["x"][1], pos["y"][1], pos["z"][1]
        sx, sy, sz = size["x"][1], size["y"][1], size["z"][1]
        # A negative component means the box runs backwards from Position; normalise to
        # min-corner + positive extent so the index maths below stays uniform.
        if sx < 0:
            px, sx = px + sx + 1, -sx
        if sy < 0:
            py, sy = py + sy + 1, -sy
        if sz < 0:
            pz, sz = pz + sz + 1, -sz
        if sx <= 0 or sy <= 0 or sz <= 0:
            continue
        palette = [palette_entry(e) for e in palette_entry_[1][1]]
        if all(name in AIRISH for name, _ in palette):
            continue
        # Litematica floors this at 2 bits, vanilla floors chunk sections at 4.
        bits = max(2, (len(palette) - 1).bit_length())
        values = unpack_longs(states_entry[1], bits, sx * sy * sz)
        for index, value in enumerate(values):
            if value >= len(palette):
                continue
            name, props = palette[value]
            if name in AIRISH:
                continue
            # Litematica iterates x fastest, then z, then y.
            x = px + index % sx
            z = pz + (index // sx) % sz
            y = py + index // (sx * sz)
            if keep(name, props, x, y, z, box, strip_bedrock):
                blocks[(x, y, z)] = (name, props)
    return finish(blocks)


def decode_varints(data):
    """Sponge stores block indices as a varint stream, not a packed long array."""
    value = 0
    shift = 0
    for byte in data:
        value |= (byte & 0x7F) << shift
        if byte & 0x80:
            shift += 7
        else:
            yield value
            value = 0
            shift = 0


def scan_schem(path, box=None, strip_bedrock=False):
    """Reads a Sponge .schem (v2 or v3). Legacy MCEdit .schematic is rejected, not mis-parsed."""
    with open(path, "rb") as handle:
        _, root = read_nbt(handle.read())
    body = root
    blocks_entry = root.get("Blocks")
    if blocks_entry is not None and isinstance(blocks_entry[1], dict):
        body = blocks_entry[1]          # v3 nests Palette/Data under Blocks
    palette_entry_ = body.get("Palette")
    data_entry = body.get("BlockData") or body.get("Data")
    if palette_entry_ is None or data_entry is None:
        raise SystemExit("%s has no Palette/BlockData -- legacy MCEdit .schematic stores numeric "
                         "block ids and cannot be converted without a 1.12 block table" % path)
    width = root["Width"][1]
    height = root["Height"][1]
    length = root["Length"][1]
    offset_entry = root.get("Offset")
    # Offset is a TAG_List of ints -> (elem_tag, [values]), so the payload is [1][1].
    ox, oy, oz = offset_entry[1][1] if offset_entry is not None else [0, 0, 0]
    index_to_name = {}
    for name, (_, value) in palette_entry_[1].items():
        index_to_name[value] = name
    blocks = {}
    for index, value in enumerate(decode_varints(data_entry[1])):
        name = index_to_name.get(value)
        if name is None or name in AIRISH:
            continue
        x = ox + index % width
        z = oz + (index // width) % length
        y = oy + index // (width * length)
        if keep(name, {}, x, y, z, box, strip_bedrock):
            blocks[(x, y, z)] = (name, {})
    return finish(blocks)


def load(path, box=None, strip_bedrock=False):
    """Dispatches on file extension: world save / .zip, .litematic, or Sponge .schem."""
    lower = path.lower()
    if lower.endswith(".litematic"):
        bbox, blocks = scan_litematic(path, box, strip_bedrock)
    elif lower.endswith(".schem") or lower.endswith(".schematic"):
        bbox, blocks = scan_schem(path, box, strip_bedrock)
    else:
        source = open_source(path)
        try:
            bbox, blocks = scan(source, box, strip_bedrock)
        finally:
            if isinstance(source, zipfile.ZipFile):
                source.close()
    # normalise recomputes the box: dropping a block at the extreme corner would leave the
    # template one block larger than the build.
    return normalise(blocks)


def report(bbox, blocks, show_palette=False):
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
    if show_palette:
        # Every name in one place, so it is possible to spot a block that 1.21.1 does not have
        # before it silently turns into air in game.
        print("all       " + ", ".join(sorted(n.replace("minecraft:", "") for n in names)))
    floors = sorted(per_y.items())
    if floors:
        busiest_y, _ = max(per_y.items(), key=lambda kv: kv[1])
        print("height    y=%d has the most blocks (%d)" % (busiest_y, per_y[busiest_y]))
        print("          first non-empty y=%d, last y=%d" % (floors[0][0], floors[-1][0]))
        # The busiest layer is usually the fighting floor, and its footprint is what the plot
        # size has to be, not the bounding box, which includes the stands.
        at = [(x, z) for (x, y, z) in blocks if y == busiest_y]
        print("          that layer spans x %d..%d  z %d..%d  (%d x %d)"
              % (min(p[0] for p in at), max(p[0] for p in at),
                 min(p[1] for p in at), max(p[1] for p in at),
                 max(p[0] for p in at) - min(p[0] for p in at) + 1,
                 max(p[1] for p in at) - min(p[1] for p in at) + 1))
    return size


def stabilise(name):
    """Replaces blocks that fall when placed with the closest block that does not.

    Concrete powder is the one that bites: it is a gravity block, so every floating patch of it
    collapses the moment the template lands. Concrete is the same colour and does not fall. Sand
    and gravel are left alone: they are usually ground fill with support underneath.
    """
    if name.endswith("_concrete_powder"):
        return name[:-len("_powder")]
    return name


def needs_support(name):
    """Blocks that pop off when the game finds nothing holding them up."""
    return (name.endswith("_pressure_plate")
            or name in ("minecraft:torch", "minecraft:redstone_torch",
                        "minecraft:wall_torch", "minecraft:redstone_wall_torch")
            or name in FLOOR_ATTACHED)


# Blocks that need something directly beneath them. Same failure mode as a pressure plate: the
# game removes them on the first tick, so the file would claim a block the world never shows.
FLOOR_ATTACHED = ("minecraft:redstone_wire", "minecraft:rail", "minecraft:powered_rail",
                  "minecraft:detector_rail", "minecraft:activator_rail",
                  "minecraft:lily_pad")


# Blocks dropped however well supported they are, because they do not hold still. `fire` ages and
# then removes itself; any entity crossing `tripwire` flips `attached`, which reverts the arena.
SELF_DESTRUCTING = ("minecraft:fire", "minecraft:soul_fire", "minecraft:tripwire")


def drop_unstable(blocks):
    """Removes blocks that cannot survive being placed.

    A block that pops after the plot baseline is captured leaves the arena permanently one block
    off its own map, and every revert puts it back just in time for it to pop again.
    """
    doomed = []
    for pos, (name, props) in blocks.items():
        if name in SELF_DESTRUCTING:
            doomed.append(pos)
            continue
        if not needs_support(name):
            continue
        x, y, z = pos
        below = blocks.get((x, y - 1, z))
        if (name.endswith("_pressure_plate") or name in ("minecraft:torch",
                                                          "minecraft:redstone_torch")
              or name in FLOOR_ATTACHED):
            supported = below is not None
        else:
            # A wall torch can be attached on any of the four sides; the facing property decides
            # which, but accepting any neighbour is enough to tell "attached" from "floating".
            supported = any((x + dx, y, z + dz) in blocks
                            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if not supported:
            doomed.append(pos)
    for pos in doomed:
        del blocks[pos]
    if doomed:
        print("dropped   %d block(s) that would not stay put after placement" % len(doomed))
    return len(doomed)


def normalise(blocks):
    """Brings a downloaded build into the 1.21.1 world it will be pasted into.

    Three passes in this order: rename first so the fallback table sees the map's own ids,
    stabilise second so a just-renamed powder is still caught, drop last.
    """
    renamed = {}
    for (x, y, z), (name, props) in list(blocks.items()):
        new_name = RENAMES.get(name) or FALLBACK.get(name)
        if new_name is not None and new_name != name:
            renamed[name] = renamed.get(name, 0) + 1
            blocks[(x, y, z)] = (new_name, props)
    if renamed:
        print("mapped    " + ", ".join("%s -> %s x%d" % (old, RENAMES.get(old) or FALLBACK[old], count)
                                       for old, count in sorted(renamed.items())))
    settled = {}
    for (x, y, z), (name, props) in list(blocks.items()):
        new_name = stabilise(name)
        if new_name != name:
            settled[name] = settled.get(name, 0) + 1
            blocks[(x, y, z)] = (new_name, props)
    if settled:
        print("settled   " + ", ".join("%s -> %s x%d" % (old, stabilise(old), count)
                                       for old, count in sorted(settled.items())))
    drop_unstable(blocks)
    return finish(blocks)


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
    probe.add_argument("--palette", action="store_true",
                       help="also list every distinct block name")

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
    box = parse_box(args.box)
    bbox, blocks = load(args.world, box, args.strip_bedrock)
    size = report(bbox, blocks, getattr(args, "palette", False))
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
        # Read it back through the same parser: a silent off-by-one in the palette index would
        # only surface as a scrambled build in game.
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
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
