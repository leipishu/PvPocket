#!/usr/bin/env bash
# Regenerates every cell's gen-src, forcing a rewrite. Usage: scripts/port/gen-all.sh
set -uo pipefail

# The Gradle task owns the port arguments (rename table, overlay, output layout), so this script
# only drives it. --rerun-tasks is what makes it a regeneration rather than a no-op.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

# Cells come from settings.gradle so this list cannot drift from the build. Same parse as
# scripts/check-run-configs.py: the block starts at "def cells = [" and ends at the first "]".
CELLS=()
while IFS= read -r cell; do
    [ -n "$cell" ] && CELLS+=("$cell")
done < <(sed -n '/def cells = \[/,/^\]/p' settings.gradle \
    | grep -oE "'[0-9.]+-(fabric|neoforge)'" | tr -d "'")

if [ "${#CELLS[@]}" -eq 0 ]; then
    echo "[gen] 没有从 settings.gradle 解析到任何格" >&2
    exit 2
fi

# NeoForge cells resolve artifacts through NeoFormRuntime, which honours http_proxy; the local
# proxy never answers piston-meta.mojang.com, so an inherited proxy stalls the run.
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY

TASKS=()
for cell in "${CELLS[@]}"; do
    TASKS+=(":$cell:generatePortSources")
done

echo "[gen] 重生成 ${#CELLS[@]} 格源码：${CELLS[*]}"
./gradlew "${TASKS[@]}" --rerun-tasks --console=plain
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
    echo "[gen] 失败，退出码 $STATUS" >&2
    exit "$STATUS"
fi

# A per-cell tally, because "Gradle succeeded" is not the same claim as "the tree was rewritten".
# The count of files touched in the last two minutes is the part that catches a no-op run.
STALE=0
for cell in "${CELLS[@]}"; do
    total=$(find "versions/$cell/gen-src" -type f | wc -l | tr -d ' ')
    fresh=$(find "versions/$cell/gen-src" -type f -mmin -2 | wc -l | tr -d ' ')
    printf '[gen] %-18s %3s files, %3s rewritten now\n' "$cell" "$total" "$fresh"
    if [ "$fresh" -eq 0 ]; then
        STALE=1
    fi
done

if [ "$STALE" -ne 0 ]; then
    echo "[gen] 有格子没有写入任何文件，检查 versions/<cell>/gen-src 是否被占用" >&2
    exit 1
fi

echo "[gen] 完成：${#CELLS[@]} 格的 gen-src 已重写"
