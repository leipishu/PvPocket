#!/usr/bin/env python3
"""Remove the Application run configurations ModDevGradle writes into .idea/workspace.xml.

`.run/` holds one Gradle entry per run task; ModDevGradle used to write a second, Application-type
copy of the same five NeoForge runs into the IDE workspace. `neoForgeIdeSync` is disabled now, so
new ones are no longer produced, but the copies written before that stay until deleted.

IDEA owns workspace.xml: it rewrites the whole file from memory, so running this while the IDE is
open only lasts until the next save. Close IDEA first.

Usage:
    python scripts/clean-idea-run-configs.py            # delete, report what went
    python scripts/clean-idea-run-configs.py --dry-run  # only list them

Exit code: 0 pass, 1 nothing removed while entries remain, 2 script error.
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKSPACE = os.path.join(ROOT, ".idea", "workspace.xml")

# One <configuration ...> block, up to its closing tag. The file has no nested configuration nodes.
BLOCK = re.compile(r"[ \t]*<configuration\b[^>]*>.*?</configuration>\n", re.S)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    if not os.path.isfile(WORKSPACE):
        print("找不到 .idea/workspace.xml")
        return 2
    with open(WORKSPACE, encoding="utf-8") as handle:
        text = handle.read()

    doomed = [m for m in BLOCK.finditer(text) if 'type="Application"' in m.group(0)]
    if not doomed:
        print("workspace.xml 里没有 Application 型运行配置")
        return 0

    names = [re.search(r'name="([^"]*)"', m.group(0)).group(1) for m in doomed]
    for name in names:
        print(f"  {name}")
    if args.dry_run:
        print(f"{len(names)} 条待删（--dry-run，未写入）")
        return 0

    # Delete from the end so earlier offsets stay valid.
    for match in reversed(doomed):
        text = text[:match.start()] + text[match.end():]
    with open(WORKSPACE, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print(f"已删除 {len(names)} 条。IDEA 开着的话，它下次保存 workspace.xml 会写回来。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
