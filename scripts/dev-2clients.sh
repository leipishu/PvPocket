#!/usr/bin/env bash
#
# 启动开发客户端并自动加入同一个房间，用于测试双人决斗与观战。
#
# 前提：共享服务端已经在另一个终端运行（见 README「双客户端联调」）。
# 本脚本只负责客户端，不托管服务端，这样服务端控制台仍然可以输入命令
# （stop / op / gamemode / pvpocket 等），这对调试比"一个窗口管全部"更有用。
#
# 用法：
#   scripts/dev-2clients.sh                     # 两个客户端（Dev1 / Dev2），默认
#   PVP_CLIENTS=3 scripts/dev-2clients.sh       # 三个客户端（Dev3 用来观战）
#   PVP_ADDR=192.168.1.10:25565 scripts/dev-2clients.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/fabric-1.21.1"
LOGS="$PROJECT/build/devlogs"
ADDR="${PVP_ADDR:-localhost:25565}"
PORT="${ADDR##*:}"
# 观战需要第三个玩家：两个参与者都在决斗里，没人能站在旁边看。
COUNT="${PVP_CLIENTS:-2}"

cd "$PROJECT" || { echo "[dev] 找不到 $PROJECT"; exit 1; }
mkdir -p "$LOGS"

port_open() {
    netstat -ano 2>/dev/null | grep -qE "[:.]${PORT}[[:space:]].*LISTENING"
}

if ! port_open; then
    echo "[dev] 端口 $PORT 没有在监听。请先在另一个终端启动服务端："
    echo "        cd fabric-1.21.1 && ./gradlew runServer"
    exit 1
fi
echo "[dev] 服务端在 $ADDR 已就绪"

# 每个客户端必须用不同的 run 目录和不同的用户名：
#   - 同一个目录会让两个 JVM 抢 logs/latest.log，输的一方静默丢日志；
#   - 同一个用户名会被服务端当成同一个玩家，第二个连接直接顶掉第一个。
#
# Gradle 任务名：第一个客户端是 runClient（loom 的默认名），之后是 runClient2 / runClient3。
PIDS=()
for i in $(seq 1 "$COUNT"); do
    if [ "$i" -eq 1 ]; then TASK="runClient"; else TASK="runClient${i}"; fi
    echo "[dev] 启动 Dev${i} (run-client${i}) ..."
    ./gradlew "$TASK" --console=plain -PquickJoin="$ADDR" > "$LOGS/client${i}.out" 2>&1 &
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
