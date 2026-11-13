#!/usr/bin/env bash
# UI overlap audit: the client renders every panel at two canvas sizes and reports [pvaudit] lines.
set -uo pipefail

# Exits non-zero on any finding, so it can gate CI. Usage: scripts/check-ui.sh [-v], with CELL=...
# to pick the cell. No server needed: the client stays on the title screen (-PquickJoin=off).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CELL="${CELL:-1.21.1-fabric}"
LOGS="$ROOT/versions/$CELL/build/devlogs"
OUT="$LOGS/ui-audit.out"
VERBOSE="${1:-}"

cd "$ROOT" || { echo "[audit] 找不到 $ROOT"; exit 1; }
mkdir -p "$LOGS"

# 82 layouts x 3 frames is about 15 s, plus client start and resource load.
LIMIT=""
if command -v timeout >/dev/null 2>&1; then
    LIMIT="timeout 300"
fi

echo "[audit] 启动客户端（$CELL），遍历全部界面..."
# shellcheck disable=SC2086
$LIMIT ./gradlew ":$CELL:runClient" -PuiAudit -PquickJoin=off --console=plain > "$OUT" 2>&1
STATUS=$?

# One finding per line: <screen> <canvas> <kind>: <box> x <box> (<area>px2)
PATTERN='\[pvaudit\] [A-Za-z]+ [0-9]+x[0-9]+ (degenerate|escapes|off-canvas|box/box|text/text|text/box):'
FINDINGS="$(grep -aE "$PATTERN" "$OUT" || true)"

if ! grep -qa 'pvaudit\] ==' "$OUT"; then
    echo "[audit] 审计没有跑完（退出码 $STATUS）。日志：$OUT"
    tail -n 25 "$OUT"
    exit 2
fi

if [ -n "$FINDINGS" ]; then
    echo
    echo "$FINDINGS" | sed -e 's/.*\[pvaudit\] /  /'
    echo
    echo "[audit] 失败：$(echo "$FINDINGS" | wc -l | tr -d ' ') 处遮挡。"
    [ "$VERBOSE" = "-v" ] && echo "[audit] 完整日志：$OUT"
    exit 1
fi

echo "[audit] 通过：没有发现遮挡。"
[ "$VERBOSE" = "-v" ] && grep -a 'pvaudit\]' "$OUT" | tail -n 8
exit 0
