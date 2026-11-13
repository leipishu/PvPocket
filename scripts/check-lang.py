#!/usr/bin/env python3
"""Language file self-check: key sets, placeholders, code references, rule key families.

Four failure modes here never break the build: a key referenced by code but missing from the
files, a placeholder count that does not match the call site, a `.label` without a `.hint`,
and a gap in the `.c<N>` option numbering.

Keys that exist but are never referenced only warn by default: catalog keys are built by
concatenation, so static scanning cannot see them. Prefixes found in the source (such as
`"pvpocket.item." +`) count as live. `--strict` makes dead keys fail.

Usage:
    python scripts/check-lang.py             # table + exit code
    python scripts/check-lang.py --verbose   # include file:line for each finding
    python scripts/check-lang.py --strict    # dead keys fail too (for CI)
    python scripts/check-lang.py --json out.json

Exit code: 0 pass, 1 problems, 2 script error.
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Sources live at the repository root: src/ is shared, a cell only decides how to compile it.
SRC_ROOT = ROOT
SRC_DIRS = [os.path.join(SRC_ROOT, "src", "main", "java"),
            os.path.join(SRC_ROOT, "src", "client", "java")]
LANG_DIR = os.path.join(SRC_ROOT, "src", "main", "resources", "assets", "pvpocket", "lang")
ZH = os.path.join(LANG_DIR, "zh_cn.json")
EN = os.path.join(LANG_DIR, "en_us.json")

# Key prefixes owned by this mod. `key.*` holds the vanilla/Fabric key names.
KEY_PREFIXES = ("pvpocket.", "key.pvpocket.", "key.categories.pvpocket")

# `%` followed by a letter other than s is a mistake: numbers go through %s as well, so both
# files agree on argument types. A literal `100% is the default` must not be flagged.
BAD_PLACEHOLDER = re.compile(r"%(?![s%])[A-Za-z]")

# These calls take strings that sit in the pvpocket namespace without being keys, such as
# -Dpvpocket.audit=true. Excluding by call name beats guessing by the shape of the string.
NOT_A_KEY_CALLS = ("getBoolean(", "getProperty(", "getenv(", "getInteger(", "getLong(")



def strip_comments(text):
    """Replaces comments with spaces, keeping newlines and offsets so line numbers stay right.

    A character scan rather than a regex: `//` can sit inside a string literal.
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '"' or ch == "'":
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                c = text[i]
                if c == "\\" and i + 1 < n:
                    out.append(c)
                    out.append(text[i + 1])
                    i += 2
                    continue
                out.append(c)
                i += 1
                if c == quote:
                    break
        elif ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
        elif ch == "/" and i + 1 < n and text[i + 1] == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


STRING_LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def java_sources():
    for root in SRC_DIRS:
        if not os.path.isdir(root):
            continue
        for base, _, files in os.walk(root):
            for name in sorted(files):
                if name.endswith(".java"):
                    yield os.path.join(base, name)


def scan():
    """Returns (literal references, concatenation prefixes, call sites).

    Literal references: [(key, relative path, line)], this mod's namespace only.
    Concatenation prefixes: {prefix}, from `"pvpocket.item." +`: the key is computed.
    Call sites: [(key, argument count, relative path, line)], literal first argument only.
    """
    literals, prefixes, calls = [], set(), []
    for path in java_sources():
        with open(path, encoding="utf-8", errors="replace") as handle:
            raw = handle.read()
        code = strip_comments(raw)
        rel = os.path.relpath(path, ROOT).replace("\\", "/")

        for match in STRING_LITERAL.finditer(code):
            value = match.group(1)
            line = code.count("\n", 0, match.start()) + 1
            if not value.startswith(KEY_PREFIXES):
                continue
            if any(code[max(0, match.start() - 24):match.start()].rstrip().endswith(call)
                   for call in NOT_A_KEY_CALLS):
                continue
            # A literal followed by `+` is the first half of a concatenation, so it is a key
            # prefix rather than a key.
            tail = code[match.end():match.end() + 8].lstrip()
            if tail.startswith("+"):
                prefixes.add(value)
                continue
            literals.append((value, rel, line))

        for match in re.finditer(r"PvLang\s*\.\s*(?:t|str)\s*\(", code):
            args, ok = split_args(code, match.end() - 1)
            if not ok or not args:
                continue
            key = literal_value(args[0])
            if key is None:
                continue
            line = code.count("\n", 0, match.start()) + 1
            calls.append((key, len(args) - 1, rel, line))
    return literals, prefixes, calls


def split_args(code, open_paren):
    """Splits arguments on top-level commas from `(`. Returns (arguments, closed)."""
    depth, args, start = 0, [], open_paren + 1
    i, n = open_paren, len(code)
    while i < n:
        ch = code[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                args.append(code[start:i])
                return [a.strip() for a in args if a.strip()], True
        elif ch == "," and depth == 1:
            args.append(code[start:i])
            start = i + 1
        elif ch == '"':
            i += 1
            while i < n:
                if code[i] == "\\":
                    i += 2
                    continue
                if code[i] == '"':
                    break
                i += 1
        i += 1
    return args, False


def literal_value(arg):
    """`"a"` or `"a" + "b"` evaluates to a string; None when a variable is involved."""
    pieces = re.split(r"\s*\+\s*", arg.strip())
    out = []
    for piece in pieces:
        match = re.fullmatch(r'"((?:[^"\\\n]|\\.)*)"', piece.strip())
        if not match:
            return None
        out.append(match.group(1))
    return "".join(out) if out else None



class Report:
    def __init__(self):
        self.problems = []   # (category, message)
        self.warnings = []

    def bad(self, category, message):
        self.problems.append((category, message))

    def warn(self, category, message):
        self.warnings.append((category, message))


def placeholders(text):
    return text.count("%s")


def check_pair(report, zh, en):
    """L1 key sets + L2 placeholders."""
    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    for key in only_zh:
        report.bad("键集", "只在 zh_cn 里有：%s" % key)
    for key in only_en:
        report.bad("键集", "只在 en_us 里有：%s" % key)

    for key in sorted(set(zh) & set(en)):
        zh_text, en_text = zh[key], en[key]
        zh_count, en_count = placeholders(zh_text), placeholders(en_text)
        if zh_count != en_count:
            report.bad("占位符", "%s：中文 %d 个 %%s，英文 %d 个" % (key, zh_count, en_count))
        for name, text in (("zh_cn", zh_text), ("en_us", en_text)):
            if BAD_PLACEHOLDER.search(text):
                report.bad("占位符", "%s 的 %s 里有非 %%s 的占位符：%s"
                           % (key, name, text))
        if not zh_text.strip():
            report.bad("空值", "%s 的中文是空字符串" % key)
        # An empty English value is by design for keys ending in _en: the title carries itself,
        # and an empty string means no subtitle, so this only warns.
        if not en_text.strip() and not key.endswith("_en"):
            report.warn("空值", "%s 的英文是空字符串" % key)


def check_structure(report, zh):
    """L5 key families: every label has a hint, cN numbering is contiguous from 0."""
    keys = set(zh)
    for key in sorted(keys):
        if key.endswith(".label") and (key[:-len(".label")] + ".hint") not in keys:
            report.bad("结构", "%s 有 label 没有 hint" % key)

    # The reverse is not checked: `pvpocket.rule.group.a` has a bare label key.
    groups = {}
    for key in keys:
        match = re.match(r"(.+)\.c(\d+)$", key)
        if match:
            groups.setdefault(match.group(1), []).append(int(match.group(2)))
    for base, indices in sorted(groups.items()):
        indices.sort()
        if indices != list(range(len(indices))):
            report.bad("结构", "%s 的选项位号不连续：%s" % (base, indices))


def check_references(report, zh, literals, prefixes, calls):
    """L3 literal references exist + L4 call-site argument counts."""
    keys = set(zh)
    for key, rel, line in literals:
        if key not in keys:
            report.bad("引用", "%s:%d 引用了不存在的键 %s" % (rel, line, key))

    resolved = 0
    for key, argc, rel, line in calls:
        if key not in keys:
            continue          # already reported by L3
        resolved += 1
        want = placeholders(zh[key])
        if argc != want:
            report.bad("实参", "%s:%d 的 %s 有 %d 个 %%s，调用点给了 %d 个实参"
                       % (rel, line, key, want, argc))
    return resolved


def check_dead(report, zh, literals, prefixes):
    """L6 dead keys: present in the file, referenced nowhere and under no prefix."""
    used = {key for key, _, _ in literals}
    dead = []
    for key in sorted(zh):
        if key in used:
            continue
        if any(key.startswith(prefix) for prefix in prefixes):
            continue
        dead.append(key)
    for key in dead:
        report.warn("死键", "语言文件里有、源码里找不到引用：%s" % key)
    return dead



def main():
    parser = argparse.ArgumentParser(description="语言文件自检")
    parser.add_argument("--verbose", action="store_true", help="附带 file:line 与统计明细")
    parser.add_argument("--strict", action="store_true", help="死键也算失败（挂 CI 时用）")
    parser.add_argument("--json", metavar="PATH", help="把结果写成 JSON")
    options = parser.parse_args()

    for path in (ZH, EN):
        if not os.path.isfile(path):
            print("[lang] 找不到 %s" % path, file=sys.stderr)
            return 2
    with open(ZH, encoding="utf-8") as handle:
        zh = json.load(handle)
    with open(EN, encoding="utf-8") as handle:
        en = json.load(handle)

    literals, prefixes, calls = scan()

    report = Report()
    check_pair(report, zh, en)
    check_structure(report, zh)
    resolved = check_references(report, zh, literals, prefixes, calls)
    dead = check_dead(report, zh, literals, prefixes)

    if options.verbose:
        print("[lang] 扫描 %d 个字面量引用、%d 个拼接前缀、%d 个可解析的调用点"
              % (len(literals), len(prefixes), resolved))
        print("[lang] 拼接前缀：%s" % " ".join(sorted(prefixes)))

    print("[lang] 键 %d（zh_cn）/ %d（en_us）" % (len(zh), len(en)))

    by_category = {}
    for category, message in report.problems:
        by_category.setdefault(category, []).append(message)
    for category in ("键集", "占位符", "结构", "引用", "实参", "空值"):
        messages = by_category.pop(category, [])
        if not messages:
            continue
        print("  ! %s：%d 处" % (category, len(messages)))
        for message in messages[:20 if not options.verbose else None]:
            print("      %s" % message)
        if not options.verbose and len(messages) > 20:
            print("      …还有 %d 处（--verbose 看全部）" % (len(messages) - 20))
    for category, messages in by_category.items():
        print("  ! %s：%d 处" % (category, len(messages)))
        for message in messages:
            print("      %s" % message)

    if report.warnings:
        warn_by_category = {}
        for category, message in report.warnings:
            warn_by_category.setdefault(category, []).append(message)
        for category, messages in warn_by_category.items():
            print("  ? %s：%d 个（不算失败，--strict 才算）" % (category, len(messages)))
            if options.verbose:
                for message in messages:
                    print("      %s" % message)
            else:
                print("      例如 %s" % "、".join(
                    m.split("：")[-1] for m in messages[:5]))

    if options.json:
        with open(options.json, "w", encoding="utf-8") as handle:
            json.dump({"problems": report.problems, "warnings": report.warnings,
                       "keys": len(zh)}, handle, ensure_ascii=False, indent=2)

    if report.problems:
        print("[lang] 失败：%d 处" % len(report.problems))
        return 1
    if options.strict and report.warnings:
        print("[lang] 失败：%d 个死键（--strict）" % len(report.warnings))
        return 1
    print("[lang] 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
