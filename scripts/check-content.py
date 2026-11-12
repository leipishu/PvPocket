#!/usr/bin/env python3
"""按格子核对配装内容：这一版到底能给出哪些物品。

为什么需要这个：`ItemKind.available()` 问的是**运行时注册表**，那是对的设计——版本差异不该
硬编码进枚举。代价是「1.21.1 到底有没有长矛」在离线状态下无处可查，而移植时最怕的正是这一格：
面板上多出一行、点下去给不到东西。AGENTS.md 里那条「高版本加长矛、低版本去重锤与风弹」的
要求，只有变成可执行的检查才算数。

数据分两处，各管一段：

  * `scripts/content-manifest.tsv` —— 版本门槛（哪个 id 从哪一版开始存在）。
  * `ItemKind` / `Material` / `LoadoutCatalog` —— 目录本身（有哪些种类、材质、变体）。

脚本不重复声明任何一边，只把两边乘起来，所以目录加一项、门槛写错，都会在这里显形。

四类检查：

  1. **分层**：版本门槛与目录的交叉结果必须符合意图——1.20.1 不得有重锤/风弹/长矛/铜制，
     1.21 起必须有重锤与风弹，1.21.11 起必须有长矛。
  2. **空页**：任何一格、任何一页都必须至少有一个可用种类。整页空着是最糟的失败方式——
     玩家看到的是四个标签页里有一个什么都没有。
  3. **语言键**：每个可用种类、材质、变体都要有 `pvpocket.item|material|option.*` 的键。
  4. **jar 核对**（`--verify-jar`）：拿真实的游戏 jar 比一遍，把清单里 `declared` 的条目
     变成可核实的结论。移植每一格时都该跑一次。

用法：
    python scripts/check-content.py 1.21.1-fabric          # 一格的清单
    python scripts/check-content.py 1.20.1-forge --verbose
    python scripts/check-content.py --all                  # 12 格总表
    python scripts/check-content.py 1.21.1-fabric --verify-jar <客户端 jar>

退出码：0 通过；1 有问题；2 参数或环境错误。
"""
import argparse
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT = os.path.join(ROOT, "fabric-1.21.1")
LOADOUT = os.path.join(PROJECT, "src", "main", "java", "com", "leipishu", "pvpocket", "loadout")
LANG_DIR = os.path.join(PROJECT, "src", "main", "resources", "assets", "pvpocket", "lang")
MANIFEST = os.path.join(ROOT, "scripts", "content-manifest.tsv")

# 版本维按时间顺序。顺序本身是数据：门槛比较靠它，`--all` 的列序也靠它。
VERSIONS = ["1.20.1", "1.21.1", "1.21.11", "26.1", "26.2", "26.3"]

# 加载器维。1.20.1 是 Forge（FG6 绑 Gradle 8），其余是 NeoForge——这个分工是方案里定死的，
# 写成数据是为了让 `1.20.1-neoforge` 这种不存在的组合当场被拒，而不是等到构建时才发现。
LOADERS = {"1.20.1": ("fabric", "forge")}
DEFAULT_LOADERS = ("fabric", "neoforge")

# 目录里没有独立物品 id 的种类：它们由条目的 variant 指名，`available()` 恒真。
VARIANT_KINDS = {"FOOD", "BLOCK", "POTION"}


# --------------------------------------------------------------------------------------------
# 版本比较
# --------------------------------------------------------------------------------------------

def version_key(text):
    """`1.21.11` -> (1, 21, 11)。缺的位按 0 补，所以 `1.21` 与 `1.21.0` 相等。"""
    parts = []
    for piece in text.split("."):
        match = re.match(r"\d+", piece)
        if not match:
            raise ValueError("版本号看不懂：%s" % text)
        parts.append(int(match.group(0)))
    return tuple(parts)


def before(a, b):
    """a 严格早于 b。"""
    ka, kb = version_key(a), version_key(b)
    width = max(len(ka), len(kb))
    return ka + (0,) * (width - len(ka)) < kb + (0,) * (width - len(kb))


# --------------------------------------------------------------------------------------------
# 从 Java 读目录
# --------------------------------------------------------------------------------------------

def read(path):
    # utf-8-sig：Windows 上的编辑器与 PowerShell 重定向都会悄悄加 BOM，而带 BOM 的第一行
    # 既不是注释也不是数据，会以一个莫名其妙的「少一列」报出来。
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
    """`food("minecraft:golden_apple")` / `block(...)` / `effect(...)` -> [(类别, id, 键前缀)]"""
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


# --------------------------------------------------------------------------------------------
# 清单
# --------------------------------------------------------------------------------------------

def load_manifest():
    """item id -> (起始版本, 依据)。没列出的 id 视为 1.20.1 就有。

    起始版本写 `never` 表示**这个版本从来没有过**。这一列不能只记"新增"：原版就没有木制与
    石制护甲，而 `Material` 是个与种类正交的枚举，所以 `wooden_helmet` 这种组合在
    `ItemKind × Material` 的叉积里存在、在游戏里不存在。运行时由 `available()` 问注册表
    挡掉，离线时只能靠这张表说清楚。
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
    """这个 id 在 `version` 里存不存在。"""
    since = manifest.get(item, ("1.20.1", "verified"))[0]
    if since == "never":
        return False
    return not before(version, since)


def item_path(kind, material):
    """与 `ItemKind.itemPath` 同一条规则：材质化的拼 `<material>_<kind>`，否则就是 kind 本身。"""
    if kind["materialised"] and material is not None:
        return "%s_%s" % (material["prefix"], kind["id"])
    return kind["id"]


def offered_kinds(kinds, materials, version, manifest):
    out = []
    for kind in kinds:
        if kind["name"] in VARIANT_KINDS:
            out.append(kind)          # 由 variant 指名，恒可用
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


# --------------------------------------------------------------------------------------------
# 检查
# --------------------------------------------------------------------------------------------

class Report:
    def __init__(self):
        self.problems = []

    def bad(self, message):
        self.problems.append(message)


def check_layers(report, kinds, materials, manifest):
    """分层：门槛与目录的交叉结果必须符合意图。这些断言独立于清单，所以清单写错会被抓住。"""
    for version in VERSIONS:
        available = {kind["name"] for kind in offered_kinds(kinds, materials, version, manifest)}

        # 重锤与风弹是 1.21（Tricky Trials）才有的。低版本给了就是一行空头承诺。
        for name, label in (("MACE", "重锤"), ("WIND_CHARGE", "风弹")):
            if name not in {kind["name"] for kind in kinds}:
                continue
            if before(version, "1.21") and name in available:
                report.bad("%s 不该提供%s（1.21 才有）" % (version, label))
            if not before(version, "1.21") and name not in available:
                report.bad("%s 应该提供%s（1.21 起有）" % (version, label))

        # 长矛是 1.21.11（Mounts of Mayhem）才有的，也是这次移植最要紧的一项。
        if "SPEAR" in {kind["name"] for kind in kinds}:
            if before(version, "1.21.11") and "SPEAR" in available:
                report.bad("%s 不该提供长矛（1.21.11 才有）" % version)
            if not before(version, "1.21.11") and "SPEAR" not in available:
                report.bad("%s 应该提供长矛（1.21.11 起有）" % version)

        # 铜制是 1.21.9（The Copper Age）才有的。配装目录里它是材质，不是种类。
        has_copper = any(item_exists(item_path(kind, material), version, manifest)
                         for kind in kinds if kind["materialised"]
                         for material in materials if material["id"] == "copper")
        if before(version, "1.21.9") and has_copper:
            report.bad("%s 不该有铜制装备（1.21.9 才有）" % version)
        if not before(version, "1.21.9") and not has_copper:
            report.bad("%s 应该有铜制装备（1.21.9 起有）" % version)


def check_pages(report, kinds, materials, manifest):
    """任何一格、任何一页都不能空。"""
    pages = sorted({kind["page"] for kind in kinds})
    for version in VERSIONS:
        available = offered_kinds(kinds, materials, version, manifest)
        for page in pages:
            if not [kind for kind in available if kind["page"] == page]:
                report.bad("%s 的 %s 页一个种类都没有" % (version, page))


def check_lang(report, kinds, materials, variants):
    """每个目录项都要有翻译键，否则面板上显示的是一串键名。"""
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
    """从客户端 jar 的 en_us.json 取物品 id。

    **两套键空间都要取。** 原版把方块类物品的键放在 `block.minecraft.*`（`tnt`、`oak_planks`、
    `white_wool` 都是），`item.minecraft.*` 里没有它们——只读前者会得出「原版没有 TNT」这种
    结论，而这正好是配装的方块页要用的东西。
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
            # 药水有 `splash_potion.effect.speed` 这种变体键，它描述的不是注册表里的一项。
            if "." not in path:
                ids.add(path)
    if len(ids) < 500:
        raise SystemExit("%s 里只读出 %d 个物品 id，jar 不对？" % (jar_path, len(ids)))
    return ids


def check_jar(report, jar_path, version, kinds, materials, variants, manifest):
    """清单与真实 jar 对账。这是把 `declared` 变成结论的唯一手段。"""
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
    # 变体也核一遍：它们同样是玩家点得到的物品。
    for category, path, _ in variants:
        if category == "effect":
            continue          # 效果不是物品
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


# --------------------------------------------------------------------------------------------
# 输出
# --------------------------------------------------------------------------------------------

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
    """12 格总表：每行一个内容项，每列一个版本。"""
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


# --------------------------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------------------------

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
