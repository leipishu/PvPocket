# PvPocket S8 实现进度：多局赛制 · 顶部计分板 · 配装全员同意

> 对应需求：
> 「那肯定是都要全员同意才能开始对局的啦。然后添加多局制度。添加在对战维度中顶部显示计分板，
> 左右各显示各个玩家的头像（多人也要写）最高支持到15局8胜」
>
> 三句话拆成三件事：
> 1. 配装从「我设好就发出去」变成「所有人对同一份配装点了头才能开局」；
> 2. 一场决斗从「一局定胜负」变成「1–15 局的系列赛，先到一半 +1 者胜」；
> 3. 对战维度顶部出现一条自绘计分板，左右各列出该侧所有玩家的头像与名字。

---

## 一、交付表

| 需求原文 | 落地位置 | 状态 |
|---|---|---|
| 全员同意才能开始对局 | `Participant.agreedLoadoutDigest` + `allLoadoutsAgreed` + `startAccepted` 二次校验 | ✅ 已实测 |
| 添加多局制度 | `a.series` 8 档；`bestOf = 2×档位 + 1` | ✅ 已实测 |
| 最高 15 局 8 胜 | `a.series` 第 8 档 = 15 局，`winsNeeded = 15/2+1 = 8` | ✅ 已实测（跑到第 4 局被掉线切断） |
| 局间重置物品 | 每局 `LoadoutBuilder.grant` 重新发放（内部先清空背包） | ✅ 已实测 |
| 局间重置状态 | `resetRoundState`：血/饿/饱和/效果/火/速度/坠落距离 | ✅ 已实测 |
| 局间保留场地租约 | `beginIntermission` 不 `release`，只 `revertRegion` | ✅ 已实测 |
| 顶部计分板 | `MatchHud`（`HudRenderCallback`） | ✅ 已截图确认 |
| 左右各显示头像 | `PlayerSkinDrawer.draw` + 实时皮肤，取不到则 `DefaultSkinHelper` | ✅ 已截图确认 |
| 多人也要写 | 侧别由 `Participant.teamIndex() % 2` 分组，每侧渲染 N 行 | ⚠️ 代码就位，**尚无多人对局可测** |

---

## 二、多局状态机

一局的生命周期从原来的四段变成七段，多出来的是「局间」：

```
                    ┌──────────────────────────────────────────────┐
                    │                                              │
  DRAFT ──propose──▶ NEGOTIATING ──accept──▶ FROZEN ──▶ ROUND_READY │
    ▲                     │                                  │      │
    │                     │ 有人改配装                        │      │
    └──── LOADOUT_REOPENED┘                                  │      │
                                                             ▼      │
                                                       ROUND_ACTIVE │
                                                             │      │
                                        ┌────────────────────┤      │
                                        │                    │      │
                              比分未到 & 人都在        比分到 / 有人离线
                                        │                    │      │
                                        ▼                    ▼      │
                                  ROUND_READY          ROUND_SETTLED│
                                  （局间，3 秒）              │      │
                                        │                    ▼      │
                                        └──▶ startRound   RESTORING │
                                                             │      │
                                                             ▼      │
                                                         ARCHIVED ◀─┘
```

每个转移在日志里都有名字，`scripts/series-test.py` 就是靠这些名字判断流程有没有走对。

### 2.1 `settle()` 分叉

`settle()` 原来只有一条路：恢复、复原、释放、归档。现在它先问一句「这个系列赛结束了吗」：

```java
String seriesWinner = seriesWinner(session);
if (seriesWinner == null && 有人离线) {
    journal.append("SERIES_CUT", "reason", "participant_offline");
    seriesWinner = leaderOf(session);
}
if (seriesWinner == null) {
    beginIntermission(session, region, arenaWorld);   // 还有下一局
    return;
}
finishSeries(session, seriesWinner);                  // 系列赛结束
```

`seriesWinner` 的判据是「有人先到 `winsNeeded`」，而不是「打满了 `bestOf` 局」：奇数局 + 先到一半 +1，
按构造不可能打满（9 局 5 胜最多打到第 9 局，那时必定已经有人 5 胜）。`round >= bestOf` 那一支是
给手改存档兜底的，正常永远走不到。

### 2.2 局间：为什么保留租约

`beginIntermission` 只做三件事：清掉落物、把地形复原、把 `roundReadyAt` 设成 `now + a.intermission`。
**不释放租约**，因为释放之后下一局要重新 `allocate`，而 `allocate` 是「找一个空网格」，不保证还是原来那块地——
玩家会在同一场决斗里换房间，这显然是错的。

下一局由 `tick()` 里的 `startDueRounds()` 拉起，而不是定时器：这个类里所有时序都是 tick 驱动的，
多一个调度器就多一个要在重启之后重新推理的东西。开局抛异常（有人掉线）走 `abort(ROUND_START_FAILED)`，
不让异常穿出 tick 循环。

### 2.3 每局重置：重新发配装，而不是重新套快照

这是这一阶段最容易做错的一个决定。

「每一小局结束物品恢复到最初状态」有两个看起来都对的实现：

| 方案 | 做法 | 为什么不行 / 行 |
|---|---|---|
| A | 每局结束重新应用 `PlayerSnapshot` | **不行**。快照是玩家生存背包的**唯一副本**，局间应用等于把真实物品提前还给玩家 |
| B | 每局开始重新 `grant` 配装 | **采用**。`grant()` 先清空背包再写入，上一局剩什么都不会活到下一局 |

B 只解决了物品。血量、饥饿、药水效果、着火、动量都不在物品里，所以另有一个 `resetRoundState`：

```java
player.clearStatusEffects();
player.extinguish();
player.setFireTicks(0);
player.setVelocity(0, 0, 0);
player.fallDistance = 0;
player.getHungerManager().setFoodLevel(20);
player.getHungerManager().setSaturationLevel(5.0F);
player.getHungerManager().setExhaustion(0.0F);
player.setHealth(Math.min(maxHealth, player.getMaxHealth()));
```

没有它，一个在第 1 局结束时只剩两颗心、身上还挂着中毒的玩家会带着这个状态进第 2 局。

---

## 三、赛制换算

`a.series` 从 `fixed("BO1")` 改成了 8 档枚举，**存的是档位序号而不是局数**：

| 档位 | 显示 | `bestOf` | `winsNeeded` |
|---|---|---|---|
| 0 | 1 局 | 1 | 1 |
| 1 | 3 局 2 胜 | 3 | 2 |
| 2 | 5 局 3 胜 | 5 | 3 |
| 3 | 7 局 4 胜 | 7 | 4 |
| 4 | 9 局 5 胜 | 9 | 5 |
| 5 | 11 局 6 胜 | 11 | 6 |
| 6 | 13 局 7 胜 | 13 | 7 |
| 7 | 15 局 8 胜 | 15 | 8 |

```java
session.bestOf = 2 * Math.max(0, Math.min(7, rules.get("a.series"))) + 1;
private static int winsNeeded(DuelSession session) { return session.bestOf / 2 + 1; }
```

存档位不存局数，是因为局数只能是奇数、而档位是连续的 0..7：一个连续的下标可以直接喂给
`PvCycle` 的 `◀ ▶`，越界只可能来自手改存档，`Math.min/max` 兜住就够了。

---

## 四、顶部计分板

### 4.1 一条居中的条，不是三个角标

第一版把两侧头像钉在屏幕左右边缘。截图一看就废了：**Minecraft 的右上角是原版画状态效果图标的地方**，
一个钉在右上角的计分板会在玩家中毒、减速、着火时正好被图标压住——也就是最需要看比分的时候看不见。

改成一条居中的整体条：

```
┌───────────────────────────────────────────────┐
│ [头] Dev1 │ 第 2 / 3 局 │ Dev2 [头]           │
│           │    1 : 1    │                      │
└───────────────────────────────────────────────┘
```

三块之间用 1px 竖线分隔，整条用 `PvDraw.roundPanel` 画（和所有自绘面板同一套皮肤）。

### 4.2 侧别从哪来

`buildHud` 不假设「两个人」，只按 `Participant.teamIndex() % 2` 分左右，每侧拼成一个字符串：

```java
row.append(p.name).append('|').append(p.uuid);   // 多个用 , 连接
```

`|` 和 `,` 都不可能出现在玩家名或 uuid 里，所以这个扁平化是安全的。客户端 `parse()` 把每行拆开，
坏行直接丢掉而不是抛——计分板是装饰，为一个画不出来的头像把客户端崩掉是不可接受的。

渲染侧因此完全没有「几打几」的概念，只有「左边一串行、右边一串行」。2v2 时每侧两行，
条高自动从 28px 长到 48px。

### 4.3 头像

```java
PlayerSkinDrawer.draw(ctx, skinOf(client, uuid), headX, y, 16);
```

`skinOf` 先在本客户端世界的玩家列表里找 uuid，找不到就用 `DefaultSkinHelper.getSkinTextures(uuid)`。
这个降级是必需的：对手可能人在竞技场而本客户端还在主世界（局间、观战），实体根本不在这个世界里。
画个默认头比缺一行好得多。

### 4.4 几个细节

- **F1**：`client.options.hudHidden` 为真时直接不画。一个关不掉的计分板，是玩家想拍张干净截图时第一个想删掉的东西。
- **开着界面时不画**：`client.currentScreen != null` 直接返回。已用游戏菜单截图确认。
- **赛点高亮**：`score + 1 >= winsNeeded` 的一侧名字染成绿色，「再赢一局就结束」不用读数字。
- **宽度兜底**：先按带名字算宽度，放不下才退化成只画头像。缩小字号会让比分在 2× GUI 缩放不可读，
  而砍掉整侧比砍掉名字更糟。

### 4.5 传输

`MatchHudPayload` 有 8 个字段，而 `PacketCodec.tuple` 只支持到 6 个，所以编解码器是手写的：

```java
public static final PacketCodec<RegistryByteBuf, MatchHudPayload> CODEC = PacketCodec.of(
    (value, buf) -> { buf.writeBoolean(...); buf.writeVarInt(...); ... },
    buf -> new MatchHudPayload(buf.readBoolean(), buf.readVarInt(), ...));
```

推送时机：开局、每局结算、系列赛结束、玩家加入（`pushHudTo`，否则中途重连的客户端会对着空场地等到下一局）。

---

## 五、配装全员同意

### 5.1 为什么不是「各自同意各自的」

配装是**一份共同约定**，不是两份独立选择：对面带了什么，决定你该带什么。所以同意必须针对
「当前这一整套」而不是「我自己的那一份」。

做法是给每个参与者存一个**组合指纹**：

```java
private static String combinedLoadoutDigest(DuelSession session) {
    StringBuilder sb = new StringBuilder();
    session.participants.stream()
            .sorted(Comparator.comparing(p -> p.uuid))      // 顺序无关
            .forEach(p -> sb.append(p.uuid).append(':')
                    .append(p.loadoutDigest == null ? "-" : p.loadoutDigest).append(';'));
    return sha256(sb).substring(0, 12);
}
```

按 uuid 排序是必须的：`participants` 的顺序是「谁先被加进列表」，不是事实。两个人用同样的配装，
不管谁是发起人，算出来的指纹必须一样，否则两边的面板会显示不同的数字。

### 5.2 三个关口

| 关口 | 检查 | 失败提示 |
|---|---|---|
| `ACTION_PROPOSE` | 记录发起人的同意 | — |
| `ACTION_ACCEPT` | 记录接受方的同意，然后 `allLoadoutsAgreed` | 「还有 N 人没同意当前配装，暂不能开始。」 |
| `startAccepted` | 再查一次 | 「配装还没有全员同意，暂不能开始。」 |

`startAccepted` 里的第二次检查不是多余的：`/pvpocket accept` 走的是另一条分支，而
「所有人都同意了」不是调用方的性质，是会话的性质。

### 5.3 改了配装就作废

协商中任何人改了配装，`noteLoadoutChanged` 把会话打回 `DRAFT`，双方面板重新弹出：
改的人拿到发起者视角，其他人拿到审阅者视角，并且收到「X 改了配装，规则需要重新征询。」

第一版这里没有比较指纹，于是「打开编辑器、什么都没改、点保存」也会让两个面板闪一下、
同意数掉到 0/2。这会把唯一一个真正重要的信号训练成噪音。现在比较改前改后的组合指纹，没变就直接返回。

### 5.4 面板上看得见

规则面板顶部右侧：`配装 Dev1 7 · Dev2 7 · 同意 0/2`。

显示**所有人的**物品数而不只是自己的，因为「我在同意什么」必须包含对面带了什么。
同意计数是把这个沉默的否决变成可见的东西——改了配装的人能亲眼看到它掉回 0/2。

---

## 六、这一阶段修掉的四个真 bug

### 6.1 干净关服会留下恢复欠账

旧代码 `shutdown()` 调 `settle(session, "SERVER_STOP", null)`。加了多局之后，`settle` 在系列赛没结束时
会进 `beginIntermission`——也就是**关服时会进局间**，然后被下一次启动当成 `ROUND_READY` 异常会话作废，
两个参与者 `restored=false`，比分丢失。日志：

```
ROUND_ENDED  round=1 reason=SERVER_STOP
STAGE_CHANGED ROUND_SETTLED → ROUND_READY     ← 关服期间进了局间
ANOMALY      unfinished session reconciled stage=ROUND_READY
STAGE_CHANGED ROUND_READY → VOIDED
```

修法是把 `finishSeries` 从 `settle` 里抽出来，关服直接调它：没有下一局要准备，快照必须在玩家还在线时应用完，
而不是留给下次启动去发现。

### 6.2 开局撞上「血量为 0」就把整个系列赛作废

`startRound` 里加了 `if (!player.isAlive()) throw`。结果第一次实测就炸：

```
IllegalStateException: participant dead at round start: Dev1
→ SESSION_ABORTED reason=START_FAILED
```

玩家在死亡画面被关服时，玩家数据存的是 0 血；下次登录不一定会被重新生成，于是他在服务端「是死的」。
这不是玩家的错，是模组自己造成的状态，正确反应是**复活他**而不是作废一场决斗：

```java
journal.append("ROUND_START_REVIVE", "player", p.name, "health", player.getHealth(), "removed", player.isRemoved());
player.setHealth(player.getMaxHealth());
```

同时把血量值记进日志，这样真的是别的原因时还查得出来。

### 6.3 平局被判定成「先手获胜」

掉线切断的系列赛走 `leaderOf`，第一版在平分时返回列表第一个：

```
SERIES_ENDED winner=Dev1 score=2:2
→ 聊天栏「决斗结束：Dev1 获胜（2:2）。」
```

2:2 没有胜者。现在平分返回 null，走「无人获胜（2:2）」那一支。

### 6.4 顶部提示被配装摘要挤掉半句

`一场决斗由几局组成、怎么…` —— 组提示被 `PvDraw.fit` 截到 12 个字，因为它要给右边的配装摘要让位。
两害相权：配装摘要是唯一显示同意计数的地方，不能砍；但砍到 12 个字的提示已经不叫句子了。
所以现在先算提示还剩多少宽，低于 190px 就把摘要退化成 `配装 · 同意 0/2`，把宽度还给提示。

---

## 七、实测记录

### 7.1 三局两胜，全程无人工干预（`scripts/series-test.py`）

认输决定每一局（死亡会把人卡在死亡画面等人点重生，无头测试点不了；认输走的是同一个 `settle()`）。

```
ROUND_STARTED   round=1
ROUND_ENDED     round=1 reason=SURRENDER
ARENA_CLEARED   removed=0
ARENA_REVERTED  region=arena-0 blocks=0 tracked=0 round=1
STAGE_CHANGED   ROUND_SETTLED → ROUND_READY          ← 局间，租约未释放
LOADOUT_APPLIED player=Dev1 ... round=2               ← 3.05 秒后（a.intermission=3）
ROUND_STARTED   round=2
ROUND_DECIDED   reason=DEATH
SERIES_ENDED    winner=... score=0:2 rounds=2 bestOf=3 ← 先到 2 即结束，没打第 3 局
RESTORE_COMPLETED Dev1 verified=true / Dev2 verified=true
ARENA_REVERTED  region=arena-0 blocks=0 tracked=0
ARENA_RELEASED  region=arena-0
STAGE_CHANGED   RESTORING → ARCHIVED
```

两处值得指出：
- **局间只 revert 不 release**：日志里第 1 局和第 2 局之间没有 `ARENA_LEASED`。
- **快照只在系列赛结束时应用一次**：`RESTORE_COMPLETED` 出现在最后，局间一次都没有——这正是 2.3 的设计。

### 7.2 三局两胜，真人打完

```
R1  Dev2 死亡 → Dev1 1:0
R2  Dev1 死亡 → 1:1
R3  Dev2 死亡 → SERIES_ENDED winner=Dev2 score=1:2
```

三局都有 `LOADOUT_APPLIED ... round=N`，说明每局都重新发了配装。

### 7.3 计分板

截图（1280×720，GUI 缩放 2）：深色圆角条居中，左 `[头] Dev1`，中 `第 2 / 3 局` + `1 : 1`，右 `Dev2 [头]`，
两侧名字为绿色（1:1 且需 2 胜，双方都在赛点）。

打开游戏菜单后截图：计分板消失。F1 同理。

### 7.4 9 局 5 胜，被掉线切断

玩家在面板上把赛制调到 9 局 5 胜，打了 4 局后一方退出：

```
R1 Dev2 死亡 → 0:1     R2 Dev2 认输 → 1:1
R3 Dev1 死亡 → 1:2     R4 DISCONNECT → SERIES_CUT → 2:2
```

第 4 局的掉线判定同时做两件事：把这一局判给留下的一方，然后切断系列赛。比分 2:2 时按 6.3 修好之后是「无人获胜」。

### 7.5 局中关服（验证 6.1 的修复）

第 1 局进行中直接 `stop`：

```
ROUND_ENDED     round=1 reason=SERVER_STOP
STAGE_CHANGED   ROUND_ACTIVE → ROUND_SETTLED
ARENA_CLEARED   removed=0
SERIES_ENDED    score=0:0 rounds=1 bestOf=3        ← 没有 winner 字段，平局无胜者
STAGE_CHANGED   ROUND_SETTLED → RESTORING
RESTORE_COMPLETED Dev1 verified=true / Dev2 verified=true
ARENA_REVERTED  region=arena-0 blocks=0 tracked=0
ARENA_RELEASED  region=arena-0
STAGE_CHANGED   RESTORING → ARCHIVED
```

关键是没有 `ROUND_READY`：关服不走局间，快照在玩家还在线时应用完。重启后服务端日志干净——
既没有 `Outstanding restore`，也没有 `Reconciling`，说明这次关服没有留下任何恢复欠账。


---

## 八、仍然没做的（诚实清单）

按影响排序：

1. **`a.roundLimit` + `a.timeoutVerdict` 依然没有执行**。规则里写着「单局时长上限 15 分钟 / 超时判定 血量高者胜」，
   但没有计时器。多局制度上线之后这是最显眼的洞：9 局 5 胜可以无限拖。
2. **`f.allowPlace=0` 没有执行**。日志里有硬证据：一局里 `ARENA_REVERTED blocks=14 tracked=14`，
   而该局的规则是禁止放置。地形复原是对的，只是「禁止」这一半没生效。
3. **37 条规则里还有 30 多条只记录不执行**：`c.naturalRegen` / `c.hunger` / `c.fallDamage` /
   `g.showOpponentHealth` / `d.allowConsumables` / `d.allowPickup` / `d.allowCrafting` / `d.loadoutScope`。
4. **多人对局尚不存在**。`requestDuel` 固定加两个参与者，所以计分板的「每侧 N 行」虽然写了，
   但没有任何一场 2v2 可以证明它对。规则里的 `b.teamCount` / `b.teamSize` / `b.maxPlayers` 同理。
5. **配装变更作废协商这条路径没有实测**。需要真人打开编辑器改一项再保存；无头测试做不到
   （改配装只能从 GUI 提交）。代码路径是 `noteLoadoutChanged`，日志事件是 `LOADOUT_REOPENED`。
6. **计分板只在 GUI 缩放 2 下看过**。1×/3×/4× 未验证；宽度兜底逻辑写了但没触发过。
7. **仍然没有自定义 `pvpocket:loadout_spec` 组件**：击退只能 0.5 一档、盾牌减伤比例不可配、
   钓鱼竿「只在命中时造成伤害」的语义无法表达。
8. **崩溃残留**：`SURFACE_Y + sizeY + 4` 以上的方块不会被打扫。
9. **`a.countdown` 只实现了宽限那一半**，没有锁移动。
10. **地图的边界矩形和战斗区矩形还是同一个**。蓟桥的 102×99 把两端基地也算进去了，
    所以边界光幕画在基地外沿（见 §十三 与
    `docs/PvPocket-场地模板-调研与工具.md` §八）。

---

## 九、给测试者的检查单

按顺序做一遍，每一步都能在聊天栏或日志里看到对应事件：

1. `/pvpocket duel Dev2` → 面板顶部应显示 `配装 Dev1 7 · Dev2 7 · 同意 0/2`，提示那句不再被截断。
2. 点「赛制」右边的 ▶ 到 `15 局 8 胜` → 点「发送征询」→ 对方面板应显示同一套规则和 `同意 1/2`。
3. **在对方还没接受时**，点「编辑配装」改一项（比如把剑换成斧）并保存 →
   两边面板都应重新弹出，你的面板是发起者视角，对方是审阅者视角，聊天栏提示「X 改了配装，规则需要重新征询。」
   日志出现 `LOADOUT_REOPENED`。这是第 8 节第 5 条要验证的东西。
4. 重新「发送征询」→ 对方「接受」→ 开局。顶部应出现计分板：`第 1 / 15 局  0 : 0`。
5. 让一方死一次 → 3 秒局间 → 第 2 局自动开始，背包回到初始配装，血量满。
6. 打到一方 8 胜 → 聊天栏「决斗结束：X 获胜」，双方生存状态恢复，计分板消失。
7. 想验「关服不丢局」：在第 N 局进行中直接 `stop`，重启后日志应有
   `ROUND_ENDED reason=SERVER_STOP` → `SERIES_ENDED` → `RESTORE_COMPLETED` ×2 → `ARCHIVED`，
   并且**没有** `ANOMALY` / `VOIDED`。

---

## 十、追加修改（21:00）

### 10.1 认输 = 输掉整场

原来 `surrender` 走 `settle(..., "SURRENDER", opponent)`，只判掉**当前这一局**。现在改成 `forfeit`：

```
SERIES_FORFEITED  player=Dev1 winner=Dev2 score=0:1 round=2 stage=ROUND_ACTIVE
ROUND_ENDED       round=2 reason=SURRENDER        ← 没有 winner 字段
SERIES_ENDED      winner=Dev2 score=0:1 rounds=2 bestOf=3
```

`ROUND_ENDED` 刻意不带 winner：这一局**没人赢**，是整场结束了。比分保持原样，对手直接成为系列赛胜者。

`ROUND_READY`（局间）也接受认输——想退出的人不该被迫再打一局才能说出口。

### 10.2 竞技场维度不再生成结构

`arena.json` 的 flat 设置加了 `"structure_overrides": []`。空列表不是「不覆盖」，是「结构集为空」，
所以平原生物群系默认的村庄、前哨站、废弃传送门全部不生成。

对照实测（同一条指令）：

```
主世界   locate structure minecraft:village_plains  → The nearest ... is at [0, ~, -800] (752 blocks away)
竞技场   execute in pvpocket:arena positioned 0 0 0 run locate structure minecraft:village_plains
                                                    → Could not find a structure ... nearby
```

刷怪仍然是三道门（`SpawnHelperMixin` 拦 `spawn` 与 `populateEntities`，加 tick 里的 `sweepMobs` 兜底），本次未改动。

### 10.3 时间与天气可自定义

E 组新增两条：

| 规则 | 选项 | 效果 |
|---|---|---|
| `e.timeOfDay` | 自然 / 清晨 / 正午 / 黄昏 / 午夜 | 选固定值时关闭 `doDaylightCycle` 并设置时间；选「自然」时重新打开 |
| `e.weather` | 晴朗 / 降雨 / 雷暴 | `ServerWorld.setWeather`，每局开始时应用 |

两个都是**维度级**属性，不是分区级：两场同时进行时以最后一局为准。规则提示里直接写了这句，
而不是假装能各下各的雨。时间同理，所以「自然」必须把 `doDaylightCycle` 打开——不打开的话下一场会继承一个停摆的时钟。

### 10.4 边界：墙 + 5 秒倒计时 / 拉回最近点

> ⚠️ **本节描述的玻璃墙与 action bar 播报已被 §11.2 推翻**，保留原文只为记录当时的判断。
> 现在的实现是纯逻辑边界 + 屏幕中央的大号数字，见 §11.2。

三件事：

1. **可见的边界墙**：每局开始时在分区最外圈建 3 格高的玻璃墙（48×48 场地 = 564 个方块），
   走正常方块写入路径，所以它进分区的偏差集，和玩家的建筑一起被同一次 revert 拆掉。
   3 格高而不是高到翻不过去——「离开」应该是主动行为（搭方块、末影珍珠、拆一段），
   否则倒计时就是死代码。
2. **`e.boundary = 拉回`**：`ArenaManager.pullInside` 把玩家放到**最近的边境内**，
   而不是传送回出生点。传送回出生点等于每次擦边都白送对手一次位置重置。
   夹取时离墙圈内缩 1 格，否则玩家会被塞进墙里窒息。
3. **`e.boundary = 倒计时判负`**：离开边界开始 5 秒倒计时，每秒用 action bar 播报剩余秒数
   （用 action bar 不用聊天栏，否则每秒一条会把开局提示顶掉）；5 秒内回到场内则取消并提示「已回到场地内」。
   倒计时结束 → `BOUNDARY_FORFEIT` → 本局判负。

开局宽限期（`max(10s, a.countdown)`）仍然优先：客户端还在下载新维度时上报的旧坐标一律按「拉回」处理，
不进入倒计时。

#### 实测

```
ARENA_WALL_BUILT   region=arena-0 blocks=564
BOUNDARY_EXIT      player=Dev1 pos=55.83,3.00,77.43 countdownMs=5000
BOUNDARY_RETURNED  player=Dev1                       ← 3.7 秒后自己走回来，倒计时取消
BOUNDARY_EXIT      player=Dev1 pos=110.50,3.00,80.50
BOUNDARY_FORFEIT   player=Dev1 elapsedMs=5007        ← 正好 5 秒
ROUND_ENDED        round=1 reason=OUT_OF_BOUNDS winner=Dev2
ARENA_REVERTED     blocks=564 tracked=564            ← 墙跟着一起拆干净
```

拉回模式（宽限期内 `/tp` 到 x=130）：

```
BOUNDARY_PUSHED_BACK  player=Dev1 grace=true
Dev1 实际坐标 → 102.5, 3.0, 80.5    ← maxX() - 0.5，最近的边境内一点
```

### 10.5 没做的

- **维度级的边界墙没有做**。Minecraft 的世界边界是**全服单例**（下界边界跟着主世界按 1/8 缩放就是证据），
  给竞技场维度单独设一条会把主世界一起缩掉，所以边界做在「场地分区」这一层。
  玩家体验上一样（每场决斗四周都有墙、出去有倒计时），只是墙围着的是自己的场地而不是整个维度。
- 时间/天气是维度级属性，多场同时进行时互相覆盖，已在规则提示里注明。
- `pullInside` 只在宽限期路径上实测过，`e.boundary=拉回` 的正式路径走的是同一个方法。

---

## 十一、追加修改（第二轮反馈）

> 对应需求：
> 「1，好像还是能生成村庄等结构！2，边界要能走出去，提示数字在屏幕中央，而不是现在的可以挖掘的玻璃！
> 这个是特殊的！3，添加回合准备时间，倒计时在聊天区，可以自定义准备时间」

三条里有两条是**上一轮做错了**，一条是新需求。写清楚错在哪比写清楚改了什么更有用。

### 11.1 结构还是会生成：改配置没用，因为区块已经在硬盘上了

`structure_overrides: []` 是对的，但它只影响**尚未生成**的区块。上一轮改完配置后我跑 `locate structure`
看到「Could not find a structure」就收工了——而那条指令查的是**新生成**的区块，老的村庄一直躺在
`run/world/dimensions/pvpocket/arena/` 里（12 MB，`r.-1.-1.mca` … `r.0.0.mca`）。玩家一走近就加载出来。

修法不是改代码，是**删掉旧区块**：停服 → `rm -rf run/world/dimensions/pvpocket/arena` → 重启 → 复查。

```
execute in pvpocket:arena positioned 0 0 0 run locate structure minecraft:village_plains
  → Could not find a structure of type "minecraft:village_plains" nearby
execute in pvpocket:arena positioned 0 0 0 run locate structure minecraft:pillager_outpost
  → Could not find a structure of type "minecraft:pillager_outpost" nearby
```

**教训**：维度生成参数的修改对已有存档是**不回填**的。以后每次动 `dimension/`、`dimension_type/`
或 flat 的 layers，都得顺手清掉该维度的区块，否则测出来的永远是旧世界。

### 11.2 边界：撤掉玻璃墙，改成纯逻辑 + 屏幕中央的大号数字

上一轮的墙错了两次：

1. **能挖**。玻璃是可破坏方块，玩家带把镐就能在墙上开洞——「特殊的边界」不该是一个可以被正常游戏行为
   拆掉的实体。做成基岩呢？又会进分区的偏差集，`ARENA_REVERTED` 每次都要多算 564 个方块，而且
   48×48 的场地四周戳一圈基岩，视觉上很难看。
2. **数字在 action bar**。action bar 在物品栏正上方，玩家的视线在水平线上——刚走出场地的人根本不会
   往那儿看。而「你还有 4 秒」是这一瞬间唯一的有效信息。

所以现在：

- **`ArenaManager.buildWall` 与 `WALL_HEIGHT` 整个删掉**。边界只存在于 `ArenaRegion.isInside` 里，
  玩家可以自由走出去，也可以走回来。走出去时脚下什么都没有，只是开始计时。
- **倒计时改成 title**：`TitleFadeS2CPacket(0, 22, 4)` + `TitleS2CPacket`，内容是红色加粗的单个数字，
  画在屏幕正中。fadeIn=0 是为了让每个数字立刻替换上一个，而不是排队；stay=22 tick 略长于 1 秒，
  保证每秒至少有一个数字在屏上。
- **回到场内**时补一个 `ClearTitleS2CPacket(false)`，否则玩家自救成功后屏幕上会挂着一个「1」不走。
  同时发一条绿色「已回到场地内。」——静默取消的倒计时会让人不知道自己还算不算在风险里。
- 判负那一刻同样清 title。

改动后 `ARENA_REVERTED blocks=0`：场地里本来就什么都没有，没有东西需要还原。这是**正确**的读数，
不是 revert 失效。

### 11.3 回合准备时间（`a.countdown`）

`a.countdown` 原本是「开局倒计时」，语义含糊。现在明确为**回合准备时间**：

| 项 | 值 |
|---|---|
| 范围 | 0–30 秒，步进 1，默认 5 |
| 播报 | 聊天栏逐秒「回合准备：N 秒」，最后一秒后接「第 X / Y 局开始（…）」 |
| 期间 | 双方 `setInvulnerable(true)`，可以走动、看场地、翻背包，但打不掉血也挨不掉血 |
| 结束 | `activateRound` 撤销无敌 → `ROUND_STARTED` → `ROUND_ACTIVE` → 广播 HUD |

实现上把 `startRound` 拆成两半：

```
startRound      传送 / 游戏模式 / 血量 / resetRoundState / CombatState.setMode / grant 配装
                → 若 prepare > 0：setInvulnerable(true)、ROUND_PREPARING、登记 Prepare 截止时间、return
activateRound   撤无敌 → ROUND_STARTED → roundStartedAt → ROUND_ACTIVE → 广播 HUD → 开局聊天行
```

`tickPrepare()` 在 `tick()` 里紧跟 `startDueRounds()`，每秒比对一次 `announced` 字段决定是否播报，
到点调 `activateRound`。**没有用调度器**：tick 里比对时间戳和整个状态机其余部分用的是同一套时钟，
多一个线程只会多一种崩溃方式。

**为什么不是「锁住移动」**：锁移动要在 `ServerPlayNetworkHandler` 上拦位置包，拦漏一个就会被判定
「作弊」踢人，拦多了玩家会觉得卡。无敌是服务端权威的、零延迟的，而且准备时间的目的是「别在传送的
半秒里被砍」，不是「不许动」。

顺带修掉两个在这条链路上暴露的问题：

- `startRound` 遇到血量 0 的参与者（上一局死在死亡画面、或者服务器停机时正在死亡画面）会抛
  `IllegalStateException` 并**中断整场**。改成 `ROUND_START_REVIVE` 记一笔原始血量后回满血。
- `ROUND_READY` 时有人掉线 → `startRound` 抛「participant offline」→ `abort` → `VOIDED`，
  比分和两份存档一起被拖住。改成**回滚局数**后 `finishSeries(leaderOf())` 切断整场，
  没打过的这一局不再计入比分。

### 11.4 实测

一局 3 局 2 胜的完整流程，全程 RCON 驱动，只看 journal：

```
ROUND_PREPARING   round=1 seconds=5
ROUND_STARTED     round=1                         ← 5037 ms 后
BOUNDARY_PUSHED_BACK player=Dev1 grace=true       ← 开局 10 秒内越界 = 拉回，不判负
BOUNDARY_EXIT     player=Dev1 pos=130.50,4.00,80.50 countdownMs=5000
BOUNDARY_FORFEIT  player=Dev1 elapsedMs=5010      ← 正好 5 秒
ROUND_ENDED       round=1 reason=OUT_OF_BOUNDS winner=Dev2
ARENA_REVERTED    blocks=0 tracked=0              ← 没有墙要拆
ROUND_PREPARING   round=2 seconds=5
ROUND_STARTED     round=2
BOUNDARY_EXIT     → BOUNDARY_FORFEIT elapsedMs=5010 → ROUND_ENDED round=2 OUT_OF_BOUNDS
SERIES_ENDED      winner=Dev2 score=0:2 rounds=2 bestOf=3
RESTORE_COMPLETED player=Dev1 verified=true
RESTORE_COMPLETED player=Dev2 verified=true
ARENA_RELEASED / ARCHIVED
```

聊天栏倒计时在客户端日志里可以直接读到：

```
[CHAT] 回合准备：5 秒 … 回合准备：1 秒
[CHAT] 第 2 / 3 局开始（现代战斗，3 局 2 胜 · 现代 · 2 队 × 1 人 · 48 格×48 格 · 生命 20）。先赢 2 局者胜。
```

### 11.5 没做的 / 没验到的

- **屏幕中央那个数字没有截图**。两个 dev 客户端当时是最小化状态（`IsIconic=True`），
  而 `ShowWindow(SW_RESTORE)` 在这个 GLFW 窗口上会把客户端打成原生崩溃（见 `scripts/client-act.ps1`
  里那条注释）。逻辑侧的证据是全的（title 包已发出、无异常、返回时清 title），**视觉确认交给人工**。
- 准备期间只挡伤害，不挡移动、不挡放方块。如果准备期有人往对手脚下垒方块，那是 §八 里
  `f.allowPlace` 未落地的同一个洞。
- 倒计时数字的字号是原版 title 的默认尺寸（GUI 缩放 2 下约 1/4 屏高）。没有做自绘，因为 title
  会跟随玩家的 GUI 缩放，自绘反而不一致。

---

## 十二、追加修改（第三轮反馈）

> 对应需求：
> 「现在不受配置界面修改的影响啊！无论如何都是弹回走不出去！然后边界要有明显标识，
> 不然都不知道边界在哪里，靠近再渲染吧，远距离不需要。」

第一条是**真 bug**，而且是我上一轮亲手加的。第二条是新需求。

### 12.1 「不受配置影响」的根因：一个看不见的 10 秒窗口

`inOpeningGrace` 原本长这样：

```java
long countdown = rules.get("a.countdown") * 1000L;
return now - roundStartedAt < Math.max(BOUNDARY_GRACE_FLOOR_MS, countdown);   // 下限 10 秒
```

这十秒是给「客户端还在下载一个从没去过的维度、位置包还是旧坐标」兜底的。问题在于它把
**整条边界规则**也一起兜住了：这十秒里无论 `e.boundary` 选的是拉回还是判负，走的都是同一条
`pullInside` 分支。于是玩家看到的是——

```
BOUNDARY_PUSHED_BACK  player=Dev2 grace=true     ← 开局 1.9 秒
BOUNDARY_PUSHED_BACK  player=Dev2 grace=true     ← 2.4 秒
BOUNDARY_PUSHED_BACK  player=Dev2 grace=true     ← 2.9 秒
...连续 9 条，每 0.5 秒一次
```

这是你自己那次对局的 journal，时间轴摊开看的：开局 1.9 秒走到边界，然后被**连续弹回 9 次**，
5.6 秒后放弃退出。玩家的感受就是「怎么改都没用，反正出不去」。

而且这不只是「规则被忽略」——被弹回 9 次本身就是一个体验灾难：屏幕每半秒跳一次，没有提示，
没有原因，看起来就像卡了。

**改法不是把 10 秒调短，是把判据从「时间」换成「事实」**：

```java
private boolean awaitingFirstEntry(DuelSession session, String key) {
    if (settledIn.contains(key)) return false;          // 已经在场地里站过 → 不再保护
    long startedAt = roundStartedAt.getOrDefault(session.id, 0L);
    return startedAt != 0L && now - startedAt < SETTLE_DEADLINE_MS;   // 30 秒逃生阀
}
```

真正需要保护的只有一件事：**这个玩家在这一局里有没有被看到站在场地上过**。出生点在两端各内缩
4 格，传送后第一个 tick 就会被判定为「在场地内」，所以保护在玩家能动之前就花掉了。之后他走出去，
就是他的选择，规则立刻生效。

`SETTLE_DEADLINE_MS = 30s` 只是逃生阀：一个 30 秒都没被看到在场的玩家不是在加载，是在别的地方，
再弹下去就是把人锁死。

顺带两件事：

- **弹回提示**：`notifyPushBack` 走 action bar，1.5 秒最多一次（「已到场地边界，继续往外会被拉回。」）。
  原来每 tick 一条 journal 也被同一个闸门挡掉了——九条一模一样的 `BOUNDARY_PUSHED_BACK`
  不是证据，是噪音。
- **规则写进 journal**：`ROUND_STARTED` 现在带 `boundary=push_back|countdown`。哈希 digest 没法反读出
  「到底跑的是哪条规则」，而「配置没生效」这类抱怨事后是无法证伪的。

**实测**（开局 0.5 秒就往外走，落在旧代码的 10 秒窗口正中间）：

```
ROUND_STARTED       round=2 boundary=countdown
BOUNDARY_EXIT       player=Dev2 pos=104.19,3.00,86.03 countdownMs=5000    ← 开局 0.5 秒
BOUNDARY_RETURNED   player=Dev2                                            ← 走回来，倒计时取消
BOUNDARY_EXIT       player=Dev2 pos=104.10,3.00,93.44
BOUNDARY_RETURNED   player=Dev2
```

没有一条 `BOUNDARY_PUSHED_BACK`。

### 12.2 边界标识：客户端画的一圈光幕

不做方块环的两个理由上一轮已经说过（能挖、会进偏差集），这一轮补上第三个：**光幕可以按规则换颜色**。

| | |
|---|---|
| 画什么 | 四条边各一片竖直渐变幕布：脚下 0.28 格近乎不透明，往上 4 格渐隐到全透明 |
| 颜色 | `拉回` = 蓝（0.36, 0.61, 1.00），`判负` = 红（0.89, 0.43, 0.43） |
| 距离 | 距该边 16 格内满强度，16→26 格线性淡出，26 格外不画 |
| 穿透 | 纯渲染，没有碰撞箱，走过去就是走过去 |
| 同步 | 新 payload `pvpocket:arena_bounds`，在**准备阶段**就下发（准备时间就是给你看场地的） |

实现要点：

- 几何走 `RenderLayer.getDebugQuads()`——原版画 debug 视锥体用的就是它：`POSITION_COLOR`、
  半透明、`DISABLE_CULLING`。剔除关掉意味着每个四边形只发一次顶点就能双面可见，不用正反各发一遍。
- 顶点用世界坐标，所以矩阵栈要先 `translate(-camX, -camY, -camZ)`。这个约定不是猜的：
  `WorldRenderer.renderCapturedFrustum` 里写的就是 `matrices.translate(worldPos - camera.getPos())`，
  两者代数上等价。
- 距离判定用的是**到线段**的距离而不是到平面的距离。否则站在西墙 2 格外、同时往北偏 500 格，
  平面距离依然很小，会画出一面谁都不在旁边的墙。

`ArenaBoundsPayload` 同时被 `pushHudTo` 复用：中途重连的客户端原来只会拿回计分板，光幕要等到
下一个回合边界才有——那正好是「看不出边界在哪」最严重的时刻。

### 12.3 顺带修掉的一句看不懂的话

实测那局结束时 journal 写的是 `SERIES_ENDED winner=Dev1 score=0:1` —— 赢家比分是 0:1。
这其实是「认输判整场」在正常工作：Dev2 领先一局但认输了，所以整场归 Dev1。规则没错，
但播报是一句 `决斗结束：Dev1 获胜（0:1）。`，看起来像比分算错了。

`DuelSession.seriesEndReason` 现在记住 `SURRENDER` / `CUT`，播报变成：

```
决斗结束：Dev1 获胜（对方认输，比分 0:1）。
决斗结束：Dev1 获胜（对手掉线，比分 0:1）。
```

不改规则，只把「为什么赢的人比分更低」说出来。

### 12.4 没验到的

- **光幕的视觉效果没有截图确认**。两台客户端在验证期间被最小化（`IsIconic=True`，rect 在 -32000），
  `SW_RESTORE` 会让 GLFW 客户端原生崩溃，游戏内 F2 又因为窗口没有焦点收不到按键。
  代码侧能确认的是：编译通过、客户端连续跑了数分钟没有异常（渲染器每帧都在跑）、
  顶点约定与原版 debug 渲染逐行一致。**摆放位置需要眼睛确认一次。**
- 光幕是 `AFTER_TRANSLUCENT` 阶段画的，开了 Fabulous 画质时可能与半透明合成顺序有关；
  当前开发环境是普通画质，没有验证 Fabulous。

---

## 十三、内置地图

这一节是插进来的另一件事（「把这几个地图添加到设置对局时新的预设类型」）。完整记录在
`docs/PvPocket-场地模板-调研与工具.md`，这里只留结论和踩过的坑。

### 13.1 落地形态

规则面板 E 组多了一项「场地模板」：平坦 / 游乐屋 / 试炼密室 / 蓟桥。选中地图后
`e.sizeX/Y/Z` 被地图包围盒接管，面板同步刷新；切回「平坦」时原来填的数字还在。

代码上是三个新文件：`ArenaMap`（一个可选地图）、`ArenaMaps`（常量表，索引即线格式）、
`data/pvpocket/structure/*.nbt`。改动集中在 `ArenaRegion`（模板基线 + `capture` +
`groundY`）、`ArenaManager`（粘贴、`PITCH` 192）、`RuleScreen`（选中即刷尺寸）。

### 13.2 最大的坑：位打包

第一版转换出来的场地在游戏里**完全错乱**。根因是我把原版区块的位解包直接套到 litematic
上——原版是「每个 long 装 floor(64/bits) 个，尾部浪费」，litematic 是**连续比特流**，一个值
可以从某个 long 的第 61 位跨到下一个 long 的第 0 位。两者只在 `bits` 整除 64 时巧合一致，
而真实地图的调色板大多在 18–29 个方块 → `bits = 5` → 必然错位。

### 13.3 更值得记的是：我的校验是假阳性

第一版 `arena-verify.py` 拿**自己生成的 `.nbt`** 当参照，80/80 通过。轴序错了、位打包错了
也一样通过，因为两边错得一样。这个测试的价值是零，而且它让我在交付时说了「已验证」。

现在参照物永远是第三方实现：`.litematic` 用 **litemapy**，世界存档用 **anvil-parser2**，
`.schem` 没有第三方实现所以脚本直接拒绝而不是假装通过。

**教训：验证必须有一个和被测实现没有共享假设的参照物。** 拿自己当参照的测试只能证明
「我没崩」，不能证明「我对」。

### 13.4 顺带修掉的一个假 bug

`ARENA_REVERTED blocks=2050`——整局没人碰方块却记了 2050 处改动。来源是某张图的 253 处火把，
火的 `age` 属性每 tick 自己增长。`onBlockChanged` 原来用 BlockState 身份比较，而 BlockState
按属性组合 intern，`age` 变一次就是另一个实例。

改成按方块比较（`getBlock()`）之后同一局是 `blocks=0 tracked=0`。代价是同种方块换朝向不再
被记录——纯外观，且下次租场地会整个清掉。

### 13.5 撤图换源：授权问题

最初四张来自 MineSchematic / BuildSchematics，这两个站**没有逐条 License 字段**，而文件是要
打进 jar 的——那就是再分发，没有授权就是侵权。四张已全部移除。

换成 [zzuf-maps](https://github.com/zzufx/zzuf-maps)：每张图自带 `LICENSE.txt`，现在内置的
三张都是 **CC BY-SA 4.0**。义务是署名 + 声明改动 + 相同方式共享，三条都写进了仓库根目录的
`THIRD-PARTY-NOTICES.md`。这个义务只挂在 `.nbt` 这几个美术资源上，不传染 Java 源码。

### 13.6 出生点：两张图沿 Z 轴

`spawnX` 原来固定沿 X 摆（中心 ± 跨度 1/4）。三张图里只有游乐屋是这样——试炼密室和蓟桥的源
XML 都把两队放在 `z` 轴上（`x` 恒为定值），照 X 摆会把人并排放进走廊中段。所以 `ArenaMap`
多了 `alongZ`，`spawnX/Z/spawnYaw` 都按它分支。

另外名义点本身也站不住：实测蓟桥的落点**在水面上**，另外两张在岩石里没有净空。
`resolvePads()` 于是把名义点当起点，向外搜「非流体地面 + 头顶两格空气」的列。修好后
游乐屋的两端正好落在它自己的橙队/紫队平台上。

### 13.7 实测

三张图，两道关卡都过：

```
                                 离线交叉校验            游戏内回读
arena_fun_house    82x28x57     wrong=0 missing=0 extra=0   60/60
arena_trial_chambers 47x29x95   wrong=0 missing=0 extra=0   60/60
arena_thistle     102x44x99     wrong=0 missing=0 extra=0   60/60
```

蓟桥第一次跑是 58/60，两处 `redstone_wire` 失败。追下去发现源世界里 254 条红石线有 220 条
下面全是空气——PGM 加载世界不触发方块更新所以不脱，`/place` 会触发。于是把它们加进
`FLOOR_ATTACHED` 在转换阶段丢掉，重转后 60/60。

完整链路（试炼密室，`alongZ=true`）：

```
Pasted arena_trial_chambers at 72,48 (ok) in 523 ms
Leased arena-0[47x29x95 @72,48 arena_trial_chambers] 113899 template blocks
ROUND_STARTED spawns=95,14,72 | 95,14,118
ARENA_REVERTED blocks=0 tracked=0
```

出生点相对坐标 `(23,11,24)` / `(23,11,70)`，与离线模拟逐格一致。
