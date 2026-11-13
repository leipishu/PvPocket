#!/usr/bin/env bash
# Full duel in one command: server in the background, then clients that auto-join it.
set -uo pipefail

# Usage: scripts/dev-duel.sh. CELL picks the cell, PVP_CLIENTS=3 adds a spectator,
# PVP_READY_TIMEOUT widens the wait when the world still has to be generated.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CELL="${CELL:-1.21.1-neoforge}"
COUNT="${PVP_CLIENTS:-2}"
ADDR="${PVP_ADDR:-localhost:25565}"
PORT="${ADDR##*:}"
READY_TIMEOUT="${PVP_READY_TIMEOUT:-300}"

# Four run tasks exist: runClient, runClient2, runClient3, runClient4.
if ! [ "$COUNT" -ge 1 ] 2>/dev/null || [ "$COUNT" -gt 4 ]; then
    echo "[duel] PVP_CLIENTS 只能是 1..4（一共四个 run 任务）"
    exit 1
fi

LOGS="$ROOT/versions/$CELL/build/devlogs"
SERVER_LOG="$LOGS/server.out"
FIFO="$LOGS/server.stdin"

cd "$ROOT" || { echo "[duel] 找不到 $ROOT"; exit 1; }
mkdir -p "$LOGS"

# A local http_proxy hangs on piston-meta.mojang.com: NeoFormRuntime stalls at DL .../17.json
# with no error at all. Clearing it is harmless for Fabric too.
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY

port_open() {
    netstat -ano 2>/dev/null | grep -qE "[:.]${PORT}[[:space:]].*LISTENING"
}

SERVER_PID=""
CLIENTS_PID=""
FIFO_OPEN=0

cleanup() {
    trap - EXIT INT TERM
    # Clients first: stopping the server first makes them log misleading connection errors.
    if [ -n "$CLIENTS_PID" ] && kill -0 "$CLIENTS_PID" 2>/dev/null; then
        echo
        echo "[duel] 关闭客户端..."
        kill -TERM "$CLIENTS_PID" 2>/dev/null
        wait "$CLIENTS_PID" 2>/dev/null
    fi
    if [ -n "$SERVER_PID" ]; then
        echo "[duel] 停止服务端..."
        # Gradle forwards stdin to the server only on a TTY, so this works on Linux/macOS only.
        # Windows relies on the port kill further down.
        if [ "$FIFO_OPEN" = "1" ]; then
            printf 'stop\n' >&3 2>/dev/null
        fi
        deadline=$((SECONDS + 15))
        while kill -0 "$SERVER_PID" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
            sleep 1
        done
        # A killed gradlew can leave the JavaExec JVM holding the port, and the next run then
        # mistakes it for a live server; kill whatever still listens on the port.
        if command -v taskkill >/dev/null 2>&1; then
            for winpid in $(netstat -ano 2>/dev/null \
                    | grep -E "[:.]${PORT}[[:space:]].*LISTENING" \
                    | awk '{print $5}' | sort -u); do
                taskkill //F //PID "$winpid" >/dev/null 2>&1
            done
        fi
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
    fi
    if [ "$FIFO_OPEN" = "1" ]; then
        exec 3>&-
    fi
    # The FIFO stays on disk; see where it is created.
}
trap cleanup EXIT INT TERM

if port_open; then
    # A server started by the other cell accepts these clients too, so a marker file records which
    # cell owns the port and a mismatch is refused.
    RUNNING_CELL=""
    [ -f "$LOGS/.server-cell" ] && RUNNING_CELL="$(cat "$LOGS/.server-cell")"
    if [ -n "$RUNNING_CELL" ] && [ "$RUNNING_CELL" != "$CELL" ]; then
        echo "[duel] $ADDR 上跑的是 $RUNNING_CELL 的服务端，不是 $CELL。"
        echo "       两个加载器共用 25565，先停掉它（或换个端口），否则测的不是你以为的那一格。"
        exit 1
    fi
    echo "[duel] $ADDR 已经在监听，复用现有服务端（本脚本不负责它的生命周期）"
else
    # Compile once first: two Gradle processes starting together contend on the build lock.
    echo "[duel] 编译 $CELL ..."
    if ! ./gradlew ":$CELL:classes" --console=plain -q; then
        echo "[duel] 编译失败，服务端不会启动"
        exit 1
    fi

    # Minecraft exits on stdin EOF, so the server reads from a FIFO held open at both ends (`<>`).
    # The FIFO is never deleted: rm fails here and a leftover file breaks the next mkfifo.
    if [ ! -p "$FIFO" ]; then
        rm -f "$FIFO" 2>/dev/null
        if ! mkfifo "$FIFO" 2>/dev/null; then
            echo "[duel] 建不了 $FIFO（Windows 上需要 MSYS 的 mkfifo）"
            exit 1
        fi
    fi
    exec 3<> "$FIFO"
    FIFO_OPEN=1

    echo "[duel] 启动服务端（日志 $SERVER_LOG）..."
    printf '%s\n' "$CELL" > "$LOGS/.server-cell"
    ./gradlew ":$CELL:runServer" --console=plain < "$FIFO" > "$SERVER_LOG" 2>&1 &
    SERVER_PID=$!

    # Wait for `Done (` rather than the port: the port binds early and a quickPlay client kicked
    # once stays on the title screen.
    echo "[duel] 等待服务端就绪（最多 ${READY_TIMEOUT}s）..."
    deadline=$((SECONDS + READY_TIMEOUT))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if grep -q 'Done (' "$SERVER_LOG" 2>/dev/null; then
            echo "[duel] 服务端就绪：$(grep -m1 'Done (' "$SERVER_LOG" | tr -d '\r')"
            break
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "[duel] 服务端进程已经退出，日志尾部："
            tail -30 "$SERVER_LOG"
            exit 1
        fi
        sleep 2
    done
    if ! grep -q 'Done (' "$SERVER_LOG" 2>/dev/null; then
        echo "[duel] 等了 ${READY_TIMEOUT}s 还没就绪，日志尾部："
        tail -30 "$SERVER_LOG"
        exit 1
    fi
fi

# Copy client1's options.txt to the others when it is missing, so the UI settings match.
CLIENT_ROOT="$ROOT/runs/$CELL"
for i in $(seq 2 "$COUNT"); do
    if [ -f "$CLIENT_ROOT/client1/options.txt" ] && [ ! -f "$CLIENT_ROOT/client$i/options.txt" ]; then
        mkdir -p "$CLIENT_ROOT/client$i"
        cp "$CLIENT_ROOT/client1/options.txt" "$CLIENT_ROOT/client$i/options.txt"
        echo "[duel] 已把 client1 的 options.txt 复制给 client$i（界面设置一致）"
    fi
done

echo "[duel] 启动 $COUNT 个客户端，自动进入 $ADDR ..."
CELL="$CELL" PVP_CLIENTS="$COUNT" PVP_ADDR="$ADDR" "$ROOT/scripts/dev-clients.sh" &
CLIENTS_PID=$!

DEVS=""
for i in $(seq 1 "$COUNT"); do DEVS="${DEVS}Dev$i "; done

cat <<EOF

[duel] 全套已拉起：
        服务端  $SERVER_LOG
        客户端  $LOGS/client1.out .. client${COUNT}.out
        存档    $ROOT/runs/$CELL/server/world
[duel] 客户端是 ${DEVS}，已经自动进服。关掉游戏窗口即退出该客户端；
       在这里按 Ctrl+C 会先关客户端，再把服务端一起停掉。
EOF

wait "$CLIENTS_PID"
