#!/usr/bin/env bash
#
# 一键起一场双人对决：**先起服务端，等它真的能接受连接，再起两个客户端自动进服**。
#
# 与 dev-2clients.sh 的分工：
#   dev-2clients.sh  服务端已经在另一个终端跑着，只起客户端——服务端控制台还能敲命令
#   dev-duel.sh      一条命令起全套，服务端在后台——适合「改完代码，跑一遍看结果」
#
# 用法：
#   scripts/dev-duel.sh                              # NeoForge，Dev1 + Dev2
#   CELL=1.21.1-fabric scripts/dev-duel.sh           # 换一格
#   PVP_CLIENTS=3 scripts/dev-duel.sh                # 加 Dev3（观战）
#   PVP_READY_TIMEOUT=600 scripts/dev-duel.sh        # 首次启动要生成世界，放宽等待
#
# 脚本的生命周期就是服务端 + 客户端的生命周期：Ctrl+C 或者关掉所有客户端窗口，服务端一起停。
# 想要一个长期开着的服务端（能在控制台敲命令），用两终端方式：`runServer` 独占一个终端，
# 另一个终端跑 dev-2clients.sh。
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CELL="${CELL:-1.21.1-neoforge}"
COUNT="${PVP_CLIENTS:-2}"
ADDR="${PVP_ADDR:-localhost:25565}"
PORT="${ADDR##*:}"
READY_TIMEOUT="${PVP_READY_TIMEOUT:-300}"

# runClient / runClient2 / runClient3 / runClient4，一共四个。
if ! [ "$COUNT" -ge 1 ] 2>/dev/null || [ "$COUNT" -gt 4 ]; then
    echo "[duel] PVP_CLIENTS 只能是 1..4（一共四个 run 任务）"
    exit 1
fi

LOGS="$ROOT/versions/$CELL/build/devlogs"
SERVER_LOG="$LOGS/server.out"
FIFO="$LOGS/server.stdin"

cd "$ROOT" || { echo "[duel] 找不到 $ROOT"; exit 1; }
mkdir -p "$LOGS"

# 跑 NeoForge 的 run 任务必须直连：本机 http_proxy=127.0.0.1:6494 对 piston-meta.mojang.com
# 只挂不回，NeoFormRuntime 走它会卡死在 `DL .../17.json`（CPU 归零、一行报错都没有）。
# 对 Fabric 清掉也无害，所以这里统一处理，不按加载器分叉。
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY

port_open() {
    netstat -ano 2>/dev/null | grep -qE "[:.]${PORT}[[:space:]].*LISTENING"
}

SERVER_PID=""
CLIENTS_PID=""
FIFO_OPEN=0

cleanup() {
    trap - EXIT INT TERM
    # 顺序是「先客户端，后服务端」：反过来的话客户端会看到连接被重置，日志里全是误导性的报错。
    if [ -n "$CLIENTS_PID" ] && kill -0 "$CLIENTS_PID" 2>/dev/null; then
        echo
        echo "[duel] 关闭客户端..."
        kill -TERM "$CLIENTS_PID" 2>/dev/null
        wait "$CLIENTS_PID" 2>/dev/null
    fi
    if [ -n "$SERVER_PID" ]; then
        echo "[duel] 停止服务端..."
        # 先试着走控制台发 stop：Linux / macOS 上 Gradle 会把写进 stdin 的东西转给服务端。
        # **Windows 上实测不转**（Gradle 客户端只在 TTY 下转发），所以下面还有兜底。
        if [ "$FIFO_OPEN" = "1" ]; then
            printf 'stop\n' >&3 2>/dev/null
        fi
        deadline=$((SECONDS + 15))
        while kill -0 "$SERVER_PID" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
            sleep 1
        done        # gradlew 被杀之后，JavaExec 起的那个 JVM 可能变成孤儿继续占着 25565。下一次启动会
        # 因为「端口已经在监听」而误判成「服务端还活着」，于是复用一个半死的服务端——症状是
        # 客户端一直连不上但脚本一路绿灯。按端口找 Windows PID 兜底一次。
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
    # FIFO 故意留着：见上面建它那段（删除在某些环境会失败，而且留着下次直接复用）。
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# 服务端

if port_open; then
    echo "[duel] $ADDR 已经在监听，复用现有服务端（本脚本不负责它的生命周期）"
else
    # 预热编译：两个客户端是两个独立的 Gradle 进程，同时启动时会抢同一个 build 目录的锁，
    # 一个编一个等，日志里会出现看着像卡死的空档。先编一次，后面就只剩启动。
    echo "[duel] 编译 $CELL ..."
    if ! ./gradlew ":$CELL:classes" --console=plain -q; then
        echo "[duel] 编译失败，服务端不会启动"
        exit 1
    fi

    # FIFO 是为了不让服务端读到 EOF：后台进程的 stdin 默认是 /dev/null，而 Minecraft 一读到
    # EOF 就自己关服（症状是日志里刚打印 Done 紧接着就是 Stopping server）。`<>` 同时持读写
    # 两端，管道永远不会空。它顺带留了一条「往服务端敲命令」的路，但 Windows 上走不通，见 cleanup。
    #
    # **FIFO 用完不删**：`rm` 在某些环境里被替换成「移到回收站」，而回收站不认 FIFO，删除会失败
    # （`[safe-delete] ... 系统找不到指定的文件`）。失败本身无害，但下一次 `mkfifo` 会因为文件
    # 还在而报错，脚本就再也起不来了。留着复用：它是特殊文件，`exec 3<>` 直接打开就行。
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
    ./gradlew ":$CELL:runServer" --console=plain < "$FIFO" > "$SERVER_LOG" 2>&1 &
    SERVER_PID=$!

    # 等的是日志里的 `Done (` 而不是端口：Minecraft 在初始化早期就 bind 了端口，此时连上去
    # 会被踢（"Server is still starting"），而 Dev1/Dev2 是 --quickPlayMultiplayer 自动进服，
    # 被踢一次就停在标题界面不动了，测试的人还得手工点。
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

# ---------------------------------------------------------------------------
# 客户端

# 「复制为两份」在配置层面也补一下：client2 首次启动会自己生成 options.txt，但那是默认值
# （语言、GUI 缩放、视频设置全和 client1 不一样），两边界面不一致会让人怀疑是代码问题。
# 只在目标文件不存在时复制，不覆盖已经调过的设置。
CLIENT_ROOT="$ROOT/runs/$CELL"
for i in $(seq 2 "$COUNT"); do
    if [ -f "$CLIENT_ROOT/client1/options.txt" ] && [ ! -f "$CLIENT_ROOT/client$i/options.txt" ]; then
        mkdir -p "$CLIENT_ROOT/client$i"
        cp "$CLIENT_ROOT/client1/options.txt" "$CLIENT_ROOT/client$i/options.txt"
        echo "[duel] 已把 client1 的 options.txt 复制给 client$i（界面设置一致）"
    fi
done

echo "[duel] 启动 $COUNT 个客户端，自动进入 $ADDR ..."
CELL="$CELL" PVP_CLIENTS="$COUNT" PVP_ADDR="$ADDR" "$ROOT/scripts/dev-2clients.sh" &
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
