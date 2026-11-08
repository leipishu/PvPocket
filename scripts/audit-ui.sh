#!/usr/bin/env bash
#
# 界面遮挡审计：让开发客户端自己把每个面板、每个页面、每个弹窗在两种画布尺寸下渲染一遍，
# 收集 [pvaudit] 报告；有任何一条发现就以非零退出码失败，可以直接挂到 CI 上。
#
# 原理见 fabric-1.21.1/src/client/java/com/leipishu/pvpocket/client/gui/PvAudit.java：
# 每一帧把实际用到的矩形（控件盒子 + 文字串）交给几何检查器，报告互相压住、越出面板、
# 以及被算成零尺寸的控件。它测的是真实渲染路径，不是布局模型的副本。
#
# 用法：
#   scripts/audit-ui.sh          # 跑一遍并打印发现
#   scripts/audit-ui.sh -v       # 额外打印客户端日志路径与每步进度
#
# 不需要服务端：客户端停在标题界面，由 harness 自己切换 screen（-PquickJoin=off）。
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/fabric-1.21.1"
LOGS="$PROJECT/build/devlogs"
OUT="$LOGS/ui-audit.out"
VERBOSE="${1:-}"

cd "$PROJECT" || { echo "[audit] 找不到 $PROJECT"; exit 1; }
mkdir -p "$LOGS"

# 60 个布局 × 3 帧 ≈ 10 秒，加上客户端启动与资源加载，两分钟足够；超时说明 harness 没跑完。
LIMIT=""
if command -v timeout >/dev/null 2>&1; then
    LIMIT="timeout 300"
fi

echo "[audit] 启动客户端，遍历全部界面..."
# shellcheck disable=SC2086
$LIMIT ./gradlew runClient -PuiAudit -PquickJoin=off --console=plain > "$OUT" 2>&1
STATUS=$?

# 一行发现的格式：<屏幕> <画布> <种类>: <盒子> x <盒子> (<面积>px2)
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
