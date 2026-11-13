#!/usr/bin/env python3
"""Generates a Yarn to Mojmap (official Mojang names) mapping table.

Both mapping sets contain obfuscated names, so joining on those gives the correspondence:

    Yarn  mappings.tiny   official(obf) -> intermediary -> named(Yarn)
    Mojang client.txt     official(named) -> obf

client.txt and server.txt each cover half the classes, so both are needed. The output is a TSV
of names that actually changed, consumed by apply-mojmap.py:

    class   <Yarn fqn>  <Mojmap fqn>
    method  <Yarn class>  <Yarn name>  <Yarn descriptor>  <Mojmap name>
    field   <Yarn class>  <Yarn name>  <Mojmap name>

Usage:
    scripts/mojmap-table.py --mc 1.21.1 --yarn 1.21.1+build.3
    scripts/mojmap-table.py --mc 26.3            # 26.x needs no table: names are runtime names

Official mappings are cached in <repo>/build/mojmap-cache/, so a rerun does not download.
"""

import argparse
import glob
import gzip
import json
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "build", "mojmap-cache")
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def download(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 1024:
        return path
    print("  下载 %s" % url)
    with urllib.request.urlopen(url, timeout=180) as response:
        data = response.read()
    with open(path, "wb") as out:
        out.write(data)
    return path


def version_json(mc):
    path = os.path.join(CACHE, "versions.json")
    download(MANIFEST, path)
    with open(path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    for entry in manifest["versions"]:
        if entry["id"] == mc:
            detail = os.path.join(CACHE, "%s.json" % mc)
            download(entry["url"], detail)
            with open(detail, encoding="utf-8") as handle:
                return json.load(handle)
    raise SystemExit("版本清单里没有 %s" % mc)


# --------------------------------------------------------------------- Mojang official mappings

PRIMITIVES = {"void", "boolean", "byte", "char", "short", "int", "long", "float", "double"}


def parse_mojang(paths):
    """Returns (official class -> obf name, obf class -> [(member kind, obf name, payload, name)])."""
    official_to_obf = {}
    members = {}

    for path in paths:
        if not os.path.exists(path):
            continue
        obf_class = None
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("#"):
                    continue
                stripped = line.strip()
                if not stripped:
                    continue
                if stripped.endswith(":"):
                    left, _, right = stripped.partition(" -> ")
                    official_to_obf[left.strip()] = right.strip().rstrip(":")
                    obf_class = right.strip().rstrip(":")
                    members.setdefault(obf_class, [])
                    continue
                if obf_class is None or " -> " not in stripped:
                    continue
                left, _, obf_name = stripped.partition(" -> ")
                left = left.strip()
                obf_name = obf_name.strip()
                # method lines carry a line-number prefix: 12:13:void foo(int) -> a
                if left[:1].isdigit():
                    left = left.split(":", 1)[1]
                    left = left.split(":", 1)[1] if left[:1].isdigit() else left
                if "(" in left:
                    ret, _, rest = left.partition(" ")
                    name, _, params = rest.partition("(")
                    params = params.rstrip(")")
                    members[obf_class].append(("method", obf_name, (ret, name, params), None))
                else:
                    # field: type name -> a
                    parts = left.split()
                    if len(parts) == 2:
                        members[obf_class].append(("field", obf_name, parts[0], parts[1]))
    return official_to_obf, members


def obf_descriptor(type_text, official_to_obf):
    """Turns an official source type into an obfuscated descriptor. Arrays and primitives as is."""
    dims = 0
    text = type_text.strip()
    while text.endswith("[]"):
        dims += 1
        text = text[:-2]
    if text in PRIMITIVES:
        code = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
                "int": "I", "long": "J", "float": "F", "double": "D"}[text]
    else:
        # A type missing from the official mapping is a library class (org.joml, java.*): Mojang
        # does not obfuscate those, so the obf name equals the official one.
        obf = official_to_obf.get(text, text.replace(".", "/"))
        code = "L%s;" % obf
    return "[" * dims + code


def mojang_member_index(official_to_obf, members):
    """(obf class, obf name, obf descriptor) -> official method name, and (class, name) -> field."""
    methods = {}
    fields = {}
    for obf_class, entries in members.items():
        for kind, obf_name, payload, official_name in entries:
            if kind == "method":
                ret, name, params = payload
                ret_code = obf_descriptor(ret, official_to_obf)
                if ret_code is None:
                    continue
                codes = []
                broken = False
                if params.strip():
                    for param in split_types(params):
                        code = obf_descriptor(param, official_to_obf)
                        if code is None:
                            broken = True
                            break
                        codes.append(code)
                if broken:
                    continue
                descriptor = "(%s)%s" % ("".join(codes), ret_code)
                methods[(obf_class, obf_name, descriptor)] = name
            else:
                fields[(obf_class, obf_name)] = official_name
    return methods, fields


def split_types(params):
    """Splits parameters on commas, ignoring commas inside generics."""
    out = []
    depth = 0
    current = []
    for ch in params:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
    if current:
        out.append("".join(current).strip())
    return out


# --------------------------------------------------------------------------- Yarn mappings

def yarn_tiny(mc, yarn):
    pattern = os.path.join(
        os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", mc,
        "net.fabricmc.yarn.%s.*" % yarn.replace("+", "."), "mappings.tiny")
    found = sorted(glob.glob(pattern))
    if not found:
        # the dot substitution in the directory name differs between versions; glob instead
        found = sorted(glob.glob(os.path.join(
            os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", mc,
            "net.fabricmc.yarn.*", "mappings.tiny")))
    if not found:
        raise SystemExit("找不到 Yarn mappings.tiny（先在工程里跑一次 ./gradlew compileJava）")
    return found[-1]


def parse_tiny(path):
    """yarn tiny v2 -> three lists of (official, intermediary, named)."""
    classes = []
    methods = []
    fields = []
    current = None
    with open(path, encoding="utf-8") as handle:
        header = handle.readline().split("\t")
        if header[0] != "tiny":
            raise SystemExit("不是 tiny v2 映射：%s" % path)
        for line in handle:
            if not line.strip():
                continue
            # tiny v2 uses indentation for nesting, and its comments also start with c. Only a
            # level 0 c and a level 1 m/f count, so the indentation has to be measured first.
            depth = len(line) - len(line.lstrip("\t"))
            parts = line.rstrip("\n").split("\t")[depth:]
            if not parts or not parts[0]:
                continue
            while len(parts) < 5:
                parts.append(parts[-1])
            if depth == 0 and parts[0] == "c":
                current = parts[1]
                classes.append((parts[1], parts[2], parts[3] if len(parts) > 3 else parts[2]))
            elif parts[0] == "m" and current:
                methods.append((current, parts[1], parts[2],
                                parts[3] if len(parts) > 3 else parts[2],
                                parts[4] if len(parts) > 4 else parts[2]))
            elif parts[0] == "f" and current:
                fields.append((current, parts[1], parts[2],
                               parts[3] if len(parts) > 3 else parts[2],
                               parts[4] if len(parts) > 4 else parts[2]))
    return classes, methods, fields


# ---------------------------------------------------------------------- class hierarchy

def class_names(data):
    """(class name, super name) from a class file, both in `a/b/C` form."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None, None
    count = int.from_bytes(data[8:10], "big")
    pool = {}
    index = 1
    pos = 10
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:                      # Utf8
            length = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
            pool[index] = data[pos:pos + length].decode("utf-8", "replace")
            pos += length
        elif tag in (7, 8, 16, 19, 20):   # Class / String / MethodType / Module / Package
            pool[index] = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
        elif tag == 15:                   # MethodHandle
            pos += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):               # Long / Double take two slots
            pos += 8
            index += 1
        else:
            return None, None
        index += 1
    this_index = int.from_bytes(data[pos + 2:pos + 4], "big")
    super_index = int.from_bytes(data[pos + 4:pos + 6], "big")
    # Interfaces are read too: `getTopY` is declared on HeightLimitView and World only
    # implements it, so the superclass alone would lose that rename.
    count_interfaces = int.from_bytes(data[pos + 6:pos + 8], "big")
    interfaces = []
    for i in range(count_interfaces):
        at = pos + 8 + i * 2
        if at + 2 > len(data):
            break
        index = int.from_bytes(data[at:at + 2], "big")
        name = pool.get(pool.get(index)) if index else None
        if name:
            interfaces.append(name)
    this_name = pool.get(pool.get(this_index)) if this_index else None
    super_name = pool.get(pool.get(super_index)) if super_index else None
    return this_name, super_name, interfaces


def read_hierarchy(jar):
    """Mojmap name -> parent Mojmap names, read from the class files (the tables have no tree)."""
    import zipfile
    out = {}
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if not entry.endswith(".class") or entry.startswith("META-INF"):
                continue
            name, parent, interfaces = class_names(archive.read(entry))
            if name:
                # Everything in the tables uses dots; class files use slashes.
                parents = [parent] if parent else []
                out[name.replace("/", ".")] = [p.replace("/", ".") for p in parents + interfaces]
    return out


def find_jar(mc):
    """The Minecraft jars in the loom cache, remapped with the current mappings.

    A project can hold both a Yarn and a Mojmap copy, so the newest by mtime wins.
    """
    # loom keeps its cache under the root project's .gradle, shared across cells.
    base = os.path.join(ROOT, ".gradle", "loom-cache", "minecraftMaven",
                        "net", "minecraft")
    found = []
    for kind in ("minecraft-common-*", "minecraft-clientOnly-*"):
        candidates = [p for p in glob.glob(os.path.join(base, kind, "*", "*.jar"))
                      if "sources" not in p and "javadoc" not in p]
        if candidates:
            found.append(max(candidates, key=os.path.getmtime))
    return found


def expand_inherited(methods, fields, yarn_to_mojang, hierarchy, warn):
    """Attaches member renames from the parent chain to the class itself.

    Yarn records a method name on its declaring class: `sendFeedback` lives on
    `ServerCommandSource`, not on `ServerPlayerEntity`, while the source writes
    `player.sendFeedback(...)` and imports only the latter.
    """
    to_yarn = {mojang: yarn for yarn, mojang in yarn_to_mojang.items()}
    by_owner = {}
    for (owner, old), new in methods.items():
        by_owner.setdefault(owner, {})[old] = new
    by_owner_fields = {}
    for (owner, old), new in fields.items():
        by_owner_fields.setdefault(owner, {})[old] = new

    out_methods = dict(methods)
    out_fields = dict(fields)
    conflicts = 0
    for yarn_class, mojang_class in yarn_to_mojang.items():
        chain = []
        pending = [mojang_class]
        seen = set()
        while pending and len(chain) < 64:
            current = pending.pop(0)
            if not current or current in seen:
                continue
            seen.add(current)
            chain.append(current)
            pending.extend(hierarchy.get(current, []))
        # Nearest wins: the chain is BFS order, so the first match is the most specific. Requiring
        # every ancestor to agree would drop hundreds of real renames.
        merged = {}
        for mojang_ancestor in chain:
            dotted = mojang_ancestor.replace("/", ".")
            yarn_ancestor = to_yarn.get(dotted, dotted)
            for old, new in by_owner.get(yarn_ancestor, {}).items():
                merged.setdefault(old, new)
        for old, new in merged.items():
            if new is None:
                continue
            key = (yarn_class, old)
            if key not in out_methods:
                out_methods[key] = new
        # Fields are not expanded: a field has only a name, so any ancestor field called AIR
        # would overwrite Blocks.AIR. Static fields are written fully qualified anyway.
        pass
    warn("层次展开：就近优先，%d 处祖先歧义按最近的一条算" % conflicts)
    return out_methods, out_fields


# --------------------------------------------------------------------------- main flow

def catalog_version(mc, key):
    """One value from [versions] in gradle/catalogs/<mc>.toml, or None.

    tomllib exists from Python 3.11, so there is a line-by-line fallback: these files only hold
    `key = "value"` pairs, which is enough to keep older interpreters working.
    """
    path = os.path.join(ROOT, "gradle", "catalogs", mc + ".toml")
    if not os.path.isfile(path):
        return None
    try:
        import tomllib
    except ImportError:
        tomllib = None
    if tomllib is not None:
        with open(path, "rb") as handle:
            return tomllib.load(handle).get("versions", {}).get(key)
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            name, sep, value = line.partition("=")
            if sep and name.strip() == key:
                return value.strip().strip('"')
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mc", required=True)
    parser.add_argument("--yarn", default=None,
                        help="Yarn 版本号；省略时从 gradle/catalogs/<mc>.toml 读")
    parser.add_argument("--out", default=None)
    parser.add_argument("--no-hierarchy", action="store_true",
                        help="跳过继承展开（只按声明类出表，成员改名会漏掉继承来的那些）")
    args = parser.parse_args()

    yarn = args.yarn
    if yarn is None:
        yarn = catalog_version(args.mc, "yarn")
    if yarn is None:
        raise SystemExit("没有 --yarn，gradle/catalogs/%s.toml 里也没有 yarn" % args.mc)

    os.makedirs(CACHE, exist_ok=True)
    detail = version_json(args.mc)
    paths = []
    for key in ("client_mappings", "server_mappings"):
        entry = detail["downloads"].get(key)
        if entry:
            paths.append(download(entry["url"], os.path.join(CACHE, "%s-%s" % (args.mc, key + ".txt"))))

    # client.txt and server.txt give the same official class different obfuscated names, so each
    # file is indexed on its own; one shared dict would keep only the file parsed last.
    method_index = {}
    field_index = {}
    obf_to_official = {}
    ambiguous = set()

    for path in paths:
        official_to_obf, members = parse_mojang([path])
        methods, fields = mojang_member_index(official_to_obf, members)
        for key, value in methods.items():
            if key in method_index and method_index[key] != value:
                ambiguous.add(key)
            else:
                method_index[key] = value
        for key, value in fields.items():
            if key in field_index and field_index[key] != value:
                ambiguous.add(key)
            else:
                field_index[key] = value
        for official, obf in official_to_obf.items():
            if obf in obf_to_official and obf_to_official[obf] != official:
                ambiguous.add(obf)
            else:
                obf_to_official[obf] = official

    for key in ambiguous:
        method_index.pop(key, None)
        field_index.pop(key, None)
        obf_to_official.pop(key, None)
    print("  官方映射：%d 个类、%d 个方法、%d 个字段（%d 处两侧冲突已丢弃）"
          % (len(obf_to_official), len(method_index), len(field_index), len(ambiguous)))

    tiny = yarn_tiny(args.mc, yarn)
    classes, methods, fields = parse_tiny(tiny)
    print("  Yarn 映射：%d 个类、%d 个方法、%d 个字段" % (len(classes), len(methods), len(fields)))

    # ---- join Yarn name -> official name before the hierarchy pass
    obf_to_yarn = {obf: named.replace("/", ".") for obf, _intermediary, named in classes}
    yarn_methods = {}
    for obf_class, desc, obf_name, _intermediary, named in methods:
        yarn_class = obf_to_yarn.get(obf_class)
        if yarn_class is None:
            continue
        target = method_index.get((obf_class, obf_name, desc))
        if target is not None and target != named:
            yarn_methods[(yarn_class, named)] = target

    yarn_fields = {}
    for obf_class, _desc, obf_name, _intermediary, named in fields:
        yarn_class = obf_to_yarn.get(obf_class)
        if yarn_class is None:
            continue
        target = field_index.get((obf_class, obf_name))
        if target is not None and target != named:
            yarn_fields[(yarn_class, named)] = target

    print("  接合：%d 个方法、%d 个字段改了名" % (len(yarn_methods), len(yarn_fields)))

    # Hierarchy expansion: member renames from the parent chain are attached to the subclass
    # too, otherwise `player.sendFeedback(...)` never resolves.
    if not args.no_hierarchy:
        jars = find_jar(args.mc)
        if not jars:
            print("  找不到 Minecraft 包，跳过层次展开（成员改名会漏掉继承来的那些）")
        else:
            hierarchy = {}
            for jar in jars:
                hierarchy.update(read_hierarchy(jar))
            print("  类层次：%d 个类（来自 %d 个包）" % (len(hierarchy), len(jars)))
            yarn_to_mojang = {named.replace("/", "."): obf_to_official[obf]
                              for obf, _i, named in classes if obf in obf_to_official}
            yarn_methods, yarn_fields = expand_inherited(
                yarn_methods, yarn_fields, yarn_to_mojang, hierarchy, print)
            print("  展开后：%d 个方法、%d 个字段" % (len(yarn_methods), len(yarn_fields)))

    out_path = args.out or os.path.join(ROOT, "build", "mojmap", "yarn-mojmap-%s.tsv" % args.mc)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    renamed_classes = 0
    unmapped = 0
    with open(out_path, "w", encoding="utf-8") as out:
        out.write("# Yarn → Mojmap（%s / yarn %s）\n" % (args.mc, yarn))
        for obf, _intermediary, named in classes:
            official = obf_to_official.get(obf)
            if official is None:
                unmapped += 1
                continue
            yarn_name = named.replace("/", ".")
            if yarn_name == official:
                continue
            out.write("class\t%s\t%s\n" % (yarn_name, official))
            renamed_classes += 1

        # Methods and fields are written as Yarn class -> Yarn name -> official name, without a
        # descriptor: the apply step looks them up by name.
        for (yarn_class, named), target in sorted(yarn_methods.items()):
            out.write("method\t%s\t%s\t%s\n" % (yarn_class, named, target))
        for (yarn_class, named), target in sorted(yarn_fields.items()):
            out.write("field\t%s\t%s\t%s\n" % (yarn_class, named, target))

    print("  写出 %s" % out_path)
    print("  改了名的：%d 个类、%d 个方法、%d 个字段（%d 个类在官方映射里找不到）"
          % (renamed_classes, len(yarn_methods), len(yarn_fields), unmapped))


if __name__ == "__main__":
    main()
