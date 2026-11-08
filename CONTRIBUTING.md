# 贡献指南

面向要改代码、加地图、报问题的人。**只想装来玩**请看 [README](README.md)，部署与玩家流程见
[`docs/zh/05-usage.md`](docs/zh/05-usage.md)。

## 先读什么

| 想知道 | 读 |
| --- | --- |
| 分层、状态机、网络协议、快照与恢复 | [`docs/zh/03-architecture.md`](docs/zh/03-architecture.md) |
| 界面视觉规范（颜色、度量、状态、动效、文案） | [`docs/zh/04-visual.md`](docs/zh/04-visual.md) |
| 内置地图怎么转换、怎么加一张 | [`docs/zh/06-maps.md`](docs/zh/06-maps.md) |
| 哪些约定是硬性的、哪里踩过坑 | [`AGENTS.md`](AGENTS.md) |

`AGENTS.md` 是给编码代理（含 AI）的浓缩版，人读同样适用：它只写「读代码读不出来、踩错要返工」的
东西——本地化、指令面、界面尺寸、沙盒、文档同步。**本文件补的是环境、构建、联调与提交流程**，
设计理由不在这里重复。

## 环境

| 工具 | 版本 |
| --- | --- |
| JDK | 21（1.21.1 强制要求，**JDK 17 跑不起客户端**） |
| Gradle | 8.14.3（wrapper 自带，不用单独装） |
| Fabric Loom | 1.11.8 |
| Yarn mappings | 1.21.1+build.3 |
| Fabric Loader | 0.16.9 |
| Fabric API | 0.116.17+1.21.1 |

`gradle.properties` 固定了 Gradle 守护进程使用的 JDK 路径（Loom 1.11 要求 JVM 21+）。换机器或接入
CI 时改这一行，或删掉它改用环境变量：

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew build
```

## 构建

**所有 Gradle 命令都在 `fabric-1.21.1/` 下执行。仓库根不是工程根。**

```bash
cd fabric-1.21.1
./gradlew compileJava compileClientJava   # 只编译，最快的反馈，改一行就能验
./gradlew build                           # 产物在 fabric-1.21.1/build/libs/
```

源码分两个 source set，边界不能破：

| 目录 | 内容 | 约束 |
| --- | --- | --- |
| `src/main/java/` | 两端共用：`session`、`rules`、`loadout`、`preset`、`network`、`arena` | **会被专用服务端加载**，不许引用客户端类 |
| `src/client/java/` | 只在客户端：`client/gui`、`client/hud`、`client/preset` | 可以引用 `src/main`，反之不行 |

IDEA 直接打开 `fabric-1.21.1/`（不是仓库根）并导入 Gradle 项目。

## 开发联调

决斗至少需要两名玩家，所以配了四个客户端。**它们不能共用一个运行目录**：同一目录下两个 JVM 会抢
`logs/latest.log`（输的一方静默丢日志），`options.txt`、`saves`、资源索引也会互相覆盖。运行目录
由 `build.gradle` 决定，全在 `fabric-1.21.1/runs/` 下（不进版本库）：

| 运行配置 | 目录 | 用户名 | 用途 |
| --- | --- | --- | --- |
| `runServer` | `runs/server/` | — | 共享服务端（存档与 `pvpocket/journal` 都在这里） |
| `runClient` | `runs/client1/` | `Dev1` | 客户端 1 |
| `runClient2` | `runs/client2/` | `Dev2` | 客户端 2 |
| `runClient3` | `runs/client3/` | `Dev3` | 客户端 3，**只在测观战时需要** |
| `runClient4` | `runs/client4/` | `Dev4` | 客户端 4，**只在测「同一张图不能开两场」时需要** |

用户名也必须不同：开发客户端默认用操作系统用户名登录，两个客户端会变成同一个玩家，后加入的会把
先加入的顶掉。

五个都是 Gradle 任务，**在 IDEA 右侧的 Gradle 工具窗口里直接双击就能跑**（`fabric-1.21.1 >
Tasks > fabric > runClient` … `runServer`），不用开终端，也不用维护运行配置。客户端默认已经连到
`localhost:25565` 且堆上限 1G，点开就是可玩状态：

| 参数 | 默认 | 用途 |
| --- | --- | --- |
| `-PquickJoin=<host:port>` | `localhost:25565` | 连别的地址 |
| `-PquickJoin=off` | — | 停在标题界面（服务端没起、只想看界面时） |
| `-PclientHeap=<size>` | `1G` | 抬客户端堆上限 |
| `-PdebugBlocking` | — | 强制本机玩家处于剑格挡状态，用来单独看格挡姿势（第一/第三人称），不必开一局经典决斗 |

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

日志：服务端 `runs/server/logs/latest.log`，客户端 `build/devlogs/client{1,2,3}.out`。
**「面板没出来」先看客户端日志里 `canSend=` 那一行。**

## 仓库结构

仓库根目录只放文档与各平台工程，**每个「加载器 + 游戏版本」组合一个独立子目录**，各自拥有独立的
Gradle 构建。这样后续增加 Forge / NeoForge 或 1.21.x 其他版本时不会互相污染。

```
PvPocket/
├── docs/
│   ├── zh/                    规范文档（中文）
│   ├── en/                    规范文档（英文，与 zh 一一对应）
│   ├── assets/                文档图片，logo.png 就是 README 顶部那张
│   └── archive/               历史存档：方案草案、阶段记录、专项调研（非规范）
├── scripts/
│   ├── dev-2clients.sh        开发联调：拉起 N 个客户端并自动连接
│   ├── audit-ui.sh            界面遮挡审计：跑完所有界面，有遮挡就失败
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
├── CONTRIBUTING.md            本文件
├── AGENTS.md                  给编码代理的说明：命令、硬约定、雷区
├── LICENSE                    LGPL-3.0
├── LICENSE-GPL-3.0.txt        LGPL-3.0 引用的 GPL-3.0 副本
├── THIRD-PARTY-NOTICES.md
└── .gitignore / .gitattributes
```

## 脚本

| 脚本 | 给谁用 | 作用 |
| --- | --- | --- |
| `scripts/dev-2clients.sh` | 开发 | 拉起开发客户端并自动加入本地服务端 |
| `scripts/audit-ui.sh` | 开发 / CI | 界面遮挡审计：遍历全部界面与两种画布，有遮挡即非零退出 |
| `scripts/world2struct.py` | 开发 / 加地图 | 世界存档、`.litematic`、`.schem` → 结构模板 |
| `scripts/colosseum.py` | 加地图 | 生成斗兽场模板 |
| `scripts/arena-verify.py` | 加地图 | 与第三方读取器逐块比对，验证转换正确 |
| `scripts/arena-map.py` | 排查 | 渲染俯视图，检查分区边界 |
| `scripts/rcon.py` | 自动化测试 | 从脚本以玩家身份执行指令 |
| `scripts/agent/*.ps1` | AI Agent | 窗口截图、按键、鼠标点击；Windows + PowerShell |

地图相关的四个脚本在 [`docs/zh/06-maps.md`](docs/zh/06-maps.md) 第 3 节有调用示例、第 6 节有清单。

`scripts/agent/` 下的脚本假定本机有正在运行的开发客户端，且窗口标题含 `Minecraft`。它们不是给人
手动执行的工具，删改前先确认没有别的自动化在引用。

## 验证

**编译通过不等于可用。** 下面四项按改动范围选：

1. **编译**：`./gradlew compileJava compileClientJava`。
2. **跑一局**：规则、会话、配装、网络的改动至少要在两端跑完一整局。服务端 journal
   （`runs/server/world/pvpocket/journal/*.jsonl`）里有 `RULES_PROPOSED`、`RULES_ACCEPTED`、
   `ROUND_STARTED` 等事件，能直接回答「这一局按哪版规则打的」。
   注意 journal 里的 `=` 被转义成 `\u003d`，`grep 'c.maxHealth='` 匹配不到。
3. **界面审计**：改过 GUI 就跑 `scripts/audit-ui.sh`（约两分钟，退出码 `0` 才算干净；`1` 是有遮挡，
   `2` 是没跑完）。它让开发客户端自己遍历 5 个界面 × 全部页面/弹窗 × 两种画布，在真实渲染帧里
   记录控件盒子和文字。**加新界面或新页面时给 `auditVariantCount()/auditVariant()` 补一个变体**，
   否则新页面不在审计范围内。
4. **语言文件**：改过 `zh_cn.json` / `en_us.json` 后跑一次键集与占位符校验（命令在
   [`AGENTS.md`](AGENTS.md) 的「本地化」一节）。

界面自动化有两条铁律，违反了会得到假结论：抓图用 `scripts/agent/shot-window.ps1`
（`PrintWindow` + `PW_RENDERFULLCONTENT`，`CopyFromScreen` 在窗口被遮挡时会静默返回桌面壁纸）；
点击用 `scripts/agent/click-frac.ps1` 按客户区比例，不要用绝对像素。其余踩坑记录见 `AGENTS.md`。

**截图用完即删**（`find runs -name '*.png' -delete`），只保留最终要展示的那几张。

## 提交

- 提交信息用中文，一句话说清「改了什么」，正文写**为什么**，尤其是取舍。
- 一个提交一件事。格式化、重命名不要混进功能改动，否则 review 时看不出真正变了什么。
- 不要为了过编译而关掉检查，也不要 `--no-verify`。
- 改到玩家可见行为的，同步 `CHANGELOG.md`。

## 文档

- `docs/zh/` 与 `docs/en/` 平行，六篇一一对应。**改一篇必须同步另一篇**，否则两种语言的规范会漂移。
- `CHANGELOG.md` 只记使用者可感知的变化与改变后续做法的决定，不记内部重构。
- `docs/archive/` 是历史存档，**非规范**，不追改其中的旧路径与旧指令。
- 分工：**README 介绍模组、本文件讲工程流程、`docs/` 写规范与设计理由、`AGENTS.md` 列硬约定。**

## 许可

提交即表示同意以 [LGPL-3.0](LICENSE) 授权你的贡献。新增第三方素材（贴图、地图、音效）必须能
兼容该许可，并在 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) 里登记来源与许可。
