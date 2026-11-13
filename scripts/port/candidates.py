#!/usr/bin/env python3
"""List the renames between two Minecraft versions that this source tree actually uses.

Reads the Loom mapping caches of both versions, joins classes and members on the intermediary
name, and prints the pairs whose old name appears in src/. Use it when a compile error on a ported
cell looks like a rename that scripts/port/<mc>.tsv does not cover yet.

Usage:
    python scripts/port/candidates.py            # 1.21.1 -> 1.21.11
"""

import collections
import os
import re
import sys

CACHE = os.path.expanduser("~/.gradle/caches/fabric-loom")
OLD = ("1.21.1", "ResourceLocation")
NEW = ("1.21.11", "Identifier")
SRC = "src"


def find_tiny(mc, marker):
    """Pick the Mojmap-layered mappings for one version, whatever hash Loom gave the directory."""
    root = os.path.join(CACHE, mc)
    candidates = []
    for name in sorted(os.listdir(root)):
        if not name.startswith("loom.mappings") or not os.path.isdir(os.path.join(root, name)):
            continue
        path = os.path.join(root, name, "mappings.tiny")
        if os.path.isfile(path):
            candidates.append(path)
    for path in candidates:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if "net/minecraft/class_2960\t" in line:
                    if line.rstrip().endswith(marker):
                        return path
                    break
    raise SystemExit("no Mojmap mappings for " + mc + " in " + root)


def parse(path):
    """Return (classes, members): intermediary -> named."""
    classes = {}
    members = {}
    owner = None
    with open(path, encoding="utf-8") as handle:
        handle.readline()
        for line in handle:
            fields = line.rstrip("\n").split("\t")
            if fields[0] == "c" and len(fields) >= 4:
                owner = fields[2]
                classes[owner] = fields[3]
            elif fields[0] == "" and len(fields) >= 5 and owner:
                members[(owner, fields[3])] = fields[4]
    return classes, members


def main():
    old_classes, old_members = parse(find_tiny(*OLD))
    new_classes, new_members = parse(find_tiny(*NEW))

    candidates = collections.Counter()
    for key, old in old_classes.items():
        new = new_classes.get(key)
        if new and new != old:
            candidates[(old.split("/")[-1], new.split("/")[-1])] += 1
    for key, old in old_members.items():
        new = new_members.get(key)
        if new and new != old:
            candidates[(old, new)] += 1

    # Keep only names the source actually mentions.
    body = []
    for root, _, names in os.walk(SRC):
        for name in names:
            if name.endswith(".java"):
                body.append(open(os.path.join(root, name), encoding="utf-8").read())
    used = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", "\n".join(body)))
    keep = []
    for (old, new), count in sorted(candidates.items()):
        if old in used:
            keep.append((old, new, count))
    print("%d candidate renames appear in src/" % len(keep))
    for old, new, count in keep:
        print("%s\t%s" % (old, new))


if __name__ == "__main__":
    sys.exit(main())
