#!/usr/bin/env bash
# Starts dev clients that auto-join one room; the server must already be running.
set -uo pipefail

# Usage: scripts/dev-2clients.sh; PVP_CLIENTS=3 adds a spectator, CELL=... picks the cell.
# PVP_ADDR selects host:port, default localhost:25565.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CELL="${CELL:-1.21.1-fabric}"
LOGS="$ROOT/versions/$CELL/build/devlogs"
ADDR="${PVP_ADDR:-localhost:25565}"
PORT="${ADDR##*:}"
COUNT="${PVP_CLIENTS:-2}"

cd "$ROOT" || { echo "[dev] 找不到 $ROOT"; exit 1; }
mkdir -p "$LOGS"

port_open() {
    netstat -ano 2>/dev/null | grep -qE "[:.]${PORT}[[:space:]].*LISTENING"
}

if ! port_open; then
    echo "[dev] 端口 $PORT 没有在监听。请先在另一个终端启动服务端："
    echo "        ./gradlew :$CELL:runServer"
    exit 1
fi
echo "[dev] 服务端在 $ADDR 已就绪"

# Each client needs its own run dir and username: a shared dir makes two JVMs fight over
# logs/latest.log, and a shared username is rejected as a duplicate login.
PIDS=()
for i in $(seq 1 "$COUNT"); do
    if [ "$i" -eq 1 ]; then TASK="runClient"; else TASK="runClient${i}"; fi
    echo "[dev] 启动 Dev${i} (runs/$CELL/client${i}) ..."
    ./gradlew ":$CELL:$TASK" --console=plain -PquickJoin="$ADDR" > "$LOGS/client${i}.out" 2>&1 &
    PIDS+=($!)
done

cleanup() {
    echo
    echo "[dev] 收到中断，正在结束客户端..."
    kill "${PIDS[@]}" 2>/dev/null
    wait 2>/dev/null
}
trap cleanup EXIT INT TERM

echo
echo "[dev] ${COUNT} 个客户端已拉起。日志："
for i in $(seq 1 "$COUNT"); do
    echo "        $LOGS/client${i}.out"
done
echo "[dev] 关闭游戏窗口即可退出；或在此按 Ctrl+C。"
echo

wait
