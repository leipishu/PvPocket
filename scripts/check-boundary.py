#!/usr/bin/env python3
"""加载器边界检查：`src/main` 里不许出现加载器 API。

AGENTS.md §5c 写了这条约定，但约定只有变成可执行的检查才算数。这条线决定了移植一格的代价：
守住了就是「写一个 200 行的实现类」，破了就是「重写一遍」。而它破掉的方式总是很温和——
某个工具方法「就一行 Fabric 调用」，顺手写进业务类里，编译照过，等移植到 NeoForge 才发现。

规则：

  * `src/main/java` 与 `src/client/java` 下**一个加载器 import 都不许有**。加载器代码在
    `src/fabric/java` 与 `src/client-fabric/java`（将来还有 neoforge / forge 那几格），
    那是另外的源根，不在这条规则的范围里。

判定看的是 **import 语句**，不是出现过的字符串：注释和 Javadoc 里提到加载器名字是正常的
（`PvPlatform` 的注释要解释三方的差异）。

用法：
    python scripts/check-boundary.py            # 表格 + 退出码
    python scripts/check-boundary.py --verbose  # 列出每一处违规的 file:line

退出码：0 通过；1 有违规；2 脚本自身出错。
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 源码在仓库根，不再挂在某个 cell 下：src/ 是共享的，cell 只决定怎么编译它。
SRC_ROOT = ROOT
MAIN = os.path.join(SRC_ROOT, "src", "main", "java")
CLIENT = os.path.join(SRC_ROOT, "src", "client", "java")
# 加载器源根：这里**应该**有加载器 import，只做计数，不判定。列出来是为了让人确认它们确实在，
# 而不是被误删——一个空的 src/fabric 与一个「接线写进了业务代码」的仓库，症状完全不同。
LOADER_ROOTS = (("fabric", os.path.join(SRC_ROOT, "src", "fabric", "java")),
                ("client-fabric", os.path.join(SRC_ROOT, "src", "client-fabric", "java")),
                ("neoforge", os.path.join(SRC_ROOT, "src", "neoforge", "java")),
                ("client-neoforge", os.path.join(SRC_ROOT, "src", "client-neoforge", "java")),
                ("forge", os.path.join(SRC_ROOT, "src", "forge", "java")))

# 加载器 API 的包前缀。`net.fabricmc.api` 也在内——入口类自己就该待在加载器源根里。
LOADER_PACKAGES = ("net.fabricmc.", "net.neoforged.", "net.minecraftforge.")

IMPORT = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)\s*;", re.M)


def scan(source_root, allowed=()):
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
    options = parser.parse_args()

    if not os.path.isdir(MAIN):
        print("[boundary] 找不到 %s" % MAIN, file=sys.stderr)
        return 2

    main_hits = scan(MAIN)
    client_hits = scan(CLIENT)

    # 两边都是硬规则。客户端曾经只统计不失败，因为分层没做完；现在做完了，再留一个「不算失败」
    # 的口子就等于给下一次悄悄泄漏开了绿灯。
    failed = report("main", main_hits, options.verbose, hard=True)
    failed |= report("client", client_hits, options.verbose, hard=True)

    for name, path in LOADER_ROOTS:
        if os.path.isdir(path):
            print("  %-8s %d 处（加载器源根，不计入判定）" % ("src/" + name, len(scan(path))))

    if failed:
        print("[boundary] 失败：业务代码里出现了加载器 API")
        return 1
    print("[boundary] 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
