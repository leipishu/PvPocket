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

换成 [zzuf-maps](https://github.com/zzufx/zzuf-maps)：每张图自带 `LICENSE.txt`，当时内置的
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

### 13.8 扩到九张：1.8 存档也能读了

后来要求「`gs_earthborn` 必须加入，再选四张，最好有一张类似角斗场的大图」。挑图时才发现
zzuf-maps 里想用的图有七张是 **1.8 存档**（`level.dat` 没有 `DataVersion`），而转换器只认
`chunk.data["sections"]`——1.8 的 key 是 `Sections`，大写，还在 `Level` 下面。于是每张都报
`no non-air blocks found`。

补上 `legacy_section_blocks()` 之后，八张 zzuf 图里只留了一张 1.21（游乐屋），其余七张全走
1.8 那条路。这条路的两个坑写在 `PvPocket-场地模板-调研与工具.md` §4.1：`index = y*256+z*16+x`
的位序，和 `Add` 数组每字节两个 4 位值。

**验证脚本比转换器更晚才修好，这一点值得记。** `arena-verify.py` 的 `read_source_world()`
同样只认 `sections`，所以 1.8 图读出来是**零个方块**。它没有崩，也没有报错——它安静地走到
「没有方块」，然后 `min()` 抛了个 `ValueError`，或者干脆比出 `wrong=0`。修完之后立刻冒出两批
「错误」：

| 现象 | 真实原因 |
|---|---|
| 198 处 `extended=True` vs `extended=true` | `OldBlock.convert()` 的属性值是 Python `bool`，`str(True)` 是大写 |
| `KeyError: 'Block 132:2 not found'` | anvil-parser2 的 ID 表缺 tripwire 的 `132:2`，转换器那张表有 |

两条都不是地图的问题，是**参照物自己的表示问题**。这说明交叉校验的价值有一半在「逼你把两边
的表示对齐」，对不齐的地方恰恰是最容易藏 bug 的地方。

### 13.9 斗兽场是生成的，不是下载的

「最好有一张类似角斗场的大图」这一条，找遍了也没有可再分发的：

- zzuf-maps 十二个目录里没有环形看台，最大的两张是桥（`armature` 171 宽超限、`double_cross`
  149 宽但与蓟桥形态重复）；
- OvercastCommunity/PublicMaps 是 PGM 社区最大的库，但**只有 XML 没有世界存档**；
- Planet Minecraft 与各聚合站默认 All Rights Reserved；
- mc-schematics 之类的「免费」站没有授权条款——免费不等于可再分发。

于是写了 `scripts/colosseum.py`：同心椭圆套循环，十排看台 + 15 格外墙 + 48 根壁柱 + 两个门洞。
`147 × 29 × 119`，109,586 块，291 KB gzip。**这是九张里唯一一张没有第三方义务的图**，也是唯一
一张尺寸是设计出来的、不是裁出来的。

过程中被渲染图纠正过三次，都记在脚本注释里：

1. 地面斑块用 3 格栅格取哈希 → 出来是一片规整的 3×3 方格，一眼就是程序生成的。换成双线性
   插值的 value noise。
2. 三种材料挂在同一条噪声的递进阈值上 → 每种材料都变成上一种的**描边**，砾石湖镶了一圈红沙边。
   改成每种材料一条独立噪声通道。
3. 灯笼用 `cos/sin` 取点再四舍五入 → 椭圆的圆整点不在椭圆上，24 次采样只有 10 次落在墙帽上，
   且全挤在 X 轴两端。改成先收集墙帽那一圈格子，再按角度排序取样。

另外门洞的地基一开始漏了：`gate_span()` 那段直接 `continue`，连 y=-1 的砂岩一起跳过了，留下
一个三格宽的地基缺口。虽然被上层盖住看不见，但那是唯一挡住沙子的那层。

### 13.10 这批没有实机测

需求原话是「不用测试了」，所以后五张只做了离线交叉校验：

```
arena_earthborn      45x34x60     7045 blocks   wrong=0 missing=0 extra=0
arena_avalon         85x32x79    11042 blocks   wrong=0 missing=0 extra=0
arena_dark_nebula    87x43x79    14509 blocks   wrong=0 missing=0 extra=0
arena_deadwood       88x36x87    60005 blocks   wrong=0 missing=0 extra=0
```

斗兽场没有源文件可比，只能自校验（写出再读回，比对 `size` 与方块数）。

风险收在两处，都是**会显形不会静默**的：1.8 图里可能有 1.21.1 不认的方块名（转换期会打印
`mapped`/`dropped`，都看过），以及出生点靠 `resolvePads()` 的环形搜索兜底。前者表现为墙上
凭空缺一块，后者表现为玩家卡在方块里。

顺带在 `SELF_DESTRUCTING` 里加了 `fire` / `soul_fire` / `tripwire`：火会烧完，绊线被玩家走过
就改 `attached`，两种都会让场地和基准对不上，`revert` 会一直触发。浮岛丢了 6 块火，枯木镇丢了
2 条绊线。

### 13.11 不测也有不测的替代：离线跑一遍出生点

「不用测试」不等于「不用检查」。`nominalSpawnX/Z` → `findStand` → `standY` 这一串是纯函数，
输入是模板方块表，所以可以在 Python 里照抄一遍跑。这个模拟抓到一个真 bug：

```
arena_avalon   85x32x79  Z
   nominal (42,20) -> NOTHING FOUND
   nominal (42,58) -> NOTHING FOUND
```

阿瓦隆深 79，两座基地在 `z=±35`，而名义点是 `z=±19`——正对着中间那片虚空。`findStand` 半径
12 够不到基地，返回 `{-1}`，`resolvePads` 于是回落到名义点，两个人都被放到**地图底下那块
裸石头地面**上。

修法是把搜索半径从 12 提到 24（`MAX_PAD_SEARCH`）。改完：

```
arena_avalon  nominal (42,20) -> pad (39,30, 6) stand-on=smooth_stone_slab ON-MAP
arena_avalon  nominal (42,58) -> pad (39,30,72) stand-on=smooth_stone_slab ON-MAP
```

`z=6` / `z=72` 关于中心 39 对称，落在两座基地的台阶上。其余七张图的落点一个没变（都在半径
0 或 1 就命中），所以这个改动只是把「找不到」那一档兜住了。

八张图全部 `ON-MAP`，站的都是地图自己的方块：

```
arena_earthborn      ( 11,8,30) coarse_dirt          (33,8,30) dirt
arena_avalon         ( 39,30, 6) smooth_stone_slab   (39,30,72) smooth_stone_slab
arena_dark_nebula    ( 43,13,20) quartz_stairs       (43,13,58) quartz_stairs
arena_deadwood       ( 22,7,43) red_sand             (66,7,43) red_sand
arena_colosseum      ( 37,5,59) sand                 (109,5,59) sand
```

`arena_trial_chambers` 的模拟结果 `(23,14,24)` / `(23,14,70)` 和 13.7 里实机测到的
`spawns=95,14,72 | 95,14,118`（`minX=72, minZ=48`）逐格一致——这条模拟本身也是被校准过的。

### 13.12 「所有地图都叠在维度中央」

用户的原话是「你把所有的地图全部对齐在你维度中央啊」。确实如此，而且是两层错叠在一起。

**第一层：`allocate()` 永远取第一个空闲槽。**

```java
for (int i = 0; i < SLOTS; i++) {
    if (!USED[i]) { slot = i; break; }
}
```

`USED` 表示的是「这一局有没有人租」，不是「这个槽里是什么」。单人测试时同时只有一个会话，所以
每一局开始时 `USED` 全是 `false`，`slot` 永远是 0。八张图全贴进 0 号槽。

**第二层：`reset()` 只清当前地图的包围盒。**

`reset` 原本从 `minX/minZ` 起、扫 `sizeX/sizeZ`，也就是「把这张图的范围还原成平坦」。147×119 的
斗兽场之后贴 45×60 的浮岛，浮岛居中，斗兽场的外墙全在浮岛的盒子**外面**——一格都不会被清，于是
它一直站在那里，浮岛就落在它中间。这也是为什么「大图之后换小图」看起来特别糟。

**改法：槽位由「是哪张图」决定，而不是由「哪个槽空着」决定。**

| 改动 | 内容 |
|---|---|
| `homeSlot(map)` | `= ArenaMaps.ALL.indexOf(map)`。斗兽场永远是 8 号分区 |
| `CELLS[]` | 记住每个分区贴的是哪张图；`release()` **故意不清它**，地图因此有固定地址 |
| `reset(world, clearSizeY)` | 清的是**分区矩形**，高度取 `max(新图 sizeY, 旧图 sizeY)` |
| `CELL` / `GAP` / `PITCH` | 160 / 96 / 256。原来 192 / 32，两张图挨太近，像同一个建筑的两部分 |
| `warmUp` | 开服时每 tick 贴一张，八张 4.8 秒贴完，单个 tick 最长约 0.6 秒 |
| `/pvpocket arena` | 现在同时列出「已布置的分区」和「进行中的租约」 |

`warmUp` 摊在 tick 上而不是一次做完，是因为单张贴一次就是 10 万方块；八张连着做会把主线程按住
几十秒，看门狗会先叫。

**一个自己踩的坑。** 第一版 `stamp()` 只返回 region，由调用方写 `CELLS[slot]`。`allocate()` 写了，
`warmUpTick()` 忘了写。结果画廊确实贴好了、日志一行不差，但 `/pvpocket arena` 报 `0 / 16`，而且
下一局会把已经贴好的图重新贴一遍（`matches()` 永远 false）。把登记挪进 `stamp()` 本身才对——
「贴进某个分区」和「记住这个分区里是什么」是同一个动作，不该拆给两个调用方。

**怎么确认的。** 光看日志坐标只能证明「它说自己在哪」，证明不了「它真的分开了」。所以写了
`scripts/arena-map.py`：直接读维度存档渲染俯视图，自己解 1.16+ 的调色板位打包（anvil-parser2 的
逐格接口跑整个维度太慢）。图上八个脚印互不相连，中间一条空白带。重贴一次时日志里的 `cleared`
从 0 变成 109569——第一次贴是空世界所以清 0 格，第二次贴时整个分区先被还原成平坦基线再重贴，
这就是「大图换小图不会留墙」的实证。

八张的落位：

```
arena-1 游乐屋   82x28x57   343,99    中心 384,127
arena-2 试炼密室 47x29x95   616,80    中心 639,127
arena-3 蓟桥     102x44x99  845,78    中心 896,127
arena-4 浮岛     45x34x60   105,354   中心 127,384
arena-5 阿瓦隆   85x32x79   341,344   中心 383,383
arena-6 暗星云   87x43x79   596,344   中心 639,383
arena-7 枯木镇   88x36x87   852,340   中心 896,383
arena-8 斗兽场   147x29x119 54,580    中心 127,639
```

中心距 256 = `CELL 160 + GAP 96`。0 号分区留给「平坦」，不参与预热；9–15 号是「同一张图开第二场」
的备用位。

### 13.13 下架三张图、把维度改成虚空、给边界加缓冲带

三件事一起做，因为它们互相咬着。

**一、下架试炼密室 / 蓟桥 / 阿瓦隆。** `.nbt` 删掉，`ArenaMaps.ALL` 从 9 项减到 6 项。这里违反了
`ArenaMaps` 自己写着的那条「只追加、不插入，下架就留个洞」——留洞意味着规则面板里三个永远选不中
的死项，而 `e.template` 是个循环控件，留洞比索引前移更难看。代价写在了类注释里：存档里
`e.template=2` 以前是试炼密室，现在是浮岛。规则只活一局，唯一读旧值的是崩溃恢复，最坏结果是
恢复出来的那局换了张图。

**二、维度改成纯虚空。** flat 生成器只剩一层 `minecraft:air`，`flatBaseline(y)` 恒定返回空气。
连带改动：

| 位置 | 改动 | 为什么 |
|---|---|---|
| `ArenaRegion.PLATFORM_Y` | 新增，`= SURFACE_Y - 1` | 「平坦」要自己铺地板，铺在地图基准下面一格 |
| `ArenaManager.buildPlatform` | 新增 | 平坦场地铺一层平滑石，尺寸取规则 |
| `capture()` | 从 `PLATFORM_Y` 起扫 | 不这样地板进不了基线，第一次 revert 就把它删了 |
| `standY()` | 从 `PLATFORM_Y` 起扫，并改为 public | 地板在 y=2，原来的起点 y=3 看不见它 |
| `isInside()` | 下界 `FLOOR_Y - 24` → `PLATFORM_Y - 8` | 下面没有基岩了，掉 8 格就该算离场 |
| `pullInside()` | 玩家低于 `SURFACE_Y` 时改用 `standY` 找地面 | 否则拉回后落在空气里，继续往下掉 |

`isInside` 那条是最容易漏的：老代码里 `-24` 是有道理的，因为基岩在 y=0，掉到 -24 只可能是穿墙。
虚空世界里掉到 -24 要 1.5 秒，而判负倒计时是 5 秒——玩家会在倒计时结束前先掉出世界。

**三、屏障缓冲带。** 地图外面一圈 `minecraft:barrier`：y=1 一整块「缓冲台」盖住 `包围盒 + 8`，
外沿立一圈墙到 `SURFACE_Y + sizeY + 2`。

缓冲台必须盖满包围盒而不只是外圈，因为**包围盒不是脚印**——浮岛是内切在方框里的圆盘，四个角
本来就是空的，只铺外圈会留四个洞。这一点是看着俯视图才想明白的。

墙的高度按 `sizeY` 算而不是拍一个常数：斗兽场看台顶在 y=27，八格高的墙挡不住站在看台上的人。

`reset()` 也跟着扩了 8 格，否则一个分区从小图换成大图时，前一张图的缓冲台会留在外面。

实测：

```
Stamped arena-5[147x29x119 @310,324 arena_colosseum] ... buffer 41541 barrier ...
Stamped arena-2[45x34x60 @617,98 arena_earthborn]    ... buffer 14896 barrier ...
Arena gallery ready: 5 maps stamped in 4528 ms
```

把玩家丢到浮岛分区缓冲带上方 `612 8 120`，落点是 `y=2.0`——缓冲台上，不是虚空。逐点探针：

```
612 1 120  → barrier   （缓冲台）
609 5 120  → barrier   （墙）
609 30 120 → barrier   （墙，一直砌到 y=37）
605 5 120  → 不是屏障  （墙外）
```

顺带一个坑：`execute if block <pos>` 用的是**命令源的维度**，RCON 的源在主世界，所以探针必须先
`execute in pvpocket:arena run execute if block ...`，否则永远不命中。

**边界光幕现在跟着玩家长高。** 服务端仍只发 4 格的最低高度，客户端渲染时取
`max(4, 相机高度 + 2.5)`，封顶 `地板 + 96`。同时把幕从「地脚 + 一整段渐隐」改成三段
（地脚 / 幕身 / 顶部 3 格渐隐），因为幕一拉高，原来那条从脚到头一路渐隐的算法会让眼睛所在
的高度完全透明。

## 十四、观战、准备硬墙、分段光幕、战斗区

### 14.1 观战：为什么要单独一套状态

观战是这个 mod 里唯一会移动**非参与者**的功能。所以它是唯一不能靠 `PlayerSnapshot` 兜底的地方
——旁观者的背包、效果、经验从头到尾没被碰过，为这点小事冻结整个人是拿大炮打蚊子。真正被改的
只有三样：游戏模式、维度、坐标朝向。这三样存在 `Spectators.Watching` 里，退出时原样放回。

维度存的是 `RegistryKey<World>` 而不是 `ServerWorld` 实例：维度卸载再加载之后旧实例就不再被
tick 了，拿着它传送等于把人送进一个不动的世界。

**相机绑定有两个半份。** `setCameraEntity` 是权威态（`getCameraEntity()` 读它，也决定下一 tick
相机还合不合法），`SetCameraEntityS2CPacket` 是可见态。只做前者客户端不会动，只做后者下一 tick
就被服务端改回来。所以 `pointCamera()` 两件都做，解绑时包里的实体传自己（协议没有 null 形式）。

### 14.2 附身 HUD：原版不给，只能自己画

`setCameraEntity` 免费给了一切**渲染**上的东西——世界、手持物、挥手、受击闪红，因为相机就是那个
玩家。它唯一不给的是 HUD：快捷栏属于旁观者自己，而旁观者在旁观模式，原版根本不画快捷栏。

于是分成两半：

| 谁画 | 画什么 |
|---|---|
| 原版渲染器 | 第一人称视角、手持物、挥手/使用动作、受击反馈 |
| `PossessionHud`（自研） | 九格快捷栏、选中格高亮、物品数量、`名字 · HP · 护甲` |

数据走新的 `PossessPayload`：`id,count` 用 `;` 拼成九组，空槽就是空串。发的是物品 id 不是 NBT
——客户端只需要画个图标和数量，每秒九格全量 NBT 换一个附魔光效不值。

**只在变化时发。** 每个 tick 算一次签名（选中槽 + 生命 + 护甲 + 九格内容），和上次相同就不发。
旁观一个站着不动的人，每 tick 一次字符串比较、零个包。

`PacketCodec.tuple` 只到六个分量，所以生命/护甲收成了一个嵌套的 `Vitals` 记录，而不是硬塞进
字符串——那样唯一真正是数字的东西反而要解析。

### 14.3 G 解除附身

原版旁观模式附身之后没有明显的退出方式，这正是「切到附身就没法拉出来」的来源。现在：

| 键 | 动作 |
|---|---|
| `V` | 切下一位（绕圈）；从解除状态按它会重新附身 |
| `G` | 解除附身，相机回到自己身上，仍在观战、仍看得到比分 |

解除是「回到自由飞行」，不是「退出观战」——退出会丢掉旁观者的原坐标，而解除不会。两条路
走的是不同的 payload 动作（`ACTION_DETACH` / `ACTION_LEAVE`），因为它们是两个问题。

`Spectators.tick()` 里有一条容易写错的判断：相机修复逻辑看到 `getCameraEntity()` 返回自己时
**必须跳过**，否则刚解除的旁观者会在下一 tick 被"修"回别人眼睛里。

### 14.4 退出观战后顶部比分条不消失

`pushSpectatorView()` 推了两样**借来的**状态：比分条和边界光幕。这两样都不属于旁观者自己的
玩家状态，所以没有任何东西会替他清掉——对局的广播只发给参与者和当前观战者。人在中途退出，
比分条就会一直挂在他的生存世界上面。

补了 `SessionManager.clearSpectatorView()`，在三条退出路径上调用：主动退出、对局结束被踢、
断线。实测退出后比分条、观战条、附身快捷栏三者同时消失，人回到主世界原来的坐标。

### 14.5 准备阶段的白色硬墙

倒计时期间场上没人能打（无敌），所以玩家唯一能做的事就是走出去，然后在对局真正开始时站在一个
莫名其妙的位置。**画出来的幕挡不住这个**——光幕是故意可穿的，因为"能被推着走的边界"是倒计时
规则永远看不见的。所以准备窗口里用的是真方块：`minecraft:barrier`，立在战斗区外一格，从平台砌
到 `max(SURFACE_Y, 出生点高度) + 8`。

立在战斗区外一格不是随手选的：那里正好是客户端画白色光幕的位置。玩家撞到的看不见的墙，和屏幕
上看到的白墙，是同一个坐标——服务端不需要每帧发任何东西。

用 barrier 而不是玻璃：不占背包、不需要清理、对局开始后场上什么都不剩。`removePrepareWall()`
只清**仍然是 barrier** 的格子，所以地图自己在同一坐标放了屏障也不会被误删。

生命周期：`startRound` 建 → `activateRound` 拆 → `finishSeries`/`abort` 兜底再拆一次。

实测（48×48 平坦场地，场地 `104..151`，墙在 `103/152`）：

```
103,5,120   → barrier     x 负向边
152,5,120   → barrier     x 正向边
120,5,103   → barrier     z 负向边
120,5,152   → barrier     z 正向边
120,11,152  → barrier     顶（top=11）
120,12,152  → 空气        不再往上长
120,2,152   → barrier     砌到平台
120,5,120   → 不是屏障    空心环
```

journal 里对应 `PREPARE_WALL_BUILT blocks=1960 top=11` / `PREPARE_WALL_REMOVED blocks=1960`，
1960 = 50×50 环的周长 196 × 10 层。缓冲区墙在 `96/159`，和准备墙不重叠，拆墙不会伤到缓冲带
（已探针确认四条边仍是 barrier）。

### 14.6 光幕按位置分段渲染

147 格长的一条边原来只要一进渲染范围就整条出现，站在中间看像一堵突然亮起来的墙。现在每条边切
成 `ceil(长度 / 2.0)` 段，**逐段按两端各自的距离**淡出：`RENDER_RANGE=26`、`FULL_STRENGTH=14`，
两端都超出范围才跳过整段。结果是光幕随玩家移动一点点亮起来，走到哪亮到哪。

白色实心模式（准备阶段）另有两处不同：顶部不渐隐（硬顶），高度固定取服务端给的值，不跟着相机
长高——因为那面墙是真实存在的，它多高就是多高。

### 14.7 战斗区（pit）与包围盒分离

`ArenaMap` 多了 `pitInsetX/pitInsetZ`，`ArenaRegion` 由它派生 `pitMinX()/pitMaxX()/pitMinZ()/
pitMaxZ()`。判定和渲染全部改读 pit：`isInside()`、`pullInside()`、光幕坐标、出生点搜索。方块
保护、实体清理、租约仍然用包围盒——那些关心的是"这张图占了多少地"，不是"人在哪打"。

目前只有斗兽场有内场：inset `23/19`，对应椭圆半轴 50×40 的沙地 `100×80`，包围盒 `147×119`。
其余五张 inset 是 0，行为和以前完全一样。

### 14.8 没验到的

- **G 解除附身没能在本机自动化验证**。这台机器上合成按键（`SendKeys`）这一轮完全进不去游戏
  ——`Esc` 打不开暂停菜单、`t` 打不开聊天框，窗口是前台也不行。真人按键是正常的（用户实测 V
  切换成功，HUD 从 `视角 Dev1 (1/2)` 变到 `视角 Dev2 (2/2)`）。服务端那半（`detach` →
  相机归位 + 推送 `index=-1`）和 `leave` 走的是同一段代码，`leave` 已实测。
- **斗兽场 pit 没有实机跑过一局**。规则面板选的还是 48×48 平坦图，要验 pit 得先选到斗兽场。
  几何是纯函数，`pitMinX()` 那串已经在离线脚本里对过。
- **五张图没有各跑一局冒烟**。`warmUp` 每张开服都会贴一遍（日志里每张都有 `pasted true`），
  但"贴上去之后打一局"仍然只做过平坦图。

## 十五、附身手部渲染、观战入场状态、掉线兜底、地图占用

### 15.1 附身手部渲染：矩阵是错的，不是变换是错的

第一版把 `context.matrixStack()` 当成相机坐标系直接用，结果"主副手不分、副手画到主手上、状态也
不对"。根因在坐标系，不在那四行装备/挥手变换。

`GameRenderer.renderWorld` 里：

```java
Quaternionf q = camera.getRotation().conjugate(new Quaternionf());
Matrix4f viewRotation = new Matrix4f().rotation(q);      // 只有旋转，没有平移
worldRenderer.render(..., viewRotation, projection);
renderHand(camera, f, viewRotation);
```

`WorldRenderer.render` 把 `viewRotation` 压进 model view 栈，然后 `new MatrixStack()`（单位阵）用于
实体。实体渲染时 `matrices.translate(x - camX, ...)` 走的是**世界轴向**，旋转交给 model view 收尾。

`GameRenderer.renderHand` 里补的是另一半：

```java
matrixStack.multiplyPositionMatrix(viewRotation.invert(new Matrix4f()));  // 相机→世界
modelView.pushMatrix().mul(viewRotation);
```

两者相乘抵消，所以 `translate(0.56, -0.52, -0.72)` 落在屏幕固定位置。之前只做了前半段的错位版本，
于是 0.56 变成了"世界 +X 半格"，朝向也跟着乱。

现在在 `WorldRenderEvents.LAST` 里照抄这套：`LAST` 触发点在 `renderChunkDebugInfo` 之前，
model view 栈还压着 `viewRotation`（`popMatrix` 在它后面几行），所以只要自己 `new MatrixStack()` 再
乘上 `viewRotation.invert()`，就是原版 `renderHand` 的初始状态。同时 `RenderSystem.clear(GL_DEPTH_BUFFER_BIT)`
——世界已经画完，后面只剩方块描边调试层和 GUI，都不读深度，和原版 `renderHand` 开头那一次 clear
等价。用 `disableDepthTest` 代替是不行的：物品模型内部的面会失去排序。

主副手严格按 `HeldItemRenderer` 分开：`Arm.RIGHT` → `side=+1` + `FIRST_PERSON_RIGHT_HAND` +
`leftHanded=false`；`Arm.LEFT` → `side=-1` + `FIRST_PERSON_LEFT_HAND` + `leftHanded=true`。空手时
走 `renderArmHoldingItem` 的完整变换 + `PlayerEntityRenderer.renderRightArm/renderLeftArm`，用的
是目标玩家自己的皮肤。

**实测**：Dev3 附身 Dev1 看到的铁剑，和 Dev1 自己屏幕上截的图逐像素一致（位置、角度、明暗都同）。

没做的：吃/喝/拉弓/举盾这些使用动作。它们依赖 `getActiveHand()` 和 `getItemUseTimeLeft()`，原版
不同步给其他玩家，猜错比不画更糟——"状态不太对"正是猜出来的。

### 15.2 观战入场不再自动附身

`Spectators.join` 原来 `state.target = 0` 后立刻 `attach()`，所以一进观战就是别人的第一人称，
快捷栏也直接出现。现在入场是 `target = -1` + `pointCamera(自己)` + `sendPossess(hidden)`：先自由
飞行看清场地和两个人，按 `V` 再附身。

`PossessionHud` 完全由 `PossessPayload.active()` 驱动，所以服务端不发就自然不画——客户端不需要
额外判断。实测截图：入场时只有底部横条 `视角 自由视角 · V 附身`，没有九格；按 `V` 后九格、
`Dev1 · HP 20 · 护甲 0` 和手持铁剑同时出现；按 `G` 三者同时消失。

### 15.3 掉线兜底：`homes.json`

观战状态只在内存里（`Spectators.Watching`）。服务器被强杀时还原来不及跑，`playerdata` 里就留下
决斗维度的坐标和 `SPECTATOR` 模式——下次登录出生在一块悬空场地上，还挖不了方块。

新增 `storage/HomePositions`：每个玩家**不在**决斗维度时，位置、朝向、游戏模式记录进
`<world>/pvpocket/homes.json`，位移超过 2 格或维度/模式变化才更新，30 秒最多落一次盘。加入服务器
时 `onJoin` 往 `deferred` 里排一次 `rescue()`，下一 tick 执行（`JOIN` 事件里玩家还没注册进
`PlayerManager`，而且初始位置包还没发，当场传送会被覆盖）。人在决斗维度就送回记录的位置；如果当
前是 `SPECTATOR` 再按记录改回去。没有记录（首次运行）就送主世界出生点。

**实测**：
- 开服时两个玩家的 `playerdata` 还停在上一次测试的场地里，两条 `Rescued ... sent to spawn` 直接
  生效（`homes.json` 还不存在，走的是兜底分支）。
- 把 Dev3 `tp` 进 `pvpocket:arena` 到 `104.5, 3, 104.5`，强杀客户端（模拟崩溃，`onDisconnect` 只
  记录不改位置），重启后日志：`logged in ... at (104.5, 3.0, 104.5)` →
  `Rescued Dev3 from the arena to -15.5 77.0 -47.5 in minecraft:overworld`。`homes.json` 里
  Dev3 那条全程是主世界坐标，没被决斗维度覆盖。

### 15.4 同一张地图不能开两场

`ArenaManager.busyMapMask(exceptSessionId)` 把当前被租出去的地图折成一个位掩码。选择在三个地方：

1. **面板显示**：`RulesOpenPayload` 多了 `busyMaps`。`PacketCodec.tuple` 最多 6 个分量，原来的
   6 个字段已经占满，所以 `otherName`/`loadoutSummary` 收进嵌套的 `Details` record，再带上掩码。
2. **不能选**：`PvCycle.skip(IntPredicate)`，左右箭头跳过被占的选项（绕一圈还没有就原地不动），
   当前值被占时显示橙色的 `平坦（使用中）`。
3. **服务端拒绝**：`startAccepted` 在 `ArenaManager.allocate` 之前再查一次。面板是快照，可能开着
   的时候别人刚好开局。

**实测**（Dev1 vs Dev2 在平坦图开打，Dev3 找 Dev4 开新局）：

- 面板 E 组：`场地模板 = 平坦（使用中）`（橙色），点 ▶ 直接跳到 `游乐屋`，长宽高同时变成
  `82×57×28`（游乐屋包围盒）。
- 不改图直接「同意并开始」：Dev4 收到 `「平坦」正在被另一场决斗使用，请换一张地图后再开始。`
- `/pvpocket arena` 同步显示 `arena-0 flat ... 会话 1767b2ce`，`1 场进行中`。

为了测这条加了一个 `runClient4` / `Dev4` 运行配置：Dev1+Dev2 占住一张图之后，第二局需要两个空闲
玩家，Dev3 一个人不够。

### 15.5 本轮没验到的

- 同一张图被占时**换到另一张图跑完整一局**：只验到"跳过 + 服务端拒绝"，没把第二局真的打完。
- 斗兽场 pit、五张图各跑一局的冒烟，仍然是 14.8 里那两条没做完的事。

## 十六、沙盒指令规则、游戏模式锁定、观战飞行区

### 16.1 钩子一开始挂错了地方

第一版把指令拦截挂在 `CommandManager.executeWithPrefix` 上，理由是"所有指令都从这里进"。编译
通过、启动无异常、`/pvpocket` 也照常工作——但玩家在场地里打 `/gamemode creative` 是**能过**的。

反编译 `ServerPlayNetworkHandler.onChatMessage` 才看清：

```java
ParseResults<ServerCommandSource> parse = dispatcher.parse(command, source);
this.server.getCommandManager().execute(parse, command);   // 不是 executeWithPrefix
```

聊天指令由网络层自己 parse，然后直接调 `execute`。走 `executeWithPrefix` 的只有控制台和 RCON——
也就是**只挡住了运维，放过了玩家**，和需求正好相反。

改挂 `execute` 之后两个入口都覆盖了，而且它还是唯一一个"已经拿到 parse 结果"的公共汇聚点，不需要
再维护第二个钩子。

白名单只认根字面量 `pvpocket`（`pvpocket:pvpocket` 这种带命名空间的写法也认，剥掉前缀再比）。选
白名单而不是黑名单的理由写在 README 里：黑名单漏一条就是一个洞，别的模组加指令又是一个洞。

### 16.2 游戏模式：两层，外加一条授权通道

第一层是上面的指令白名单，挡住 `/gamemode` 和 `F3+F4`（后者发的也是 `/gamemode`）。

第二层挂在 `ServerPlayerInteractionManager.changeGameMode`，挡住"谁都没发命令"的路径。先确认了它
不会被登录流程踩到：`ServerPlayerEntity` 里 `changeGameMode` 只被 `GameModeCommand` 和模组自己调用，
playerdata 恢复走的是另一个方法 `setGameMode(GameMode, GameMode)`，所以拦这里不会把玩家卡在错误的
登录状态。

模组自己的切换（开局切生存、观战切旁观、退出还回去、快照恢复、掉线救援，共 5 处）全部改走
`Sandbox.changeGameMode`，内部是一个 `try/finally` 的授权计数器。**计数器而不是布尔**：`PlayerSnapshot`
的恢复可能在已经授权的调用栈里再执行一次。

### 16.3 运维那一下被自己拦住了

实测时发现控制台 `gamemode survival Dev1`（Dev1 站在场地里）**什么也没输出，也没生效**。原因是第二层
只认"目标玩家在不在场地"，不认"谁发起的"，所以把运维也一起拦了；而 `GameModeCommand` 只在模式真的
变了时才发反馈，于是表现成一条静默的无效指令。

这不能留：一个卡在决斗维度里的玩家，运维除了改存档没有别的办法。补法是让非玩家来源的指令在执行
期间持有同一份授权——`CommandManagerMixin` 在 `execute` 的 HEAD 抬开、RETURN 收掉，条件都是"来源
没有玩家实体"。`execute` 内部把所有 `Exception` 都 catch 住了，正常返回一定会走到 RETURN，所以不
存在授权泄漏；唯一漏得掉的是 `Error`，那已经是整个 tick 都没了。

判定依据从此统一成**发起者**：玩家发起的模式切换一律拒绝，服务端发起的放行。

### 16.4 观战飞行区

自由飞行的旁观视角原来没有任何约束，可以飞出场地、飞到场外、甚至飞到隔壁格子里看另一场决斗——
而"两场决斗不共享一个格子"正是整个 plot 网格存在的理由。

限制盒子 = **包围盒**（不是战斗区）每边 +12 格，顶 = 场地顶 +10，底 = 平台下 6。选包围盒的理由：
五张图里四张包围盒等于战斗区，这个限制就是"光幕再往外 12 格"；斗兽场两者不同，战斗区是沙子、包围盒
是外墙加十排看台——按战斗区限制会把人关在看台内部，只能看到方块内壁。

实现是每 tick 的 `MathHelper.clamp` + 服务端传送（`ArenaManager.pullSpectatorInside`），和参与者
越界拉回用的是同一套：旁观飞行是客户端权威的，服务端唯一的杠杆就是直接说"你在哪"。只在**未附身**
时执行——附身时自己的身体根本没在动。

边界提示是边沿触发 + 距离复位：碰到时提示一次，退回 4 格以内才重新武装。用计时器会在贴着墙飞的时候
反复刷屏。

### 16.5 实测

| 项目 | 结果 |
| --- | --- |
| 场地内玩家 `/gamemode survival` | 红字 `决斗场地内只能使用 /pvpocket 指令。`，`playerGameType` 保持 1 |
| 场地内玩家 `/pvpocket list` | 面板正常打开 |
| 控制台 `gamemode survival Dev1`（Dev1 在场地内） | `Set Dev1's game mode to Survival Mode`，`playerGameType` 变 0 |
| 开局（Dev1 原本在场地里是创造） | 传送到出生点后 `playerGameType` = 0，授权通道生效 |
| 观战入场 | Dev3 `playerGameType` = 3，维度 `pvpocket:arena`，坐标 `128.5, 9, 128.5` |
| 观战者被 tp 到 `2000, 200, 2000` | 两秒后 `164, 45, 164`，客户端收到 `已到达观战视角的边界…` |

`164, 45, 164` 是手算的期望值：平坦场地 `minX=104, sizeX=48` → `maxX=151`，`151+1+12=164`；
`SURFACE_Y + sizeY + 10 = 3 + 32 + 10 = 45`。逐位吻合。

### 16.6 本轮没验到的

- 斗兽场（唯一包围盒 ≠ 战斗区的图）上的观战飞行区实际手感：数值上盒子是 `298..469 / 312..455 /
  y -4..42`，没有实机飞过一圈。
- 观战者贴着边界持续前推时的观感（每 tick 一次拉回，理论上等于沿墙滑行，没有实测）。

## 十七、滞留保护、可点击豁免、场地硬重置

### 17.1 三条合法理由之外，都是滞留

决斗维度是虚空，掉出去就是掉进虚空。能站在里面的合法状态只有三种：参与者、观战者、以及两者之间
那半个 tick。其余全部是事故——传送落错地方、崩溃吃掉了一次还原、别的插件一条命令、运维手滑。

`HomePositions` 已经盖住了"登录时人在里面"这一种。新加的 `ArenaGuard` 盖住活着的那一种：每 tick
扫一遍在线玩家，`isArena && sessionOf(uuid) == null && !isSpectating(uuid)` 就是滞留。

**5 秒不是礼貌。** 参与者的会话在传送之前就登记了，观战者也是，所以被看见只可能是真的两边都不占。
但扫描跑在服务端 tick 上，客户端落后一两个 tick，单 tick 判定会打在正在过渡的玩家身上。5 秒比这个
窗口大三个数量级，顺便也给了玩家读完提示的时间。

倒计时走动作栏而不是聊天区：5 秒刷 5 行聊天会把别的东西全顶上去，而倒计时不值得这个代价。

### 17.2 按钮就是指令

聊天里那条提示带一个 `[ 不要移出 ]`，`ClickEvent.Action.RUN_COMMAND` 指向
`/pvpocket guard false`。**不做自定义包**：点按钮走的就是玩家手打这条命令的同一条路径——同样的权限
检查、同样的沙盒白名单（`/pvpocket` 在维度内是放行的）、同样的代码。两套实现迟早会漂移。

豁免是**按玩家**的，不是全局开关。全局开关意味着一次忘记恢复就等于对所有人关掉保护；按玩家的最坏
情况是那一个人一直留在里面，而那个人本来就打算留在里面。豁免写进 `<world>/pvpocket/guard.json`，
因为"我进去看一下"很可能跨越一次重启——重开着看一个坏掉的维度是常态。

`guard.json` 读失败时按"没有任何豁免"处理：所有人重新获得保护，这是安全的方向。

### 17.3 硬重置：为什么每一步的顺序都不能换

```bash
/pvpocket arena reset   # OP 等级 3
```

1. **观战者先走。** 相机绑在即将被传送出维度的人身上，等于看空帧。
2. **对局全部结束。** 用 `abort` 而不是 `settle`：结算会颁奖、可能进入下一局，而重置要的是
   "什么都没发生过"。`abort` 正好是完整清单——还原快照、释放租约、作废会话。
3. **剩下的人送出去。** 不在会话里的滞留者，不能等 5 秒后的守卫，因为下一步就要抽地板了。
4. **清空 + 重贴。** 重贴是一 tick 一张，走开服填展位那条路径。一次做完是 5 个格子约 15 万方块，
   主线程停 4.5 秒，看门狗会报警、在线客户端会掉线。

第一版漏了一格：预热只重贴"地图展位"，而平坦场地按设计不参与（它的尺寸由规则决定，只能按需生成）。
于是重置后平坦格保留着上一局的平台和残骸，要等下一次选平坦图才被 `stamp` 清掉。补法是
`ArenaManager.wipeUnstampedCells`，在 `clearAll()` **之前**执行——因为区域对象才知道这一格的残骸
有多高——只清预热不会碰的那几格：平坦格、以及被第二场对局借用的备用格。

同时给 `startAccepted` 加了一道"正在重建"的拒绝：重贴期间开的局会拿到一个还没重贴完的展位。

### 17.4 实测

| 项目 | 结果 |
| --- | --- |
| 传进维度（无对局） | 聊天出现提示 + `[ 不要移出 ]`，动作栏 `5 秒后移出决斗维度…`，5 秒后回主世界原位 |
| 点击按钮 | 日志 `Stray-player guard disabled for Dev1 (1 exemptions on record)`，`guard.json` 写入 uuid |
| 豁免后再传进去 | 等 9 秒仍在维度内，无提示 |
| 维度内 `/pvpocket guard true` | 倒计时立刻开始，随后被移出 |
| `/pvpocket arena reset`（对局进行中） | `强制结束 1 场对局`；两人回主世界且恢复生存；5 张图全部重贴；`arena` 诊断 0 场进行中 |
| 重置后平坦格 | `if block 128 2 128 air` 成立（平台确实没了） |
| 重置后再开一局 | 平台重建，`if block ... smooth_stone` 成立，对局正常开始 |

### 17.5 本轮没验到的

- 重置时**有观战者**在场：代码路径是先 `ejectEverything`，但没有实机跑过"观战中被重置"。
- 服务器刚启动、展位还在填的时候执行 `arena reset`：会被拒绝（`warmUpPending`），拒绝分支没实测。
