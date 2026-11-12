#!/usr/bin/env bash
# 离线逻辑自检：跑 scripts/agent/*Check.java 里的每一个检查。
#
# 这些检查跑的是**编译出来的真实代码**，不是把公式抄一遍：
#   RuleLogicCheck       选项切换、步进、夹取、规范化
#   LoadoutContentCheck  配装目录的版本可用性（重锤 / 风弹 / 长矛 / 铜制）
#   MixinTargetCheck     mixin 注解里的字符串（method= / @At target= / @Accessor）能否解析
#
# 不需要客户端，也不需要服务端：只要 build/classes/java/main 和 loom 的依赖类路径。
# 新增一个检查 = 在 scripts/agent/ 下放一个 `*Check.java`，带 main，退出码 0 表示通过。
# 每个检查都会收到同样的参数，认得的自己取，认不得的忽略：
#   --mcjar <客户端 jar>  --src <源码根>（可重复）
#
# 用法：
#   scripts/check-logic.sh
#
# 退出码：0 全部通过；1 有失败；2 环境不全（没编译 / 找不到 JDK 21 / 找不到 loom 类路径）。
#
# 挂 CI 时先跑 `./gradlew compileJava`（脚本自己也会补），再跑本脚本。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

# 这个脚本跑的是**编译产物**，所以得说清用哪一格。规则逻辑与配装目录在 src/main 里，两个 cell
# 编出来是一样的，取 Fabric 那一格就够；mixin 目标要**两边都核**——NeoForge 会 patch 掉原版的
# 调用点，同一处 @At(target=…) 在两个加载器上未必都成立。
FABRIC_CELL="${FABRIC_CELL:-1.21.1-fabric}"
NEOFORGE_CELL="${NEOFORGE_CELL:-1.21.1-neoforge}"
FABRIC_BUILD="versions/$FABRIC_CELL/build"

# --- JDK 21：工程的 target 是 21，用 17 的 javac 会报 class file version 65.0 -------------------
find_jdk() {
    for candidate in "${JAVA_HOME:-}" "/c/Program Files/Java/jdk-21" "/usr/lib/jvm/java-21-openjdk-amd64"; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/javac" ]; then
            if "$candidate/bin/javac" -version 2>&1 | grep -q '^javac 21'; then
                echo "$candidate/bin"
                return 0
            fi
        fi
    done
    return 1
}
JDK="$(find_jdk)" || { echo "[logic] 找不到 JDK 21（设 JAVA_HOME 或装到默认路径）" >&2; exit 2; }

# --- 编译产物 ------------------------------------------------------------------------------
if [ ! -f "$FABRIC_BUILD/classes/java/main/com/leipishu/pvpocket/rules/RuleDefinition.class" ]; then
    echo "[logic] 先编译主源码…"
    ./gradlew ":$FABRIC_CELL:compileJava" -q || exit 2
fi

# --- 类路径：由 Gradle 写成文件，两个 cell 同一个任务 ------------------------------------------
# 以前是从 Loom 的 .gradle/loom-cache/remapClasspath.txt 里读，那是 mixin 注解处理器的副产物，
# 不是稳定接口（关掉 legacy AP 或换 Loom 版本就没了）。现在由每个 cell 的 writeCompileClasspath
# 任务写出 build/compile-classpath.txt —— 两个加载器一条路径，不再有「Fabric 这样取、NeoForge
# 那样取」的分叉。
classpath_file() {
    local cell="$1"
    local out="versions/$cell/build/compile-classpath.txt"
    ./gradlew ":$cell:writeCompileClasspath" -q >/dev/null 2>&1
    if [ ! -f "$out" ]; then
        echo "[logic] 拿不到 $cell 的编译类路径（先跑一次 ./gradlew :$cell:compileJava）" >&2
        return 1
    fi
    echo "$out"
}

FABRIC_CP_FILE="$(classpath_file "$FABRIC_CELL")" || exit 2
FABRIC_CP="$(tr -d '\r\n' < "$FABRIC_CP_FILE")"

# LoadoutContentCheck 要一个真实游戏 jar 来读 en_us.json。从上面那份类路径里挑，比另外猜一个
# 目录可靠——那份文件是 Gradle 自己写的。
MC_CLIENT="$(tr ';' '\n' < "$FABRIC_CP_FILE" | grep -iE 'minecraft-clientOnly-.*\.jar$' \
    | grep -v sources | head -1)"

OUT="$FABRIC_BUILD/logic-check"
rm -rf "$OUT"
mkdir -p "$OUT"

# 编译期也要 MC：ItemKind 的签名里出现了 net.minecraft.item.Item，只给 build/classes 解析不了。
CP="$FABRIC_BUILD/classes/java/main;$OUT;$FABRIC_CP"
SOURCES=$(ls "$ROOT"/scripts/agent/*Check.java 2>/dev/null)
if [ -z "$SOURCES" ]; then
    echo "[logic] scripts/agent/ 下没有 *Check.java" >&2
    exit 2
fi
# -proc:none 关掉批注处理（类路径上有 loom 带的处理器，javac 会为此多打五行提示）；
# -J-Duser.language=en：javac 的提示是本地化消息，在 GBK 控制台下和下面 UTF-8 的检查输出混在一起，
# `iconv` 转一遍必然一半乱码。让它说英文，整份日志就只有一个编码。
# shellcheck disable=SC2086
"$JDK/javac" -J-Duser.language=en -J-Duser.country=US -proc:none -encoding UTF-8 \
    -classpath "$CP" -d "$OUT" $SOURCES || exit 2

FAILED=0
# 数组而不是 `${VAR:+--mcjar "$VAR"}`：后者展开出来的引号是字面量，会被当成路径的一部分。
MCJAR_ARGS=()
if [ -n "$MC_CLIENT" ]; then
    MCJAR_ARGS=(--mcjar "$MC_CLIENT")
fi
for source in $SOURCES; do
    CLASS="$(basename "$source" .java)"
    # 把游戏 jar 和源码根传给检查：认得的（LoadoutContentCheck / MixinTargetCheck）会自己取，
    # 认不得的忽略。stdout.encoding 从 JDK 19 起独立于 file.encoding，不给它的话 CI 日志里的中文是乱码。
    "$JDK/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -classpath "$CP" "$CLASS" "${MCJAR_ARGS[@]}" \
        --src src/main/java --src src/client/java \
        --src src/fabric/java --src src/client-fabric/java || FAILED=1
done

# --- NeoForge 那一格的 mixin 目标 --------------------------------------------------------------
# 同一处 @At(target = "Lnet/minecraft/…") 在两个加载器上未必都成立：NeoForge 会把原版的调用点
# 换成自己的钩子。MixinTargetCheck 只回答「这个类 / 这个成员还在不在」，注入点被搬到别处它看不出来
# ——那一类只有真开一局才知道。但「NeoForge 把整个方法删了」它是能抓的，所以值得多跑一遍。
#
# 没有编过 NeoForge 就跳过并提示，不判失败：只编 Fabric 的机器上不该因为这个红。
if NEOFORGE_CP_FILE="$(classpath_file "$NEOFORGE_CELL")"; then
    NEOFORGE_CP="$(tr -d '\r\n' < "$NEOFORGE_CP_FILE")"
    echo "[logic] NeoForge 的 mixin 目标（$NEOFORGE_CELL）"
    "$JDK/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -classpath "$OUT;$NEOFORGE_CP" MixinTargetCheck \
        --src src/main/java --src src/client/java \
        --src src/neoforge/java --src src/client-neoforge/java || FAILED=1
else
    echo "[logic] 跳过 NeoForge 的 mixin 核对（先跑一次 ./gradlew :$NEOFORGE_CELL:compileJava）"
fi

exit $FAILED
