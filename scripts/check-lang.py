#!/usr/bin/env python3
"""语言文件自检：中英键集、占位符、代码引用、规则键族结构。

为什么需要这个：AGENTS.md 只留了一条一次性的一行命令，而那条命令只看两件事（键集差、
`%s` 数量）。真正会让玩家看到半句话的还有四种情况，它们都不会让编译失败：

  1. **代码引用了不存在的键** —— 面板上直接显示 `pvpocket.gui.foo.bar` 这串原文。
     删一个键、改一个 id 就会发生，而且只有走到那个界面才看得见。
  2. **占位符个数与调用点的实参个数不一致** —— `String.format` 对多余的 `%s` 原样保留，
     于是消息末尾挂着一个 `%s`；少一个则抛异常，而抛在聊天消息里的异常会被吞掉。
  3. **有 `.label` 没有 `.hint`** —— 规则行的提示句是空字符串，看起来像「这行没写说明」。
  4. **`.c<N>` 位号不连续** —— `c0`/`c1`/`c3` 这种，中间那一位永远是空白 chip。
     位号就是线格式（AGENTS.md 2b），所以这里只报不修：删位要整条删，不能重新编号。

反过来「语言文件里有、代码里没人用」的键只算警告：目录类（`ItemKind` / `ArenaMap` /
`RuleDefinition`）的键是**拼出来的**，静态引用扫不到。脚本按源码里出现过的拼接前缀
（`"pvpocket.item." +`）把它们认作活键，剩下的才是真的死键。默认只报告，`--strict` 才算失败。

用法：
    python scripts/check-lang.py             # 表格 + 退出码
    python scripts/check-lang.py --verbose   # 附带每处的 file:line
    python scripts/check-lang.py --strict    # 死键也算失败（挂 CI 时用）
    python scripts/check-lang.py --json out.json

退出码：0 通过；1 有问题；2 脚本自身出错。
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT = os.path.join(ROOT, "fabric-1.21.1")
SRC_DIRS = [os.path.join(PROJECT, "src", "main", "java"),
            os.path.join(PROJECT, "src", "client", "java")]
LANG_DIR = os.path.join(PROJECT, "src", "main", "resources", "assets", "pvpocket", "lang")
ZH = os.path.join(LANG_DIR, "zh_cn.json")
EN = os.path.join(LANG_DIR, "en_us.json")

# 我们自己管的键前缀。`key.*` 是原版/Fabric 的按键名，也归这个模组所有。
KEY_PREFIXES = ("pvpocket.", "key.pvpocket.", "key.categories.pvpocket")

# `%` 后面跟字母（且不是 `s`）才算写错：数字也必须走 `%s`，见 PvLang.t 的注释——混用 `%d`
# 会让两个语言文件对「哪些参数是数字」产生分歧。
#
# 不能写成「`%` 后面不是 `s` 就报」：文案里的 `100% is the default` 是字面百分号，
# 那样会把它当成占位符。所以只认「后面紧跟字母」这一种。
BAD_PLACEHOLDER = re.compile(r"%(?![s%])[A-Za-z]")

# 这些调用里的字符串不是翻译键，只是恰好也在 `pvpocket.` 命名空间下——审计开关的
# `-Dpvpocket.audit=true` 就是这种。按调用名排除，比按键的形状猜可靠。
NOT_A_KEY_CALLS = ("getBoolean(", "getProperty(", "getenv(", "getInteger(", "getLong(")


# --------------------------------------------------------------------------------------------
# 源码扫描
# --------------------------------------------------------------------------------------------

def strip_comments(text):
    """把注释替换成空格，保留换行与偏移，好让行号还算得准。

    不能只删 `//` 到行尾：`String s = "http://x";` 里的 `//` 在字符串里。所以这里是一个
    真正的字符扫描，而不是正则。
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
    """返回 (字面量引用, 拼接前缀, 调用点)。

    字面量引用：[(键, 相对路径, 行号)]，只收本模组命名空间下的。
    拼接前缀：{前缀}，来自 `"pvpocket.item." +` 这种写法——它是「这类键是算出来的」的证据。
    调用点：[(键, 实参数, 相对路径, 行号)]，只收首参是纯字面量的调用。
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
            # 紧跟 `+` 的字面量是拼接的前半截（`"pvpocket.map." + id`、
            # `"pvpocket.rule.f.blockFilter.c" + index`）：它是一类键的前缀，不是键本身。
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
    """从 `(` 起按顶层逗号切参数。返回 (参数列表, 是否找到配对括号)。"""
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
    """`"a"` 或 `"a" + "b"` 求值成字符串；含变量则返回 None（调用点无从判断，跳过）。"""
    pieces = re.split(r"\s*\+\s*", arg.strip())
    out = []
    for piece in pieces:
        match = re.fullmatch(r'"((?:[^"\\\n]|\\.)*)"', piece.strip())
        if not match:
            return None
        out.append(match.group(1))
    return "".join(out) if out else None


# --------------------------------------------------------------------------------------------
# 检查
# --------------------------------------------------------------------------------------------

class Report:
    def __init__(self):
        self.problems = []   # (类别, 说明)
        self.warnings = []

    def bad(self, category, message):
        self.problems.append((category, message))

    def warn(self, category, message):
        self.warnings.append((category, message))


def placeholders(text):
    return text.count("%s")


def check_pair(report, zh, en):
    """L1 键集 + L2 占位符。"""
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
        # 英文可以是空的，而且是设计：`pvpocket.gui.result.win_en` 这类双语标题在英文环境里
        # 由标题本身承担（见 SeriesResultScreen 的类注释），空串的意思是「不要画副标题」。
        # 所以这里只报告，不算失败——真正该问的是「中文为什么是空的」。
        if not en_text.strip() and not key.endswith("_en"):
            report.warn("空值", "%s 的英文是空字符串" % key)


def check_structure(report, zh):
    """L5 键族结构：label 必有 hint，cN 从 0 连续。"""
    keys = set(zh)
    for key in sorted(keys):
        if key.endswith(".label") and (key[:-len(".label")] + ".hint") not in keys:
            report.bad("结构", "%s 有 label 没有 hint" % key)

    # 反方向不查：`pvpocket.rule.group.a` / `pvpocket.flag.punch` 的 label 就是裸键本身。
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
    """L3 字面量引用存在 + L4 调用点实参数。"""
    keys = set(zh)
    for key, rel, line in literals:
        if key not in keys:
            report.bad("引用", "%s:%d 引用了不存在的键 %s" % (rel, line, key))

    resolved = 0
    for key, argc, rel, line in calls:
        if key not in keys:
            continue          # 已经在 L3 报过了，不重复
        resolved += 1
        want = placeholders(zh[key])
        if argc != want:
            report.bad("实参", "%s:%d 的 %s 有 %d 个 %%s，调用点给了 %d 个实参"
                       % (rel, line, key, want, argc))
    return resolved


def check_dead(report, zh, literals, prefixes):
    """L6 死键：语言文件里有、代码里既没直接引用也不在任何拼接前缀之下。"""
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


# --------------------------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------------------------

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
