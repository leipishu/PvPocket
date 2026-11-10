# 架构规范

> 规范文档 · 中文版。英文版：[`../en/03-architecture.md`](../en/03-architecture.md)

## 1. 分层

工程用 Loom 的 `splitEnvironmentSourceSets()` 分成两套源集：

| 源集 | 内容 | 说明 |
| --- | --- | --- |
| `src/main/java` | 会话、规则、场地、战斗、网络注册、指令、存储 | 服务端与客户端共用的部分 |
| `src/client/java` | 自绘 GUI、HUD、输入、渲染 | 只在客户端加载 |

模组**强制要求客户端同版本**：规则面板、配装编辑器、对局面板、计分板全部是自绘的，
没有客户端就没有界面。但网络层仍对每个下行包做 `canSend` 检查——「强制」是策略，
不是协议，一个原版客户端仍可能连进来，此时面板打开应退化为「什么都不发生」，
而不是服务端抛异常。

## 2. 权威模型

**客户端只表达意图，服务端决定一切。**

| 客户端能说的 | 服务端如何处理 |
| --- | --- |
| 「我接受这份规则」（`RulesSubmitPayload`） | 重新核对阶段、是否轮到它、配装指纹是否仍然一致 |
| 「我要观战这一局」（`SpectateRequestPayload`） | 核对会话存在、观战开关、是否已是参与者 |
| 「我按住了右键格挡」（`BlockingPayload`） | 核对经典模式、主手是剑、没死、不是旁观者 |
| 「把配装存成这个」（`LoadoutSubmitPayload`） | 逐项校验，非法项丢弃并回报 |

没有任何包携带「我造成了多少伤害」或「我的生命是多少」。战斗数值、击退、护甲全部在
`LivingEntityMixin` / `PlayerEntityMixin` 里按 1.8 公式重算，客户端只负责显示。

## 3. 会话状态机

```
DRAFT ──发送征询──> NEGOTIATING ──全员同意──> FROZEN
                        │                      │
                        │                      ├─> ROUND_READY ─> ROUND_ACTIVE ─> ROUND_SETTLED
                        │                      │                        │              │
                        └──拒绝/取消──┐         │                        └──────────────┘
                                      ↓         ↓                              （还有下一局）
                                   VOIDED   RESTORING ─> ARCHIVED
```

| 阶段 | 含义 | 有快照 |
| --- | --- | --- |
| `DRAFT` | 发起方在改规则，还没发给对方 | 否 |
| `NEGOTIATING` | 已发给对方，等回应 | 否 |
| `FROZEN` | 规则与配装已锁定，正在传送与快照 | 是 |
| `ROUND_READY` | 回合准备期，硬墙未拆 | 是 |
| `ROUND_ACTIVE` | 回合进行中 | 是 |
| `ROUND_SETTLED` | 本回合已结算，等下一局或结束 | 是 |
| `RESTORING` | 正在逐项还原玩家状态 | 是 |
| `ARCHIVED` / `VOIDED` | 终态：正常归档 / 未开始就作废 | — |

`isPreFreeze()`（`DRAFT` / `NEGOTIATING`）是分界线：在此之前取消不需要还原任何东西，
在此之后每个终态都必须走还原路径。**「有快照」等价于「必须还原」**，这条不变量是
崩溃对账的基础。

## 4. 快照与恢复

进场前对每个参与者做完整快照：位置与朝向、背包（36 格 + 副手 + 盔甲）、生命、饥饿与饱和、
状态效果、经验等级与进度、游戏模式、属性修饰符。

- 快照落在内存的 `DuelSession` 里，同时把「进入前的位置」写进
  `<world>/pvpocket/homes.json`。
- 还原走 `PlayerSnapshot.restore`，逐项写回并重新同步客户端。
- 崩溃或重启后，`homes.json` 是唯一还能用的东西：玩家上线时若人在决斗维度，
  直接送回记录的位置。**快照本身不跨重启**，这是有意的取舍——跨重启恢复背包需要
  处理「期间玩家在别处获得过物品」的合并冲突，代价远大于收益。

游戏模式还原走 `setGameMode(GameMode, GameMode)`（playerdata 恢复路径），
不是 `changeGameMode`，因此不受沙盒的模式锁定影响。

## 5. 维度与分区

所有地图并排放在 `pvpocket:arena` 维度的不同格子里，`ArenaManager.CELLS` 定义 16 个分区，
每格 160 格见方、中心距 256，格与格之间留 96 格空地。选图 = 传送到固定坐标。

- **租用**：开局时按 `e.template` 申请一格，记录会话 id。一张图能同时租「它拥有的分区数」次——
  地图的「家」是它在 `ArenaMaps.ALL` 里的索引，额外分区（副本）记在 `pvpocket/arena.json`，
  由 `/pvpocket arena copy` 分配。副本的格子编号是地址，重启不会挪。
- **地形保护**：本局内被改动的方块记录在 `ArenaRegion` 的变更集里，结束时分批复原。
- **重建**：`/pvpocket arena reset` 会先 `abort` 所有对局、送走所有人、清空维度，
  然后**一 tick 重贴一张图**。5 张图约 15 万方块，一次做完主线程停 4.5 秒，
  会触发看门狗报警并踢掉客户端。

维度是虚空，地图外圈有屏障缓冲带。边界由客户端光幕绘制，服务端只判定位置。

## 6. 网络协议

全部走 Fabric 的自定义 payload，注册集中在 `PvPocketNetworking`。

| 方向 | 包 | 用途 |
| --- | --- | --- |
| C→S | `BlockingPayload` | 格挡按住/松开 |
| C→S | `RulesSubmitPayload` | 征询 / 接受 / 拒绝 / 关闭面板 |
| C→S | `LoadoutRequestPayload` | 请求打开配装编辑器 |
| C→S | `LoadoutSubmitPayload` | 提交配装 |
| C→S | `SpectateRequestPayload` | 列表 / 进入 / 退出 / 切换 / 解除 |
| C→S | `PresetSharePayload` | 发布 / 取用分享码 |
| C→S | `HomeRequestPayload` | 「给我一份主页面快照」 |
| C→S | `HomeActionPayload` | 发起决斗 / 用规则预设开局 / 用配装预设开局；OP 3 还能复制分区、重建场地、开关滞留保护 |
| S→C | `RulesOpenPayload` / `RulesClosePayload` | 规则面板开/关 |
| S→C | `LoadoutOpenPayload` | 配装编辑器打开 |
| S→C | `MatchListPayload` | 对局列表 |
| S→C | `MatchHudPayload` | 计分板 |
| S→C | `ArenaBoundsPayload` | 场地轮廓 |
| S→C | `SpectateStatePayload` / `PossessPayload` | 观战条 / 附身目标快捷栏 |
| S→C | `CombatStatePayload` | 战斗模式与格挡状态镜像 |
| S→C | `PresetShareResultPayload` | 分享码的结果：状态码 + 预设内容 |
| S→C | `HomeStatePayload` | 主页面快照：生涯计数、历史、进行中对局、状态位；OP 另带场地分区表 |

**主页面是唯一由客户端主动要求打开的界面**，其余界面都是服务端推过来的。它因此自带一对
请求/应答：`HomeRequestPayload`（空包）→ `HomeStatePayload`。快照不推送，只在玩家打开面板
时现算——没有别人需要这份数据，推它只是白烧带宽。快照里的计数、历史、进行中对局都是扁平的
`|` 分隔串，解析在客户端；理由与分享码相同：服务端没有语言，也不该决定面板长什么样。

**判断「服务端是否支持主页面协议」要问上行通道。** `canSend` 问的是「我能不能往这个通道发」，
而 `HomeStatePayload` 只走下行，问它恒为 false——表现是按钮永远灰着，哪怕服务端在同一 tick
就回了快照。问 `HomeRequestPayload` 才是对的。

上行包一律在服务端 `server.execute(...)` 里处理，确保跑在主线程上。主页面请求额外做了节流
（每玩家 1 秒一次，表上限 512 条），因为它是唯一一个客户端可以无限催的包。

`PacketCodec.tuple` 最多 6 个分量，超过就得把字段收进嵌套 record（`RulesOpenPayload` 的
`Details` 就是这么来的）。

上行包一律在服务端 `server.execute(...)` 里处理，确保跑在主线程上。

**分享码过网只带状态码，不带句子。** `PresetShareResultPayload` 的 `status` 是
`PUBLISHED` / `FETCHED` / `UNKNOWN_CODE` / `REJECTED` / `FULL` 之一，文案由客户端按自己的
语言拼——服务端没有语言，一条「分享码已过期」的中文句子发给英文客户端就是乱码。
`PresetShareRegistry` 是纯内存、纯主线程的：5 分钟 TTL，每 100 tick 扫一次，读取时再查一次
过期，没有锁也没有持久化。分享码是传输物，预设文件才是库。

## 7. 沙盒规则

决斗维度内是一个受限环境：

| 限制 | 实现 |
| --- | --- |
| 聊天只能发 `/pvpocket` 指令 | `CommandManagerMixin` 在 `CommandManager.execute` 的 HEAD 拦截 |
| 任何人不得自行切换游戏模式 | 命令层 + `ServerPlayerInteractionManager.changeGameMode` 两层 |

**钩子必须挂在 `CommandManager.execute`，不是 `executeWithPrefix`。** 玩家聊天指令由
`ServerPlayNetworkHandler.onChatMessage` 自己解析后直接调 `execute`；`executeWithPrefix`
只有控制台和 RCON 用。挂错位置的表现是「运维被拦住、玩家畅通无阻」，而且启动、编译、
`/pvpocket` 都正常，测试时看起来一切没事。

白名单而不是黑名单：只放行根字面量 `pvpocket`（剥掉命名空间前缀）。黑名单要维护
`/gamemode`、`/tp`、`/give`、`/fill`……漏一个就是一个洞，别的模组加一条指令又是一个洞。

**运维必须留口子**：控制台、RCON、命令方块发起的模式切换一律放行，否则救不了卡在场地里的玩家。
实现方式是 `Sandbox.authorised` 计数器，`CommandManagerMixin` 在非玩家来源执行期间抬开限制，
模组自己的切换走 `Sandbox.changeGameMode` 并 `try/finally` 配对。

## 8. 存储

| 文件 | 位置 | 内容 |
| --- | --- | --- |
| `journal/<session>.log` | `<world>/pvpocket/` | 会话逐条事件，用于崩溃对账与排障 |
| `homes.json` | 同上 | 进入决斗维度前的位置，掉线兜底 |
| `guard.json` | 同上 | 滞留保护的按玩家豁免名单 |
| `stats/stats.json` | 同上 | 每个玩家的生涯计数，整文件重写 |
| `history/<uuid>.jsonl` | 同上 | 每个玩家一场一行的历史，追加写 |
| `presets.json` | `config/pvpocket/`（**客户端**） | 玩家的配装与规则预设 |
| `client.json` | 同上 | 主题与动效开关（**不含按键**，按键在原版 `options.txt`） |

**两个统计文件，因为写入模式不同。** 计数每挨一刀就变，所以留在内存、在系列赛结束/断线/关服时
刷盘——每刀一次磁盘写等于每半秒一次；历史是追加的，一人一个文件读的时候一次顺序读到底，
共享文件则每次追加都要重写所有人的记录。

历史上限是**读时**执行的 200 场：文件在压实阈值（250 行）之前允许长到 250，追加才是 O(1)，
但 `history()` 只交回最新 200 条——承诺是 200，文件布局是实现细节。压实会**保留最新**的 200 行，
丢掉最旧的。

追加前会先确认文件以换行结尾：崩溃留下的半行如果不补分隔符，下一条记录会被粘在它后面，
两条一起变成废行。

采集点：

| 数值 | 位置 | 理由 |
| --- | --- | --- |
| 伤害 | `LivingEntity#applyDamage` 的 HEAD | 此处已过护甲、未过吸收，是「实际打出去的伤害」；且双方都必须在同一场 `ROUND_ACTIVE` 里、不是队友 |
| 击杀 | 死亡时读 `ServerPlayerEntity#getAttacker()` | 摔死、自爆、环境杀不计给任何人 |
| 小局 | `settle` 时追加一条 `RoundLog` | `winner` 存的是阵营序号，写进 `MatchRecord` 时换算成「我这边赢没赢」 |

`MatchRecord` 是**一人一条**，不是一场一条：同一个系列赛里每个参与者各写一份，各自带着自己一侧的
比分与自己的对手名单。共享一条的话，读的人得先被告诉自己是哪一边。

`presets.json` 是唯一一个不在世界存档里的文件，也**只有客户端读写**。配装和规则是玩家跨服务器
携带的东西，跟着存档走意味着换一个服务器就要重建一遍；服务端从不读它，用预设时走的是普通的
`RulesSubmitPayload` / `LoadoutSubmitPayload`，下游不需要知道预设存在。

写入走 `PvPocketStorage.writeAtomically`（先写临时文件再改名），避免断电时留下半个 JSON。
`guard.json` 读失败按「无豁免」处理：宁可多踢一次，不会少踢。

## 9. 已知取舍

- **快照不跨重启**（见 §4）。代价是重启后玩家回到进入前的位置但物品按当前存档算。
- **队伍是显式名册，但形状仍然是硬的。** 一方最多 5 人、最多 4 方、总共 20 人，这是
  `ArenaRegion.PAD_COUNT`、`SessionManager.MAX_TEAMS` / `MAX_PARTICIPANTS` 与 `b.teamSize` /
  `b.maxPlayers` 共同决定的；超过就在 `submitRoster` 里被拒。
  分队由发起方在名册页上直接画卡片决定，`RosterBoard.encode()` 编码成 `"uuid,uuid;uuid"` 随
  `rules_submit` 一起上来，`submitRoster` 压实掉空卡片后把 `team` / `member` 写进
  `Participant`——**队伍不再是派生量**，因此不需要 round-robin，也不存在「改队数就地重排」。
  代价是分队只存在于名册里：`b.teamCount` 这条规则已经删掉，场上有几方等于几张非空卡片。
- **拒绝是终局标记，不是取消对局。** `Participant.rejected` 一旦置位就不再参与结算、出生与 HUD，
  但会话继续；`settleNegotiation` 只在「所有人都回应过」且「剩余非空队 < 2」时才作废整场。
- **观战者看不到队伍关系。** 观战列表只显示谁在场，不显示谁和谁一边；想知道分组只能看 HUD。
- **没有回合计时器。** `a.roundLimit` / `a.timeoutVerdict` 未接，两边都不动手时一局不会自己结束。
- **天气是维度级属性**：多场决斗同时进行时，最后一局设置的天气对所有人生效。
