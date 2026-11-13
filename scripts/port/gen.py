#!/usr/bin/env python3
"""Generate a version-ported copy of src/ for one cell.

The source tree is written against one Minecraft version (the canonical one). A cell on another
version compiles a generated copy instead of src/ directly: this script applies the rename table
and resolves the //? if blocks, so one tree still feeds every cell.

Two operations, in this order:

  //? if <cond> {     a block that only survives when <cond> holds for --mc
  //?} else {          the alternative block
  //?}                 end of block

  scripts/port/<mc>.tsv   `old<TAB>new` pairs, applied as whole-word replacements

Renames are applied to the whole file, string literals included: a mixin target such as
@Inject(method = "getServer") names a method in a string, and it has to follow the rename. Only
put a pair in the table when the old name cannot mean anything else.

Usage:
    python scripts/port/gen.py --mc 1.21.11 --src src --out versions/<cell>/gen-src

Exit code: 0 written, 1 bad invocation, 2 a block directive is unbalanced.
"""
import argparse
import os
import re
import shutil
import sys

DIRECTIVE = re.compile(r"^\s*//\?\s*(.*)$")
TEXT_SUFFIXES = {".java", ".json", ".toml", ".mcmeta", ".txt", ".properties"}


def version_key(text):
    """1.21.11 -> (1, 21, 11); 26.1 -> (26, 1, 0). Missing parts compare as zero."""
    parts = re.findall(r"\d+", text)
    numbers = [int(p) for p in parts] + [0, 0, 0]
    return tuple(numbers[:3])


def condition_holds(expression, target):
    match = re.match(r"^(>=|<=|==|>|<)?\s*(\S+)$", expression.strip())
    if not match:
        raise ValueError("cannot parse condition: %r" % expression)
    operator = match.group(1) or ">="
    limit = version_key(match.group(2))
    here = version_key(target)
    return {
        ">=": here >= limit,
        ">": here > limit,
        "<=": here <= limit,
        "<": here < limit,
        "==": here == limit,
    }[operator]


def resolve_blocks(text, target, path):
    """Drop the directives and keep only the branches that hold for --mc."""
    out = []
    # Each entry is (keep, branch_taken): keep says whether lines are emitted.
    stack = []
    for number, line in enumerate(text.splitlines(keepends=True), start=1):
        match = DIRECTIVE.match(line)
        if match is None:
            if all(frame[0] for frame in stack):
                out.append(line)
            continue
        body = match.group(1).strip()
        if body.startswith("if ") and body.endswith("{"):
            holds = condition_holds(body[3:-1], target)
            stack.append([holds, holds])
        elif body in ("} else {", "else {", "else"):
            if not stack:
                raise SyntaxError("%s:%d: //?} else without //? if" % (path, number))
            frame = stack[-1]
            frame[0] = not frame[1]
            frame[1] = True
        elif body == "}":
            if not stack:
                raise SyntaxError("%s:%d: //?} without //? if" % (path, number))
            stack.pop()
        else:
            raise SyntaxError("%s:%d: unknown directive //? %s" % (path, number, body))
    if stack:
        raise SyntaxError("%s: %d unclosed //? if block(s)" % (path, len(stack)))
    return "".join(out)


def load_table(path):
    pairs = []
    if not os.path.isfile(path):
        raise SystemExit("rename table not found: %s" % path)
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 2:
                raise ValueError("%s: expected `old<TAB>new`, got %r" % (path, line))
            pairs.append((parts[0].strip(), parts[1].strip()))
    # A mixin target descriptor spells the class with slashes, so every renamed class needs a
    # second spelling: without it the annotation compiles and just stops matching at runtime.
    for old, new in list(pairs):
        if "." in old:
            pairs.append((old.replace(".", "/"), new.replace(".", "/")))
    # Longest first, so `getServerLevel` is not clipped by `getServer`.
    pairs.sort(key=lambda pair: len(pair[0]), reverse=True)
    return [(re.compile(r"\b%s\b" % re.escape(old)), new) for old, new in pairs]


def port_file(text, target, table, path):
    text = resolve_blocks(text, target, path)
    for pattern, replacement in table:
        text = pattern.sub(replacement, text)
    return text


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mc", required=True)
    parser.add_argument("--src", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--table", default=None)
    args = parser.parse_args()

    table_path = args.table or os.path.join("scripts", "port", "%s.tsv" % args.mc)
    table = load_table(table_path)
    # A deleted source file has to disappear from the generated tree too, so start from empty.
    if os.path.abspath(args.out) != os.path.abspath(args.src) and os.path.isdir(args.out):
        shutil.rmtree(args.out)
    written = 0
    for root, _, names in os.walk(args.src):
        for name in names:
            source = os.path.join(root, name)
            relative = os.path.relpath(source, args.src)
            destination = os.path.join(args.out, relative)
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            if os.path.splitext(name)[1].lower() in TEXT_SUFFIXES:
                with open(source, encoding="utf-8", newline="") as handle:
                    text = handle.read()
                text = port_file(text, args.mc, table, relative)
                with open(destination, "w", encoding="utf-8", newline="") as handle:
                    handle.write(text)
            else:
                with open(source, "rb") as handle:
                    data = handle.read()
                with open(destination, "wb") as handle:
                    handle.write(data)
            written += 1
    print("[port] %d file(s) -> %s (%d rename(s), target %s)"
          % (written, args.out, len(table), args.mc))
    return 0


if __name__ == "__main__":
    sys.exit(main())
