#!/usr/bin/env bash
# 离线逻辑自检：跑 scripts/agent/*Check.java 里的每一个检查。
#
# 这些检查跑的是**编译出来的真实代码**，不是把公式抄一遍：
#   RuleLogicCheck       选项切换、步进、夹取、规范化
#   LoadoutContentCheck  配装目录的版本可用性（重锤 / 风弹 / 长矛 / 铜制）
#
# 不需要客户端，也不需要服务端：只要 build/classes/java/main 和 loom 的依赖类路径。
# 新增一个检查 = 在 scripts/agent/ 下放一个 `*Check.java`，带 main，退出码 0 表示通过。
#
# 用法：
#   scripts/check-logic.sh
#
# 退出码：0 全部通过；1 有失败；2 环境不全（没编译 / 找不到 JDK 21 / 找不到 loom 类路径）。
#
# 挂 CI 时先跑 `./gradlew compileJava`（脚本自己也会补），再跑本脚本。
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$ROOT/fabric-1.21.1"
cd "$PROJECT" || exit 2

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
if [ ! -f build/classes/java/main/com/leipishu/pvpocket/rules/RuleDefinition.class ]; then
    echo "[logic] 先编译主源码…"
    ./gradlew compileJava -q || exit 2
fi

# --- 类路径：MC 本体不在 remapClasspath.txt 里，要单独拼 --------------------------------------
LOOM_CP="$(tr -d '\r\n' < .gradle/loom-cache/remapClasspath.txt 2>/dev/null)"
if [ -z "$LOOM_CP" ]; then
    echo "[logic] 找不到 .gradle/loom-cache/remapClasspath.txt（先跑一次 ./gradlew compileJava）" >&2
    exit 2
fi
MC_COMMON="$(ls .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/*/minecraft-common-*.jar 2>/dev/null | grep -v sources | head -1)"
MC_CLIENT="$(ls .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-*/*/minecraft-clientOnly-*.jar 2>/dev/null | grep -v sources | head -1)"
if [ -z "$MC_COMMON" ]; then
    echo "[logic] 找不到 Minecraft 合并包（先跑一次 ./gradlew compileJava）" >&2
    exit 2
fi

OUT="build/logic-check"
rm -rf "$OUT"
mkdir -p "$OUT"

# 编译期也要 MC：ItemKind 的签名里出现了 net.minecraft.item.Item，只给 build/classes 解析不了。
CP="build/classes/java/main;$MC_COMMON;$MC_CLIENT;$LOOM_CP"
SOURCES=$(ls "$ROOT"/scripts/agent/*Check.java 2>/dev/null)
if [ -z "$SOURCES" ]; then
    echo "[logic] scripts/agent/ 下没有 *Check.java" >&2
    exit 2
fi
# shellcheck disable=SC2086
"$JDK/javac" -encoding UTF-8 -classpath "$CP" -d "$OUT" $SOURCES || exit 2

FAILED=0
for source in $SOURCES; do
    CLASS="$(basename "$source" .java)"
    # stdout.encoding 从 JDK 19 起独立于 file.encoding，不给它的话 CI 日志里的中文是乱码。
    if ! "$JDK/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -classpath "build/classes/java/main;$OUT;$MC_COMMON;$MC_CLIENT;$LOOM_CP" "$CLASS"; then
        FAILED=1
    fi
done
exit $FAILED
