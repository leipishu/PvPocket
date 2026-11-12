#!/usr/bin/env python3
"""按 Yarn → Mojmap 对照表改写源码。

分工：`mojmap-table.py` 只负责生成对照表（那是唯一需要联网、需要两份映射接合的地方），本脚本只做
文本替换。两者分开是因为替换规则需要反复调，而对照表生成一次就够了。

替换分四步，从"完全可靠"排到"可能有歧义"：

1. **全限定名**：`net.minecraft.util.math.BlockPos` → `net.minecraft.core.BlockPos`。只可能出现在
   导入行和少数内联处，替换是安全的。
2. **简单名**：文件导入了 `net.minecraft.world.World`，正文里写的 `World` 要变成 `Level`。
   只对**该文件真的导入过的**类生效，且单趟替换（A→B、B→C 不会连锁）。
3. **成员名**：`.getAttackCooldownProgress(` → `.getAttackStrengthScale(`。这一步没有类型信息，所以
   保守：只认 `.名字(` 和 `.名字` 形式，且同一个文件里同一个旧名字若对应两个不同的新名字就跳过并
   报告（那种情况必须人看）。
4. **字符串里的类路径**：mixin 的 `method = "...(Lnet/minecraft/util/math/BlockPos;...)"` 和
   `@At(target = "Lnet/minecraft/...;")`。编译器完全看不见它们，但 mixin 运行时看得见。

注释与 javadoc 不参与替换（以 `//`、`/*`、`*` 开头的行整行跳过），否则散文里的 "World" 会被改成
"Level"，那属于把文档改坏。

用法：
    scripts/apply-mojmap.py build/mojmap/yarn-mojmap-1.21.1.tsv [--dry-run]
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 源码在仓库根：cell 只决定怎么编译 src/，源码本身不分版本也不分加载器。
SOURCES = os.path.join(ROOT, "src")

COMMENT_LINE = re.compile(r"^\s*(//|/\*|\*)")

# 不做成员改名的名字。
#
# 成员名替换没有类型信息，而 Java 标准库与 fastutil 的集合方法名和 MC 的方法名大面积重合：
# `List.get` / `Map.Entry.getValue` / `Set.contains` / `List.add` / `List.of` / `Builder.build`
# 全都撞上 MC 的同名方法。按名字全局替换的结果是 `slots.get(slot)` 变成 `slots.offset(slot)`、
# `entry.getValue()` 变成 `entry.location()`——编译报错指向一行完全无辜的集合操作。
#
# 所以这些名字一律不改，留给编译器逐站点发现（真需要改的 MC 调用会报"找不到符号"，那是可定位的；
# 误改的 JDK 调用只会让错误指向别处）。这份名单只增不减：加错了顶多多几条编译错误，漏一个就是
# 一批语义错误。
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

# 我们自己起的名字，恰好和某个 MC 方法同名。
#
# 这一类不是"JDK 撞名"，是"我们的领域词撞名"：`ArenaManager.Screen.bounds()` 被 AABB 的
# `unitCubeFromLowerCorner` 顶掉、`LoadoutEntry.damage()` 被 `Entity.hurt` 顶掉、
# `ArenaRegion.onBlockChanged()` 被 `Level.onBlockStateChange` 顶掉。三处报错都指向调用处，
# 而声明在另一个文件里、名字看着完全正常。
#
# 判断标准：这个名字在本仓库里**只属于我们**，MC 那边即使有同名的也不会被我们调用。
OWN_COLLISIONS = set("""
bounds damage onBlockChanged encode floorY from
""".split())


STATIC_METHODS = {}
STATIC_FIELDS = {}
REGEX_RULES = []


def load_table(path):
    """读表，成员按**所属类**分组。

    分组不是为了好看：层次展开后方法有十八万条，每个文件再按 (类, 名) 全表扫一遍就是几亿次比较，
    跑一个版本要几分钟。分组之后每个文件只碰自己 import 的那几十个类。
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
                # static  接收者类简名  旧名  新名  [参数个数，缺省 -1 = 不限]
                #
                # 静态调用写成 `ResourceLocation.of(a, b)`，接收者就是类名本身，可以精确命中——
                # 而 `of` 在 JDK 集合上到处都是（`List.of`），按名字全局改会误伤，所以只能这样分出来。
                # 同名重载去向不同的（`of(s)` → parse、`of(a, b)` → fromNamespaceAndPath）靠参数个数区分。
                count = int(parts[4]) if len(parts) > 4 and parts[4].strip() else -1
                STATIC_METHODS[(parts[1], parts[2], count)] = parts[3]
            elif parts[0] == "staticfield":
                STATIC_FIELDS[(parts[1], parts[2])] = parts[3]
            elif parts[0] == "regex":
                # 正则写错（漏一个转义括号是最常见的）时立刻说清楚是哪一行，而不是让整趟改名
                # 跑到一半崩在某个文件上——那样源码会停在一个"改了一半"的状态，报出来的错误
                # 全是残留物，看起来像模组坏了。
                # regex  正则  替换（Python 语法，可用 \1 回引）
                #
                # 留给"必须看上下文才能定的那几处"：`ItemEnchantments.Mutable.add` 与
                # `ItemAttributeModifiers.Builder.add` 同名但去向不同，`builder` 这个变量名在同一个
                # 文件里出现了两次。这类不能按名字、也不能按接收者类名定位，只能写死一段上下文。
                # 替换串里的 \n 还原成换行：TSV 是按行读的，多行替换只能在单元格里写转义。
                try:
                    REGEX_RULES.append((re.compile(parts[1]), parts[2].replace("\\n", "\n")))
                except re.error as error:
                    raise SystemExit("%s:%d 的正则写错了：%s\n  %s"
                                     % (path, lineno, error, parts[1]))
    return classes, methods, fields


def simple(fqn):
    return fqn.rsplit(".", 1)[-1]


def is_identifier(name):
    """匿名内部类在表里叫 `Outer$3`，它的"名字"是 `3`——不是标识符，不能拿去替换。"""
    return bool(name) and (name[0].isalpha() or name[0] in "_$") and all(
        ch.isalnum() or ch in "_$" for ch in name)


def nested_target(fqn, classes):
    """`a.b.Outer.Inner` 在表里是 `a.b.Outer$Inner`；逐段把点号换成 $ 去试。

    返回表里的 Mojmap 名（仍是 $ 形式），找不到返回 None。只试最后几段，因为 `Outer.Inner.Deep`
    这种两层嵌套也要能命中。
    """
    parts = fqn.split(".")
    for depth in range(1, len(parts) - 1):
        candidate = ".".join(parts[:-depth]) + "$" + "$".join(parts[-depth:])
        if candidate in classes:
            return classes[candidate]
    return None


MIXIN_TARGET = re.compile(r"@Mixin\(\s*([\w.]+)\s*\.class\s*\)")
# method = "名字" 或 method = "名字(描述符)…"；名字永远紧跟引号，描述符才可能被 + 拼开。
MIXIN_METHOD = re.compile(r'(method\s*=\s*")(\w+)')
# @At(target = "Lpkg/Cls;名字(…")
AT_TARGET = re.compile(r'(target\s*=\s*"L)([\w/$]+);(\w+)')
ACCESSOR = re.compile(r'@(Accessor|Invoker)\(\s*"(\w+)"\s*\)')


def rewrite_mixin_strings(text, classes, methods, fields, import_map, report, where):
    """把 mixin 注解里的方法名 / 字段名 / @At 目标改成 Mojmap 名。

    这些是**字符串**，编译器一个字都不看；改错了的表现是注入点匹配不到，mixin 静默跳过，游戏照跑，
    只是那个功能没了。`MixinTargetCheck` 事后能抓到，但先改对更省事。
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

    # 被 `+` 拼开的目标：`"Lpkg/Cls;" + "方法(描述符)返回"`。
    #
    # 长描述符几乎总是这么写的，而 AT_TARGET 只看得见第一个字面量里的类名，方法名留在第二个
    # 字面量里没人管。表现是：类改对了、方法名还是 Yarn 的，mixin 注入点匹配不到（0 targets），
    # 游戏照跑、功能静默消失。MixinTargetCheck 当时也没抓到，因为它同样只读第一个字面量。
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

    # 接收者是不是"我们自己的对象"，靠变量声明判断——见 local_types。
    declared = local_types(original)
    own = own_types()
    # 本文件自己声明的方法名。无接收者的调用（`quad(...)`、`send(...)`）分不清是"我们自己的
    # 方法"还是"静态导入的 MC 方法"，而这个名字在本文件里被声明过，那就是我们自己的。
    mine = own_names(path)

    def ours(receiver):
        return receiver in own or receiver in ("this", "super") or declared.get(receiver) in own

    # ---- 0. mixin 注解里的字符串
    #
    # 必须排在最前面，而且必须用 Yarn 名去查表：这些字符串是"旧名字"，一旦前面的步骤把文件里的
    # 导入改成 Mojmap，就再也对不上表的键了。
    text = rewrite_mixin_strings(original, classes, methods, fields, import_map, report,
                                os.path.basename(path))

    imported = [fqn for fqn in imports if fqn in classes]

    # ---- 1. 全限定名（点号形式）
    #
    # 正则按"最长前缀"退让，而不是整串查表。源码里会出现 `net.minecraft.enchantment.Enchantments.KNOCKBACK`
    # 这种**类名后面还挂着成员**的写法，整串当然查不到表——早期版本就这么静默放过了，于是导入行
    # 改对了、内联的那一处没改，报错是"程序包 net.minecraft.enchantment 不存在"，指向一行看起来
    # 完全正确的代码。
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

    # 嵌套类的导入：源码写的是 Outer.Inner，表里是 Outer$Inner，上面的全限定名替换看不见它。
    for fqn in imports:
        if fqn in classes:
            continue
        target = nested_target(fqn, classes)
        if target is not None:
            text = text.replace("import " + fqn + ";", "import " + target.replace("$", ".") + ";")
            import_map[fqn.rsplit(".", 1)[-1]] = target
            imported.append(target)

    # ---- 遮蔽：把全限定名和字符串字面量挖掉，后面两步只该动"代码里的裸名字"
    #
    # 这一步是必须的，不是保险。成员名替换匹配的是 `.名字`，而 `net.minecraft.world` 里正好有一个
    # `.world`——MC 有字段 world → level（Entity.level），于是整条包名会被改成 `net.minecraft.level`，
    # 而导入行自己看着还挺像回事。踩过一次：118 个文件全被这条规则污染。
    masked, stash = mask(text)

    # ---- 2. 简单名
    #
    # 键是源码里写的那个标识符，值是它该变成的标识符。顶层类两边都是最后一段；嵌套导入
    # （import ...CustomPayload.Id）左边是 Id、右边是 Type，得分开取。
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

    # 继承来的嵌套类型：`implements CustomPayload` 之后正文里可以直接写 `Id`，根本没有 import。
    # 只对**本文件导入过的外层类**的嵌套类型做，且同名冲突就跳过——这是启发式，编译器兜底。
    #
    # 冲突判断必须看**全表**，不能只看本文件导入的那几个外层类：`Builder` 这种名字在几十个类里
    # 都有，`AttributeModifiersComponent.Builder` → `ItemAttributeModifiers.Builder`（名字没变），
    # 而 `ItemEnchantmentsComponent.Builder` → `ItemEnchantments.Mutable`。只看本文件的话两者
    # 都进了表、后一个把前一个顶掉，于是 `ItemAttributeModifiers.Builder` 被改成 `Mutable`。
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
    # 不去串：`Id`→`Type` 之后若再把 `Type`→`TypeAndCodec` 也放进同一张表，单趟替换里
    # `CustomPayload.Id` 先变成 `...Type`，紧接着那个 `Type` 又被改成 `TypeAndCodec`——名字是对的
    # 起点、错的重点。凡是"新名字又是另一个旧名字"的条目一律丢掉。
    chained = {new for new in nested_simple.values() if new in nested_simple}
    for old, new in nested_simple.items():
        if old in chained:
            continue
        simple_map.setdefault(old, new)

    # 嵌套类：源码里写的是 Outer.Inner，不是 Outer$Inner。表里两者都是 $ 形式，要还原成点号路径。
    # 必须先于裸名字替换——否则 Outer 先被改名，这条点号路径就再也匹配不上了。
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
        # 撞名判断看的是"没被改名的导入"，不是"文件里出现过的名字"：单趟替换里 A→B、B→C 可以同时
        # 成立（RegistryKeys→Registries 与 Registries→BuiltInRegistries 就是一对），只有新的名字
        # 被一个**不改名**的导入占着时才是真冲突。
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

    # ---- 3. 成员名
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

    # JDK 同名方法一律不改，理由见 JDK_NAMES 上的注释。
    for name in [n for n in method_map if n in JDK_NAMES or n in OWN_COLLISIONS]:
        method_map.pop(name, None)

    # 字段：全表范围内同名不同义的，一律不改。
    #
    # 字段没有层次展开（同名会串味），所以按类分组的表看起来是干净的；但这里的 field_map 是按
    # **名字**合并的，`Blocks.AIR` 于是被 `Entity.AIR → DATA_AIR_SUPPLY_ID` 顶掉了——错误信息
    # 是"找不到符号 DATA_AIR_SUPPLY_ID"，指向一行 `Blocks.AIR`。只有同名的所有类都指向同一个
    # 新名字时才敢改。
    for name in [n for n in field_map if n in ambiguous_fields(fields)]:
        field_map.pop(name, None)

    # 本文件自己声明的名字不动：`private UUID getUuid()` 里的 `getUuid` 是我们自己的方法，
    # 顺手被当成 MC 的成员改名，报错会指向一个根本不该改的声明。只按本文件算——按全仓库算会把
    # 真正需要改的调用一起挡掉（`getUuid` 就是这样被整批漏掉的）。
    # 不按"本文件声明过的名字"整批排除：ArenaManager 里有一个自己的 formatted 辅助方法，于是那个
    # 文件里所有 `text.copy().formatted(...)` 都不改了。声明本身不会被 `.名字(` 匹配到，所以真正
    # 需要防的是"接收者是我们自己的对象"，那件事由下面的 own_types 负责。
    pass

    if method_map:
        # 只改 ".名字("，并且要求**接收者不是我们自己的类**。`PvPocket.id(...)` 就栽在这里：
        # `id` 同时是某个 MC 类的成员，于是模组自己的工具方法被改成了 `createType`，
        # 编译报的是"找不到 id"，而错的地方看起来完全无辜。
        # `(?<![\w$])` 是必需的：`$` 是合法的标识符续接字符，`pvpocket$save(` 里的 `save`
        # 会被当成一个无接收者的方法名改掉，变成 `pvpocket$savePlayerData(`——声明没变、调用变了。
        pattern = re.compile(r"(?<![\w$])(?:([\w$]+)\.)?(" + "|".join(
            sorted(map(re.escape, method_map), key=len, reverse=True)) + r")\s*\(")

        def method_repl(m):
            receiver, name = m.group(1), m.group(2)
            if receiver is None:
                # 接收者组是可选的，所以**方法声明**也会被这条正则命中：`public static void
                # onBlockChanged(` 里的名字前面没有点号，看起来和"无接收者的调用"一模一样。
                # 不改声明的后果是灾难性的——声明改名了、调用处（接收者是我们自己的类，被
                # own_types 挡下）没改名，于是 `SessionManager.get()` 报"没有这个方法"，而
                # 声明明明还在那里，只是叫别的名字。
                if is_declaration(m.string, m.start()) or name in mine:
                    return m.group(0)
                # `copy().formatted(...)` 这种链式调用点号前面是右括号，没有接收者名字可用。
                # 那个点号本来就在，不要自己再加一个——加过一次，全仓库变成 `..withStyle(`。
                return method_map[name] + "("
            if ours(receiver):
                return m.group(0)
            return receiver + "." + method_map[name] + "("

        masked = pattern.sub(method_repl, masked)
        # 方法引用：Foo::oldName
        ref = re.compile(r"::(" + "|".join(sorted(map(re.escape, method_map), key=len,
                                                reverse=True)) + r")\b")
        # 方法引用同理：`BoundaryRenderer::render` 里的 render 是我们自己的方法，
        # 按名字换成 MC 的 runTick 之后，"方法引用无效"的报错会指向 register 那一行。
        masked = ref.sub(lambda m: m.group(0) if m.group(1) in mine
                         else "::" + method_map[m.group(1)], masked)

    if field_map:
        only_fields = {k: v for k, v in field_map.items() if k not in method_map}
        if only_fields:
            pattern = re.compile(r"\.(" + "|".join(sorted(map(re.escape, only_fields), key=len,
                                                         reverse=True)) + r")\b(?!\s*\()")
            masked = pattern.sub(lambda m: "." + only_fields[m.group(1)], masked)

    # 手工补充的那几条最后**无条件**再走一遍。它们会被 per-file 的"同名不同义"过滤掉——文件里
    # 同时导入了 Text 和 ScoreboardEntry 时，formatted 就有两个去向，于是整批跳过。手工那几条是
    # 逐条核对过的，不需要再让程序犹豫。
    if OVERRIDES_METHODS:
        pattern = re.compile(r"(?<![\w$])(?:([\w$]+)\.)?(" + "|".join(
            sorted(map(re.escape, OVERRIDES_METHODS), key=len, reverse=True)) + r")\s*\(")

        def override_repl(m):
            receiver, name = m.group(1), m.group(2)
            if receiver is None:
                # 和上面同理：无接收者且不是链式调用，说明这是声明，不能动。
                if is_declaration(m.string, m.start()) or name in mine:
                    return m.group(0)
                return OVERRIDES_METHODS[name] + "("
            if ours(receiver):
                return m.group(0)
            return receiver + "." + OVERRIDES_METHODS[name] + "("

        masked = pattern.sub(override_repl, masked)

    # ---- 3b. 静态成员（接收者是类名，可以精确命中）
    #
    # `ResourceLocation.of(a, b)` / `ResourceKey.of(...)` / `UUIDUtil.PACKET_CODEC` 这类调用，
    # 接收者是**类名本身**，没有"这是谁的实例"的歧义，可以安全地按 (接收者, 名字, 参数个数) 定位。
    # 参数个数是必需的：`ResourceLocation.of(s)` → `parse(s)`，`of(ns, path)` →
    # `fromNamespaceAndPath(ns, path)`，同名不同去向。
    for (owner, old, count), new in sorted(STATIC_METHODS.items()):
        pattern = re.compile(r"\b" + re.escape(owner) + r"\." + re.escape(old) + r"\s*\(")

        def static_repl(m, owner=owner, new=new, count=count):
            if count >= 0 and count_args(m.string, m.end() - 1) != count:
                return m.group(0)
            # 匹配串里带着左括号，替换时得自己补回去。
            return owner + "." + new + "("

        masked = pattern.sub(static_repl, masked)

    for (owner, old), new in sorted(STATIC_FIELDS.items()):
        pattern = re.compile(r"\b" + re.escape(owner) + r"\." + re.escape(old) + r"\b(?!\s*\()")
        masked = pattern.sub(lambda m, owner=owner, new=new: owner + "." + new, masked)

    text = unmask(masked, stash)

    # ---- 3c. 手写的上下文规则（必须看得到完整上下文，所以放在 unmask 之后）
    for pattern, replacement in REGEX_RULES:
        text = pattern.sub(replacement, text)

    # ---- 4. 字符串里的类路径（mixin 注解看得见，编译器看不见）
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
    """数一数 `open_paren` 这个左括号起的调用里有几个顶层参数（`()` 算 0 个）。

    只看括号深度，不解析表达式——够用来区分 `of(s)` 和 `of(a, b)`，而字符串里的逗号早就被 mask
    成占位符了，不会混进来。
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
    """按行替换，整行注释（// /* *）原样保留。

    注释里的 "World" 是散文，不是类型；把 javadoc 改成 "Level" 属于把文档改坏。行尾注释会被一起
    替换，这是刻意接受的代价——为了它去写一个真正的词法器不值。
    """
    out = []
    for line in text.split("\n"):
        if not COMMENT_LINE.match(line):
            out.append(pattern.sub(repl, line))
            continue
        # 一行里注释结束后还接着代码的（`/** 说明 */    public static void foo(`），后半段仍然是
        # 代码，整行跳过会把那一行的改名漏掉。踩过一次：PvPocketNetworking 里有一行就是这样，
        # 于是整个文件留着一个 ServerPlayerEntity。
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
    """把全限定名与字符串字面量换成占位符，返回 (遮蔽后的文本, 占位符表)。"""
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

# 方法/字段声明里名字前面会出现的东西：基本类型、修饰符、泛型收尾、返回类型。
DECL_TAIL = re.compile(
    r"(?:\b(?:void|boolean|byte|short|int|long|float|double|char|var|public|private|protected|"
    r"static|final|abstract|synchronized|native|default|transient|volatile)\b"
    r"|[>\]])"
    r"\s*$")


def is_declaration(text, start):
    """`start` 处的名字是不是一个声明（而不是调用）。

    调用总有点号在前面（`x.foo(` / `).foo(` / `this.foo(` / `super.foo(`）；声明前面是返回类型或
    修饰符。只有"前面什么都没有"这一种情况需要判断，而它恰好是最危险的那种：声明改了名、调用处
    因为接收者是我们自己的类而被放过，于是编译器报"没有这个方法"，可那个方法就在文件里。

    **带 `@Override` 的声明不算**：那种声明实现的是 MC 的接口或父类，名字必须跟着 MC 走
    （`shouldPause` → `isPauseScreen`、`close` → `onClose`）。把它们当声明保护起来，报错会是
    "方法不会覆盖或实现超类型的方法"，指向一行看起来完全正确的 `@Override`。
    """
    if start == 0:
        return False
    before = text[:start]
    if before.rstrip().endswith("."):
        return False
    tail = before.rsplit("\n", 1)[-1]
    if DECL_TAIL.search(tail):
        return not has_override(before)
    # 返回类型是普通类名（`MutableText name(` / `Component get(`）：前面那个词首字母大写。
    word = re.search(r"([\w$]+)\s*$", tail)
    if word and word.group(1)[0].isupper():
        return not has_override(before)
    return False


def has_override(before):
    """这个声明上面（同一条语句链里）有没有 `@Override`。

    往回找，遇到 `;`、`{`、`}` 就说明已经跨过了上一个成员。
    """
    window = before[-400:]
    for index in range(len(window) - 1, -1, -1):
        if window[index] in ";{}":
            window = window[index + 1:]
            break
    return "@Override" in window


AMBIGUOUS_FIELDS = None


def ambiguous_fields(fields):
    """全表里同名但指向不同新名字的字段名。

    `AIR` 就是典型：`Blocks.AIR` 不变，`Entity.AIR` → `DATA_AIR_SUPPLY_ID`。按名字合并之后，
    改 `Blocks.AIR` 会变成改成一个 Entity 的私有字段名。
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
    """本模组自己的类型名（就是文件名），用来判断接收者是不是我们自己的对象。"""
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
    """这个文件里 `变量名 → 声明的类型名`。

    没有类型信息时，`seed.save()` 和 `player.save()` 长得一模一样，于是我们自己类的
    `Loadout.save()` 会被 MC 的某个 `save` 顶掉——报错指向调用处，而声明在另一个文件里、
    名字看着完全正常。这里把"变量声明成什么类型"扫出来，至少能挡住"接收者显然是我们自己的类"
    这一类误伤。字段、参数、局部变量、增强 for、catch 都覆盖到了。
    """
    found = {}
    for match in LOCAL_DECL.finditer(text):
        found.setdefault(match.group(2), match.group(1))
    return found


def own_names(path):
    """这个文件自己声明的方法/字段名。"""
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
