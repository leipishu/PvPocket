#!/usr/bin/env python3
"""Loader boundary check: `src/main` and `src/client` must not import a loader API.

Only import statements are judged, so naming a loader in a comment is fine.

Usage:
    python scripts/check-boundary.py            # table + exit code
    python scripts/check-boundary.py --verbose  # list every violation as file:line

Exit code: 0 pass, 1 violations, 2 script error.
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Sources live at the repository root: src/ is shared and a cell only decides how to compile it.
SRC_ROOT = ROOT
MAIN = os.path.join(SRC_ROOT, "src", "main", "java")
CLIENT = os.path.join(SRC_ROOT, "src", "client", "java")
# Loader source roots: these should contain loader imports, so they are counted, never judged.
LOADER_ROOTS = (("fabric", os.path.join(SRC_ROOT, "src", "fabric", "java")),
                ("client-fabric", os.path.join(SRC_ROOT, "src", "client-fabric", "java")),
                ("neoforge", os.path.join(SRC_ROOT, "src", "neoforge", "java")),
                ("client-neoforge", os.path.join(SRC_ROOT, "src", "client-neoforge", "java")),
                ("forge", os.path.join(SRC_ROOT, "src", "forge", "java")))

# Loader API package prefixes. net.fabricmc.api is included: an entry point belongs in a root.
LOADER_PACKAGES = ("net.fabricmc.", "net.neoforged.", "net.minecraftforge.")

IMPORT = re.compile(r"^\s*import\s+(static\s+)?([\w.]+)\s*;", re.M)


def scan(source_root, allowed=()):
    """Returns [(relative path, line, imported package)], skipping files under `allowed`."""
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

    # Both are hard rules: a counted-only client side is a loophole for the next leak.
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
