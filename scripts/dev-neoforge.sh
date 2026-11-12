#!/usr/bin/env bash
#
# 起 NeoForge 那一格的全套测试环境：服务端先起来，等它真的能接受连接，再拉起自动进服的客户端。
#
# 用法：
#   scripts/dev-neoforge.sh                 # Dev1 + Dev2，自动进 localhost:25565
#   PVP_CLIENTS=3 scripts/dev-neoforge.sh   # 加 Dev3（观战）
#   PVP_READY_TIMEOUT=600 scripts/dev-neoforge.sh   # 首次生成世界时放宽等待
#
# 只是 dev-duel.sh 的一层壳，逻辑（等 `Done (`、FIFO、清理顺序、端口护栏）全在那边，改行为请改那边。
# 两个加载器共用 25565，所以 neoforge 和 fabric 的脚本不能同时跑——真的撞上了会被护栏拦下来。
#
# 关掉所有客户端窗口即结束；在这里按 Ctrl+C 会先关客户端再停服务端。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec env CELL=1.21.1-neoforge "$ROOT/scripts/dev-duel.sh" "$@"
