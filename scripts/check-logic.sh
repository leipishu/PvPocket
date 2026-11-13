#!/usr/bin/env bash
# Offline logic self-check: runs every check in scripts/agent/*Check.java.
set -u

# Usage: scripts/check-logic.sh. Exit codes: 0 pass, 1 failure, 2 incomplete environment.
# The checks run against compiled classes, so a build of the cells is required first.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

# Rule logic and the loadout catalog live in src/main, so one cell is enough for those. Mixin
# targets are checked on both cells: NeoForge patches vanilla call sites.
FABRIC_CELL="${FABRIC_CELL:-1.21.1-fabric}"
NEOFORGE_CELL="${NEOFORGE_CELL:-1.21.1-neoforge}"
FABRIC_BUILD="versions/$FABRIC_CELL/build"

# JDK 21: the project targets 21, and a 17 javac rejects class file version 65.0.
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

if [ ! -f "$FABRIC_BUILD/classes/java/main/com/leipishu/pvpocket/rules/RuleDefinition.class" ]; then
    echo "[logic] 先编译主源码…"
    ./gradlew ":$FABRIC_CELL:compileJava" -q || exit 2
fi

# The classpath is written by the per-cell writeCompileClasspath task, so both loaders share one
# code path instead of reading a Loom cache file.
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

# LoadoutContentCheck reads en_us.json out of a real game jar, picked from that classpath.
MC_CLIENT="$(tr ';' '\n' < "$FABRIC_CP_FILE" | grep -iE 'minecraft-clientOnly-.*\.jar$' \
    | grep -v sources | head -1)"

OUT="$FABRIC_BUILD/logic-check"
rm -rf "$OUT"
mkdir -p "$OUT"

# The compile step needs MC as well: ItemKind signatures mention net.minecraft.item.Item.
CP="$FABRIC_BUILD/classes/java/main;$OUT;$FABRIC_CP"
SOURCES=$(ls "$ROOT"/scripts/agent/*Check.java 2>/dev/null)
if [ -z "$SOURCES" ]; then
    echo "[logic] scripts/agent/ 下没有 *Check.java" >&2
    exit 2
fi
# -proc:none skips annotation processing; the language flags keep javac messages ASCII.
# shellcheck disable=SC2086
"$JDK/javac" -J-Duser.language=en -J-Duser.country=US -proc:none -encoding UTF-8 \
    -classpath "$CP" -d "$OUT" $SOURCES || exit 2

FAILED=0
# An array, not ${VAR:+--mcjar "$VAR"}: the latter expands literal quotes into the argument.
MCJAR_ARGS=()
if [ -n "$MC_CLIENT" ]; then
    MCJAR_ARGS=(--mcjar "$MC_CLIENT")
fi
for source in $SOURCES; do
    CLASS="$(basename "$source" .java)"
    # Pass the game jar and source roots to every check; a check that does not know them ignores
    # them. stdout.encoding is separate from file.encoding since JDK 19.
    "$JDK/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -classpath "$CP" "$CLASS" "${MCJAR_ARGS[@]}" \
        --src src/main/java --src src/client/java \
        --src src/fabric/java --src src/client-fabric/java || FAILED=1
done

# NeoForge mixin targets: the same @At target does not have to hold on both loaders. A missing
# NeoForge build is skipped rather than failed, so a Fabric-only machine stays green.
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

# Comment conventions. Pure text, no JDK needed, so it also runs on a machine that cannot build.
PYTHON_BIN="$(command -v python || command -v python3 || true)"
if [ -n "$PYTHON_BIN" ]; then
    echo "[logic] comment conventions"
    "$PYTHON_BIN" scripts/check-comments.py || FAILED=1
else
    echo "[logic] skipped comment conventions (no python)"
fi

exit $FAILED
