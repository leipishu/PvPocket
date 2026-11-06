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
│   ├── PvPocket-视觉规范-v0.6.md   界面视觉与动效规范（现行）
│   ├── PvPocket-1.8-数值对照表.md
│   ├── PvPocket-S1-实现进度.md
│   ├── PvPocket-S2-实现进度.md
│   └── PvPocket-S3-实现进度.md
├── scripts/
│   ├── dev-2clients.sh        双客户端联调启动脚本
│   ├── world2struct.py        地图转换：世界存档 / .litematic / .schem -> 结构模板
│   ├── colosseum.py           生成斗兽场模板（唯一一张不是下载来的图）
│   ├── arena-verify.py        转换校验：以第三方读取器为参照逐格比对 + 游戏内回读
│   └── arena-map.py           把决斗维度的存档渲染成俯视图，用来确认分区真的分开了
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
└── .gitignore / .gitattributes / THIRD-PARTY-NOTICES.md
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
| `runClient3` | `run-client3/` | `Dev3` | 客户端 3，**只在测观战时需要** |
| `runClient4` | `run-client4/` | `Dev4` | 客户端 4，**只在测「两场决斗同时进行」时需要** |

用户名也必须不同：开发客户端默认用操作系统用户名登录，两个客户端会变成同一个玩家，后加入的会把先加入的顶掉。

**推荐流程**（服务端单独占一个终端，这样控制台还能输入 `stop` / `op` / `pvpocket`）：

```bash
# 终端 1：共享服务端，保持前台运行
cd fabric-1.21.1 && ./gradlew runServer

# 终端 2：等看到 "Done (...)" 后，拉起两个客户端并自动连接
scripts/dev-2clients.sh
```

脚本默认连 `localhost:25565`，可用 `PVP_ADDR` 覆盖（例如从另一台机器连进来）。

**测观战要三个客户端**：两个参与者都在场里，没有人能站在旁边看。第三个客户端是显式开启的，
不传变量就不会启动：

```bash
PVP_CLIENTS=3 scripts/dev-2clients.sh
```

三个 JVM 对一台开发机来说是重的，第三个客户端有时会在标题界面卡住不自动加入（`quickPlay`
没触发），关掉重开即可。

**测「同一张图不能开两场」要四个客户端**：Dev1+Dev2 开一局占住地图之后，第二局需要两个空闲玩家，
Dev3 一个人不够。第四个客户端不接进脚本，单独起：

```bash
cd fabric-1.21.1 && ./gradlew runClient4 -PquickJoin=localhost:25565
```

不带 `-PquickJoin` 单独运行 `./gradlew runClient` / `runClient2` 时客户端停在标题界面，此时也可以手动「对局域网开放」：主机客户端开一个世界并点「对局域网开放」，记下聊天栏里的端口，另一个客户端用「直接连接 → `localhost:端口`」加入。这条路径每次端口都是随机的，所以日常测试优先用上面的专用服务端。

## 观战

对局默认允许观战（规则面板 B 组的「允许观战」，`b.spectator`），发起方可以关掉。

```bash
/pvpocket list              # 打开对局面板，列出服务器上正在进行的每一局
/pvpocket spectate <前缀>    # 不进面板，直接用 session id 前缀进入
/pvpocket spectate leave    # 退出观战，回到原来的位置
```

面板里每行是一个对局，右侧按钮有三种状态：可以看的是「观战」，自己正在看的是「退出」，自己是
这一局的参与者则显示「本局中」（禁用）。没开观战的对局按钮也是禁用的，鼠标悬停会说明原因。

进入之后是旁观模式，但**先是自由视角**：相机还在自己身上，可以飞过去看清场地和两个人，确认要看谁
再按 `V` 附身。不附身的时候屏幕上只有底部那条横条，快捷栏不出现——还没有一个身体可以描述。

按 `V` 附身后，相机绑在某位参与者身上，看到的就是他的第一人称画面。原版在旁观模式里完全不画手
（`GameRenderer.renderHand` 直接跳过），所以手持物是模组自己按原版 `HeldItemRenderer` 的变换重画的：
主副手分开、空手时画手臂、挥手弧线和装备偏移都照抄，和那个人自己屏幕上看到的逐像素一致。屏幕上多
两条东西：

| 位置 | 内容 |
| --- | --- |
| 底部横条 | `观战 · <地图> · 第 N 局` + `视角 <名字> (1/2) · V 切换 · G 解除`；未附身时是 `视角 自由视角 · V 附身` |
| 快捷栏 | 只在附身后出现。被附身者的九格快捷栏，选中格高亮，上方一行 `名字 · HP · 护甲` |

| 按键 | 作用 |
| --- | --- |
| `V` | 附身到下一位参与者（绕圈）；解除附身后按它会重新附身 |
| `G` | 解除附身，回到自由飞行的旁观视角（仍在观战，仍看得到比分） |

自由飞行有边界：横向比场地包围盒每边多 12 格，顶高是场地顶再往上 10 格，底部是平台往下 6 格。
碰到会收到一行灰字说明，退回 4 格后重新武装，下一次再碰到还会说。为什么不是贴着光幕：光幕画在
战斗区上，而斗兽场的战斗区是沙子、包围盒是外墙加十排看台——按光幕限制会把人关在看台里面，只能看到
方块内部。

退出观战会把三样东西还回去：游戏模式、维度、坐标朝向。背包、效果、经验从来没被动过，所以
不需要还。

**兜底**：观战状态只存在内存里，服务器被强杀时来不及还原，玩家 `playerdata` 里就留下了决斗维度的
坐标和 `SPECTATOR` 模式，下次登录会出生在一块悬空的场地上、还没法挖方块。所以每个玩家在**不在**
决斗维度时，位置和游戏模式会被持续记录到 `<world>/pvpocket/homes.json`（30 秒最多写一次盘）。
加入服务器时如果发现人在决斗维度里，直接送回记录的位置并把 `SPECTATOR` 改回去——正常流程永远走不到
这一步，它只在还原丢了的时候生效。

## 沙盒规则

决斗维度（`pvpocket:arena`）对玩家来说是个沙盒：进去之后没有管理员权限。

| 限制 | 拒绝方式 |
| --- | --- |
| 聊天区指令 | 只放行 `/pvpocket`，其余一律不发下去，回一行红字 |
| 切换游戏模式 | 命令层和交互管理器各一道，任何来源的玩家请求都拒绝 |

**为什么是指令白名单而不是黑名单。** 黑名单要维护 `/gamemode`、`/tp`、`/give`、`/effect`、
`/fill`……漏一个就是一个洞，别的模组加一条指令又是一个洞。白名单只有一条规则，新指令自动被拒。

**为什么游戏模式要拦在两层。** 命令层挡住 `/gamemode` 和 `F3+F4` 的模式切换器（后者发的也是
`/gamemode`）。但换模式不止这一条路——别的模组可以直接调 `changeGameMode`，所以交互管理器上还有
一道，拦的是"谁都没发命令"的那种。

**模组自己怎么办。** 开局要切生存、观战要切旁观、退出要还回去，这些都得能过。它们走
`Sandbox.changeGameMode`，调用期间临时抬开限制。直接写 `player.changeGameMode` 的话，在场地里
会被静默拒绝——开局就停在错误的模式上，而且没有任何报错。

**运维留了口子。** 控制台、RCON、命令方块不受限：`/gamemode`、`/tp` 照样能对场地里的玩家执行。
否则一个卡在决斗维度里的玩家，除了改存档没有别的办法救。判定依据是**发起者**是不是玩家，不是
玩家在不在场地里。

## 滞留保护与场地重置

决斗维度是虚空，进得去不代表出得来。所以有两层兜底：

**滞留检测。** 每隔一 tick 扫一遍在线玩家：人在决斗维度里，又不是任何一场对局的参与者、也不是观战者，
就给他 5 秒倒计时，然后送回进入该维度之前的位置（记录在 `homes.json`，不一定是主世界）。倒计时走
动作栏，聊天区只留一条说明，带一个可点的 `[ 不要移出 ]`。

**给运维留的开关。** 按钮点下去执行的就是 `/pvpocket guard false`，也可以在进去之前先执行：

| 指令 | 作用 |
| --- | --- |
| `/pvpocket guard true` | 开启自动移出（默认） |
| `/pvpocket guard false` | 关闭自动移出，可以留在维度里检查；重启后仍然有效 |

按玩家记录，不是全局开关——一个忘了恢复的全局开关等于对所有人关掉保护。需要 OP（等级 2）。

**场地重置。** 维度被改坏了、崩溃留下了残骸、两张图叠在了一起，重贴一遍是唯一能修的办法：

```bash
/pvpocket arena reset     # 需要 OP（等级 3）
```

它会按顺序做四件事：踢掉所有观战者（相机绑在即将被传送的人身上等于看空帧）→ 强制结束所有对局
（`abort` 而不是结算，结算会颁奖、会进入下一局，都不是重置想要的）→ 把维度里剩下的人送出去 →
清空所有格子并重新贴图。重新贴图是一 tick 一张，和开服时填展位走同一条路径：一次做完要卡主线程
4.5 秒，看门狗会报警，在线客户端会掉线。

平坦场地不参与重贴（它的尺寸由规则决定，只能按需生成），所以重置时单独清空，下一次选平坦图时重建。

## 文档

设计决策已锁定，进入技术方案阶段。文档阅读顺序：

1. `PvPocket-vision-v0.1.md` — 产品愿景、范围、领域概念、风险
2. `PvPocket-decisions-v0.1.md` — 9 个决策点的完整分析
3. `PvPocket-v0.2-方案确认.md` — 决策落定后的方案修订（协商流程、队伍、方块、GUI）
4. `PvPocket-v0.3-战斗规格与终稿.md` — 1.8 战斗六层规格、双端约束、验收标准
5. `PvPocket-v0.4-技术方案.md` — 工程架构、接管点、协议、存储、测试

## 场地地图

规则面板 E 组的「场地模板」可以在**平坦**（一块悬空平台）和五张内置地图之间切换。地图存在
`data/pvpocket/structure/*.nbt`，是原版结构模板。

决斗维度是**纯虚空**：没有陆地，每张图各自浮在自己的分区里，图的外面是一圈屏障方块铺的缓冲
带——走出地图会落在隐形平台上而不是掉进虚空。

| 显示名 | 尺寸 (x×y×z) | 形态 |
|---|---|---|
| 游乐屋 | 82 × 28 × 57 | 封闭室内竞技场，石英 |
| 浮岛 | 45 × 34 × 60 | 悬空圆盘岛，边缘外是空的 |
| 暗星云 | 87 × 43 × 79 | 封闭空间站，两层走廊 |
| 枯木镇 | 88 × 36 × 87 | 西部小镇街道 |
| 斗兽场 | 147 × 29 × 119 | 椭圆竞技场，十排看台 |

四张来自 [zzuf-maps](https://github.com/zzufx/zzuf-maps)，**CC BY-SA 4.0**；斗兽场是本仓库
用 `scripts/colosseum.py` 生成的，没有第三方义务。署名与改动声明见
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。

**同一张地图同一时间只能有一场决斗。** 服务器上可能同时有好几局在打，两张图撞在一起时双方会站在
两份一模一样的地形上，"去斗兽场"就成了一句没法执行的话。所以：

- 打开规则面板时，正被占用的地图标成橙色的 `游乐屋（使用中）`；
- 左右箭头会**跳过**它们，不会停在那里；
- 真要绕过去（面板开着的时候别人刚好开局），点「同意并开始」时服务端会拒绝并说明是哪张图。

（曾经还有试炼密室、蓟桥、阿瓦隆三张，已按需求下架；`e.template` 的索引因此整体前移。）

**想换成自己的地图**：不需要打开游戏，也不需要结构方块（它有 48³ 上限，装不下 1v1 场地）。
用 `scripts/world2struct.py` 直接读源文件（1.21 / 1.13–1.17 / 1.8 的世界存档都支持）：

```bash
python -m pip install nbtlib litemapy anvil-parser2

# 1. 看尺寸、方块数、调色板
python scripts/world2struct.py probe 你的图.litematic --palette

# 2. 转换
python scripts/world2struct.py convert 你的图.litematic \
    --out fabric-1.21.1/src/main/resources/data/pvpocket/structure/arena_你的图.nbt

# 3. 验证（必须以第三方读取器为参照，不能拿自己的输出当参照）
python scripts/arena-verify.py --source 你的图.litematic --nbt .../arena_你的图.nbt
```

然后在 `ArenaMaps.java` 里加一个常量、追加进 `ALL`（**只能追加，索引是线格式的一部分**），
补一行授权声明。

完整流程、尺寸门槛、常见坑见
[`docs/PvPocket-场地模板-调研与工具.md`](docs/PvPocket-场地模板-调研与工具.md)。

## 许可

模组代码的许可未指定，`fabric.mod.json` 中暂标为 `All-Rights-Reserved`，确定后再改。

**内置地图不受此影响**：`data/pvpocket/structure/*.nbt` 是第三方美术资源，按各自的
CC BY-SA 4.0 分发，详见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。
