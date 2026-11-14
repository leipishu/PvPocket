<p align="center">
  <strong>中文</strong> · <a href="docs/en/CONTRIBUTING.md">English</a>
</p>

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
| JDK | 17 / 21 / 25 / 27，四份都要装，按 cell 分工见下 |
| Gradle | 9.9.1（wrapper 自带，不用单独装） |
| Fabric Loom | 1.13.6（1.21.x）/ 1.19.4（26.x，`net.fabricmc.fabric-loom` 不混淆变体） |
| ModDevGradle | 2.0.148 |
| 映射 | Mojmap（`loom.officialMojangMappings()`）；26.1 起不混淆，不需要映射 |
| Fabric Loader | 0.16.9（1.21.x）/ 0.20.1（26.x） |

**JDK 不能只装一个**，四个版本各有各的用处：

| JDK | 谁用 |
| --- | --- |
| 27 | Gradle 守护进程（Loom 1.19 要求 JVM 25+，且不能低于它要配置的 MC 版本） |
| 25 | 26.x 两个 cell 的 toolchain：编译和运行都是它 |
| 21 | 1.21.1 与 1.21.11 的 toolchain |
| 17 | `legacy/1.20.1`（FG6） |

26.x 的 toolchain **不能**用 27：NeoForge 26.1 自带的 ASM 9.9.1 拒绝 level 27 的 class 文件，
服务端在第一个 tick 之前就 `Unsupported class file major version 71` 退出。Fabric 那格跑 27 是能
跑的，但两格用同一个 JDK 才不会出现「一个能跑一个不能」。

`gradle.properties` 固定了守护进程用的 JDK 和 toolchain 的搜索路径（Gradle 的注册表探测在这台
机器上找不到 jdk-17）。换机器时改这两行：

```properties
org.gradle.java.home=C:\\Program Files\\Java\\jdk-27
org.gradle.java.installations.paths=...jdk-17,...jdk-21,...jdk-25,...jdk-27
```

## 构建

**仓库根就是 Gradle 根。** 一个 **cell** = 一个 Minecraft 版本 × 一个加载器，是一个子项目，
命令写成 `./gradlew :<cell>:<task>`。现在有六格：`1.21.1` / `1.21.11` / `26.1` 各 fabric 与
neoforge 一份。

```bash
./gradlew :1.21.1-fabric:compileJava :1.21.1-fabric:compileClientJava   # 只编译一格，最快反馈
./gradlew :1.21.1-fabric:build            # 产物归到 dist/1.21.1-fabric/
./gradlew buildAll                        # 全部 cell，产物按格子分放在 dist/ 下
```

**产物只有一个去处：仓库根的 `dist/<cell>/`。** 每个格子两份 jar（本体 + sources），六格就是十二份，
`buildAll` 结束时会把清单打出来。`versions/<cell>/build/libs/` 里还有中间产物（Fabric 的 dev jar 在
`devlibs/`），那些不是拿去装的，别从那里取。`dist/` 在 `.gitignore` 里，构建产物不入库。

两个加载器是**平行的**，没有主次：`versions/1.21.1-fabric/` 与 `versions/1.21.1-neoforge/` 各一个
`build.gradle`，共享根下的 `src/`。与加载器无关的那一半配置（产物名、Java 级别、许可、dev 客户端
清单）在 `gradle/pvpocket-cells.gradle`，两个 cell 都 `apply from:` 它。

> 为什么加载器必须是子项目而不是同一个 project 的两套配置：Loom 与 ModDevGradle 都要接管 minecraft
> 依赖、run 任务与 remap，一个 project 只能应用其中一个。

**jar 根目录里带三份法律文件**：`LICENSE`、`LICENSE-GPL-3.0.txt`、`THIRD-PARTY-NOTICES.md`。
它们不放在 `src/main/resources` 里，而是由 `gradle/pvpocket-cells.gradle` 的 `processResources`
从仓库根复制进去——
许可全文只该有一份，仓库根那份是唯一来源，复制品会漂移。LGPL-3.0 §4 要求收到二进制的人同时收到
许可副本，而分发的是 jar 不是仓库。改这几个文件名时要同步 `gradle/pvpocket-cells.gradle` 里的 `legalFiles`，
文件名对不上会直接让构建失败，不会悄悄打出一个没有许可的 jar。

源码分两个 source set，边界不能破：

| 目录 | 内容 | 约束 |
| --- | --- | --- |
| `src/main/java/` | 两端共用：`session`、`rules`、`loadout`、`preset`、`network`、`arena` | **会被专用服务端加载**，不许引用客户端类 |
| `src/client/java/` | 只在客户端：`client/gui`、`client/hud`、`client/preset` | 可以引用 `src/main`，反之不行 |

IDEA 直接打开仓库根并导入 Gradle 项目，工程列表里会出现 `1.21.1-fabric` 与 `1.21.1-neoforge` 两个。

## 开发联调

决斗至少需要两名玩家，所以配了四个客户端。**它们不能共用一个运行目录**：同一目录下两个 JVM 会抢
`logs/latest.log`（输的一方静默丢日志），`options.txt`、`saves`、资源索引也会互相覆盖。运行目录
由 `gradle/pvpocket-cells.gradle` 决定，全在 `runs/<cell>/` 下（不进版本库）：

| 运行配置 | 目录 | 用户名 | 用途 |
| --- | --- | --- | --- |
| `runServer` | `runs/<cell>/server/` | — | 共享服务端（存档与 `pvpocket/journal` 都在这里） |
| `runClient` | `runs/<cell>/client1/` | `Dev1` | 客户端 1 |
| `runClient2` | `runs/<cell>/client2/` | `Dev2` | 客户端 2 |
| `runClient3` | `runs/<cell>/client3/` | `Dev3` | 客户端 3，**只在测观战时需要** |
| `runClient4` | `runs/<cell>/client4/` | `Dev4` | 客户端 4，**只在测「同一张图不能开两场」时需要** |

用户名也必须不同：开发客户端默认用操作系统用户名登录，两个客户端会变成同一个玩家，后加入的会把
先加入的顶掉。

五个都是 Gradle 任务，**在 IDEA 右侧的 Gradle 工具窗口里直接双击就能跑**（`Tasks > 1.21.1-fabric >
runClient` … `runServer`；换成 `1.21.1-neoforge` 是同一套名字），不用开终端，也不用维护运行配置。客户端默认已经连到
`localhost:25565` 且堆上限 1G，点开就是可玩状态：

| 参数 | 默认 | 用途 |
| --- | --- | --- |
| `-PquickJoin=<host:port>` | `localhost:25565` | 连别的地址 |
| `-PquickJoin=off` | — | 停在标题界面（服务端没起、只想看界面时） |
| `-PclientHeap=<size>` | `1G` | 抬客户端堆上限 |
| `-PdebugBlocking` | — | 强制本机玩家处于剑格挡状态，用来单独看格挡姿势（第一/第三人称），不必开一局经典决斗 |

```bash
# 终端 1：共享服务端，保持前台运行（这样控制台还能输入 stop / op / pvpocket）
./gradlew :1.21.1-fabric:runServer

# 终端 2：等看到 "Done (...)" 后，拉起客户端并自动连接
scripts/dev-clients.sh                       # 两个客户端
PVP_CLIENTS=3 scripts/dev-clients.sh         # 三个，第三个用来看观战
PVP_ADDR=192.168.1.10:25565 scripts/dev-clients.sh
```

想一条命令起全套（服务端在后台、等到能连才起客户端）：

```bash
scripts/dev-duel.sh                           # NeoForge，Dev1 + Dev2
CELL=1.21.1-fabric scripts/dev-duel.sh        # 换一格
```

代价是服务端控制台不可交互（`stop` / `op` 敲不了），换来的是「改完代码，跑一遍看结果」不用管两个终端。
Ctrl+C 会把客户端和服务端一起停掉。

脚本只是 `runClient` / `runClient2` / … 的批量包装，单独起某一个用任务名即可（`./gradlew :1.21.1-fabric:runClient3`），
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
│   ├── en/                    规范文档（英文，与 zh 一一对应）；README.md 与 CONTRIBUTING.md 是英文入口
│   ├── assets/                文档图片，logo.png 就是 README 顶部那张
│   └── archive/               历史存档：方案草案、阶段记录、专项调研（非规范）
├── scripts/                   见 scripts/README.md 的索引
│   ├── dev-duel.sh            一条命令起全套：服务端 + 两个客户端（自动进服）
│   ├── dev-clients.sh         开发联调：只拉客户端，服务端自己开
│   ├── dev.cmd                dev-duel.sh 的 Windows 双击入口，可传 cell 名
│   ├── check-logic.sh         逻辑自检：规则 / 配装内容 / mixin 目标
│   ├── check-*.py             注释、语言键、配装清单、分层、规则文档
│   ├── check-ui.sh            界面遮挡审计：跑完所有界面，有遮挡就失败
│   ├── checks/                三个 *Check.java，由 check-logic.sh 编译并运行
│   ├── map/                   地图转换与校验（world2struct / colosseum / arena-* / rcon）
│   ├── ui/                    截图与按键脚本，供 AI Agent 做界面验证，普通开发不需要
│   ├── mojmap/                Yarn→Mojmap 迁移工具（一次性，已完成）
│   └── port/                  版本差生成器与改名表
├── settings.gradle            cells 清单：一个 cell 一个子项目
├── build.gradle               只放仓库面 + buildAll，不应用任何加载器插件
├── gradle.properties          JDK 路径、Loom 下载源、模组身份（mod_id / mod_version / maven_group）
├── gradle/
│   ├── pvpocket-cells.gradle  两个 cell 共用的那一半：产物名、Java 级别、许可、dev 客户端清单
│   ├── libs.versions.toml     加载器工具链版本（fabric-loader / Loom / ModDevGradle）+ 插件坐标
│   ├── catalogs/<mc>.toml     每个 MC 版本一份版本号：minecraft / fabricApi / neoforge
│   └── wrapper/
├── src/                       共享源码，所有 cell 编译同一份
│   ├── main/                  common：服务端与客户端共用
│   ├── client/                客户端专用，与加载器无关
│   ├── fabric/ client-fabric/ Fabric 接线
│   └── neoforge/ client-neoforge/   NeoForge 接线
├── versions/
│   ├── 1.21.1-fabric/         Fabric 那一格：加载器插件 + 依赖 + run
│   └── 1.21.1-neoforge/       NeoForge 那一格，与上一行平行
├── legacy/1.20.1/             1.20.1 的独立 Gradle 根（FG6 要 Gradle 8，未建）
├── runs/<cell>/               运行目录（客户端 1–4 + 服务端，不进版本库）
├── arena-src/                 地图源素材（不进版本库，见 docs/zh/06-maps.md）
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
| `scripts/dev-duel.sh` | 开发 | 一条命令起全套：后台服务端 + 两个客户端，等 `Done (` 后才连 |
| `scripts/dev-clients.sh` | 开发 | 只拉起开发客户端并自动加入本地服务端 |
| `scripts/dev.cmd` | 开发 | 上一行的 Windows 入口，双击即可，`scripts\dev.cmd 1.21.11-neoforge` 换格 |
| `scripts/check-logic.sh` | 开发 / CI | 编译并运行 `scripts/checks/*Check.java`，再核 mixin 目标 |
| `scripts/check-ui.sh` | 开发 / CI | 界面遮挡审计：遍历全部界面与两种画布，有遮挡即非零退出 |
| `scripts/map/world2struct.py` | 开发 / 加地图 | 世界存档、`.litematic`、`.schem` → 结构模板 |
| `scripts/map/colosseum.py` | 加地图 | 生成斗兽场模板 |
| `scripts/map/arena-verify.py` | 加地图 | 与第三方读取器逐块比对，验证转换正确 |
| `scripts/map/arena-map.py` | 排查 | 渲染俯视图，检查分区边界 |
| `scripts/map/rcon.py` | 自动化测试 | 从脚本以玩家身份执行指令 |
| `scripts/ui/*.ps1` | AI Agent | 窗口截图、按键、鼠标点击；Windows + PowerShell |

地图相关的四个脚本在 [`docs/zh/06-maps.md`](docs/zh/06-maps.md) 第 3 节有调用示例、第 6 节有清单。
全部脚本的索引与「我想做 X 该跑哪个」在 [`scripts/README.md`](scripts/README.md)。

`scripts/ui/` 下的脚本假定本机有正在运行的开发客户端，且窗口标题含 `Minecraft`。它们不是给人
手动执行的工具，删改前先确认没有别的自动化在引用。

## 验证

**编译通过不等于可用。** 下面五项按改动范围选：

1. **编译**：`./gradlew compileJava compileClientJava`。
2. **跑一局**：规则、会话、配装、网络的改动至少要在两端跑完一整局。服务端 journal
   （`runs/server/world/pvpocket/journal/*.jsonl`）里有 `RULES_PROPOSED`、`RULES_ACCEPTED`、
   `ROUND_STARTED` 等事件，能直接回答「这一局按哪版规则打的」。
   注意 journal 里的 `=` 被转义成 `\u003d`，`grep 'c.maxHealth='` 匹配不到。
3. **界面审计**：改过 GUI 就跑 `scripts/check-ui.sh`（约两分钟，退出码 `0` 才算干净；`1` 是有遮挡，
   `2` 是没跑完）。它让开发客户端自己遍历 6 个界面 × 全部页面/弹窗 × 两种画布，在真实渲染帧里
   记录控件盒子和文字。**加新界面或新页面时给 `auditVariantCount()/auditVariant()` 补一个变体**，
   否则新页面不在审计范围内。
4. **数据自检**：改过 `stats/DuelStats` 就跑 `./gradlew statsCheck`。统计文件是唯一一类 bug 会
   永久留下的东西——重复的历史行、丢错端的上限，都不会当场报错，只会让某个玩家的记录永远错下去。
5. **语言文件**：改过 `zh_cn.json` / `en_us.json` 后跑一次键集与占位符校验（命令在
   [`AGENTS.md`](AGENTS.md) 的「本地化」一节）。

界面自动化有两条铁律，违反了会得到假结论：抓图用 `scripts/ui/shot-window.ps1`
（`PrintWindow` + `PW_RENDERFULLCONTENT`，`CopyFromScreen` 在窗口被遮挡时会静默返回桌面壁纸）；
点击用 `scripts/ui/click-frac.ps1` 按客户区比例，不要用绝对像素。其余踩坑记录见 `AGENTS.md`。

**截图用完即删**（`find runs -name '*.png' -delete`），只保留最终要展示的那几张。

## 注释

注释写**这段代码是什么、怎么用**，不写它是怎么变成现在这样的。改动的来龙去脉属于提交信息与
`CHANGELOG.md`，写进注释里会随时间腐烂，而且和代码放在一起时没人分得清哪句还成立。

允许的三类内容：

1. 说明一个类型或方法做什么、参数与返回值的约定、边界条件。
2. 说明调用方式，尤其是调用顺序有要求、或调用方容易误用的地方。
3. 标记：`TODO:`、`FIXME:`、`NOTE:`，后面跟一句具体的说明。

不允许的两类：**改动史**（「以前是……」「不再……」）和**论证**（「这样做是因为……」）。需要论证
的地方写进 `docs/`，注释里只留一句结论，必要时用 `// see docs/zh/...` 指过去。

```java
// 不要
// 这里以前是直接读 remapClasspath.txt 的，但那是注解处理器的副产物，换 Loom 版本就没了，
// 所以改成由 Gradle 写一个稳定的文件出来，这样两个加载器走同一条路，不用再分叉。

// 要
// Written by the cell's writeCompileClasspath task; stable across Loom versions.
```

```java
/**
 * Restores the snapshot onto the player and verifies the result.
 *
 * @param player an online, living player; a corpse is rejected by the caller
 * @return true when the state digest matches the snapshot
 */
```

### 可以脚本验证的（CI 会跑）

```bash
python scripts/check-comments.py            # 全仓库
python scripts/check-comments.py --summary  # 每个文件的违规数
```

| 规则 | 判据 |
| --- | --- |
| `cjk` / `non-ascii` | 注释里不出现中文或任何非 ASCII 字符（破折号、箭头、弯引号都算） |
| `first-person` | 不出现 I / me / my / we / us / our |
| `narrative` | 不出现 `used to`、`no longer`、`the old code`、`this fix` 一类叙述改动的短语 |
| `javadoc-form` | 摘要句首字母大写、以句号结尾 |
| `javadoc-p` | `<p>` 与 `@param` / `@return` 等标签前有一个空 `*` 行 |
| `javadoc-long` | 单个 Javadoc 不超过 12 行 |
| `javadoc-tag` | 不用 `@author` / `@since` / `@version` |
| `type-doc` | public 顶层类型必须有 Javadoc |
| `code-run` | 连续 `//` 不超过 2 行；成段说明用 Javadoc |
| `commented-out` | 不留注释掉的代码，删掉或恢复 |
| `width` | 注释行不超过 100 列 |

### 靠人判断的

- **一句话说完**。需要第二句才写第二句，不要为了显得完整而铺开。
- **不复述签名**。`getName()` 不需要注释说「返回名字」。
- **不写修辞**。「这正是……的原因」「关键在于」这类结构一律删掉，直接陈述事实。
- **不用口语**（「搞」「弄」「一堆」），也不用过度书面语（「 hereby 」「it is worth noting that」）。
- **术语与代码一致**：代码里叫 `session`，注释就不要写「对局对象」。
- 配置与脚本文件（`*.gradle`、`*.sh`、`*.py`、`*.toml`）里的注释**只保留解释非显然行为的**，
  能用代码说清的一律删掉。

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
