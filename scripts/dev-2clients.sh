#!/usr/bin/env bash
#
# 启动两个开发客户端并自动加入同一个房间，用于测试双人决斗。
#
# 前提：共享服务端已经在另一个终端运行（见 README「双客户端联调」）。
# 本脚本只负责两个客户端，不托管服务端，这样服务端控制台仍然可以输入命令
# （stop / op / gamemode / pvpocket 等），这对调试比"一个窗口管全部"更有用。
#
# 用法：
#   scripts/dev-2clients.sh                 # 连接 localhost:25565
#   PVP_ADDR=192.168.1.10:25565 scripts/dev-2clients.sh
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/fabric-1.21.1"
LOGS="$PROJECT/build/devlogs"
ADDR="${PVP_ADDR:-localhost:25565}"
PORT="${ADDR##*:}"

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

# 两个客户端必须用不同的 run 目录和不同的用户名：
#   - 同一个目录会让两个 JVM 抢 logs/latest.log，输的一方静默丢日志；
#   - 同一个用户名会被服务端当成同一个玩家，第二个连接直接顶掉第一个。
echo "[dev] 启动 Dev1 (run-client1) ..."
./gradlew runClient --console=plain -PquickJoin="$ADDR" > "$LOGS/client1.out" 2>&1 &
CLIENT1_PID=$!

echo "[dev] 启动 Dev2 (run-client2) ..."
./gradlew runClient2 --console=plain -PquickJoin="$ADDR" > "$LOGS/client2.out" 2>&1 &
CLIENT2_PID=$!

cleanup() {
    echo
    echo "[dev] 收到中断，正在结束客户端..."
    kill "$CLIENT1_PID" "$CLIENT2_PID" 2>/dev/null
    wait 2>/dev/null
}
trap cleanup EXIT INT TERM

echo
echo "[dev] 两个客户端已拉起。日志："
echo "        $LOGS/client1.out"
echo "        $LOGS/client2.out"
echo "[dev] 关闭游戏窗口即可退出；或在此按 Ctrl+C。"
echo

wait
