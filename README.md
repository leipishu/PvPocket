# PvPocket（口袋决斗）

在 SMP 生存服务器内提供一个与正常生存完全隔离的「决斗口袋」：玩家可以互相发起单挑，在独立场地中
使用自定义配装进行多局对抗，可按对局开启 Legacy 1.8 战斗；每局结束状态完全重置，整个决斗不影响
双方在生存模式下的任何数据。

**两个人想打一场，三十秒内能开打，打完什么痕迹都不留。**

## 文档

规范文档在 [`docs/`](docs/)，中英双语，`zh/` 与 `en/` 平行、篇目一一对应。

| 想知道 | 读 |
| --- | --- |
| 这是什么、为什么这么设计、从哪读起 | [`docs/zh/01-overview.md`](docs/zh/01-overview.md) |
| 39 条规则的含义，哪些真正生效 | [`docs/zh/02-rules.md`](docs/zh/02-rules.md) |
| 分层、状态机、协议、快照与恢复 | [`docs/zh/03-architecture.md`](docs/zh/03-architecture.md) |
| 界面视觉规范（颜色、度量、状态、动效、文案） | [`docs/zh/04-visual.md`](docs/zh/04-visual.md) |
| 部署、权限、玩家流程、指令、运维、排障 | [`docs/zh/05-usage.md`](docs/zh/05-usage.md) |
| 地图来源与许可、转换流程、怎么加一张图 | [`docs/zh/06-maps.md`](docs/zh/06-maps.md) |
| 每个版本改了什么 | [`CHANGELOG.md`](CHANGELOG.md) |
| 设计草案与阶段记录（非规范） | [`docs/archive/`](docs/archive/README.md) |

视觉规范与地图的第三方许可另见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。

## 版本矩阵

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

./gradlew build          # 产物在 fabric-1.21.1/build/libs/
./gradlew runClient      # 开发客户端
./gradlew runServer      # 专用服务端
```

`gradle.properties` 固定了 Gradle 守护进程使用的 JDK 路径（Loom 1.11 要求 JVM 21+）。换机器或接入
CI 时改这一行，或删掉它改用环境变量：

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew build
```

## 开发联调

决斗至少需要两名玩家，所以开发环境配了四个客户端。**它们不能共用一个运行目录**：同一个目录下两个
JVM 会抢 `logs/latest.log`（输的一方静默丢日志），`options.txt`、`saves`、资源索引也会互相覆盖。
运行目录由 `build.gradle` 决定，全部在 `fabric-1.21.1/runs/` 下（不进版本库）：

| 运行配置 | 目录 | 用户名 | 用途 |
| --- | --- | --- | --- |
| `runServer` | `runs/server/` | — | 共享服务端（存档与 `pvpocket/journal` 都在这里） |
| `runClient` | `runs/client1/` | `Dev1` | 客户端 1 |
| `runClient2` | `runs/client2/` | `Dev2` | 客户端 2 |
| `runClient3` | `runs/client3/` | `Dev3` | 客户端 3，**只在测观战时需要** |
| `runClient4` | `runs/client4/` | `Dev4` | 客户端 4，**只在测「同一张图不能开两场」时需要** |

用户名也必须不同：开发客户端默认用操作系统用户名登录，两个客户端会变成同一个玩家，后加入的会把先
加入的顶掉。

五个都是 Gradle 任务，**在 IDEA 右侧的 Gradle 工具窗口里直接双击就能跑**（`fabric-1.21.1 >
Tasks > fabric > runClient` … `runServer`），不用开终端，也不用维护运行配置。客户端默认已经连到
`localhost:25565` 且堆上限 1G，点开就是可玩状态：

| 参数 | 默认 | 用途 |
| --- | --- | --- |
| `-PquickJoin=<host:port>` | `localhost:25565` | 连别的地址 |
| `-PquickJoin=off` | — | 停在标题界面（服务端没起、只想看界面时） |
| `-PclientHeap=<size>` | `1G` | 抬客户端堆上限 |

```bash
# 终端 1：共享服务端，保持前台运行（这样控制台还能输入 stop / op / pvpocket）
cd fabric-1.21.1 && ./gradlew runServer

# 终端 2：等看到 "Done (...)" 后，拉起客户端并自动连接
scripts/dev-2clients.sh                       # 两个客户端
PVP_CLIENTS=3 scripts/dev-2clients.sh         # 三个，第三个用来看观战
PVP_ADDR=192.168.1.10:25565 scripts/dev-2clients.sh
```

脚本只是 `runClient` / `runClient2` / … 的批量包装，单独起某一个用任务名即可（`./gradlew runClient3`），
在 IDEA 的 Gradle 面板里则是分别双击。

三个 JVM 对一台开发机来说是重的，第三个客户端有时会在标题界面卡住不自动加入（`quickPlay` 没触发），
关掉重开即可。服务端没起时客户端会停在「连接失败」，点「返回标题界面」再手动连——日常测试优先用
上面的专用服务端。

## 仓库结构

仓库根目录只放文档与各平台工程，**每个「加载器 + 游戏版本」组合一个独立子目录**，各自拥有独立的
Gradle 构建。这样后续增加 Forge / NeoForge 或 1.21.x 其他版本时不会互相污染。

```
PvPocket/
├── docs/
│   ├── zh/                    规范文档（中文）
│   ├── en/                    规范文档（英文，与 zh 一一对应）
│   └── archive/               历史存档：方案草案、阶段记录、专项调研（非规范）
├── scripts/
│   ├── dev-2clients.sh        开发联调：拉起 N 个客户端并自动连接
│   ├── world2struct.py        地图转换：世界存档 / .litematic / .schem → 结构模板
│   ├── colosseum.py           生成斗兽场（唯一一张不是下载来的图）
│   ├── arena-verify.py        转换校验：以第三方读取器为参照逐块比对
│   ├── arena-map.py           把场地存档渲染成俯视图，确认分区真的分开了
│   ├── rcon.py                向开发服务端发 RCON 指令（脚本化测试用）
│   └── agent/                 截图与按键脚本，供 AI Agent 做界面验证，普通开发不需要
├── fabric-1.21.1/             Fabric / Minecraft 1.21.1 工程
│   ├── build.gradle           五个 run 配置与运行目录都在这里
│   ├── settings.gradle
│   ├── gradle.properties
│   ├── gradlew / gradlew.bat
│   ├── runs/                  运行目录（客户端 1–4 + 服务端，不进版本库）
│   ├── arena-src/             地图源素材（不进版本库，见 docs/zh/06-maps.md）
│   └── src/
│       ├── main/              common：服务端与客户端共用
│       └── client/            客户端专用（split source set）
├── CHANGELOG.md
├── THIRD-PARTY-NOTICES.md
└── .gitignore / .gitattributes
```

## 脚本

| 脚本 | 给谁用 | 作用 |
| --- | --- | --- |
| `scripts/dev-2clients.sh` | 开发 | 拉起开发客户端并自动加入本地服务端 |
| `scripts/world2struct.py` | 开发 / 加地图 | 世界存档、`.litematic`、`.schem` → 结构模板 |
| `scripts/colosseum.py` | 加地图 | 生成斗兽场模板 |
| `scripts/arena-verify.py` | 加地图 | 与第三方读取器逐块比对，验证转换正确 |
| `scripts/arena-map.py` | 排查 | 渲染俯视图，检查分区边界 |
| `scripts/rcon.py` | 自动化测试 | 从脚本以玩家身份执行指令 |
| `scripts/agent/*.ps1` | AI Agent | 窗口截图、按键、鼠标点击；Windows + PowerShell |

`scripts/agent/` 下的脚本假定本机有正在运行的开发客户端，且窗口标题含 `Minecraft`。它们不是给人
手动执行的工具，删改前先确认没有别的自动化在引用。

## 许可

模组代码的许可未指定，`fabric.mod.json` 中暂标为 `All-Rights-Reserved`，确定后再改。

**内置地图不受此影响**：`data/pvpocket/structure/*.nbt` 中四张来自
[zzuf-maps](https://github.com/zzufx/zzuf-maps)，按 **CC BY-SA 4.0** 分发；斗兽场由
`scripts/colosseum.py` 生成，无第三方义务。署名与改动声明见
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。
