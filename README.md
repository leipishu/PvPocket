# PvPocket（口袋决斗）

在 SMP 生存服务器内提供一个与正常生存完全隔离的「决斗口袋」：玩家可以互相发起单挑，在独立场地中使用自定义配装进行多局对抗，可按对局开启 Legacy 1.8 战斗；每局结束状态完全重置，整个决斗不影响双方在生存模式下的任何数据。

## 仓库结构

仓库根目录只放文档与各平台工程，**每个「加载器 + 游戏版本」组合一个独立子目录**，各自拥有独立的 Gradle 构建。这样后续增加 Forge / NeoForge 或 1.21.x 其他版本时，不会互相污染。

```
PvPocket/
├── docs/                      设计与技术文档
│   ├── PvPocket-vision-v0.1.md
│   ├── PvPocket-decisions-v0.1.md
│   ├── PvPocket-v0.2-方案确认.md
│   ├── PvPocket-v0.3-战斗规格与终稿.md
│   ├── PvPocket-v0.4-技术方案.md
│   ├── PvPocket-1.8-数值对照表.md
│   ├── PvPocket-S1-实现进度.md
│   ├── PvPocket-S2-实现进度.md
│   └── PvPocket-S3-实现进度.md
├── scripts/
│   └── dev-2clients.sh        双客户端联调启动脚本
├── fabric-1.21.1/             Fabric / Minecraft 1.21.1 工程
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   ├── gradlew / gradlew.bat
│   ├── gradle/wrapper/
│   └── src/
│       ├── main/              common：服务端与客户端共用
│       │   ├── java/com/leipishu/pvpocket/
│       │   └── resources/fabric.mod.json, pvpocket.mixins.json
│       └── client/            客户端专用（split source set）
│           ├── java/com/leipishu/pvpocket/client/
│           └── resources/pvpocket.client.mixins.json
└── .gitignore / .gitattributes
```

## 版本矩阵（fabric-1.21.1）

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.9 |
| Fabric API | 0.116.17+1.21.1 |
| Yarn mappings | 1.21.1+build.3 |
| Fabric Loom | 1.11.8 |
| Gradle | 8.14.3 |
| Java | 21 |

模组 ID `pvpocket`，Java 包名 `com.leipishu.pvpocket`。

## 构建

前置条件：JDK 21。**1.21.1 要求 Java 21，JDK 17 无法运行客户端**。

```bash
cd fabric-1.21.1

# 编译
./gradlew build

# 启动客户端（含开发环境）
./gradlew runClient

# 启动专用服务端
./gradlew runServer
```

产物位于 `fabric-1.21.1/build/libs/`。

`gradle.properties` 中固定了 Gradle 守护进程使用的 JDK 路径（Loom 1.11 要求 JVM 21+）。换机器或接入 CI 时修改这一行，或删除它并改用环境变量：

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew build
```

## 双客户端联调

决斗至少需要两名玩家，所以开发环境提供两个独立客户端。**它们不能共用一个运行目录**：同一个 `run/` 下两个 JVM 会抢 `logs/latest.log`（输的一方静默丢日志），`options.txt`、`saves`、资源索引也会互相覆盖。因此运行目录分成三份：

| 运行配置 | 目录 | 用户名 | 用途 |
| --- | --- | --- | --- |
| `runServer` | `run/` | — | 共享服务端（存档与 `pvpocket/journal` 都在这里） |
| `runClient` | `run-client1/` | `Dev1` | 客户端 1 |
| `runClient2` | `run-client2/` | `Dev2` | 客户端 2 |

用户名也必须不同：开发客户端默认用操作系统用户名登录，两个客户端会变成同一个玩家，后加入的会把先加入的顶掉。

**推荐流程**（服务端单独占一个终端，这样控制台还能输入 `stop` / `op` / `pvpocket`）：

```bash
# 终端 1：共享服务端，保持前台运行
cd fabric-1.21.1 && ./gradlew runServer

# 终端 2：等看到 "Done (...)" 后，拉起两个客户端并自动连接
scripts/dev-2clients.sh
```

脚本默认连 `localhost:25565`，可用 `PVP_ADDR` 覆盖（例如从另一台机器连进来）。

不带 `-PquickJoin` 单独运行 `./gradlew runClient` / `runClient2` 时客户端停在标题界面，此时也可以手动「对局域网开放」：主机客户端开一个世界并点「对局域网开放」，记下聊天栏里的端口，另一个客户端用「直接连接 → `localhost:端口`」加入。这条路径每次端口都是随机的，所以日常测试优先用上面的专用服务端。

## 文档

设计决策已锁定，进入技术方案阶段。文档阅读顺序：

1. `PvPocket-vision-v0.1.md` — 产品愿景、范围、领域概念、风险
2. `PvPocket-decisions-v0.1.md` — 9 个决策点的完整分析
3. `PvPocket-v0.2-方案确认.md` — 决策落定后的方案修订（协商流程、队伍、方块、GUI）
4. `PvPocket-v0.3-战斗规格与终稿.md` — 1.8 战斗六层规格、双端约束、验收标准
5. `PvPocket-v0.4-技术方案.md` — 工程架构、接管点、协议、存储、测试

## 许可

未指定。`fabric.mod.json` 中暂标为 `All-Rights-Reserved`，确定后再改。
