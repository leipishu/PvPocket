#!/usr/bin/env python3
"""Comment linter for this repository.

The rules below are the parts of the comment convention that a machine can decide.

Rules, all of them errors:

  cjk           a comment contains CJK characters (comments are English, no exceptions)
  non-ascii     a comment contains any non-ASCII character (em dash, arrows, curly quotes)
  first-person  a comment uses I / me / my / we / us / our
  narrative     a comment narrates a change: "used to", "no longer", "the old code", ...
  javadoc-form  the summary sentence is missing a capital, a full stop, or both
  javadoc-p     <p> or a block tag is not preceded by a blank comment line
  javadoc-long  a Javadoc block is longer than MAX_JAVADOC_LINES
  javadoc-tag   @author / @since / @version
  type-doc      a public top-level type has no Javadoc
  code-run      more than MAX_RUN consecutive line comments
  commented-out a line comment that looks like disabled code
  width         a comment line is wider than MAX_WIDTH columns

Usage:
    python scripts/check-comments.py               # every file in the repository
    python scripts/check-comments.py --files src/main/java/.../Foo.java
    python scripts/check-comments.py --summary     # one line per file, worst first
    python scripts/check-comments.py --limit 40    # stop printing after 40 violations

Exit code: 0 clean, 1 violations, 2 bad invocation.
"""

import argparse
import os
import re
import sys

MAX_WIDTH = 100
MAX_JAVADOC_LINES = 12
MAX_RUN = 2

CJK = re.compile(r"[\u2e80-\u9fff\u3000-\u303f\uff00-\uffef]")
FIRST_PERSON = re.compile(r"\b(I|me|my|mine|we|us|our|ours)\b")
# Phrases that describe an edit rather than the code. The list is literal, not heuristic.
NARRATIVE = [
    "used to", "no longer", "previously", "before this", "the old code", "the old version",
    "this change", "this fix", "this patch", "now that", "which is exactly", "the whole point",
    "that is why", "this is why", "the reason is", "on purpose", "deliberately",
    "what this does is", "note that", "it turns out", "the fix is", "was wrong", "had to be",
]
FORBIDDEN_TAGS = ("@author", "@since", "@version")
# A line comment counts as disabled code when it ends like a statement or starts like a declaration.
CODE_END = re.compile(r"[;{}]\s*$")
CODE_START = re.compile(
    r"^(return|if|for|while|try|catch|throw|new|final|var|this\.|[A-Za-z_][\w.<>\[\]]*\s*=\s*)"
)

JAVA_SUFFIX = (".java",)
SLASH_SUFFIX = (".gradle",)  # // and /* */ comments
HASH_SUFFIX = (".sh", ".py", ".ps1", ".toml", ".properties", ".cmd", ".yml", ".yaml")

# gen-src is derived from src/ by scripts/port/gen.py: the //? markers that keep the source
# readable are stripped there, so the same comments cannot satisfy the rules in both trees.
SKIP_DIRS = {".git", "build", "gen-src", "runs", "logs", "arena-src", ".gradle", "legacy"}


def iter_files(roots):
    for root in roots:
        if os.path.isfile(root):
            yield root
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for name in sorted(filenames):
                if name.endswith(JAVA_SUFFIX + SLASH_SUFFIX + HASH_SUFFIX):
                    yield os.path.join(dirpath, name)


class Violation:
    __slots__ = ("path", "line", "rule", "text")

    def __init__(self, path, line, rule, text):
        self.path = path
        self.line = line
        self.rule = rule
        self.text = text


def check_text(path, lineno, text, rule_prefix, out):
    """Rules that apply to one piece of comment text, whatever the file type."""
    if CJK.search(text):
        out.append(Violation(path, lineno, "cjk", text))
    if any(ord(ch) > 127 for ch in text):
        out.append(Violation(path, lineno, "non-ascii", text))
    if FIRST_PERSON.search(text):
        out.append(Violation(path, lineno, "first-person", text))
    lowered = text.lower()
    for phrase in NARRATIVE:
        if phrase in lowered:
            out.append(Violation(path, lineno, "narrative", text))
            break
    if len(text) > MAX_WIDTH:
        out.append(Violation(path, lineno, "width", text))


def check_java(path, source, out, javadoc_rules=True):
    lines = source.splitlines()
    in_block = False
    block_is_doc = False
    block = []
    run = 0
    for index, raw in enumerate(lines, start=1):
        stripped = raw.strip()

        if in_block:
            block.append((index, raw))
            if "*/" in raw:
                in_block = False
                if block_is_doc and javadoc_rules:
                    check_javadoc(path, block, out)
            continue

        if stripped.startswith("/**"):
            if "*/" in stripped[3:]:
                if javadoc_rules:
                    check_javadoc(path, [(index, raw)], out)
            else:
                in_block = True
                block_is_doc = True
                block = [(index, raw)]
            run = 0
            continue

        if stripped.startswith("/*"):
            if "*/" not in stripped[2:]:
                in_block = True
                block_is_doc = False
                block = [(index, raw)]
            run = 0
            continue

        if stripped.startswith("//?"):
            # A version marker read by scripts/port/gen.py, not a comment: it is stripped from the
            # generated tree and must not be linted as prose.
            run = 0
            continue

        if stripped.startswith("//"):
            run += 1
            text = stripped[2:].strip()
            check_text(path, index, raw.rstrip(), "//", out)
            if run > MAX_RUN:
                out.append(Violation(path, index, "code-run", raw.rstrip()))
            body = text
            if CODE_END.search(body) or CODE_START.match(body):
                if not body.startswith(("TODO", "FIXME", "NOTE", "XXX")):
                    out.append(Violation(path, index, "commented-out", raw.rstrip()))
            continue

        if stripped:
            run = 0
            # Trailing comment after code.
            marker = raw.find("//")
            if marker > 0 and raw[:marker].strip() and '"' not in raw[:marker]:
                check_text(path, index, raw.rstrip(), "//", out)

    if javadoc_rules:
        check_public_types(path, lines, out)


def check_public_types(path, lines, out):
    """Every public top-level type carries a Javadoc."""
    for index, raw in enumerate(lines):
        if not re.match(r"^public\s+(final\s+|abstract\s+)?(class|interface|enum|record)\s", raw):
            continue
        # Annotations sit between the doc and the declaration, so walk back over them.
        cursor = index - 1
        depth = 0
        while cursor >= 0:
            previous = lines[cursor].strip()
            if depth == 0 and previous.endswith("*/"):
                break
            if depth == 0 and previous.startswith("@"):
                depth += previous.count("(") - previous.count(")")
                cursor -= 1
                continue
            if depth > 0:
                depth += previous.count("(") - previous.count(")")
                cursor -= 1
                continue
            out.append(Violation(path, index + 1, "type-doc", raw.rstrip()))
            break
        else:
            out.append(Violation(path, index + 1, "type-doc", raw.rstrip()))


def check_javadoc(path, block, out):
    if len(block) > MAX_JAVADOC_LINES:
        out.append(Violation(path, block[0][0], "javadoc-long",
                             "%d lines: %s" % (len(block), block[0][1].strip())))

    body = []
    for lineno, raw in block:
        text = raw.strip()
        if text.startswith("/**"):
            text = text[3:]
        if text.endswith("*/"):
            text = text[:-2]
        text = text.lstrip("*").strip()
        body.append((lineno, text))
        check_text(path, lineno, raw.rstrip(), "*", out)

    # Blank line before <p> and before the first block tag.
    previous_blank = True
    for lineno, text in body[1:]:
        if not text:
            previous_blank = True
            continue
        if (text.startswith("<p>") or text.startswith("@")) and not previous_blank:
            out.append(Violation(path, lineno, "javadoc-p", text))
        if text.startswith("@"):
            for tag in FORBIDDEN_TAGS:
                if text.startswith(tag):
                    out.append(Violation(path, lineno, "javadoc-tag", text))
        previous_blank = False

    summary_parts = []
    for lineno, text in body:
        if not text:
            break
        if text.startswith("@"):
            break
        summary_parts.append(text)
    summary = " ".join(summary_parts).strip()
    if summary:
        if not summary[0].isupper():
            out.append(Violation(path, block[0][0], "javadoc-form", summary))
        if not re.search(r"[.!?]$", summary) or summary.endswith(("...", "e.g.", "etc.")):
            out.append(Violation(path, block[0][0], "javadoc-form", summary))


def check_hash(path, source, out):
    lines = source.splitlines()
    run = 0
    for index, raw in enumerate(lines, start=1):
        stripped = raw.strip()
        if stripped.startswith("#"):
            run += 1
            text = stripped.lstrip("#").strip()
            if text:
                check_text(path, index, raw.rstrip(), "#", out)
            if run > MAX_RUN:
                out.append(Violation(path, index, "code-run", raw.rstrip()))
            if CODE_END.search(text) or CODE_START.match(text):
                out.append(Violation(path, index, "commented-out", raw.rstrip()))
        elif stripped:
            run = 0


def check_file(path, out):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            source = handle.read()
    except OSError as error:
        print("cannot read %s: %s" % (path, error), file=sys.stderr)
        return
    if path.endswith(JAVA_SUFFIX):
        check_java(path, source, out)
    elif path.endswith(SLASH_SUFFIX):
        check_java(path, source, out)
    else:
        check_hash(path, source, out)


def main():
    parser = argparse.ArgumentParser(description="Comment linter.")
    parser.add_argument("--files", nargs="*", default=None)
    parser.add_argument("--summary", action="store_true")
    parser.add_argument("--limit", type=int, default=200)
    args = parser.parse_args()

    roots = args.files if args.files else ["src", "versions", "scripts", "gradle", "build.gradle",
                                           "settings.gradle", "gradle.properties"]
    out = []
    for path in iter_files(roots):
        check_file(path, out)

    if not out:
        print("[comments] clean")
        return 0

    by_file = {}
    for violation in out:
        by_file.setdefault(violation.path, []).append(violation)

    if args.summary:
        for path, items in sorted(by_file.items(), key=lambda kv: -len(kv[1])):
            print("%5d  %s" % (len(items), path))
    else:
        shown = 0
        for path, items in sorted(by_file.items(), key=lambda kv: -len(kv[1])):
            for violation in sorted(items, key=lambda v: v.line)[:args.limit - shown]:
                if shown >= args.limit:
                    break
                print("%s:%d: %s: %s" % (path, violation.line, violation.rule,
                                         violation.text.strip()[:110]))
                shown += 1
            if shown >= args.limit:
                print("... (%d more)" % (len(out) - shown))
                break

    rules = {}
    for violation in out:
        rules[violation.rule] = rules.get(violation.rule, 0) + 1
    print("[comments] %d violations in %d files" % (len(out), len(by_file)))
    print("           " + ", ".join("%s=%d" % (k, v) for k, v in sorted(rules.items())))
    return 1


if __name__ == "__main__":
    sys.exit(main())
