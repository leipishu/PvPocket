#!/usr/bin/env python3
"""加载器边界检查：`src/main` 里不许出现加载器 API。

AGENTS.md §5c 写了这条约定，但约定只有变成可执行的检查才算数。这条线决定了移植一格的代价：
守住了就是「写一个 200 行的实现类」，破了就是「重写一遍」。而它破掉的方式总是很温和——
某个工具方法「就一行 Fabric 调用」，顺手写进业务类里，编译照过，等移植到 NeoForge 才发现。

规则：

  * `src/main/java` 下，除 `platform/<加载器>/` 之外的文件，不得 import
    `net.fabricmc.*` / `net.neoforged.*` / `net.minecraftforge.*`。
  * `src/client/java` 下同理，但客户端的分层还没做，所以现在只统计不失败（`--strict` 才失败）。

判定看的是 **import 语句**，不是出现过的字符串：注释和 Javadoc 里提到加载器名字是正常的
（`PvPlatform` 的注释要解释三方的差异）。

用法：
    python scripts/check-boundary.py            # 表格 + 退出码
    python scripts/check-boundary.py --verbose  # 列出每一处违规的 file:line
    python scripts/check-boundary.py --strict   # 客户端也算失败

退出码：0 通过；1 有违规；2 脚本自身出错。
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT = os.path.join(ROOT, "fabric-1.21.1")
MAIN = os.path.join(PROJECT, "src", "main", "java")
CLIENT = os.path.join(PROJECT, "src", "client", "java")

# 加载器 API 的包前缀。`net.fabricmc.api` 也在内——入口类自己就该待在平台目录里。
LOADER_PACKAGES = ("net.fabricmc.", "net.neoforged.", "net.minecraftforge.")

# 允许出现加载器 import 的目录（相对各源码根）。每个加载器一个实现包，入口类也放在里面。
# 注意这里是**完整**相对路径：文件在 `com/leipishu/pvpocket/platform/fabric/` 下，只写
# `platform/fabric/` 匹配不上（踩过：前缀不匹配，于是两个合规文件被报成违规）。
PACKAGE_ROOT = "com/leipishu/pvpocket/"
LOADERS = ("fabric", "neoforge", "forge")
ALLOWED_IN_MAIN = tuple(PACKAGE_ROOT + "platform/" + name + "/" for name in LOADERS)

IMPORT = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)\s*;", re.M)


def scan(source_root, allowed):
    """返回 [(相对路径, 行号, 被 import 的包)]，跳过 allowed 下的文件。"""
    hits = []
    if not os.path.isdir(source_root):
        return hits
    for base, _, files in os.walk(source_root):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            rel = os.path.relpath(path, source_root).replace("\\", "/")
            if any(rel.startswith(prefix) for prefix in allowed):
                continue
            with open(path, encoding="utf-8-sig") as handle:
                text = handle.read()
            for match in IMPORT.finditer(text):
                imported = match.group(2)
                if not imported.startswith(LOADER_PACKAGES):
                    continue
                line = text.count("\n", 0, match.start()) + 1
                hits.append((rel, line, imported))
    return hits


def report(title, hits, verbose, hard):
    if not hits:
        print("  %-8s 干净" % title)
        return 0
    files = sorted({rel for rel, _, _ in hits})
    print("  %-8s %d 处，分布在 %d 个文件%s"
          % (title, len(hits), len(files), "" if hard else "（只统计，不算失败）"))
    if verbose:
        for rel, line, imported in hits:
            print("      %s:%d  %s" % (rel, line, imported))
    else:
        for rel in files[:6]:
            print("      %s" % rel)
        if len(files) > 6:
            print("      …还有 %d 个文件（--verbose 看全部）" % (len(files) - 6))
    return 1 if hard else 0


def main():
    parser = argparse.ArgumentParser(description="加载器边界检查")
    parser.add_argument("--verbose", action="store_true", help="列出每一处违规的 file:line")
    parser.add_argument("--strict", action="store_true", help="客户端违规也算失败")
    options = parser.parse_args()

    if not os.path.isdir(MAIN):
        print("[boundary] 找不到 %s" % MAIN, file=sys.stderr)
        return 2

    main_hits = scan(MAIN, ALLOWED_IN_MAIN)
    client_hits = scan(CLIENT, ())

    print("[boundary] src/main 允许的目录：%s"
          % "、".join("platform/%s/" % name for name in LOADERS))
    failed = report("main", main_hits, options.verbose, hard=True)
    # 客户端的分层是 Day 3 的事，现在只给一个可追踪的数字：这个数降到 0 就说明客户端也搬完了。
    failed |= report("client", client_hits, options.verbose, hard=options.strict)

    if failed:
        print("[boundary] 失败：业务代码里出现了加载器 API")
        return 1
    print("[boundary] 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
