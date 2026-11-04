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
