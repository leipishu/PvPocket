# AGENTS.md

给在本仓库工作的编码代理。人类读者请从 [`README.md`](README.md) 开始，工程流程见
[`CONTRIBUTING.md`](CONTRIBUTING.md)。

这个文件只写**代码里读不出来、但踩错就要返工**的东西：命令在哪跑、哪些约定是硬性的、怎么验证。
设计理由在 `docs/`，那里是规范；这里是指南。

## 项目

Minecraft 模组「口袋决斗」（`pvpocket`）：在 SMP 里开一个与生存完全隔离的决斗口袋，
自定义配装、多局对抗、打完不留痕迹。目标形态是 **6 个 Minecraft 版本 × 2 个加载器 = 12 格**
（1.20.1 / 1.21.1 / 1.21.11 / 26.1 / 26.2 / 26.3，每版 fabric + neoforge；1.20.1 用 forge）。
已建 6 格：1.21.1、1.21.11、26.1，各 fabric 与 neoforge 一份。

**服务端与客户端必须同版本。** 两端协议是自定义 payload，版本不一致时表现为面板不出现或规则不生效，
而不是一个明确的报错。

## 工作目录：一个 cell = 一个版本 × 一个加载器

仓库根就是 Gradle 根。**没有「工程根」这一层了**——每个 cell 是根下的一个子项目，
命令写成 `./gradlew :<cell>:<task>`。

```
PvPocket/
├── settings.gradle           # cells 清单，一个 cell 一个子项目
├── build.gradle              # 只放仓库面 + buildAll，不应用任何加载器插件
├── gradle/pvpocket-cells.gradle   # 六格共用的那一半（产物名/Java 级别/许可/dev 客户端清单）
├── gradle/catalogs/<mc>.toml      # 每个 MC 版本一份版本号，注册成 catalog `v<mc>`
├── gradle/libs.versions.toml      # 加载器工具链版本 + 插件坐标，catalog `libs`
├── src/                      # 手写源，不直接编译
├── versions/<cell>/build.gradle   # 每格约 100 行：加载器插件 + 依赖 + run
├── versions/<cell>/gen-src/  # 六格各自编译的生成树，由 scripts/port/gen.py 写出，不入库
├── runs/<cell>/{server,client1..4}   # 运行目录，按 cell 分开
├── legacy/1.20.1/            # 1.20.1 的独立 Gradle 根（FG6 要 Gradle 8）
└── scripts/ docs/ arena-src/
```

| 路径 | 内容 |
| --- | --- |
| `src/main/java/` | **所有加载器共用**（`session`、`rules`、`loadout`、`preset`、`network`、`platform`）。这里不许出现加载器 import |
| `src/fabric/java/` | Fabric 专用：`platform/fabric/` 的入口与平台实现。NeoForge 那一格对应 `src/neoforge/java/` |
| `src/fabric/resources/` | Fabric 专用资源，目前只有 `fabric.mod.json` |
| `src/client/java/` | 客户端里**与加载器无关**的部分：`client/gui`、`client/hud`、`client/render`、`client/platform/PvClientPlatform` |
| `src/client-fabric/java/` | 客户端加载器接线：`client/platform/fabric/` 的入口与平台实现。NeoForge 那一格对应 `src/client-neoforge/java/` |
| `src/main/resources/assets/pvpocket/lang/` | `zh_cn.json` / `en_us.json` |
| `versions/<cell>/` | 一格一个：`build.gradle` + `build/` + `gen-src/`（只有设了 `ext.pvpocketPort` 的格子才有后者） |
| `gradle/catalogs/<mc>.toml` | 一个 MC 版本一份：`minecraft` / `fabricApi` / `neoforge`。**加版本 = 加文件** |
| `gradle/libs.versions.toml` | 跨版本共享：`fabric-loader` / Loom / ModDevGradle，以及 `[plugins]` 的插件坐标 |
| `gradle.properties` | 只剩 `org.gradle.*`、Loom 下载源、`mod_id` / `mod_name` / `mod_version` / `maven_group` |
| `runs/<cell>/` | 运行目录，`server` 与 `client1..4`，**由 `gradle/pvpocket-cells.gradle` 决定** |
| `docs/zh/`、`docs/en/` | 六篇规范，平行且一一对应 |
| `docs/archive/` | 历史存档，**非规范**，不追改 |
| `scripts/` | 联调、地图转换、界面自动化、自检。多数脚本认 `CELL` 环境变量，索引见 `scripts/README.md` |

**放文件的判据**：这段代码要 import `net.fabricmc` / `net.neoforged` 吗？要 → `src/*-fabric` 或
`src/*-neoforge`；不要 → 不带后缀的那一层。判据不是「跟谁关系近」，因为这一条决定了移植一格的
代价。服务端与客户端各有一对：`src/main` ↔ `src/fabric`，`src/client` ↔ `src/client-fabric`。

**为什么加载器这一维落在子项目边界上**：Loom 与 ModDevGradle 都要接管 minecraft 依赖、run 任务与
remap，一个 project 只能应用其中一个。塞进同一个 project 就是让两个插件互相覆盖。

**26.x 的 Fabric 插件 id 不是 `fabric-loom`。** Loom 1.19 把插件拆成两个，26.1 用的是
`net.fabricmc.fabric-loom`：它是「不混淆」变体，apply 时自己把 remap 流水线整个关掉。用
`fabric-loom` + `fabric.loom.disableObfuscation` 这个开关在 1.19 上不生效，报的是
`Configuration 'mappings' has no dependencies`——看起来像缺依赖，其实是没关掉混淆。顺带一提，那个
开关只认 String，写成 Boolean 会被静默当成没设置。

**版本差异不进业务代码。** `src/` 里只有一层手写源，六格编译的都是
`versions/<cell>/gen-src/`——`scripts/port/gen.py` 复制 `src/` 时按表改名、按 `//? if` 剔分支
（1.21.1 那两格也走这一步，只是改名 0 条）。
所以 `src/` 里那 200 多个 `//?` 标记**两支代码同时存在**，直接拿某格的类路径编 `src/` 会报几十个
错，这是正常的。规则与踩坑记录见 `scripts/port/README.md`。

**为什么版本号按 MC 版本分文件**：`gradle/catalogs/<mc>.toml` 里的键名在每个文件里完全一样
（`minecraft` / `fabricApi` / `neoforge`），区分靠 catalog 名。catalog 名由 cell 名派生
（`1.21.1-fabric` → `v1_21_1`，见 `gradle/pvpocket-cells.gradle`），所以 cell 的 `build.gradle`
里**看不到 catalog 名**，复制一格不会漏改版本号。全塞进单个 `libs.versions.toml` 的话键名全局
唯一，第二个 MC 版本必然要叫 `minecraft2` 之类，而填错的表现是「编译过了但类属于另一版」——
构建期不报错。**不要把版本号写回 `gradle.properties`。**

## 构建与运行

```bash
./gradlew :1.21.1-fabric:compileJava :1.21.1-fabric:compileClientJava   # 编译一格
./gradlew buildAll                                                     # 全部 cell
```

**工具链版本不是随便挑的，四个 JDK 各管一段：**

| JDK | 用途 | 为什么不能换 |
| --- | --- | --- |
| 27 | Gradle 守护进程（`org.gradle.java.home`） | Loom 1.19 要求 JVM 25+，且不低于它要配置的 MC 版本 |
| 25 | 26.x 两格的 toolchain（编译与运行） | NeoForge 26.1 的 ASM 9.9.1 拒绝 level 27 的 class 文件，服务端起不来 |
| 21 | 1.21.1 / 1.21.11 两版的 toolchain | 那两版就是 21 |
| 17 | `legacy/1.20.1`（FG6） | ForgeGradle 6 只认 17 |

路径写在 `gradle.properties` 的 `org.gradle.java.installations.paths`，**不要靠 Gradle 自动探测**：
这台机器上它找不到 jdk-17。

**Gradle 是 9.9.1**，不是 8.x：Loom 1.19（26.x 用的不混淆变体）要求 ≥9.8。1.21.x 那几格用 Loom
1.13.6，在 9.9.1 上照常工作。`./gradlew wrapper --gradle-version` 在配置阶段就会失败（26.x 的
cell 需要新 Gradle 才能配置），要升级只能直接改 `gradle/wrapper/gradle-wrapper.properties`。

**产物只有一个出口：仓库根的 `dist/<cell>/`。** `assemble` 结束时 `collectDist` 会把 `build/libs`
里的 jar 同步过去（Fabric 的 dev jar 在 `devlibs/`，不会进去），所以一格两份、六格十二份，`buildAll`
末尾会把清单打出来。`dist/` 在 `.gitignore` 里。**不要去 `versions/<cell>/build/libs/` 找发行用的
jar**，那里还有中间产物。

**五个 run 任务六格完全一致**：`runServer`、`runClient`、`runClient2`、`runClient3`、
`runClient4`，换成另一格是同一套名字、同一批用户名（Dev1..Dev4）。IDEA 里这五条在 `.run/` 下，
六格各五条、统一放在 `PvPocket 单跑` 文件夹里，命名 `<cell> - Client / Client2 / Client3 /
Client4 / Server`。它们都是 Gradle 型配置，等价于 `./gradlew :<cell>:<task>`。

ModDevGradle 本来会自己往 `.idea/workspace.xml` 写一套 Application 型配置（引用
`build/moddev/*RunVmArgs.txt`），但那套落在文件夹外面、与 `.run/` 逐条重复，所以
`gradle/pvpocket-cells.gradle` 把 `neoForgeIdeSync` 关掉了。**不要再开回来**：两套同名配置会让
下拉里出现两份，其中一份不在文件夹里。

- 客户端默认 `--quickPlayMultiplayer localhost:25565` 直接进服，堆上限 1G。
- `-PquickJoin=off` 停在标题界面；`-PclientHeap=<size>` 改堆上限。
- 用户名、窗口尺寸、run 目录**只写在 `gradle/pvpocket-cells.gradle` 一处**。两个 cell 各写一遍的
  下场是联调脚本按 Dev1..Dev4 找人，另一边叫 NeoDev1，永远对不上。
- datagen 只有 1.21.x 的 Fabric 格有（`fabricApi.configureDataGeneration`），是 client 环境的子
  run，那两格的 `build.gradle` 里都清空了它的 `programArgs`。26.1-fabric 没有这一段：Loom 1.19
  把 program-arg 列表改成了只读，清不掉，而模组的资源是手写的，本来也没有 provider 要跑。

联调（服务端独占终端，客户端另起）：

```bash
./gradlew :1.21.1-fabric:runServer                   # 终端 1
PVP_CLIENTS=3 scripts/dev-clients.sh                # 终端 2；三个才能测对局面板 + 观战
CELL=1.21.1-neoforge PVP_CLIENTS=3 scripts/dev-clients.sh   # 同一套脚本跑另一格
```

**一条命令起全套**（服务端在后台，等到真的能连再起客户端）：

```bash
scripts/dev-duel.sh                        # NeoForge，Dev1 + Dev2，自动进服
CELL=1.21.1-fabric scripts/dev-duel.sh     # 换一格
```

`dev-duel.sh` 与 `dev-clients.sh` 的分工：前者自己管服务端（Ctrl+C 全停，服务端控制台不可交互），
后者假设服务端已在别处跑着（控制台还能敲命令）。`dev-duel.sh` 等的是服务端日志里的 `Done (`
而不是端口——Minecraft 在初始化早期就 bind 了端口，那时连上去会被踢，而 `--quickPlayMultiplayer`
被踢一次就停在标题界面，测试的人还得手工点。

服务端的 stdin 必须挂在一个**永不 EOF** 的管道上：后台进程的 stdin 默认是 `/dev/null`，
Minecraft 读到 EOF 会立刻自己关服。`dev-duel.sh` 用 `mkfifo` + `exec 3<>` 实现，并且
**不要以为往那个 FIFO 里写 `stop` 就能关服**——Windows 上 Gradle 不转发 stdin，实测无效，
脚本最后是按端口 `taskkill` 兜底。那个 FIFO **用完不删**：`rm` 在本机被替换成「移到回收站」，
回收站不认 FIFO，删除会失败，而下一次 `mkfifo` 会因文件还在而报错——脚本就再也起不来了。

日志：服务端 `runs/<cell>/server/logs/latest.log`，客户端 `versions/<cell>/build/devlogs/client{1,2,3}.out`。
**「面板没出来」先看客户端日志里 `canSend=` 那一行。**

### 跑 NeoForge 的 run 任务前必须清代理

NeoFormRuntime 会走系统代理（本机 `127.0.0.1:6494`），而该代理对 `piston-meta.mojang.com` 只挂不回；
直连 3 秒就好。症状是**卡住不是报错**：`:1.21.1-neoforge:downloadAssets` 打印 `DL .../17.json` 之后
CPU 归零，没有任何报错。

```bash
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY ./gradlew :1.21.1-neoforge:runServer
```

`versions/1.21.1-neoforge/build.gradle` 里 `repositories.clear()` 不能删：ModDevGradle 在 apply 时自动
塞两个 `maven.neoforged.net` 仓库，那个域名在本机连不上，Gradle 会换 CDN 节点逐个等到超时。
现用的是 `neoforged.forgecdn.net`（`/releases` + `/mojang-meta`），路径结构与官方逐字相同。

## 硬性约定

### 1. 本地化（最容易忘的一条）

**所有玩家可见文本一律走翻译键，代码里不出现中文字符串字面量。** 注释可以用中文，字符串不行。

- `PvLang.t(key, args…)` → `MutableText`，**服务端拼消息只能用这个**，由客户端按自己的语言解析。
- `PvLang.str(key, args…)` → `String`，**只给客户端自绘 GUI 用**。在服务端调用会吞掉玩家语言
  （专用服务端只加载 `en_us`），等于硬编码英文。
- 键布局：`pvpocket.rule.<id>.{label,hint,c<N>,unit}`、`pvpocket.item|material|flag|option|map.*`、
  `pvpocket.gui.<screen>.<element>`、`pvpocket.msg.<area>.<event>`、`pvpocket.command.*`、
  `key.pvpocket.*`。
- 目录类（`ItemKind` / `Material` / `WeaponFlag` / `LoadoutCatalog` / `ArenaMap` / `RuleDefinition`）
  **不持有文案**，只有 `labelKey` + `labelText()`（Text）+ `label()`（String）。
- 规则键从规则 id 派生——id 就是线格式，天然稳定。
- **命令语法不翻译**：`/pvpocket spectate leave` 是 token 不是句子。
- 中英键集必须完全一致，`%s` 占位符数量也必须一致。数字也用 `%s`，不要混 `%d`。

改完语言文件跑一次：

```bash
python scripts/check-lang.py
```

它查的不只是键集与 `%s` 数量，还有三件编译不会报的事：代码引用了不存在的键（面板上直接显示
键名）、`PvLang.t("key", ...)` 的实参个数与占位符对不上（消息末尾挂一个 `%s`）、以及
`.label` 有而 `.hint` 没有。`--strict` 把「语言文件里有、源码里没人用」也算失败。

配装内容的版本门槛在 `scripts/content-manifest.tsv`，逐格核对用：

```bash
python scripts/check-content.py 1.21.11-neoforge            # 这一格能给出哪些物品
python scripts/check-content.py --all                       # 12 格总表
python scripts/check-content.py 1.21.1-fabric --verify-jar <客户端 jar>   # 对着真实 jar 对账
```

移植每一格时都要跑一次 `--verify-jar`：清单里 `declared` 的条目只有对着 jar 才能变成结论。

### 2. 指令面收敛

**面板能做的，指令就不该有第二条路。** 新增指令前先问「面板能不能做」。保留的都有不可替代的理由：
控制台/RCON 没有客户端（`arena`、`guard`、`loadout give`）、按 id 找一场（`spectate <前缀>`）、
只读诊断（`status`、`loadout`）、GUI 通道失效时的兜底。

`arena copy` / `arena remove` / `arena reset` / `guard` 现在**两边都有**：主页「管理」页（仅 OP 等级 3
可见）给玩家，指令给控制台与 RCON。这不是「第二条路」，是同一个动作的两个入口——运维场景里一半是
没有客户端的。所以面板的动作必须落在同一段代码上（`HomeActionPayload` 的四个动作直接调
`ArenaManager.addCopy` / `removeCopy`、`resetArena`、`setGuardEnabled`），并且**服务端自己校验权限**：
面板不画按钮是方便，不是锁。

### 2b. 规则层（加规则之前先读这一条）

**一条规则只能有一个开关管它。** 两个开关问同一个问题，无论默认值怎么调都会有一半的玩家觉得
「改了没反应」——`c.fallDamage` 与 `e.hazard` 的坠落位曾经就是这样，取消坠落位之后面板仍显示
「开」，落地却不疼。发现重叠就删一个，别调和。

**删规则可以，改位序不行。** `e.hazard` / `a.winConditions` / `f.blockFilter` 的位号就是线格式，
重新编号会让旧存档静默指向别的选项。要删就整条删，位图不动。

**互斥语义要在数据层收敛，不只是界面。** `e.hazard` 的「无」是「其余三项都没有」，不是第四种危害；
`RuleCatalog.exclusiveBits` 声明它，`DuelRules.normalise` 把它收成一个值（`0` 与 `15` 都变 `1`），
`PvChips` 在点击时保证它——三处都要有，否则手改的包能让面板显示一个不生效的勾。

**规则之间的依赖要在面板上置灰，并写进 hint。** 目前三组：`a.roundLimit`/`a.timeoutVerdict` 依赖
`a.winConditions` 的「超时」位；`e.sizeX/Y/Z` 只对「平坦」生效（其余地图用包围盒）；`e.hazard` 的
「无」互斥。置灰优于隐藏（隐藏像丢了东西），优于保持可点（等于让人改一个没人读的数）。
`RuleRow` 的 `editable` 在构造时固定，所以依赖变了必须 `buildRows()` 重建整页，不能只改值。

**被夹过的值必须留痕。** `DuelRules.decode` 记录每个被 `clamp` 移动过的 id，`RULES_ACCEPTED` 里
有 `clamped` 一列。没有它，「我同意的图和实际打的图不是一张」在日志里是查不出来的——digest 只
哈希存下来的值。同一行还有 `changed`（与目录默认不同的 id），排查时不用解码三十多个键值对。

**`clamp` 同时负责吸附到步长网格。** `e.sizeX` 的步长是 8、从 16 起算，盘上若留着 45（旧版本或手改
的包），面板会显示 45 而箭头跳 45 → 53 → 61，这一行就再也回不到网格上。网格锚在 `min` 不是 0，
跟滑杆、步进器的算法一致；吸附之后越界的要退回一格，`max` 不保证落在网格上。

### 3. 场地分区与副本

- **`ArenaMaps.ALL` 的索引就是地图的「家」，永久不变。** 副本的格子编号存在
  `<world>/pvpocket/arena.json`，编号即地址——不要改成「取第一个空位」，那会让每份副本在
  有人加副本的瞬间集体搬家。
- **一张图能同时开几场 = 它有几个分区。** `busyMapMask` 的位含义是「按下去会被拒」，包括
  「分区全被租用」和「分区已删光」两种情况，不是「有人在打这张图」。
- **副本可以删到 0 份，而且删光后要能原址复活。** `removeCopy` 从 `slotsOf` 末尾收，正在打的格子
  跳过；删掉「家」记在 `arena.json` 的 `noHome` 里（家是从索引推出来的，抹不掉）。`addCopy` 见到
  `noHome` 先还家、再谈空格子——地址是运维记在纸上的东西，不能因为删过一次就搬到网格末尾。
- **地块可以溢出分区。** 上限是 `MAX_SIZE_XZ = PITCH − MIN_ARENA_GAP`，不是 `CELL`。所以
  `ArenaRegion.reset()` 清的是**分区与地块的并集**，只清分区会把大地图的外圈留在空地里。
- **启动预热只贴每张图的第一份。** 副本是容量不是展品。
- **准备期的 barrier 环只写空气**（`standIfAir`）。斗兽场的战斗区内缩，环正好压在沙地与看台之间
  唯一的讲台墙上，无条件写入会把那格挖成空气，而写入被 `suppressRecording` 包着，`revertRegion`
  也补不回来——每局都在同一位置留一条沟。

### 4. 界面（画布只有 426×240）

1280×720 窗口在 GUI scale 3 下就是 426×240。规范见 `docs/zh/04-visual.md`，以下是踩过的坑：

- **形状只走 `PvRaster`（SDF），不要自己写逐像素循环。** 覆盖率取「到边界的垂直距离」；不要再对
  `0.5 − d` 做超采样——那等于把 1px 滤波平均成 1.5px，直边上会报 0.75 而真值是 1.0（实现期踩过）。
  改完跑一次覆盖率自检（无洞、无重绘、误差 < 0.12）：
  `java -cp build/classes/java/client com.leipishu.pvpocket.client.gui.PvRaster`。
- **面板分区靠明度差，不靠描边。** 顶栏与侧栏是同一块 `SIDEBAR` 表面（`PvScreen.chromeW/chromeH`），
  内容凹槽叠在上面，只有右下角跟面板圆角。分隔线只能画在凹槽内部，不许横穿侧栏。
- **1px 的线一律用 `ctx.fill`**，不要用 `roundRect(..., h=1, radius=0)`。
- **模态弹窗挂 `PvScreen.overlay`**，不要只 append 到 `roots`。`roots` 只决定「画在上面」，
  不决定「拦住下面的点击」。弹窗存在时它**独占指针**：`PvScreen.render` 交给下面那层的坐标是
  `-1,-1`，滚动区通过 `PvScreen.modalPointer()` 拿到同一个事实——只清 `updateHover()` 不够，
  不少控件是在 `render()` 里用坐标自己算悬停的（卡片行、步进器端帽、chip、输入框光标）。
- **物品图标盖不住，只能不画。** `ctx.drawItem` 送到 `z=150` 的深度测试层，任何顺序的半透明遮罩
  都盖不住；弹窗存在时置 `PvDraw.itemsVisible = false`。
- **框定控件里的文本走 `PvDraw.textFit*` / `textWrap*`，不要直接 `ctx.drawText`。** 阶梯是
  原样 → 缩小（地板 `MIN_TEXT_SCALE = 0.75`）→ 换行 → 截断，差 2px 以内算放得下。任何写死宽度的
  框（按钮、chip、cycle、步进器、行标签列）都必须过它，否则英文一定溢出——中文放得下不代表英文
  放得下，而 `-PuiAudit` 默认跑中文，测英文要先把 `runs/client1/options.txt` 的 `lang` 改掉。
- **尺寸能改就别靠缩小硬塞。** 装饰性的东西先让出去：`PvCycle` 的箭头内距、历史行的回合点阵、
  对局行的阶段 chip，都是被让掉的一方。审计里的 `cut:` 清单说的是「这个框真的不够宽」，不是
  「字太大」——照着清单改框。`shrunk` 计数则是机制在正常工作，不用管。
- **`PvScrollPane` 的顺序是「宽度 → `onWidth()` → 高度」**。高度依赖宽度的控件必须走
  `PvWidget.onWidth()` 钩子，先算高度会让滚动范围永远是错的。`SettingRow.hint` 就是这样拿到
  行高的（提示句几行取决于宽度）。
- **`init()` 在窗口 resize 时也会跑**。选中的标签页、正在编辑的名册、已填的输入必须跨 `init()`
  保留，否则改一次窗口大小就丢一次工作。
- **页面 id 不等于标签索引。** 主页的「管理」页只对 OP 出现，所以标签条是
  `visiblePages()` 过滤出来的列表，`page` 是 id、`tabs.selected()` 是索引，两者用 `indexOf` 换算。
  之前两者相等只是因为每个页面都在——一旦有条件页面，上面的每一页都会错位一格。
- **条件页面的容器要在数据到达时重建，不能只重建内容。** `PvTabs` 的标签在构造时就固定了，而
  「有没有管理页」要等第一份快照才知道：只调 `buildPage()` 会让标签条停在旧项数上，管理页要等
  第二次 `init()` 才出现，而多出来的那一格会让它下面每个标签都打开上一页（点「设置」进管理页）。
  现在 `syncPages()` 先比页面集合、变了就整条重建。**审计发现不了这一类**——它按布局走，不模拟
  数据到达的先后。
- **侧栏是一条竖向预算。** 侧栏高 = 顶栏以下全部，`Tab 高 × 条目数 + 按钮高 × 个数 + 间距` 必须装得下；
  装不下先换排布（`compact`：按钮整排移到凹槽下方），再减按钮，最后才是减字号。八项标签加三个按钮
  在 190px 里只剩 12px 的 Tab，这已经是下限。侧栏宽要**同时**量 Tab 标签和按钮标签——英文的
  `Accept & start` 比任何 Tab 都宽，只量 Tab 会把它截成 `Accept & st…`。
- **两行制行高从控件算起**（`14 + 控件高 + 4`），不要从行底往上减：控件高度不一样时（chip 22、
  步进器 20），从底部减会让控件压住标签最后两行像素。
- **动效只动连续量**（位置、alpha、铺底浓度），**不要动绘制尺寸**。1.5px 的半径变化只能落在两个整数
  上，阻尼再平滑也读成两级跳变——滑杆悬停曾经就是圆的半径从 5 跳到 7，看起来像卡顿。要「变大」就
  在背后加一层光晕动它的 alpha。
- **两端留够半径。** 滑杆轨道两端各要预留滑块半径（`KNOB_MAX`），否则滑块到最大值时压在读数上。
  同理：控件宽度一律夹在行内，任何宽度下都不许越出本行。
- **不要用 `Formatting.BOLD` 给标题加重。** 原版粗体是同一字形右移一像素再画一遍，内置位图字体有
  手做的粗体表，TrueType 资源包没有——而这个界面就是给换过字体的玩家排的版。层级靠颜色（`TEXT`
  对 `TEXT_DIM`）和位置（页眉带分隔线）。`PvDraw.title()` 已经删了，标题就是普通 `PvDraw.text`。
- **新控件要能被审计看到。** 内联绘制的控件（`RuleRow` 的 control 之类）必须由容器在
  `childrenForAudit()` 里交出去，否则 `PvAudit` 完全不知道它存在——chips 压住标签两像素那次，
  所有检查都报 clean。
- **控件在布局期创建，不要在 `render` 里 `add()`。** 渲染里加一个控件就是每帧加一个：审计抓到过
  三份 "Watch" 叠在同一个像素上，而画面上只是文字比平时粗一点。
- **`PvTheme` 的颜色令牌不能是 `static final`。** javac 会把 `static final int` 内联进调用点，
  改值对已编译的 282 处引用无效——没有运行时换肤。亮色主题见 `docs/zh/04-visual.md` §2.5，
  强调色必须压深（白底上 `#F5A524` 只有 1.9:1，而它被当文字用）。
- **导出海报 = 同帧重画 + `ctx.draw()` 冲刷 + 截帧缓冲**，不是把面板放大。`SeriesResultScreen.drawPoster`
  临时把 `panelX/Y/W/H` 换成整个视口再跑一遍 `drawPanelFrame + drawContent`，然后
  `ScreenshotRecorder.takeScreenshot(framebuffer)` 写 PNG。这样分辨率跟着窗口与 GUI 缩放走、字号仍是
  1.0×，位图字体不会在非整数缩放下重采样；海报也比面板高（15 局在 320×180 上只放得下 6 局）。
  **必须先 `ctx.draw()`**，否则截到的是海报之前的帧。
- **整屏绘制要 overfill，`rect(-2, -2, w + 4, h + 4)`。** GUI 逻辑像素 = 帧缓冲 / scale **向下取整**
  （1280/3 = 426，426×3 = 1278），最后 `scale − 1` 列物理像素没有任何逻辑像素覆盖，海报右边会留一条世界。
- **审计里想抓某一帧必须等淡入结束。** `setScreen` 后的第一帧还在淡入，单帧钩子截到的是暗影；
  用倒计时（等 2~3 帧）再抓。同一秒内的两次导出会互相覆盖文件名，加序号。
- **结算卡片是「每人一份」。** `SeriesResultPayload` 带 `mySide`，胜负与「你」取决于收件人，
  不要改成一份广播再让客户端猜。

### 5. 协议方向

**判断「服务端支不支持某个功能」要问上行通道。** `canSend` 问的是「我能不能往这个通道发」；
下行包（如 `HomeStatePayload`）问它恒为 false。踩过一次：按钮永远灰着，而服务端在同一 tick
就回了快照。

主页面是唯一由客户端主动请求的界面（`HomeRequestPayload` → `HomeStatePayload`），
其余都是服务端推的。新增这类「客户端想要才要」的数据，照这个形状做，不要改成推送。

### 5b. 配装目录（跨版本的内容差）

**配装目录是物品注册表的投影，不是常量表。** 重锤与风弹是 1.21 才有的，长矛是 1.21.11 才有的，
铜制武器更晚——而且铜可能只对部分种类成立（有铜矛不一定有铜剑）。把这张表写成常量，每移植一个版本
就要靠人记得改，改漏的表现是「面板上有这一行，加上去却什么都没发」。

- **界面只列能建出东西的条目**：种类用 `ItemKind.availableOf(page)`，材质用 `kind.materials()`。
  不要直接遍历 `ItemKind.values()` 或 `Material.values()`——那样会把当前版本没有的选项画出来。
- **可用性是（种类 × 材质）的组合，不是材质全局属性**。`materials()` 按种类问注册表，所以铜矛有、
  铜剑没有这件事是自动成立的。
- **非材质化的种类没有自己的物品 id**（FOOD / BLOCK / POTION 由 `variant` 决定物品），它们的可用性
  恒为真。用「注册表里有没有 `food`」来判断会让整页消失——踩过一次。
- **线格式用 `kind.id` / `material.id`，永远不用 `ordinal()`**。`Material` 的序号会随插入新材质变化
  （`COPPER` 就插在中间），而序号一旦落进预设就是静默错位。
- **校验要分两句**：`item_missing`（这个版本没有这类物品，只能删掉这一行）与 `no_such_item`
  （这个材质建不出这个物品，换一个材质就好）。混成一句会让玩家不知道该怎么修。
- 新增物品或材质之后跑 `scripts/check-logic.sh`（`LoadoutContentCheck` 用三份手写的版本清单断言
  每个版本应当看到什么），再跑 `python scripts/check-content.py --all` 看 12 格总表——后者把版本
  门槛放在 `scripts/content-manifest.tsv` 里，新增一格时用 `--verify-jar <该格客户端 jar>` 对账。

### 5c. 加载器边界

**`src/main` 与 `src/client` 里一个加载器 import 都不许有。** 这条线决定了移植一格的代价：
守住了就是「写一个 200 行的实现类」，破了就是「重写一遍」。

```bash
python scripts/check-boundary.py            # main 与 client 都必须干净，退出码 1 即失败
python scripts/check-boundary.py --verbose  # 列出每一处违规的 file:line
```

- 加载器接触面是 `platform/PvPlatform`：payload 注册与收发、生命周期、四个否决点。实现类
  （`src/fabric/java/.../platform/fabric/FabricPlatform`）只做转发，**不写判断**——实现类里
  出现 if，那个判断多半属于 `PvBootstrap`。
- 「发生某事时该做什么」全部在 `platform/PvBootstrap.wire(platform)` 里，那部分不含加载器的名字。
- **入口类在 `platform/<加载器>/` 里**，根包的 `PvPocket` 只留 `MOD_ID` / `LOGGER` / `id()`。
  身份被九十多处引用，必须对所有加载器是同一个类；入口每个加载器都不同。
- 否决点只承诺**能不能发生**，不承诺**发生成什么样**。Fabric 的 `ALLOW_DAMAGE` 只能取消，
  NeoForge 能改伤害值——接口承诺后者就等于前者做不到，所以统一成 boolean。
- **线程切换归平台。** Fabric 的 receiver 跑在 netty 线程，必须跳回服务端线程；NeoForge 的
  handler 本来就在服务端线程。所以 `PvPayloads` 的接收器里没有 `server.execute`。
- **payload 的 codec 用原版的 `StreamCodec`，不要包一层自定义缓冲接口。** 1.20.5 起原版就有
  这一套，Fabric 与 NeoForge 吃的是同一个类型，字段顺序只写在 payload 类里。只有 1.20.1
  （1.20.5 之前）需要另写一层，那是那一格自己的事，不要为此把公共接口改复杂。
- **客户端有第二个接触面 `client/platform/PvClientPlatform`**（网络、tick、断线、界面初始化、
  按键、HUD、世界渲染、配置目录、版本号）。它必须待在 `src/client`，不能并进 `PvPlatform`：
  它的每个参数都来自 `net.minecraft.client`，并进公共接口就等于让专用服务端去解析 `Minecraft`。
- **渲染钩子按「栈的状态」分，不按加载器的名字分。** `onWorldRenderAfterTranslucent` 与
  `onWorldRenderLast` 是两个方法，因为 `PossessedHandRenderer` 依赖后者触发时模型视图栈仍持有
  视图旋转；合成一个就得让实现类去猜调用方要的是哪一个。
- **`@Environment(EnvType.CLIENT)` 一律不写。** 它只是文档，源码集已经保证了这件事；写了反而
  多一个加载器依赖。
- **往已初始化的界面里加控件走 `mixin/client/ScreenInvoker`**（`@Invoker("addRenderableWidget")`），
  不要用加载器的 `Screens.getButtons` 那种「把只读列表强转回可写」的绕法。
- **注入点被加载器搬走过的 mixin，一个类写不成两份行为。** NeoForge 会 patch 原版方法体，把
  `@At(target = "…")` 指的那条调用换掉（`ItemRenderer.render` 里的
  `ItemTransform.apply` → `ClientHooks.handleCameraTransforms`）。这时 Fabric 的 mixin 在 NeoForge 上
  匹配到 0 个候选，客户端在加载 `RenderType` 时就崩：`Critical injection failure … (0/1) succeeded`。
  做法是**两个文件、同一个全限定名**，分别放在 `src/client-fabric` 与 `src/client-neoforge`——
  每个构建只看得见其中一个，所以 `pvpocket.client.mixins.json` 仍然只有一条。**数字与判据
  （pose、比例、条件）必须提到 `src/client` 里的普通类**（如 `client/render/ItemBlockPose`），
  两份 mixin 只保留「在哪个调用点拦」这一句差异。
- **`MixinTargetCheck` 抓不到这一类**：类和成员都还在，是调用点搬走了。`scripts/check-logic.sh`
  因此跑两遍（Fabric 一遍、NeoForge 一遍），能抓的是「NeoForge 把整个方法删了」那种。

### 5d. 脚本

- **`.ps1` 一律 ASCII**，注释也一样。这些文件没有 BOM，Windows PowerShell 5.1 按 ANSI 代码页读，
  中文注释会让它解析失败，报的是**下一个 `}` 处的语法错误**——离真正的原因隔着十几行。
- `scripts/ui/shot-window.ps1` 按 `--username` 认客户端。ModDevGradle 把程序参数写进
  `@clientRunProgramArgs.txt`，命令行里只有文件路径，所以脚本会把 argfile 展开再匹配。

### 6. 沙盒

决斗维度内聊天只放行 `/pvpocket` 根字面量（白名单，不是黑名单）；任何人不得自行切游戏模式
（命令层 + `ServerPlayerInteractionManager.changeGameMode` 两层），但控制台/RCON 发起的放行，
否则运维救不了卡住的玩家。

钩子必须挂在 `CommandManager.execute`，不是 `executeWithPrefix`——玩家聊天指令直接调前者。

### 7. 文档

- `docs/zh/` 与 `docs/en/` 平行，六篇一一对应。**改一篇必须同步另一篇。**
- 改动历史写 `CHANGELOG.md`，只记使用者可感知的变化与改变后续做法的决定，不记内部重构。
- `docs/archive/` 是历史存档，不追改其中的旧路径与旧指令。
- README 只做模组介绍：定位、特性、玩法、运行要求、安装、文档索引、许可。
- 工程流程（构建、联调、仓库结构、脚本、提交）在 `CONTRIBUTING.md`，本文件不重复。
- 技术细节与设计理由写进 `docs/`，也不写进 README。

### 7b. 注释

**注释只写代码做什么、怎么用，不写它是怎么变成现在这样的。** 改动史进提交信息与 `CHANGELOG.md`，
论证进 `docs/`。完整规则与正反例在 `CONTRIBUTING.md` 的「注释」一节，这里只列会卡住你的硬项：

- 全英文，**注释里不出现任何非 ASCII 字符**（中文、`—`、`→`、弯引号都算）。
- 不写第一人称（I / we / our），不写 `used to` / `no longer` / `the old code` / `this fix` 一类叙述。
- 成段说明用 Javadoc（`/** */`），单个不超过 12 行；连续 `//` 不超过 2 行。
- Javadoc 摘要句首字母大写、句号结尾；`<p>` 与 `@param` 前留一个空 `*` 行；不写 `@author` / `@since`。
- public 顶层类型必须有 Javadoc；不留注释掉的代码；注释行不超过 100 列。
- `*.gradle` / `*.sh` / `*.py` / `*.toml` 里的注释只留解释非显然行为的，能删就删。

```bash
python scripts/check-comments.py            # 改完必须跑，exit 0 才算过
python scripts/check-comments.py --summary  # 哪个文件最多
```

### 8. 性能

- **稳态 tick 已经不用管，别去优化读路径。** 唯一真贵的是贴图，而贴图的瓶颈是**逐方块的调色板
  写入**：按 chunk 分块 + `BlockPos.Mutable` 实测 4560ms 对原 4577ms（噪声内），再加「跳过空
  section」4446ms 且斗兽场反而变慢；空格子纯读 320 万次就要 1300ms。`BlockPos` 的分配量早被
  JIT 逃逸分析消掉，`Mutable` 一点用都没有。
- **`regionAt` 只在「未抑制记录」的写入上被调用**（`recordingSuppressed > 0` 在它之前就 return，
  贴图与清格子根本不经过它），每局几百到几千次，线性扫描最多 16 次矩形比较。网格除法换来的
  收益测不出来，而地块溢出分区时 26% 的查询仍要走兜底——别再改。
- 判断瓶颈看这两行日志，不要凭感觉：
  `Stamped …; clear {} ms, build {} ms, capture {} ms` 与 `Vacated cell … in {} ms`。
- 真要再压贴图，方向是**减少写入量**（平地改尺寸会触发整格重贴），不是加速读写。
- `sweepMobs` 已有 `BY_SESSION.isEmpty()` 早退；`SessionJournal.append` 每条 flush 是「先写日志
  再改状态」的崩溃恢复顺序，不要为了性能动。

## 验证

**编译通过不等于可用。** 规则类改动至少要在两端跑一局，服务端 journal
（`runs/<cell>/server/world/pvpocket/journal/*.jsonl`）里有 `ROUND_STARTED`、`RULES_ACCEPTED` 等事件，
能直接回答「这一局按哪版规则打的」。注意 journal 里的 `=` 是 `\u003d`，`grep 'c.maxHealth='` 匹配不到。

脚本怎么选、每个认哪些环境变量，见 `scripts/README.md`。IDEA 里 `.run/` 下已经配好联调、自检、
单跑三类入口，右上角下拉直接点；它们与下面的命令行等价。

**改完界面先跑遮挡审计**，不要靠眼睛过六十个布局：

```bash
scripts/check-ui.sh          # 退出码 0 = 零发现，1 = 有遮挡，2 = 审计没跑完
```

它让开发客户端自己遍历 6 个界面 × 全部页面/弹窗 × 2 种画布（426×240 与 320×180），在真实渲染帧里
记录控件盒子和文字，检查零尺寸、越出裁剪区、同层互压。**加新界面或新页面时，记得给
`auditVariantCount()/auditVariant()` 补一个变体**，否则新页面不在审计范围内。
`-Dpvpocket.audit=true` 才启用，`-PuiAudit` 会把两个开关都带上。

审计测的是几何，不是内容：页面上一个控件都没有时它照样报 clean。主页面和预设页的数据是
`PvAuditHarness` 自己塞进去的（`homeData()` / `seedPresets()`），所以别把它们删了。

不需要 Minecraft 运行时的逻辑用自检类验证，一条命令：

```bash
./gradlew :<cell>:statsCheck  # 统计文件的幂等、上限、跨重启、坏行容错
```

统计文件是**唯一一类 bug 会永久留下**的东西：重复的历史行、丢错端的上限，都不会当场报错，
只会让某个玩家的记录永远错下去。改 `DuelStats` 必须跑它。

规则层有两条命令，都不需要客户端：

```bash
python scripts/check-rules.py --docs                       # 静态：读取点 / 默认值 / 语言键 / 文档一致性
python scripts/check-rules.py --coverage runs/1.21.1-fabric/server/world
                                                           # 历史：哪些规则从未以非默认值跑过
scripts/check-logic.sh                                     # 运行时：规则切换 + 配装目录的版本可用性
```

`check-rules.py` 的判据是「规则 id 在 `RuleCatalog` 之外还有没有**非注释**读取点」。`--coverage`
读 journal 的 `RULES_ACCEPTED`，回答的是另一个问题——**这条规则有没有被真的验证过**：一份「全部
OK」的静态报告只能说明线接上了，说明不了它会变。改规则默认值或语义之后先看这张表，别只看静态结果。

`check-logic.sh` 会编译并运行 `scripts/checks/*Check.java` 里的每一个检查，跑的是编译出来的真实类，
不是把公式抄一遍。目前三个：`RuleLogicCheck`（选项切换、步进、夹取、规范化）、`LoadoutContentCheck`
（配装目录的版本可用性）与 `MixinTargetCheck`（注入点的字符串目标，逐格用该格类路径核）。
加规则、改 `clamp` / `step` / `normalise`、增删物品或材质、改 mixin 之后必须跑它。
新增一个检查就是在 `scripts/checks/` 放一个带 `main` 的 `*Check.java`，退出码 0 表示通过。

界面自动化两条铁律：

- 抓图用 `scripts/ui/shot-window.ps1`（`PrintWindow` + `PW_RENDERFULLCONTENT`）。
  `CopyFromScreen` 在窗口被遮挡或失焦时**静默返回桌面壁纸**，看起来像模组卡死。
- 点击用 `scripts/ui/click-frac.ps1`（客户区比例），不要绝对像素——窗口最大化/还原后会点到别处，
  失败表现是「模组忽略了点击」，与真实 bug 无法区分。
- 暂停的 Minecraft 不出新帧，重复抓同一画面会拿到相同 PNG，别据此判断「客户端卡住」。
- **截图用完即删**（`find runs -name '*.png' -delete`），只有最终要展示的才保留。

## 提交

- 提交信息用中文，一句话说清「改了什么」，正文写**为什么**（尤其是取舍）。
- 不要为了过编译而关掉检查，也不要 `--no-verify`。

## 许可

模组代码按 **GNU Lesser General Public License v3.0** 分发，全文见 [`LICENSE`](LICENSE)；
它引用 GPL-3.0 的条款，副本见 [`LICENSE-GPL-3.0.txt`](LICENSE-GPL-3.0.txt)。

**这两份（外加 `THIRD-PARTY-NOTICES.md`）会被打进 jar 根目录**，由 `gradle/pvpocket-cells.gradle` 的
`processResources` 从仓库根复制。**不要**在 `src/main/resources` 里再放一份副本——许可全文只能有
一处来源。改文件名要同步 `legalFiles`，对不上时构建会直接失败。

**内置地图不受此影响**：`data/pvpocket/structure/*.nbt` 中四张来自
[zzuf-maps](https://github.com/zzufx/zzuf-maps)，按 CC BY-SA 4.0 分发；斗兽场由 `scripts/map/colosseum.py`
生成。署名与改动声明见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。
