#!/usr/bin/env python3
"""Checks loadout content per cell: which items a version can actually offer.

`ItemKind.available()` asks the runtime registry, which leaves "does 1.21.1 have a spear"
unanswerable offline. The data comes from two places: `scripts/content-manifest.tsv` holds version
thresholds, and `ItemKind` / `Material` / `LoadoutCatalog` hold the catalog. The script multiplies
the two, so a wrong threshold or a missing catalog entry shows up here.

Four checks: layering, empty pages, language keys, and `--verify-jar` against a real game jar.

Usage:
    python scripts/check-content.py 1.21.1-fabric          # one cell
    python scripts/check-content.py 1.20.1-forge --verbose
    python scripts/check-content.py --all                  # every cell
    python scripts/check-content.py 1.21.1-fabric --verify-jar <client jar>

Exit code: 0 pass, 1 problems, 2 bad arguments or environment.
"""
import argparse
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Sources live at the repository root: src/ is shared and a cell only decides how to compile it.
SRC_ROOT = ROOT
LOADOUT = os.path.join(SRC_ROOT, "src", "main", "java", "com", "leipishu", "pvpocket", "loadout")
LANG_DIR = os.path.join(SRC_ROOT, "src", "main", "resources", "assets", "pvpocket", "lang")
MANIFEST = os.path.join(ROOT, "scripts", "content-manifest.tsv")

# Versions in chronological order: threshold comparison and the --all column order rely on it.
VERSIONS = ["1.20.1", "1.21.1", "1.21.11", "26.1", "26.2", "26.3"]

# Loader dimension. 1.20.1 is Forge (FG6 pins Gradle 8), everything else NeoForge. Keeping it as
# data rejects an impossible cell such as 1.20.1-neoforge up front instead of at build time.
LOADERS = {"1.20.1": ("fabric", "forge")}
DEFAULT_LOADERS = ("fabric", "neoforge")

# Kinds with no item id of their own: an entry's variant names them, so available() is always true.
VARIANT_KINDS = {"FOOD", "BLOCK", "POTION"}



def version_key(text):
    """`1.21.11` -> (1, 21, 11). Missing parts count as 0, so `1.21` equals `1.21.0`."""
    parts = []
    for piece in text.split("."):
        match = re.match(r"\d+", piece)
        if not match:
            raise ValueError("版本号看不懂：%s" % text)
        parts.append(int(match.group(0)))
    return tuple(parts)


def before(a, b):
    """True when a is strictly earlier than b."""
    ka, kb = version_key(a), version_key(b)
    width = max(len(ka), len(kb))
    return ka + (0,) * (width - len(ka)) < kb + (0,) * (width - len(kb))



def read(path):
    # utf-8-sig: editors and PowerShell redirection add a BOM, and a BOM makes the first line
    # neither a comment nor data, which shows up as a bogus missing-column error.
    with open(path, encoding="utf-8-sig") as handle:
        return handle.read()


def parse_item_kinds():
    """`SWORD(Page.WEAPON, "sword", true, 7),` -> [{'name','page','id','materialised'}]"""
    text = read(os.path.join(LOADOUT, "ItemKind.java"))
    pattern = re.compile(
        r'^\s*([A-Z][A-Z_0-9]*)\(\s*Page\.([A-Z_]+)\s*,\s*"([^"]+)"\s*,\s*(true|false)\s*,\s*\d+\s*\)',
        re.M)
    kinds = [{"name": m.group(1), "page": m.group(2), "id": m.group(3),
              "materialised": m.group(4) == "true"} for m in pattern.finditer(text)]
    if not kinds:
        raise SystemExit("从 ItemKind.java 里解析不出枚举常量——格式变了？")
    return kinds


def parse_materials():
    """`WOOD("wood", "wooden", 0),` -> [{'name','id','prefix'}]"""
    text = read(os.path.join(LOADOUT, "Material.java"))
    pattern = re.compile(
        r'^\s*([A-Z][A-Z_0-9]*)\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*\d+\s*\)', re.M)
    materials = [{"name": m.group(1), "id": m.group(2), "prefix": m.group(3)}
                 for m in pattern.finditer(text)]
    if not materials:
        raise SystemExit("从 Material.java 里解析不出枚举常量——格式变了？")
    return materials


def parse_variants():
    """`food("minecraft:golden_apple")` / `block(...)` / `effect(...)` -> [(kind, id, key)]"""
    text = read(os.path.join(LOADOUT, "LoadoutCatalog.java"))
    out = []
    for category, prefix in (("food", "pvpocket.option.food."),
                             ("block", "pvpocket.option.block."),
                             ("effect", "pvpocket.option.effect.")):
        for match in re.finditer(category + r'\(\s*"minecraft:([a-z0-9_]+)"', text):
            out.append((category, match.group(1), prefix + match.group(1)))
    if not out:
        raise SystemExit("从 LoadoutCatalog.java 里解析不出变体——格式变了？")
    return out



def load_manifest():
    """item id -> (first version, basis). Ids not listed are assumed to exist in 1.20.1.

    A first version of `never` means the version never had it: vanilla has no wooden or stone
    armour, and `Material` is orthogonal to kind, so `wooden_helmet` exists in the product of
    `ItemKind` and `Material` but not in the game.
    """
    if not os.path.isfile(MANIFEST):
        raise SystemExit("找不到 %s" % MANIFEST)
    table = {}
    with open(MANIFEST, encoding="utf-8-sig") as handle:
        for lineno, line in enumerate(handle, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                raise SystemExit("%s:%d 少一列（要 item_id 与起始版本）" % (MANIFEST, lineno))
            item, since = parts[0].strip(), parts[1].strip()
            basis = parts[2].strip() if len(parts) > 2 else "declared"
            if since != "never":
                try:
                    version_key(since)
                except ValueError as error:
                    raise SystemExit("%s:%d 的版本号写错：%s" % (MANIFEST, lineno, error))
            table[item] = (since, basis)
    return table


def item_exists(item, version, manifest):
    """True when the id exists in `version`."""
    since = manifest.get(item, ("1.20.1", "verified"))[0]
    if since == "never":
        return False
    return not before(version, since)


def item_path(kind, material):
    """Same rule as `ItemKind.itemPath`: materialised kinds join <material>_<kind>."""
    if kind["materialised"] and material is not None:
        return "%s_%s" % (material["prefix"], kind["id"])
    return kind["id"]


def offered_kinds(kinds, materials, version, manifest):
    out = []
    for kind in kinds:
        if kind["name"] in VARIANT_KINDS:
            out.append(kind)          # named by a variant, so always offered
            continue
        if not kind["materialised"]:
            if item_exists(kind["id"], version, manifest):
                out.append(kind)
            continue
        if any(item_exists(item_path(kind, material), version, manifest)
               for material in materials):
            out.append(kind)
    return out


def offered_materials(kind, materials, version, manifest):
    if not kind["materialised"]:
        return []
    return [material for material in materials
            if item_exists(item_path(kind, material), version, manifest)]



class Report:
    def __init__(self):
        self.problems = []

    def bad(self, message):
        self.problems.append(message)


def check_layers(report, kinds, materials, manifest):
    """Layering: the product of thresholds and catalog has to match intent.

    These assertions do not read the manifest, so a wrong manifest is caught here.
    """
    for version in VERSIONS:
        available = {kind["name"] for kind in offered_kinds(kinds, materials, version, manifest)}

        # Mace and wind charge exist from 1.21 (Tricky Trials).
        for name, label in (("MACE", "重锤"), ("WIND_CHARGE", "风弹")):
            if name not in {kind["name"] for kind in kinds}:
                continue
            if before(version, "1.21") and name in available:
                report.bad("%s 不该提供%s（1.21 才有）" % (version, label))
            if not before(version, "1.21") and name not in available:
                report.bad("%s 应该提供%s（1.21 起有）" % (version, label))

        # Spear exists from 1.21.11 (Mounts of Mayhem).
        if "SPEAR" in {kind["name"] for kind in kinds}:
            if before(version, "1.21.11") and "SPEAR" in available:
                report.bad("%s 不该提供长矛（1.21.11 才有）" % version)
            if not before(version, "1.21.11") and "SPEAR" not in available:
                report.bad("%s 应该提供长矛（1.21.11 起有）" % version)

        # Copper gear exists from 1.21.9 (The Copper Age); copper is a material, not a kind.
        has_copper = any(item_exists(item_path(kind, material), version, manifest)
                         for kind in kinds if kind["materialised"]
                         for material in materials if material["id"] == "copper")
        if before(version, "1.21.9") and has_copper:
            report.bad("%s 不该有铜制装备（1.21.9 才有）" % version)
        if not before(version, "1.21.9") and not has_copper:
            report.bad("%s 应该有铜制装备（1.21.9 起有）" % version)


def check_pages(report, kinds, materials, manifest):
    """No cell and no page may be empty."""
    pages = sorted({kind["page"] for kind in kinds})
    for version in VERSIONS:
        available = offered_kinds(kinds, materials, version, manifest)
        for page in pages:
            if not [kind for kind in available if kind["page"] == page]:
                report.bad("%s 的 %s 页一个种类都没有" % (version, page))


def check_lang(report, kinds, materials, variants):
    """Every catalog entry needs a translation key, or the panel shows the raw key."""
    with open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8-sig") as handle:
        keys = set(json.load(handle))

    for kind in kinds:
        for key in ("pvpocket.item." + kind["id"], "pvpocket.item.page." + kind["page"].lower()):
            if key not in keys:
                report.bad("语言键缺失：%s（种类 %s）" % (key, kind["name"]))
    for material in materials:
        key = "pvpocket.material." + material["id"]
        if key not in keys:
            report.bad("语言键缺失：%s（材质 %s）" % (key, material["name"]))
    for category, path, key in variants:
        if key not in keys:
            report.bad("语言键缺失：%s（%s 变体 %s）" % (key, category, path))


def jar_item_ids(jar_path):
    """Item ids from the client jar's en_us.json.

    Both key spaces are read: vanilla keeps block items under `block.minecraft.*` (tnt,
    oak_planks, white_wool) and those are absent from `item.minecraft.*`.
    """
    with zipfile.ZipFile(jar_path) as archive:
        try:
            raw = archive.read("assets/minecraft/lang/en_us.json")
        except KeyError:
            raise SystemExit("%s 里没有 assets/minecraft/lang/en_us.json" % jar_path)
    ids = set()
    for key in json.loads(raw.decode("utf-8")):
        for prefix in ("item.minecraft.", "block.minecraft."):
            if not key.startswith(prefix):
                continue
            path = key[len(prefix):]
            # Potions have variant keys such as `splash_potion.effect.speed`, which are not
            # registry entries.
            if "." not in path:
                ids.add(path)
    if len(ids) < 500:
        raise SystemExit("%s 里只读出 %d 个物品 id，jar 不对？" % (jar_path, len(ids)))
    return ids


def check_jar(report, jar_path, version, kinds, materials, variants, manifest):
    """Reconciles the manifest against a real jar."""
    ids = jar_item_ids(jar_path)
    relevant = []
    for kind in kinds:
        if kind["name"] in VARIANT_KINDS:
            continue
        if kind["materialised"]:
            relevant.extend((item_path(kind, material), kind, material) for material in materials)
        else:
            relevant.append((kind["id"], kind, None))

    checked = 0
    for item, kind, material in relevant:
        declared = item_exists(item, version, manifest)
        actual = item in ids
        checked += 1
        if declared and not actual:
            report.bad("清单说 %s 在 %s 有，jar 里没有（%s）"
                       % (item, version, kind["name"] if material is None
                          else "%s / %s" % (kind["name"], material["name"])))
        elif actual and not declared:
            since = manifest.get(item, ("1.20.1", ""))[0]
            report.bad("jar 里 %s 存在，但清单写的是 %s 起才有" % (item, since))
    # Variants are checked too: players can pick them.
    for category, path, _ in variants:
        if category == "effect":
            continue          # effects are not items
        declared = item_exists(path, version, manifest)
        actual = path in ids
        checked += 1
        if declared and not actual:
            report.bad("清单说 %s 在 %s 有，jar 里没有（%s 变体）" % (path, version, category))
        elif actual and not declared:
            report.bad("jar 里 %s 存在，但清单写的是 %s 起才有" % (path, manifest[path][0]))
    print("[content] 对着 jar 核了 %d 个物品 id（jar 共 %d 个）" % (checked, len(ids)))

    pending = sorted(item for item, (_, basis) in manifest.items() if basis != "verified")
    if pending:
        print("[content] 清单里还有 %d 条 declared（未核对）：%s"
              % (len(pending), " ".join(pending[:8]) + (" …" if len(pending) > 8 else "")))



def print_cell(version, loader, kinds, materials, variants, manifest, verbose):
    print("[content] %s-%s —— MC %s / %s" % (version, loader, version, loader))
    available = offered_kinds(kinds, materials, version, manifest)
    pages = sorted({kind["page"] for kind in kinds})
    for page in pages:
        names = [kind["id"] for kind in available if kind["page"] == page]
        print("  %-8s %s" % (page, " ".join(names) if names else "（空）"))

    if verbose:
        for kind in available:
            if not kind["materialised"]:
                continue
            names = [material["id"] for material in offered_materials(kind, materials, version,
                                                                     manifest)]
            print("  %-8s 材质：%s" % (kind["id"], " ".join(names)))

    offered = {kind["name"] for kind in available}
    fresh = []
    for kind in kinds:
        if kind["name"] not in offered:
            continue
        items = ([item_path(kind, material) for material in materials]
                 if kind["materialised"] else [item_path(kind, None)])
        since = [manifest.get(item, ("1.20.1", ""))[0] for item in items]
        since = [value for value in since if value != "never"]
        if not since:
            continue
        first = min(since, key=version_key)
        if first != "1.20.1":
            fresh.append("%s（%s 起）" % (kind["id"], first))
    if fresh:
        print("  门槛     %s" % "、".join(fresh))

    counted = sum(1 for kind in available if not kind["materialised"])
    counted += sum(len(offered_materials(kind, materials, version, manifest))
                   for kind in available if kind["materialised"])
    print("  物品 id  %d 个（不含 %d 个变体种类）"
          % (counted, sum(1 for kind in available if kind["name"] in VARIANT_KINDS)))
    if variants:
        print("  变体     food %d、block %d、effect %d"
              % tuple(len([1 for c, _, _ in variants if c == name])
                      for name in ("food", "block", "effect")))


def print_matrix(kinds, materials, manifest):
    """One row per content kind, one column per version."""
    names = [kind["id"] for kind in kinds]
    width = max(len(name) for name in names) + 2
    header = " " * width + "".join("%-10s" % version for version in VERSIONS)
    print(header)
    for kind in kinds:
        row = ("%-*s" % (width, kind["id"]))
        for version in VERSIONS:
            offered = {k["name"] for k in offered_kinds(kinds, materials, version, manifest)}
            row += "%-10s" % ("有" if kind["name"] in offered else "—")
        print(row)
    print()
    print("材质可用性（按种类，列同上）：")
    for kind in kinds:
        if not kind["materialised"]:
            continue
        row = ("%-*s" % (width, kind["id"]))
        for version in VERSIONS:
            count = len(offered_materials(kind, materials, version, manifest))
            row += "%-10s" % ("%d 种" % count if count else "—")
        print(row)



def parse_cell(text):
    if "-" not in text:
        raise SystemExit("格子要写成 <版本>-<加载器>，例如 1.21.1-fabric；收到 %r" % text)
    version, loader = text.rsplit("-", 1)
    if version not in VERSIONS:
        raise SystemExit("未知版本 %r，可选：%s" % (version, " ".join(VERSIONS)))
    allowed = LOADERS.get(version, DEFAULT_LOADERS)
    if loader not in allowed:
        raise SystemExit("%s 的加载器只能是 %s，收到 %r"
                         % (version, " / ".join(allowed), loader))
    return version, loader


def main():
    parser = argparse.ArgumentParser(description="按格子核对配装内容")
    parser.add_argument("cell", nargs="?", help="格子，例如 1.21.1-fabric")
    parser.add_argument("--all", action="store_true", help="打印 12 格总表")
    parser.add_argument("--verbose", action="store_true", help="列出每个种类的材质")
    parser.add_argument("--verify-jar", metavar="PATH", help="用真实客户端 jar 核对清单")
    options = parser.parse_args()

    kinds = parse_item_kinds()
    materials = parse_materials()
    variants = parse_variants()
    manifest = load_manifest()

    report = Report()
    check_layers(report, kinds, materials, manifest)
    check_pages(report, kinds, materials, manifest)
    check_lang(report, kinds, materials, variants)

    if options.all:
        print_matrix(kinds, materials, manifest)
    if options.cell:
        version, loader = parse_cell(options.cell)
        print_cell(version, loader, kinds, materials, variants, manifest, options.verbose)
    if options.verify_jar:
        if not options.cell:
            raise SystemExit("--verify-jar 要配一个格子，才知道拿哪个版本比")
        version, _ = parse_cell(options.cell)
        if not os.path.isfile(options.verify_jar):
            raise SystemExit("找不到 jar：%s" % options.verify_jar)
        check_jar(report, options.verify_jar, version, kinds, materials, variants, manifest)

    if report.problems:
        print("  ! %d 处" % len(report.problems))
        for message in report.problems:
            print("      %s" % message)
        print("[content] 失败")
        return 1
    print("[content] 全部通过（%d 个种类、%d 种材质、%d 个变体）"
          % (len(kinds), len(materials), len(variants)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
