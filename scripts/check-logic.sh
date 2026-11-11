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
# -proc:none 关掉批注处理（类路径上有 loom 带的处理器，javac 会为此多打五行提示）；
# -J-Duser.language=en：javac 的提示是本地化消息，在 GBK 控制台下和下面 UTF-8 的检查输出混在一起，
# `iconv` 转一遍必然一半乱码。让它说英文，整份日志就只有一个编码。
# shellcheck disable=SC2086
"$JDK/javac" -J-Duser.language=en -J-Duser.country=US -proc:none -encoding UTF-8 \
    -classpath "$CP" -d "$OUT" $SOURCES || exit 2

FAILED=0
for source in $SOURCES; do
    CLASS="$(basename "$source" .java)"
    # 把游戏 jar 传给检查：认得的（LoadoutContentCheck）会拿它核对真实物品表，认不得的忽略。
    # stdout.encoding 从 JDK 19 起独立于 file.encoding，不给它的话 CI 日志里的中文是乱码。
    if [ -n "$MC_CLIENT" ]; then
        "$JDK/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
            -classpath "build/classes/java/main;$OUT;$MC_COMMON;$MC_CLIENT;$LOOM_CP" "$CLASS" \
            --mcjar "$MC_CLIENT" || FAILED=1
    else
        "$JDK/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
            -classpath "build/classes/java/main;$OUT;$MC_COMMON;$LOOM_CP" "$CLASS" || FAILED=1
    fi
done
exit $FAILED
