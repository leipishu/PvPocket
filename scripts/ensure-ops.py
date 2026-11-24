#!/usr/bin/env python3
"""Makes Dev1 an operator in the dev server run directories.

The dev server has no usable console stdin (Gradle does not forward it, see AGENTS.md), so
``op Dev1`` cannot be typed while the server runs and ``ops.json`` is the only way in. The file
is written before the server reads it, and merged rather than replaced, so an op list somebody
edited by hand survives.

The uuid is the offline one: a dev client never authenticates, so the server derives it from the
name (``OfflinePlayer:<name>``, MD5, version 3) instead of asking Mojang.

Usage::

    python scripts/ensure-ops.py                       # every cell in settings.gradle
    python scripts/ensure-ops.py --cell 26.1-fabric     # one cell
    python scripts/ensure-ops.py --dir runs/26.1-fabric/server   # one directory (Gradle calls this)
    python scripts/ensure-ops.py --name Dev1 --name Dev2         # extra names, repeatable

Exit code is 0 when every directory ended up with the operators, 2 when a cell or directory
could not be resolved.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_NAMES = ("Dev1",)
OP_LEVEL = 4


def offline_uuid(name: str) -> str:
    """The uuid an offline-mode server assigns to ``name``."""
    digest = hashlib.md5(f"OfflinePlayer:{name}".encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest, version=3))


def cells_from_settings() -> list[str]:
    """Cell names, parsed the same way the port and run-config scripts parse them."""
    settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
    block = re.search(r"def cells = \[(.*?)\]", settings, re.S)
    if block is None:
        return []
    return re.findall(r"'([0-9.]+-(?:fabric|neoforge))'", block.group(1))


def ensure(directory: Path, names: tuple[str, ...]) -> tuple[bool, str]:
    """Writes or repairs ``ops.json`` in ``directory``. Returns (changed, note)."""
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "ops.json"

    entries: list = []
    if path.is_file():
        try:
            parsed = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError) as error:
            # Left alone otherwise: a server that cannot read this file complains loudly, and
            # silently dropping somebody's op list would be worse than starting from empty.
            return False, f"unreadable ({error}); left as is"
        if isinstance(parsed, list):
            entries = [entry for entry in parsed if isinstance(entry, dict)]

    changed = False
    for name in names:
        wanted = offline_uuid(name)
        existing = next((e for e in entries if e.get("name") == name), None)
        if existing is None:
            entries.append({
                "uuid": wanted,
                "name": name,
                "level": OP_LEVEL,
                "bypassesPlayerLimit": False,
            })
            changed = True
            continue
        # A stale uuid (the name was opped online at some point) is replaced: the server matches
        # ops by uuid first, so keeping it would leave the dev client un-opped.
        if existing.get("uuid") != wanted:
            existing["uuid"] = wanted
            changed = True
        if existing.get("level") != OP_LEVEL:
            existing["level"] = OP_LEVEL
            changed = True

    if not changed and path.is_file():
        return False, "already set"

    path.write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")
    return True, "written"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--cell", action="append", default=[], help="cell name, repeatable")
    parser.add_argument("--dir", action="append", default=[], help="server run directory")
    parser.add_argument("--name", action="append", default=[], help="operator name, repeatable")
    args = parser.parse_args(argv)

    names = tuple(args.name) or DEFAULT_NAMES
    targets: list[Path] = []

    for raw in args.dir:
        directory = Path(raw)
        targets.append(directory if directory.is_absolute() else (ROOT / directory))
    if args.cell:
        for cell in args.cell:
            targets.append(ROOT / "runs" / cell / "server")
    if not targets:
        cells = cells_from_settings()
        if not cells:
            print("[ops] settings.gradle 里没解析到任何格", file=sys.stderr)
            return 2
        targets = [ROOT / "runs" / cell / "server" for cell in cells]

    failed = False
    for directory in targets:
        changed, note = ensure(directory, names)
        label = directory.relative_to(ROOT) if directory.is_relative_to(ROOT) else directory
        print(f"[ops] {str(label):<32} {', '.join(names)} level {OP_LEVEL}: {note}")
        if not changed and note == "unreadable":
            failed = True
    return 2 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
