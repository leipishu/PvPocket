#!/usr/bin/env python3
"""规则接线审计：每条规则 id 在 RuleCatalog 之外是否真的还有读取点。

为什么需要这个：面板能改、能存盘、能进 journal，都不能说明它影响对局。唯一的判据是
「这条 id 除了 RuleCatalog 之外还有没有别的地方读它」——这正是 docs/zh/02-rules.md §3
写下的判定方法，以前是手工过一遍，现在把它变成可以挂 CI 的检查。

它回答三个问题：

  1. 每条规则有没有行为读取点（不是只写日志，也不是根本没人读）
  2. 默认值是否落在自己声明的范围内（越界的默认值会在面板上第一帧就被夹住，看起来「改了没反应」）
  3. Rulebook 里有没有「写了 getter 但没人调」的死接口

四类结论：

    OK        至少有一处读取点，且不只是在写日志
    LOG_ONLY  有读取点，但全部出现在 journal / LOGGER / sendMessage 那一行（"仅记录"）
    NO_READER 除 RuleCatalog 之外没有任何代码读它（面板改了个寂寞）
    FIXED     RuleKind.FIXED，本来就只做展示，不进问题清单

用法：
    python scripts/audit-rules.py              # 表格 + 退出码
    python scripts/audit-rules.py --verbose    # 附带每处读取点的 file:line
    python scripts/audit-rules.py --docs       # 额外比对 docs/zh/02-rules.md 里的"默认"
    python scripts/audit-rules.py --json out.json

退出码：0 全部 OK；1 存在 LOG_ONLY / NO_READER / 默认值越界 / 死 getter；2 脚本自身出错。
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 源码在仓库根，不再挂在某个 cell 下：src/ 是共享的，cell 只决定怎么编译它。
SRC_ROOT = ROOT
SRC = os.path.join(SRC_ROOT, "src")
RULES_PKG = os.path.join(SRC, "main", "java", "com", "leipishu", "pvpocket", "rules")
CATALOG = os.path.join(RULES_PKG, "RuleCatalog.java")
RULEBOOK = os.path.join(RULES_PKG, "Rulebook.java")
DUEL_RULES = os.path.join(RULES_PKG, "DuelRules.java")
ARENA_MAPS = os.path.join(SRC, "main", "java", "com", "leipishu", "pvpocket", "arena",
                          "ArenaMaps.java")
DOCS = os.path.join(ROOT, "docs", "zh", "02-rules.md")
LANG = os.path.join(SRC, "main", "resources", "assets", "pvpocket", "lang", "zh_cn.json")

# 一行里出现这些词，说明这条规则的读取只是为了留证据，不改变任何行为。
LOG_MARKERS = ("journal(", "LOGGER", "sendMessage", "append(\"", "PvAudit")

# 工厂方法 → (默认值在参数里的下标, 是否枚举/多选)
KINDS = {
    "toggle": (2, False),
    "toggleNamed": (4, False),
    "intRange": (5, False),
    "decimal": (5, False),
    "percent": (2, False),
    "enumOf": (2, True),
    "multi": (2, True),
    "fixed": (None, False),
}


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def split_args(text, start):
    """从 start（紧跟 '(' 之后）开始按顶层逗号切分，返回 (参数列表, 右括号位置)。

    必须真的数括号和引号：参数里有 `choices("a.maxRounds", 6)` 和 `ArenaManager.MAX_SIZE_XZ`，
    逐字段正则会在第一处嵌套括号上错位，把后面几条规则一起吃掉。
    """
    args, buf = [], []
    depth, in_str, i = 0, False, start
    while i < len(text):
        ch = text[i]
        if in_str:
            buf.append(ch)
            if ch == "\\":
                i += 1
                if i < len(text):
                    buf.append(text[i])
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
            buf.append(ch)
        elif ch in "([{":
            depth += 1
            buf.append(ch)
        elif ch in ")]}":
            if depth == 0:
                tail = "".join(buf).strip()
                if tail:
                    args.append(tail)
                return args, i
            depth -= 1
            buf.append(ch)
        elif ch == "," and depth == 0:
            args.append("".join(buf).strip())
            buf = []
        else:
            buf.append(ch)
        i += 1
    raise ValueError("括号不闭合")


def unquote(expr):
    m = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', expr.strip())
    return m.group(1) if m else None


def arena_map_count():
    """ArenaMaps.labelKeys() 的长度——e.template 的选项数由它决定。"""
    try:
        text = read(ARENA_MAPS)
    except OSError:
        return 0
    # 地图以 `new ArenaMap(...)` 或 ALL 列表里的枚举项登记；数 ALL 里的条目更稳。
    m = re.search(r"ALL\s*=\s*List\.of\((.*?)\);", text, re.S)
    if m:
        return len(re.findall(r"\bnew\s+\w+\s*\(", m.group(1))) or \
               len([x for x in split_args(m.group(1) + ")", 0)[0] if x.strip()])
    return len(re.findall(r"new ArenaMap\(", text))


def choice_count(expr):
    """`choices("id", 6)` → 6；`ArenaMaps.labelKeys()` → 地图数；否则 None。"""
    m = re.search(r"choices\(\s*\"[^\"]+\"\s*,\s*(\d+)\s*\)", expr)
    if m:
        return int(m.group(1))
    if "labelKeys()" in expr:
        return arena_map_count()
    return None


def parse_catalog():
    """[{'id','group','kind','default','min','max','step','choices'}] 按目录顺序。"""
    text = read(CATALOG)
    out = []
    for m in re.finditer(r"\badd\(RuleDefinition\.(\w+)\(", text):
        kind = m.group(1)
        if kind not in KINDS:
            print(f"[audit-rules] 未知工厂方法 {kind}，跳过", file=sys.stderr)
            continue
        try:
            args, _end = split_args(text, m.end())
        except ValueError as exc:
            print(f"[audit-rules] {kind} 参数切分失败：{exc}", file=sys.stderr)
            continue
        rid = unquote(args[0]) if args else None
        if not rid:
            print(f"[audit-rules] {kind} 第一个参数不是 id 字面量：{args[:1]}", file=sys.stderr)
            continue
        def_index, is_choice = KINDS[kind]
        entry = {
            "id": rid,
            "group": rid.split(".")[0].upper(),
            "kind": kind,
            "default": None,
            "default_value": None,
            "min": None,
            "max": None,
            "step": None,
            "choices": None,
            "choices_local": True,
        }
        if kind == "fixed":
            entry["default"] = "只读"
        elif def_index is not None and def_index < len(args):
            raw = args[def_index]
            if raw in ("true", "false"):
                entry["default"] = "开" if raw == "true" else "关"
                entry["default_value"] = 1 if raw == "true" else 0
            elif raw.startswith("0b"):
                value = int(raw, 2)
                width = choice_count(args[def_index + 1]) if def_index + 1 < len(args) else 4
                # 按 bit0 在最左显示，和面板上 chip 的排列一致
                entry["default"] = "".join(
                    "1" if value >> i & 1 else "0" for i in range(width or 4))
                entry["default_value"] = value
            else:
                entry["default"] = raw
                entry["default_value"] = int(raw) if re.fullmatch(r"-?\d+", raw) else None
        if kind in ("intRange", "decimal") and len(args) >= 6:
            entry["min"], entry["max"], entry["step"] = args[2], args[3], args[4]
        if is_choice and def_index is not None and def_index + 1 < len(args):
            entry["choices"] = choice_count(args[def_index + 1])
            # 选项键未必是 pvpocket.rule.<id>.cN —— e.template 用的是 ArenaMaps 的地图名。
            entry["choices_local"] = "choices(" in args[def_index + 1]
        out.append(entry)
    return out


def parse_lang():
    try:
        return json.loads(read(LANG))
    except (OSError, ValueError):
        return {}


def java_body(text, open_brace):
    depth, i = 1, open_brace + 1
    while i < len(text) and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    return text[open_brace + 1:i - 1]


def rulebook_getters():
    """{getter: [ids]} —— Rulebook 里每个 public 方法体读到的规则 id。"""
    text = read(RULEBOOK)
    out = {}
    for m in re.finditer(r"public\s+[\w<>\[\].]+\s+(\w+)\([^)]*\)\s*\{", text):
        body = java_body(text, m.end() - 1)
        ids = sorted(set(re.findall(r'"(?P<id>[a-g]\.[A-Za-z]+)"', body)))
        if ids:
            out[m.group(1)] = ids
    return out


def java_sources():
    for base, _dirs, files in os.walk(SRC):
        for name in files:
            if name.endswith(".java"):
                yield os.path.join(base, name)


def is_comment(line):
    stripped = line.strip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def find_readers(rule_id, skip=()):
    """除 skip 里的文件之外，每一处非注释的 `"rule.id"` 出现。"""
    needle = '"%s"' % rule_id
    skip = {os.path.abspath(p) for p in skip}
    sites = []
    for path in java_sources():
        if os.path.abspath(path) in skip:
            continue
        try:
            lines = read(path).splitlines()
        except OSError:
            continue
        for n, line in enumerate(lines, 1):
            if needle not in line or is_comment(line):
                continue
            sites.append({
                "file": os.path.relpath(path, ROOT).replace("\\", "/"),
                "line": n,
                "code": line.strip(),
                "log": any(marker in line for marker in LOG_MARKERS),
            })
    return sites


def getter_callers(getter):
    """除 Rulebook 自己之外，谁调用了 getter()。"""
    needle = getter + "("
    out = []
    for path in java_sources():
        if os.path.abspath(path) in (os.path.abspath(RULEBOOK), os.path.abspath(CATALOG)):
            continue
        try:
            lines = read(path).splitlines()
        except OSError:
            continue
        for n, line in enumerate(lines, 1):
            if needle not in line or is_comment(line):
                continue
            if re.search(r"\b%s\s*=\s*%s\s*\(" % (getter, getter), line):
                continue  # 局部变量同名，不是调用
            out.append("%s:%d" % (os.path.relpath(path, ROOT).replace("\\", "/"), n))
    return out


def range_problem(rule):
    """默认值是否落在声明的范围内。越界的默认值会被面板第一帧夹住，看起来像「改了没反应」。"""
    if rule["kind"] not in ("intRange", "decimal", "percent", "enumOf", "multi"):
        return None
    value = rule["default_value"]
    if value is None:
        return None
    if rule["kind"] in ("intRange", "decimal"):
        lo = _num(rule["min"])
        hi = _num(rule["max"])
        if lo is not None and value < lo:
            return "默认 %s 小于下限 %s" % (value, rule["min"])
        if hi is not None and value > hi:
            return "默认 %s 大于上限 %s" % (value, rule["max"])
    if rule["kind"] == "enumOf" and rule["choices"]:
        if not 0 <= value < rule["choices"]:
            return "默认 %s 超出选项数 %s" % (value, rule["choices"])
    if rule["kind"] == "multi" and rule["choices"]:
        if value >> rule["choices"]:
            return "默认 %s 超出选项位宽 %s" % (rule["default"], rule["choices"])
    return None


def _num(expr):
    if expr is None:
        return None
    expr = expr.strip()
    if re.fullmatch(r"-?\d+", expr):
        return int(expr)
    return None  # 常量（ArenaManager.MAX_SIZE_XZ）不解析，交给编译期


def default_label(rule, lang):
    """把默认值翻译成面板上真正显示的文字，才能和文档里那句「默认 …」比。"""
    rid = rule["id"]
    value = rule["default_value"]
    if value is None:
        return None
    if rule["kind"] == "enumOf":
        return lang.get("pvpocket.rule.%s.c%d" % (rid, value))
    if rule["kind"] == "multi":
        width = rule["choices"] or 4
        labels = [lang.get("pvpocket.rule.%s.c%d" % (rid, i)) for i in range(width)]
        return "、".join(lab for i, lab in enumerate(labels) if lab and value >> i & 1)
    return None


def coverage(rules, world_dir):
    """哪些规则从来没以非默认值跑过。

    「有读取点」是代码层的问题，「真的会变」是运行时的问题，而运行时只有跑过才知道。这里不去
    启动服务端，而是读已经写下的 journal：每一次被接受的规则集都在 RULES_ACCEPTED 里，把历史上
    出现过的取值和目录默认值一比，就能把「从来没验证过」的规则列出来。比默认值更值得看的是
    「跑过非默认值的那些」——它们才是这套配置被真正检验过的部分。
    """
    import glob
    files = glob.glob(os.path.join(world_dir, "pvpocket", "journal", "*.jsonl"))
    seen = {}
    for path in files:
        try:
            lines = read(path).splitlines()
        except OSError:
            continue
        for line in lines:
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            if entry.get("type") not in ("RULES_ACCEPTED", "RULES_AGREED"):
                continue
            for pair in (entry.get("rules") or "").split(";"):
                if "=" not in pair:
                    continue
                key, value = pair.split("=", 1)
                try:
                    seen.setdefault(key, set()).add(int(value))
                except ValueError:
                    pass
    exercised, never = [], []
    for rule in rules:
        if rule["kind"] == "fixed" or rule["default_value"] is None:
            continue
        values = seen.get(rule["id"])
        if values is None:
            never.append((rule["id"], "从未出现在任何已接受的规则集"))
            continue
        others = sorted(v for v in values if v != rule["default_value"])
        if others:
            exercised.append((rule["id"], rule["default_value"], others))
        else:
            never.append((rule["id"], "只跑过默认值"))
    return len(files), exercised, never


def doc_defaults():
    """{id: '默认 ...'} 来自 docs/zh/02-rules.md，尽力而为。"""
    out = {}
    if not os.path.exists(DOCS):
        return out
    for line in read(DOCS).splitlines():
        m = re.search(r"`([a-g]\.[A-Za-z]+)`.*?默认\s*([^·。\n|]+)", line)
        if m:
            out[m.group(1)] = m.group(2).strip()
    return out


def check_lang(rules):
    """每条规则的 label / hint / 每个选项键是否齐全，以及中英两套是否对得上。

    缺 label 的规则在面板上就是一行空白 —— 它不报错、不进日志，只是看起来像界面没画完。
    """
    missing, mismatched = [], []
    try:
        zh = json.loads(read(LANG))
        en = json.loads(read(LANG.replace("zh_cn", "en_us")))
    except (OSError, ValueError) as exc:
        return [("lang", "读不了语言文件：%s" % exc)], []
    for rule in rules:
        rid = rule["id"]
        for suffix in ("label", "hint"):
            key = "pvpocket.rule.%s.%s" % (rid, suffix)
            if key not in zh:
                missing.append((rid, key))
        if not rule.get("choices_local", True):
            continue  # 选项键来自别处（ArenaMaps），按 cN 检查会全部误报
        count = rule["choices"]
        if rule["kind"] == "fixed":
            count = 1
        for i in range(count or 0):
            key = "pvpocket.rule.%s.c%d" % (rid, i)
            if key not in zh:
                missing.append((rid, key))
    for key in sorted(set(zh) | set(en)):
        if key not in zh or key not in en:
            mismatched.append(key)
            continue
        a, b = zh[key], en[key]
        if a.count("%s") != b.count("%s"):
            mismatched.append(key)
    return missing, mismatched


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true", help="打印每处读取点")
    ap.add_argument("--docs", action="store_true", help="比对文档写的默认值")
    ap.add_argument("--json", metavar="PATH", help="把结果写成 JSON（CI 用）")
    ap.add_argument("--coverage", metavar="WORLD",
                    help="读 <WORLD> 下的对局日志，列出从未以非默认值跑过的规则")
    args = ap.parse_args()

    if not os.path.exists(CATALOG):
        print("[audit-rules] 找不到 %s" % CATALOG, file=sys.stderr)
        return 2

    rules = parse_catalog()
    if not rules:
        print("[audit-rules] 一条规则都没解析出来", file=sys.stderr)
        return 2
    lang = parse_lang()
    getters = rulebook_getters()
    by_id_getter = {}
    for name, ids in getters.items():
        for rid in ids:
            by_id_getter.setdefault(rid, []).append(name)

    doc = doc_defaults() if args.docs else {}
    results, bad, out_of_range = [], [], []
    for rule in rules:
        rid = rule["id"]
        if rule["kind"] == "fixed":
            sites, status = [], "FIXED"
        else:
            sites = find_readers(rid, skip=(CATALOG,))
            behaviour = [s for s in sites if not s["log"]]
            if not sites:
                status = "NO_READER"
            elif not behaviour:
                status = "LOG_ONLY"
            else:
                status = "OK"
        if status in ("NO_READER", "LOG_ONLY"):
            bad.append(rid)
        problem = range_problem(rule)
        if problem:
            out_of_range.append((rid, problem))
        entry = dict(rule)
        entry.update({
            "label": lang.get("pvpocket.rule.%s.label" % rid, ""),
            "getters": by_id_getter.get(rid, []),
            "status": status,
            "range_problem": problem,
            "sites": sites,
        })
        if args.docs:
            entry["doc_default"] = doc.get(rid)
        results.append(entry)

    width = max(len(r["id"]) for r in results) + 2
    print("%-*s %-2s %-9s %-6s %5s  %s" % (width, "规则", "组", "控件", "默认", "读取点", "状态"))
    print("-" * (width + 40))
    for r in results:
        flag = r["status"] if r["status"] in ("OK", "FIXED") else "!! " + r["status"]
        print("%-*s %-2s %-9s %-6s %5d  %s" % (
            width, r["id"], r["group"], r["kind"], str(r["default"]),
            len(r["sites"]), flag))
        if r["status"] not in ("OK", "FIXED") or args.verbose:
            if r["getters"]:
                print("%-*s   Rulebook: %s" % (width, "", ", ".join(r["getters"])))
            for s in r["sites"]:
                mark = "日志" if s["log"] else "行为"
                print("%-*s   [%s] %s:%d  %s" % (width, "", mark, s["file"], s["line"],
                                                 s["code"][:96]))
            if not r["sites"] and r["status"] != "FIXED":
                print("%-*s   （除 RuleCatalog 外无任何读取点）" % (width, ""))

    fixed = [r["id"] for r in results if r["kind"] == "fixed"]
    configurable = [r for r in results if r["kind"] != "fixed"]
    print()
    print("合计 %d 条：可配置 %d，固定展示 %d（%s）" % (
        len(results), len(configurable), len(fixed), ", ".join(fixed) or "无"))

    if out_of_range:
        print("\n默认值越界（面板会在第一帧把它夹回范围内，玩家看到的是「改了没反应」）：")
        for rid, why in out_of_range:
            print("  ! %-22s %s" % (rid, why))

    # 反向检查：Rulebook 里 public 但没有外部调用者的 getter。
    dead = []
    for name in sorted(getters):
        if not getter_callers(name):
            dead.append(name)
    if dead:
        print("\nRulebook 里的死 getter（没有任何外部调用者）：")
        for name in dead:
            print("  ? %-28s 读 %s" % (name, ", ".join(getters[name])))

    missing, mismatched = check_lang(rules)
    if missing:
        print("\n缺语言键（面板上会是一行空白或一个没有文字的选项）：")
        for rid, key in missing:
            print("  ! %-22s %s" % (rid, key))
    if mismatched:
        print("\n中英语言文件对不上（键缺失或 %%s 数量不同）：")
        for key in mismatched[:40]:
            print("  ! %s" % key)
        if len(mismatched) > 40:
            print("  … 共 %d 条" % len(mismatched))

    if args.docs:
        print("\n文档默认值比对（docs/zh/02-rules.md）：")
        mismatch = 0
        for r in results:
            want = r.get("doc_default")
            if r["kind"] == "fixed":
                continue
            if want is None:
                print("  ? %-22s 文档未写默认值（代码 %s）" % (r["id"], r["default"]))
                continue
            label = default_label(r, lang)
            if label is None:
                # 数字与开关的文档措辞太自由，只在文档里出现了数字时比数字
                m = re.search(r"-?\d+", want)
                if m and str(r["default"]) != m.group(0):
                    print("  ! %-22s 代码 %-6s 文档 %s" % (r["id"], r["default"], want))
                    mismatch += 1
                continue
            hit = label in want or want in label
            if not hit and r["kind"] == "multi":
                hit = all(part in want for part in label.split("、"))
            if not hit:
                print("  ! %-22s 代码 %-6s（%s） 文档 %s"
                      % (r["id"], r["default"], label, want))
                mismatch += 1
        print("  不一致 %d 条" % mismatch if mismatch else "  全部一致")

    if args.coverage:
        count, exercised, never = coverage(rules, args.coverage)
        print("\n运行时覆盖（读了 %d 份 journal）：" % count)
        if not count:
            print("  ? 没有找到对局日志，无法判断")
        print("  跑过非默认值的规则 %d 条：" % len(exercised))
        for rid, dflt, others in exercised:
            print("    OK %-24s 默认 %-4s 跑过 %s" % (rid, dflt, others))
        print("  从未以非默认值跑过 %d 条：" % len(never))
        for rid, why in never:
            print("    ?? %-24s %s" % (rid, why))

    if bad:
        print("\n[pvaudit-rules] 无行为读取点：%d 条 → %s" % (len(bad), ", ".join(bad)))
    else:
        print("\n[pvaudit-rules] 全部可配置规则都有行为读取点")

    problems = {"no_reader": bad,
                "out_of_range": [rid for rid, _ in out_of_range],
                "dead_getters": dead,
                "missing_lang": [key for _rid, key in missing],
                "lang_mismatch": mismatched}
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"rules": results, "problems": problems}, fh,
                      ensure_ascii=False, indent=2)
        print("[pvaudit-rules] 已写出 %s" % args.json)

    return 1 if (bad or out_of_range or dead or missing or mismatched) else 0


if __name__ == "__main__":
    sys.exit(main())
