#!/usr/bin/env bash
# 规则逻辑离线自检：选项切换、步进、夹取。
#
# 跑的是编译出来的真实 RuleDefinition —— 不是把公式抄一遍。不需要客户端，也不需要服务端：
# 只要 build/classes/java/main 和 loom 的依赖类路径。
#
# 用法：
#   scripts/check-rules-logic.sh
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
JDK="$(find_jdk)" || { echo "[rules-logic] 找不到 JDK 21（设 JAVA_HOME 或装到默认路径）" >&2; exit 2; }

# --- 编译产物 ------------------------------------------------------------------------------
if [ ! -f build/classes/java/main/com/leipishu/pvpocket/rules/RuleDefinition.class ]; then
    echo "[rules-logic] 先编译主源码…"
    ./gradlew compileJava -q || exit 2
fi

# --- 类路径：MC 本体不在 remapClasspath.txt 里，要单独拼 --------------------------------------
LOOM_CP="$(tr -d '\r\n' < .gradle/loom-cache/remapClasspath.txt 2>/dev/null)"
if [ -z "$LOOM_CP" ]; then
    echo "[rules-logic] 找不到 .gradle/loom-cache/remapClasspath.txt（先跑一次 ./gradlew compileJava）" >&2
    exit 2
fi
MC_COMMON="$(ls .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/*/minecraft-common-*.jar 2>/dev/null | grep -v sources | head -1)"
MC_CLIENT="$(ls .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-*/*/minecraft-clientOnly-*.jar 2>/dev/null | grep -v sources | head -1)"
if [ -z "$MC_COMMON" ]; then
    echo "[rules-logic] 找不到 Minecraft 合并包（先跑一次 ./gradlew compileJava）" >&2
    exit 2
fi

OUT="build/rules-logic"
mkdir -p "$OUT"
"$JDK/javac" -encoding UTF-8 -classpath "build/classes/java/main" -d "$OUT" \
    "$ROOT/scripts/agent/RuleLogicCheck.java" || exit 2
# stdout.encoding 从 JDK 19 起独立于 file.encoding，不给它的话 CI 日志里的中文是乱码。
"$JDK/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -classpath "build/classes/java/main;$OUT;$MC_COMMON;$MC_CLIENT;$LOOM_CP" RuleLogicCheck
