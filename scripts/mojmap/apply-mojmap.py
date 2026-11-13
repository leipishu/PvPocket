#!/usr/bin/env python3
"""Rewrites source files according to the Yarn -> Mojmap table.

Four passes, from fully reliable to possibly ambiguous:

1. Fully qualified names: net.minecraft.util.math.BlockPos -> net.minecraft.core.BlockPos.
2. Simple names: a file importing net.minecraft.world.World gets `World` -> `Level` in its
   body. Only for classes the file really imports, and in a single pass (no A->B->C chain).
3. Member names: `.getAttackCooldownProgress(` -> `.getAttackStrengthScale(`. Without type
   information this only handles `.name(` and `.name`, and a name with two different targets
   in one file is skipped and reported.
4. Class paths inside strings: mixin `method = "...(Lnet/...;...)"` and
   `@At(target = "Lnet/...;")`. The compiler never sees those, Mixin does.

Lines starting with `//`, `/*` or `*` are skipped entirely.

Usage:
    scripts/mojmap/apply-mojmap.py build/mojmap/yarn-mojmap-1.21.1.tsv [--dry-run]
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Sources live at the repository root: a cell only decides how to compile src/.
SOURCES = os.path.join(ROOT, "src")

COMMENT_LINE = re.compile(r"^\s*(//|/\*|\*)")

# Member names that are never renamed. JDK and fastutil collections reuse MC method names
# (List.get, Map.Entry.getValue, List.of), so renaming by name corrupts collection calls.
JDK_NAMES = set("""
get getValue getKey getOrDefault getOrThrow setValue contains containsKey containsValue
add addAll remove removeAll removeIf put putAll putIfAbsent computeIfAbsent computeIfPresent
merge size isEmpty isBlank of copyOf asList build builder create clear clone equals hashCode
toString compareTo iterator listIterator stream parallelStream forEach forEachOrdered
map mapToInt mapToLong mapToObj filter collect toList toSet toMap toArray sorted sort distinct
limit skip count anyMatch allMatch noneMatch findFirst findAny reduce min max sum average
orElse orElseGet orElseThrow ifPresent ifPresentOrElse apply accept test and then compose negate
keySet values entrySet indexOf lastIndexOf subList join format length charAt substring split
trim strip replace replaceAll startsWith endsWith toLowerCase toUpperCase valueOf parseInt
asLong asInt asDouble asFloat doubleValue intValue longValue floatValue booleanValue
addFirst addLast getFirst getLast poll peek push pop offer
getLong putLong removeLong containsLong longIterator longStream long2ObjectEntrySet
""".split())

# Domain names of this repository that collide with an MC member (bounds, damage,
# onBlockChanged). Renaming them breaks the declarations that use them.
OWN_COLLISIONS = set("""
bounds damage onBlockChanged encode floorY from
""".split())


STATIC_METHODS = {}
STATIC_FIELDS = {}
REGEX_RULES = []


def load_table(path):
    """Reads the table, grouping members by owning class.

    Grouping is a performance requirement: after hierarchy expansion there are 180k methods,
    and a full scan per file would be hundreds of millions of comparisons.
    """
    classes = {}
    methods = {}
    fields = {}
    with open(path, encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, 1):
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "class":
                classes[parts[1]] = parts[2]
            elif parts[0] == "method":
                methods.setdefault(parts[1], {})[parts[2]] = parts[3]
            elif parts[0] == "field":
                fields.setdefault(parts[1], {})[parts[2]] = parts[3]
            elif parts[0] == "static":
                # static  receiver  old  new  [arg count, default -1 = any]: a static call names
                # its receiver class, so overloads can be told apart by argument count.
                count = int(parts[4]) if len(parts) > 4 and parts[4].strip() else -1
                STATIC_METHODS[(parts[1], parts[2], count)] = parts[3]
            elif parts[0] == "staticfield":
                STATIC_FIELDS[(parts[1], parts[2])] = parts[3]
            elif parts[0] == "regex":
                # regex  pattern  replacement (Python syntax, \1 backreferences). These cover the
                # sites that need context: two `add` members in one file with different targets.
                try:
                    REGEX_RULES.append((re.compile(parts[1]), parts[2].replace("\\n", "\n")))
                except re.error as error:
                    raise SystemExit("%s:%d 的正则写错了：%s\n  %s"
                                     % (path, lineno, error, parts[1]))
    return classes, methods, fields


def simple(fqn):
    return fqn.rsplit(".", 1)[-1]


def is_identifier(name):
    """`Outer$3` has the name `3`, which is not an identifier and cannot be substituted."""
    return bool(name) and (name[0].isalpha() or name[0] in "_$") and all(
        ch.isalnum() or ch in "_$" for ch in name)


def nested_target(fqn, classes):
    """`a.b.Outer.Inner` is `a.b.Outer$Inner` in the table; try each dot-to-dollar split.

    Returns the Mojmap name still in $ form, or None. Two levels of nesting also have to hit.
    """
    parts = fqn.split(".")
    for depth in range(1, len(parts) - 1):
        candidate = ".".join(parts[:-depth]) + "$" + "$".join(parts[-depth:])
        if candidate in classes:
            return classes[candidate]
    return None


MIXIN_TARGET = re.compile(r"@Mixin\(\s*([\w.]+)\s*\.class\s*\)")
# MIXIN_METHOD reads `method = "name"`: the name always follows the quote directly.
MIXIN_METHOD = re.compile(r'(method\s*=\s*")(\w+)')
# AT_TARGET reads `@At(target = "Lpkg/Cls;name(desc)")`.
AT_TARGET = re.compile(r'(target\s*=\s*"L)([\w/$]+);(\w+)')
ACCESSOR = re.compile(r'@(Accessor|Invoker)\(\s*"(\w+)"\s*\)')


def rewrite_mixin_strings(text, classes, methods, fields, import_map, report, where):
    """Rewrites method names, field names and @At targets inside mixin annotations.

    These are strings, so the compiler ignores them. A wrong one means the injection point
    does not match, the mixin is skipped silently, and the feature disappears.
    """
    match = MIXIN_TARGET.search(text)
    if match is None:
        return text
    target_simple = match.group(1).rsplit(".", 1)[-1]
    target = import_map.get(target_simple)
    if target is None:
        report.append("%s：@Mixin 的目标 %s 找不到导入，注解里的名字没改" % (where, target_simple))
        return text
    yarn_target = target

    def method_repl(m):
        old = m.group(2)
        new = methods.get(yarn_target, {}).get(old)
        return m.group(1) + (new if new else old)

    text = MIXIN_METHOD.sub(method_repl, text)

    def at_repl(m):
        class_path = m.group(2)
        old_class = class_path.replace("/", ".")
        new_class = classes.get(old_class)
        new_name = methods.get(old_class, {}).get(m.group(3))
        if new_class is None and new_name is None:
            return m.group(0)
        return (m.group(1)
                + (new_class.replace(".", "/") if new_class else class_path) + ";"
                + (new_name if new_name else m.group(3)))

    text = AT_TARGET.sub(at_repl, text)

    # A target split by `+`: `"Lpkg/Cls;" + "method(descriptor)ret"`. AT_TARGET only sees the
    # class name in the first literal, so the method name needs its own pass.
    split_target = re.compile(r'"L([\w/$]+);"(\s*\+\s*)"(\w+)')

    def split_repl(m):
        class_path = m.group(1)
        old_class = class_path.replace("/", ".")
        new_class = classes.get(old_class)
        new_name = methods.get(old_class, {}).get(m.group(3))
        if new_class is None and new_name is None:
            return m.group(0)
        return ('"L' + (new_class.replace(".", "/") if new_class else class_path) + ';"'
                + m.group(2) + '"' + (new_name if new_name else m.group(3)))

    text = split_target.sub(split_repl, text)

    def accessor_repl(m):
        kind, old = m.group(1), m.group(2)
        table = fields if kind == "Accessor" else methods
        new = table.get(yarn_target, {}).get(old)
        return "@%s(\"%s\")" % (kind, new if new else old)

    text = ACCESSOR.sub(accessor_repl, text)
    return text


def rewrite_file(path, classes, methods, fields, report):
    with open(path, encoding="utf-8") as handle:
        original = handle.read()

    imports = re.findall(r"^import\s+([\w.]+)\s*;", original, re.M)
    import_map = {fqn.rsplit(".", 1)[-1]: fqn for fqn in imports}

    # Whether the receiver is a local object, decided from declarations; see local_types.
    declared = local_types(original)
    own = own_types()
    # Names declared in this file. A call without a receiver cannot be told apart from a
    # statically imported MC method, and a declaration here settles it.
    mine = own_names(path)

    def ours(receiver):
        return receiver in own or receiver in ("this", "super") or declared.get(receiver) in own

    # ---- 0. strings in mixin annotations. This runs first and looks names up as Yarn: after
    # the imports become Mojmap, the table keys stop matching.
    text = rewrite_mixin_strings(original, classes, methods, fields, import_map, report,
                                os.path.basename(path))

    imported = [fqn for fqn in imports if fqn in classes]

    # ---- 1. fully qualified names. The regex backs off to the longest known prefix, because
    # source can write `net.minecraft.enchantment.Enchantments.KNOCKBACK` with a member attached.
    def fqn_repl(match):
        parts = match.group(0).split(".")
        for cut in range(len(parts), 2, -1):
            candidate = ".".join(parts[:cut])
            target = classes.get(candidate)
            if target is None:
                target = nested_target(candidate, classes)
                if target is not None:
                    target = target.replace("$", ".")
            if target is not None:
                return target + ("." + ".".join(parts[cut:]) if cut < len(parts) else "")
        return match.group(0)

    text = re.sub(r"\bnet\.minecraft\.[\w.]*\w\b", fqn_repl, text)

    # Nested imports: source writes Outer.Inner and the table has Outer$Inner.
    for fqn in imports:
        if fqn in classes:
            continue
        target = nested_target(fqn, classes)
        if target is not None:
            text = text.replace("import " + fqn + ";", "import " + target.replace("$", ".") + ";")
            import_map[fqn.rsplit(".", 1)[-1]] = target
            imported.append(target)

    # ---- mask fully qualified names and string literals before the next two passes. Member
    # replacement matches `.name`, and `net.minecraft.world` contains `.world`.
    masked, stash = mask(text)

    # ---- 2. simple names. The key is the identifier as written. For a nested import
    # (CustomPayload.Id) the two sides take their last segment separately.
    simple_map = {}
    kept = set()
    for fqn in imports:
        target = classes.get(fqn)
        if target is None:
            target = nested_target(fqn, classes)
        if target is None:
            kept.add(fqn.rsplit(".", 1)[-1])
            continue
        old = fqn.rsplit(".", 1)[-1]
        new = target.rsplit("$", 1)[-1].rsplit(".", 1)[-1]
        if old != new:
            simple_map[old] = new

    # Nested types inherited from an imported outer class need no import. The conflict check has
    # to look at the whole table: `Builder` appears in dozens of classes with different targets.
    every_inner = {}
    for yarn_fqn, moj_fqn in classes.items():
        if "$" not in yarn_fqn or "$" not in moj_fqn:
            continue
        every_inner.setdefault(yarn_fqn.rsplit("$", 1)[-1], set()).add(moj_fqn.rsplit("$", 1)[-1])

    nested_simple = {}
    nested_conflict = set()
    for yarn_fqn, moj_fqn in classes.items():
        if "$" not in yarn_fqn or "$" not in moj_fqn:
            continue
        outer_yarn = yarn_fqn.split("$", 1)[0]
        if outer_yarn not in imported:
            continue
        old_inner = yarn_fqn.rsplit("$", 1)[-1]
        new_inner = moj_fqn.rsplit("$", 1)[-1]
        if old_inner == new_inner or not is_identifier(old_inner):
            continue
        if len(every_inner.get(old_inner, ())) > 1:
            nested_conflict.add(old_inner)
        if old_inner in nested_simple and nested_simple[old_inner] != new_inner:
            nested_conflict.add(old_inner)
        nested_simple[old_inner] = new_inner
    for name in sorted(nested_conflict):
        nested_simple.pop(name, None)
        report.append("%s：嵌套类型名 %s 对应多个新名字，跳过" % (os.path.basename(path), name))
    # No chaining: with `Id`->`Type` and `Type`->`TypeAndCodec` in one map, a single pass would
    # turn Id into TypeAndCodec. Entries whose new name is another old name are dropped.
    chained = {new for new in nested_simple.values() if new in nested_simple}
    for old, new in nested_simple.items():
        if old in chained:
            continue
        simple_map.setdefault(old, new)

    # Nested classes: the table uses $, source uses a dot path. This runs before the bare-name
    # pass, otherwise Outer is renamed first and the dot path never matches.
    nested_map = {}
    for yarn_fqn, moj_fqn in classes.items():
        if "$" not in yarn_fqn or "$" not in moj_fqn:
            continue
        outer_yarn = yarn_fqn.split("$", 1)[0]
        if outer_yarn not in imported:
            continue
        inner = yarn_fqn.split("$", 1)[1]
        if not is_identifier(inner):
            continue
        old_path = simple(outer_yarn) + "." + inner.replace("$", ".")
        new_path = simple(moj_fqn.split("$", 1)[0]) + "." + moj_fqn.split("$", 1)[1].replace("$", ".")
        if old_path != new_path:
            nested_map[old_path] = new_path

    if simple_map:
        # A clash is real only when the new name is held by an import that is not renamed: in a
        # single pass A->B and B->C can both hold.
        for old, new in list(simple_map.items()):
            if new in kept:
                report.append("%s：简单名 %s → %s 会撞上已有的导入，跳过"
                              % (os.path.basename(path), old, new))
                del simple_map[old]

    if nested_map:
        pattern = re.compile(r"\b(" + "|".join(sorted(map(re.escape, nested_map), key=len,
                                                      reverse=True)) + r")\b")
        masked = pattern.sub(lambda m: nested_map[m.group(1)], masked)

    if simple_map:
        pattern = re.compile(r"\b(" + "|".join(sorted(map(re.escape, simple_map), key=len,
                                                        reverse=True)) + r")\b")
        masked = sub_outside_comments(masked, pattern,
                                      lambda m: simple_map[m.group(1)])

    # ---- 3. member names
    method_map = {}
    field_map = {}
    conflicts = set()
    for fqn in imported:
        for old, new in methods.get(fqn, {}).items():
            if old in method_map and method_map[old] != new:
                conflicts.add(old)
            method_map[old] = new
        for old, new in fields.get(fqn, {}).items():
            if old in field_map and field_map[old] != new:
                conflicts.add(old)
            field_map[old] = new
    for name in sorted(conflicts):
        method_map.pop(name, None)
        field_map.pop(name, None)
        report.append("%s：成员名 %s 在同一文件里对应多个新名字，跳过" % (os.path.basename(path), name))

    # JDK names are never renamed; see the JDK_NAMES comment.
    for name in [n for n in method_map if n in JDK_NAMES or n in OWN_COLLISIONS]:
        method_map.pop(name, None)

    # Fields with the same name but different targets anywhere in the table are never renamed:
    # `Blocks.AIR` would otherwise be taken over by `Entity.AIR -> DATA_AIR_SUPPLY_ID`.
    for name in [n for n in field_map if n in ambiguous_fields(fields)]:
        field_map.pop(name, None)

    # Names declared in this file are left alone: renaming a private `getUuid()` points the
    # error at a declaration that should never have changed.
    pass

    if method_map:
        # Only `.name(` is rewritten, and only when the receiver is not a local class.
        # The `(?<![\w$])` guard is required: `$` is a legal identifier character.
        pattern = re.compile(r"(?<![\w$])(?:([\w$]+)\.)?(" + "|".join(
            sorted(map(re.escape, method_map), key=len, reverse=True)) + r")\s*\(")

        def method_repl(m):
            receiver, name = m.group(1), m.group(2)
            if receiver is None:
                # The receiver group is optional, so declarations match too: `void
                # onBlockChanged(` looks exactly like a call without a receiver.
                if is_declaration(m.string, m.start()) or name in mine:
                    return m.group(0)
                # A chained call (`copy().formatted(...)`) has a `)` before the dot; the dot is
                # already there, so it must not be added again.
                return method_map[name] + "("
            if ours(receiver):
                return m.group(0)
            return receiver + "." + method_map[name] + "("

        masked = pattern.sub(method_repl, masked)
        ref = re.compile(r"::(" + "|".join(sorted(map(re.escape, method_map), key=len,
                                                reverse=True)) + r")\b")
        # Method references too: `BoundaryRenderer::render` names a local method.
        masked = ref.sub(lambda m: m.group(0) if m.group(1) in mine
                         else "::" + method_map[m.group(1)], masked)

    if field_map:
        only_fields = {k: v for k, v in field_map.items() if k not in method_map}
        if only_fields:
            pattern = re.compile(r"\.(" + "|".join(sorted(map(re.escape, only_fields), key=len,
                                                         reverse=True)) + r")\b(?!\s*\()")
            masked = pattern.sub(lambda m: "." + only_fields[m.group(1)], masked)

    # The hand-written overrides run again unconditionally: per-file conflict filtering drops
    # them when one file imports two classes that share the name.
    if OVERRIDES_METHODS:
        pattern = re.compile(r"(?<![\w$])(?:([\w$]+)\.)?(" + "|".join(
            sorted(map(re.escape, OVERRIDES_METHODS), key=len, reverse=True)) + r")\s*\(")

        def override_repl(m):
            receiver, name = m.group(1), m.group(2)
            if receiver is None:
                # Same as above: no receiver and no chaining means a declaration.
                if is_declaration(m.string, m.start()) or name in mine:
                    return m.group(0)
                return OVERRIDES_METHODS[name] + "("
            if ours(receiver):
                return m.group(0)
            return receiver + "." + OVERRIDES_METHODS[name] + "("

        masked = pattern.sub(override_repl, masked)

    # ---- 3b. static members. The receiver is a class name, so `ResourceLocation.of` can be
    # matched exactly; the argument count tells the two `of` overloads apart.
    for (owner, old, count), new in sorted(STATIC_METHODS.items()):
        pattern = re.compile(r"\b" + re.escape(owner) + r"\." + re.escape(old) + r"\s*\(")

        def static_repl(m, owner=owner, new=new, count=count):
            if count >= 0 and count_args(m.string, m.end() - 1) != count:
                return m.group(0)
            # The pattern includes the open paren, so the replacement adds it back.
            return owner + "." + new + "("

        masked = pattern.sub(static_repl, masked)

    for (owner, old), new in sorted(STATIC_FIELDS.items()):
        pattern = re.compile(r"\b" + re.escape(owner) + r"\." + re.escape(old) + r"\b(?!\s*\()")
        masked = pattern.sub(lambda m, owner=owner, new=new: owner + "." + new, masked)

    text = unmask(masked, stash)

    # ---- 3c. hand-written context rules, which need the full unmasked text.
    for pattern, replacement in REGEX_RULES:
        text = pattern.sub(replacement, text)

    # ---- 4. class paths inside strings (mixin sees them, the compiler does not).
    def path_repl(match):
        fqn = match.group(1).replace("/", ".")
        target = classes.get(fqn)
        return "L" + (target.replace(".", "/") if target else match.group(1)) + ";"

    text = re.sub(r"L(net/minecraft/[\w/$]+);", path_repl, text)

    if text != original:
        if not DRY_RUN:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(text)
        return True
    return False


def count_args(text, open_paren):
    """Counts top-level arguments in the call starting at `open_paren` (`()` counts as 0).

    Only bracket depth is tracked, which is enough to tell `of(s)` from `of(a, b)`.
    """
    depth = 0
    commas = 0
    content = False
    for index in range(open_paren, len(text)):
        ch = text[index]
        if ch in "([{":
            depth += 1
            if depth == 1:
                continue
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return 0 if not content else commas + 1
        elif ch == "," and depth == 1:
            commas += 1
        if depth >= 1 and not ch.isspace():
            content = True
    return -1


def sub_outside_comments(text, pattern, repl):
    """Substitutes line by line, leaving whole-line comments (`//`, `/*`, `*`) untouched.

    A trailing comment is replaced along with the code, which is an accepted trade-off.
    """
    out = []
    for line in text.split("\n"):
        if not COMMENT_LINE.match(line):
            out.append(pattern.sub(repl, line))
            continue
        # Code can continue after `/** ... */` on the same line; skipping the whole line would
        # leave that code unrenamed.
        end = line.find("*/")
        if end >= 0 and line.lstrip().startswith("/*"):
            out.append(line[:end + 2] + pattern.sub(repl, line[end + 2:]))
        else:
            out.append(line)
    return "\n".join(out)


MASKABLE = re.compile(
    r"\b(?:net\.minecraft|com\.mojang|com\.leipishu|net\.fabricmc)\.[\w.$]+"
    r"|\"(?:[^\"\\\n]|\\.)*\"")
MASK = "\x01%d\x01"


def mask(text):
    """Replaces fully qualified names and string literals with placeholders."""
    stash = []

    def take(match):
        stash.append(match.group(0))
        return MASK % (len(stash) - 1)

    return MASKABLE.sub(take, text), stash


def unmask(text, stash):
    def give(match):
        return stash[int(match.group(1))]

    return re.sub(r"\x01(\d+)\x01", give, text)


DRY_RUN = False
OVERRIDES_METHODS = {}
OWN_TYPES = None
DECLARATION = re.compile(
    r"\b(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\],. ]+?\s+(\w+)\s*[(;=]")

# Tokens that precede a name in a declaration: primitives, modifiers, generics.
DECL_TAIL = re.compile(
    r"(?:\b(?:void|boolean|byte|short|int|long|float|double|char|var|public|private|protected|"
    r"static|final|abstract|synchronized|native|default|transient|volatile)\b"
    r"|[>\]])"
    r"\s*$")


def is_declaration(text, start):
    """True when the name at `start` is a declaration rather than a call.

    A call always has a dot in front of it; a declaration has a return type or a modifier. A
    declaration with `@Override` is not protected: its name has to follow MC.
    """
    if start == 0:
        return False
    before = text[:start]
    if before.rstrip().endswith("."):
        return False
    tail = before.rsplit("\n", 1)[-1]
    if DECL_TAIL.search(tail):
        return not has_override(before)
    # A plain class name as return type (`MutableText name(`, `Component get(`): the word
    # before it starts with an upper case letter.
    word = re.search(r"([\w$]+)\s*$", tail)
    if word and word.group(1)[0].isupper():
        return not has_override(before)
    return False


def has_override(before):
    """True when `@Override` sits above this declaration in the same member."""
    window = before[-400:]
    for index in range(len(window) - 1, -1, -1):
        if window[index] in ";{}":
            window = window[index + 1:]
            break
    return "@Override" in window


AMBIGUOUS_FIELDS = None


def ambiguous_fields(fields):
    """Field names that map to different new names somewhere in the table.

    `AIR` is the typical case: `Blocks.AIR` stays, `Entity.AIR` becomes DATA_AIR_SUPPLY_ID.
    """
    global AMBIGUOUS_FIELDS
    if AMBIGUOUS_FIELDS is None:
        seen = {}
        for members in fields.values():
            for old, new in members.items():
                seen.setdefault(old, set()).add(new)
        AMBIGUOUS_FIELDS = {name for name, targets in seen.items() if len(targets) > 1}
    return AMBIGUOUS_FIELDS


def own_types():
    """Type names of this mod (its file names), used to recognise our own receivers."""
    global OWN_TYPES
    if OWN_TYPES is None:
        names = set()
        for _base, _dirs, files in os.walk(SOURCES):
            for name in files:
                if name.endswith(".java"):
                    names.add(name[:-len(".java")])
        OWN_TYPES = names
    return OWN_TYPES


LOCAL_DECL = re.compile(
    r"(?:^|[\s(,;{])([A-Z][\w$]*)(?:<[^<>;=()]*>)?(?:\[\])?\s+([a-z_$][\w$]*)\s*(?=[=;,):])")


def local_types(text):
    """`variable name -> declared type name` for one file.

    Without type information `seed.save()` and `player.save()` look alike, so our own
    `Loadout.save()` would be taken over by an MC `save`.
    """
    found = {}
    for match in LOCAL_DECL.finditer(text):
        found.setdefault(match.group(2), match.group(1))
    return found


def own_names(path):
    """Method and field names declared in this file."""
    with open(path, encoding="utf-8") as handle:
        return set(DECLARATION.findall(handle.read()))


def main():
    global DRY_RUN
    parser = argparse.ArgumentParser()
    parser.add_argument("table")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--overrides", default=None,
                        help="手工补充的映射（对照表自动生成，这一份是补漏的）")
    args = parser.parse_args()
    DRY_RUN = args.dry_run

    classes, methods, fields = load_table(args.table)
    if args.overrides and os.path.exists(args.overrides):
        extra_c, extra_m, extra_f = load_table(args.overrides)
        classes.update(extra_c)
        for owner, table in extra_m.items():
            methods.setdefault(owner, {}).update(table)
        for owner, table in extra_f.items():
            fields.setdefault(owner, {}).update(table)
        for table in extra_m.values():
            OVERRIDES_METHODS.update(table)
        print("手工补充：%d 个类、%d 个方法、%d 个字段"
              % (len(extra_c), sum(len(t) for t in extra_m.values()),
                 sum(len(t) for t in extra_f.values())))
    print("对照表：%d 个类、%d 个方法、%d 个字段"
          % (len(classes), sum(len(t) for t in methods.values()),
             sum(len(t) for t in fields.values())))

    report = []
    changed = 0
    total = 0
    for base, _dirs, names in os.walk(SOURCES):
        for name in names:
            if not name.endswith(".java"):
                continue
            total += 1
            if rewrite_file(os.path.join(base, name), classes, methods, fields, report):
                changed += 1

    for line in report:
        print("  ! " + line)
    print("%d/%d 个文件有改动%s" % (changed, total, "（dry run，未写盘）" if DRY_RUN else ""))


if __name__ == "__main__":
    main()
