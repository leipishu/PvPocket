#!/usr/bin/env bash
# Full duel in one command: server in the background, then clients that auto-join it.
set -uo pipefail

# Usage: scripts/dev-duel.sh [cell]; the cell also comes from the CELL env var.
# PVP_CLIENTS raises the client count, PVP_READY_TIMEOUT widens the wait for world generation.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CELL="${1:-${CELL:-1.21.1-neoforge}}"
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
        # Bounded, because the wrapper does not always come back: a client JVM that wedges on
        # shutdown (NeoForge's dev-env shutdown watchdog throws ClassNotFoundException instead of
        # halting) keeps its ./gradlew alive, and an unbounded wait here would hang the trap.
        client_deadline=$((SECONDS + 10))
        while kill -0 "$CLIENTS_PID" 2>/dev/null && [ "$SECONDS" -lt "$client_deadline" ]; do
            sleep 1
        done
        kill -KILL "$CLIENTS_PID" 2>/dev/null
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
    # A stopped run can leave a child holding the FIFO, and the next open then fails with EBUSY.
    # Recreating it once covers that; a second failure means something is still alive.
    if ! exec 3<> "$FIFO" 2>/dev/null; then
        rm -f "$FIFO" 2>/dev/null
        mkfifo "$FIFO" 2>/dev/null
        if ! exec 3<> "$FIFO" 2>/dev/null; then
            echo "[duel] $FIFO 打不开：上一次的服务端可能还没退干净"
            echo "        关掉残留的 runServer 进程，或删掉这个文件后重试"
            exit 1
        fi
    fi
    FIFO_OPEN=1

    echo "[duel] 启动服务端（日志 $SERVER_LOG）..."
    printf '%s\n' "$CELL" > "$LOGS/.server-cell"
    # Dev clients log in offline as Dev1..Dev4, so a fresh run directory must not start with
    # online-mode or white-list on: the client is rejected before it reaches the mod.
    SERVER_PROPS="$ROOT/runs/$CELL/server/server.properties"
    mkdir -p "$(dirname "$SERVER_PROPS")"
    if [ -f "$SERVER_PROPS" ]; then
        sed -i -e 's/^online-mode=.*/online-mode=false/' \
               -e 's/^white-list=.*/white-list=false/' \
               -e 's/^enforce-whitelist=.*/enforce-whitelist=false/' "$SERVER_PROPS"
    else
        printf 'online-mode=false\nwhite-list=false\nenforce-whitelist=false\n' > "$SERVER_PROPS"
    fi
    # Truncate first: a leftover `Done (` from an earlier run would look like a ready server.
    : > "$SERVER_LOG"
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

    # `Done (` is not the same as ready. The mod stamps the whole arena gallery on the server
    # thread right after the start event, which freezes the server for 7-30s on a cold cache (the
    # log says "Can't keep up ... 199 ticks behind" when it comes back). A client launched into
    # that window can sit on "Connecting to server" and never join, which is exactly the Dev1
    # hang: the run that failed had the clients started 10s before the gallery was done.
    echo "[duel] 等场地铺完（这期间服务端是卡住的，客户端连不进去）..."
    GALLERY_TIMEOUT="${PVP_GALLERY_TIMEOUT:-180}"
    gallery_deadline=$((SECONDS + GALLERY_TIMEOUT))
    while [ "$SECONDS" -lt "$gallery_deadline" ]; do
        if grep -q 'Arena gallery ready' "$SERVER_LOG" 2>/dev/null; then
            echo "[duel] 场地就绪：$(grep -m1 'Arena gallery ready' "$SERVER_LOG" | tr -d '\r')"
            # The stamping leaves a tick backlog ("Can't keep up ... 199 ticks behind" comes
            # after that line), and vanilla drains it at up to ten ticks a loop. Three seconds
            # covers a full backlog; connecting before that is what the clients must not do.
            sleep 3
            break
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    if ! grep -q 'Arena gallery ready' "$SERVER_LOG" 2>/dev/null; then
        echo "[duel] ${GALLERY_TIMEOUT}s 内没等到场地就绪标记（模组没加载时正常），照常启动客户端"
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

# Wait on the server's player list rather than on the client wrappers. A client JVM that wedges
# on shutdown keeps its ./gradlew alive forever, and then a bare `wait` never returns — the trap
# never fires and the server stays up after every window is closed. "Nobody online any more" is
# the signal that actually means the session is over.
#
# Only when this script owns the server: a reused one brings a log full of somebody else's
# joins and leaves, which would read as "already empty" and stop the clients on the spot.
if [ -n "$SERVER_PID" ]; then
    JOIN_TIMEOUT="${PVP_JOIN_TIMEOUT:-120}"
    CLIENTS_STARTED=$SECONDS
    JOINED_ANY=0
    while kill -0 "$CLIENTS_PID" 2>/dev/null; do
        joined=$(grep -c ' joined the game' "$SERVER_LOG" 2>/dev/null)
        joined=${joined:-0}
        left=$(grep -c ' left the game' "$SERVER_LOG" 2>/dev/null)
        left=${left:-0}
        if [ "$joined" -gt 0 ]; then
            JOINED_ANY=1
        fi
        if [ "$JOINED_ANY" -eq 1 ] && [ "$joined" -le "$left" ]; then
            echo
            echo "[duel] 服务端已经没人了，收工。"
            break
        fi
        if [ "$JOINED_ANY" -eq 0 ] && [ $((SECONDS - CLIENTS_STARTED)) -ge "$JOIN_TIMEOUT" ]; then
            echo
            echo "[duel] ${JOIN_TIMEOUT}s 内一个客户端都没进服，多半卡在 \"Connecting to server\"。"
            echo "        看 $LOGS/client1.out 里 Connecting 那行之后有没有 logged in；"
            echo "        服务端日志：$SERVER_LOG"
            break
        fi
        sleep 2
    done
else
    wait "$CLIENTS_PID"
fi
