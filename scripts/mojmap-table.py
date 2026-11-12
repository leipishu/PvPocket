#!/usr/bin/env python3
"""生成 Yarn → Mojmap（Mojang 官方映射）的对照表。

为什么要这张表：模组原本按 Yarn 命名写，而 26.1 起 Minecraft 不再混淆，官方名（Mojmap）成了
唯一不会过期的一套。要一次性把 148 个文件从 Yarn 改到 Mojmap，靠人肉逐个查是不现实的，靠编译器
报错又只能看到"找不到 World"而看不到"应该叫 Level"。

做法是把两份映射按**混淆名**接起来：

    Yarn  mappings.tiny   official(混淆) → intermediary → named(Yarn)
    Mojang client.txt     official(官方) → 混淆

两边都有混淆名，于是 Yarn 名和官方名就有了对应关系。client.txt 与 server.txt 各覆盖一半，
合并成并集（模组两端都要用）。

输出一份 TSV（只列名字确实变了的），供 apply-mojmap.py 使用：

    class   <Yarn 全限定名>  <Mojmap 全限定名>
    method  <Yarn 类>  <Yarn 名>  <Yarn 描述符>  <Mojmap 名>
    field   <Yarn 类>  <Yarn 名>  <Mojmap 名>

用法：
    scripts/mojmap-table.py --mc 1.21.1 --yarn 1.21.1+build.3
    scripts/mojmap-table.py --mc 26.3            # 26.x 起官方名就是运行时名，无需对照表

缓存：官方映射下载到 <repo>/build/mojmap-cache/，重复运行不再下载。
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


# --------------------------------------------------------------------------- Mojang 官方映射

PRIMITIVES = {"void", "boolean", "byte", "char", "short", "int", "long", "float", "double"}


def parse_mojang(paths):
    """返回 (官方类名→混淆名, 混淆类名→[(成员种类, 混淆名, 混淆描述符, 官方名)])。"""
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
                # 方法行有行号前缀：12:13:void foo(int) -> a
                if left[:1].isdigit():
                    left = left.split(":", 1)[1]
                    left = left.split(":", 1)[1] if left[:1].isdigit() else left
                if "(" in left:
                    ret, _, rest = left.partition(" ")
                    name, _, params = rest.partition("(")
                    params = params.rstrip(")")
                    members[obf_class].append(("method", obf_name, (ret, name, params), None))
                else:
                    # 字段：type name -> a
                    parts = left.split()
                    if len(parts) == 2:
                        members[obf_class].append(("field", obf_name, parts[0], parts[1]))
    return official_to_obf, members


def obf_descriptor(type_text, official_to_obf):
    """把官方源码里的类型写法翻成混淆描述符。数组与基本类型原样。"""
    dims = 0
    text = type_text.strip()
    while text.endswith("[]"):
        dims += 1
        text = text[:-2]
    if text in PRIMITIVES:
        code = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
                "int": "I", "long": "J", "float": "F", "double": "D"}[text]
    else:
        # 不在官方映射里的类型是**库类**（org.joml、com.mojang.authlib、java.* 之类）：Mojang 不混淆
        # 它们，所以混淆名就等于官方名。早期版本在这里返回 None，于是凡是参数里带 Vec3/Quaternionf
        # 的方法全被丢掉——13k 个方法映射不出来，症状是"改完名还有一半方法名是旧的"。
        obf = official_to_obf.get(text, text.replace(".", "/"))
        code = "L%s;" % obf
    return "[" * dims + code


def mojang_member_index(official_to_obf, members):
    """(混淆类, 混淆名, 混淆描述符) → 官方方法名；以及 (混淆类, 混淆名) → 官方字段名。"""
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
    """按逗号切参数，注意泛型里的逗号与内部类。官方映射里的参数已经是擦除后的源码类型。"""
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


# --------------------------------------------------------------------------- Yarn 映射

def yarn_tiny(mc, yarn):
    pattern = os.path.join(
        os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", mc,
        "net.fabricmc.yarn.%s.*" % yarn.replace("+", "."), "mappings.tiny")
    found = sorted(glob.glob(pattern))
    if not found:
        # 目录名的点号替换规则在不同版本上不完全一致，退一步模糊找
        found = sorted(glob.glob(os.path.join(
            os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", mc,
            "net.fabricmc.yarn.*", "mappings.tiny")))
    if not found:
        raise SystemExit("找不到 Yarn mappings.tiny（先在工程里跑一次 ./gradlew compileJava）")
    return found[-1]


def parse_tiny(path):
    """yarn tiny v2 → 类、方法、字段三组 (official, intermediary, named)。"""
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
            # tiny v2 用缩进表达层级，而且**注释也用 c 开头**：`\tc\tAn object that exists...` 是给
            # 上一个元素写的 javadoc，不是类。只看第一个字段会把注释当成类，`current` 于是变成一段
            # 散文，那个类后面的方法/字段全部挂到不存在的类上——Entity 就是这样丢掉的（458 个方法
            # 一个都没进表）。所以必须先量缩进，只认第 0 层的 c 和第 1 层的 m/f。
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


# --------------------------------------------------------------------------- 类层次

def class_names(data):
    """从 class 文件里取出 (类名, 父类名)，都是 `a/b/C` 形式。"""
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
        elif tag in (5, 6):               # Long / Double 占两个槽位
            pos += 8
            index += 1
        else:
            return None, None
        index += 1
    this_index = int.from_bytes(data[pos + 2:pos + 4], "big")
    super_index = int.from_bytes(data[pos + 4:pos + 6], "big")
    # 接口也要读：`getTopY` 声明在 HeightLimitView（接口）上，World 只是实现它。只看父类，
    # World 就永远拿不到这条改名，而源码里到处是 world.getTopY(...)。
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
    """Mojmap 名 → 父类 Mojmap 名。用 class 文件自己说话，不问映射表（映射表没有层次）。"""
    import zipfile
    out = {}
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.namelist():
            if not entry.endswith(".class") or entry.startswith("META-INF"):
                continue
            name, parent, interfaces = class_names(archive.read(entry))
            if name:
                # 统一成点号：表里全是点号，class 文件里全是斜杠，混着查一定查不到。
                parents = [parent] if parent else []
                out[name.replace("/", ".")] = [p.replace("/", ".") for p in parents + interfaces]
    return out


def find_jar(mc):
    """loom 缓存里**当前映射**重映射过的 Minecraft 包。

    同一个工程里可能同时留着 Yarn 版和 Mojmap 版（切映射时旧的不删），所以按修改时间取最新的一份，
    不能按名字猜。
    """
    # loom 的缓存挂在**根工程**的 .gradle 下（Loom 有意跨 cell 共享它），不是各 cell 自己一份。
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
    """把每个类的父类链上的成员改名也挂到它自己名下。

    Yarn 的方法名是**按声明类**记的：`sendFeedback` 声明在 `ServerCommandSource` 上，
    `ServerPlayerEntity` 自己没有这一条。源码里写的是 `player.sendFeedback(...)`，而文件只 import
    了 `ServerPlayerEntity`——按名字查表就查不到，改完名编译报"找不到 sendFeedback"。
    这就是"成员名改了但还有一半没改"的全部原因，所以层次必须展开。
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
        # 就近优先：链是按 BFS 排的，第一次见到的就是最具体的声明，之后祖先再说什么都不算。
        # 早期版本要求"所有祖先一致"，结果 1300 多处被判定为冲突而丢弃——`formatted` 就是被
        # ScoreboardEntry 的 formatValue 顶掉的，而源码里九成九用的是 Text.withStyle。
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
        # 字段**不做**层次展开。方法有描述符可以把"同名不同签"区分开，字段只有名字：祖先链上任何
        # 一个叫 AIR 的字段都会覆盖到 Blocks.AIR 上，改出来的东西看着像模像样但根本不存在。
        # 静态字段本来也是写全限定名的（Blocks.AIR），直接声明那一条就够；实例字段漏掉的由编译器报。
        pass
    warn("层次展开：就近优先，%d 处祖先歧义按最近的一条算" % conflicts)
    return out_methods, out_fields


# --------------------------------------------------------------------------- 主流程

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mc", required=True)
    parser.add_argument("--yarn", default=None, help="Yarn 版本号；省略时从 gradle.properties 读")
    parser.add_argument("--out", default=None)
    parser.add_argument("--no-hierarchy", action="store_true",
                        help="跳过继承展开（只按声明类出表，成员改名会漏掉继承来的那些）")
    args = parser.parse_args()

    yarn = args.yarn
    if yarn is None:
        properties = os.path.join(ROOT, "gradle.properties")
        with open(properties, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("yarn_mappings="):
                    yarn = line.split("=", 1)[1].strip()
    if yarn is None:
        raise SystemExit("没有 --yarn，gradle.properties 里也没有 yarn_mappings")

    os.makedirs(CACHE, exist_ok=True)
    detail = version_json(args.mc)
    paths = []
    for key in ("client_mappings", "server_mappings"):
        entry = detail["downloads"].get(key)
        if entry:
            paths.append(download(entry["url"], os.path.join(CACHE, "%s-%s" % (args.mc, key + ".txt"))))

    # 客户端与服务端**各有一份**混淆命名空间：同一个官方类在 client.txt 和 server.txt 里叫不同的
    # 混淆名，而合并 jar 里客户端类用前者、服务端专属类用后者。两份必须各自成表再取并集；先合成一个
    # official→obf 字典再取反，等于只留了后解析的那一份——37% 的类因此查不到官方名，方法只映射出
    # 三成。这个坑踩过一次，症状是"改完名还有一半方法名是旧的"。
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

    # ---- 先接合成「Yarn 类 → Yarn 名 → 官方名」两张表，再谈层次
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

    # 层次展开：把父类链上的成员改名也挂到子类名下，否则 `player.sendFeedback(...)` 这种
    # 调用（文件里只 import 了 ServerPlayerEntity）永远查不到。
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

        # 方法/字段按「Yarn 类 → Yarn 名 → 官方名」输出，不带描述符：同一个类里同名重载在两边
        # 都是同一个名字，带描述符只会让应用阶段没法按名字查。描述符只在上面做接合时用。
        for (yarn_class, named), target in sorted(yarn_methods.items()):
            out.write("method\t%s\t%s\t%s\n" % (yarn_class, named, target))
        for (yarn_class, named), target in sorted(yarn_fields.items()):
            out.write("field\t%s\t%s\t%s\n" % (yarn_class, named, target))

    print("  写出 %s" % out_path)
    print("  改了名的：%d 个类、%d 个方法、%d 个字段（%d 个类在官方映射里找不到）"
          % (renamed_classes, len(yarn_methods), len(yarn_fields), unmapped))


if __name__ == "__main__":
    main()
