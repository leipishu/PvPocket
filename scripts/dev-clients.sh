#!/usr/bin/env bash
# Starts dev clients that auto-join one room; the server must already be running.
set -uo pipefail

# Usage: scripts/dev-clients.sh [cell]; the cell also comes from the CELL env var.
# PVP_CLIENTS raises the client count, PVP_ADDR selects host:port (default localhost:25565).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CELL="${1:-${CELL:-1.21.1-fabric}}"
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
#
# One at a time. Three cold JVMs (registries, textures, resource reload) landing on the server in
# the same second is what starves the machine, and a connection that arrives in that window can
# be left on "Connecting to server" for good.
STAGGER="${PVP_CLIENT_STAGGER:-4}"
PIDS=()
for i in $(seq 1 "$COUNT"); do
    if [ "$i" -eq 1 ]; then TASK="runClient"; else TASK="runClient${i}"; fi
    echo "[dev] 启动 Dev${i} (runs/$CELL/client${i}) ..."
    ./gradlew ":$CELL:$TASK" --console=plain -PquickJoin="$ADDR" > "$LOGS/client${i}.out" 2>&1 &
    PIDS+=($!)
    if [ "$i" -lt "$COUNT" ]; then
        sleep "$STAGGER"
    fi
done

any_alive() {
    local pid
    for pid in "${PIDS[@]}"; do
        kill -0 "$pid" 2>/dev/null && return 0
    done
    return 1
}

cleanup() {
    echo
    echo "[dev] 收到中断，正在结束客户端..."
    kill "${PIDS[@]}" 2>/dev/null
    # Bounded: a client JVM that wedges on shutdown keeps its wrapper alive, and an unbounded
    # wait here would never return (see the note in dev-duel.sh).
    deadline=$((SECONDS + 10))
    while [ "$SECONDS" -lt "$deadline" ] && any_alive; do
        sleep 1
    done
    kill -KILL "${PIDS[@]}" 2>/dev/null
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
