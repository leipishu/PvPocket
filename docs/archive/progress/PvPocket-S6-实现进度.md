# PvPocket S6 实现进度：场地分区、禁刷怪、地形保护

> **历史存档 · 非规范。** S6 阶段记录：场地分区、禁刷怪、方块保护与地形复原。
> 分区模型与偏差集（delta set）的设计在这一阶段定型。
> 现行规范见 [`../../zh/01-overview.md`](../../zh/01-overview.md) 起六篇；使用者可感知的变化见 [`../../../CHANGELOG.md`](../../../CHANGELOG.md)。
> 本文按当时原样保留：其中的目录（如 `run/`）、指令（如 `/pvpocket accept`）与类名反映当时状态，与当前代码不一定一致。

> 对应需求：**二、关于地图**
> 1. 不要刷新史莱姆，其他怪也不要刷新
> 2. 给每个区域添加边界（后续要支持多个对战房间）
> 3. 可开关的地图本身方块保护
>
> 对应设计：`docs/PvPocket-v0.4-技术方案.md` §10（场地与方块）、`docs/PvPocket-v0.5-配装与场地方案.md` §2

---

## 一、这一阶段交付了什么

| 需求 | 交付 | 落点 |
|---|---|---|
| 每个区域有自己的边界 | 场地切成 4×4 网格，每个会话租一块，边界按租到的地块算 | `ArenaRegion` / `ArenaManager` |
| 支持多个对战房间 | 租约按会话 id 分配，最多 16 场并发，互不重叠 | `ArenaManager.allocate/release` |
| 不刷怪（含史莱姆） | 三个入口全部封死 + 周期清扫兜底 | `SpawnHelperMixin` / `ArenaManager.sweepMobs` |
| 地图方块保护可开关 | 新增规则 `f.protectTerrain`，默认开 | `RuleCatalog` / `ArenaManager.allowBreak` |
| 一局结束后地形复原 | 偏差集合 + 基线回写 | `ArenaRegion.revert/reset` |

---

## 二、分区模型

> **本节参数已过时。** 下面写的是 S6 当时的取值（`PITCH` 160、`MARGIN` 16、每块 128）。
> S8 引入内置地图后改成了「一张图一个固定分区」：`CELL` 160 / `GAP` 96 / `PITCH` 256 /
> `MARGIN` 48，上限 160×64×160。当前口径见 `PvPocket-S8-实现进度.md` §13.12。

### 2.1 为什么是网格而不是动态创建维度

Minecraft 不能在运行时创建维度（v0.4 §10.1 已锁）。所以只有一条路：**一个固定维度，内部切成互不重叠的地块，每个会话租一块**。

```
PITCH = 160 格，4 列 × 4 行 = 16 块地，整片 640×640
每块地居中对齐在自己的格子里，四周至少留 MARGIN=16 格空带
地块尺寸由规则 e.sizeX / e.sizeY / e.sizeZ 决定，上限 128×64×128
```

地块 0 的实际落点（默认规则 48×32×48）：

```
slot 0 → col 0, row 0
minX = 0*160 + (160-48)/2 = 56
minZ = 0*160 + (160-48)/2 = 56
占位  x ∈ [56, 103]，z ∈ [56, 103]，地面 y=3
出生点 Dev1 (60, 3, 80) 朝东 / Dev2 (99, 3, 80) 朝西
```

### 2.2 租约

```java
ArenaManager.allocate(server, sessionId, sizeX, sizeY, sizeZ, protectTerrain, allowBreakOwn)
ArenaManager.release(sessionId)
ArenaManager.region(sessionId)
```

三个约束：

1. **租约在冻结之前拿**。场地满了必须在 `NEGOTIATING` 阶段就失败，不能等两份背包都拍完快照才说"没地方"——那时候唯一的诚实结局是为一个跟玩家无关的原因回滚两套生存存档。
2. **租约按会话 id 键控**，重复释放不会释放别人的地。
3. **租约不落盘**。重启时 `reconcile()` 会把所有未完成会话作废，玩家本来就不在场地上，所以内存态足够；落盘反而要额外处理"磁盘说有租约但世界已经重置"。

### 2.3 边界

`ArenaRegion.isInside(x, y, z)`：

```java
y >= FLOOR_Y - 24 && x ∈ [minX, maxX+1] && z ∈ [minZ, maxZ+1]
```

向下留 24 格余量是给"挖穿了掉下去"的判定——正常情况挖不穿，因为 y=0 是基岩。

`e.boundary` 决定越界的含义（拉回 / 判负），和 S4 一致，只是现在按地块算而不是按全局圆心算。旧实现是 `(x-4)² + z² <= 40²`，只要有人租了第 2 块地就会被误判——这就是为什么多房间必须先把分区做完。

---

## 三、禁刷怪：三道门

### 3.1 为什么 `monster_spawn_light_level: 0` 不够

维度类型里已经写了：

```json
"monster_spawn_light_level": 0,
"monster_spawn_block_light_limit": 0
```

这个字段只是**把生成所需的亮度阈值压到 0**，不是"禁止生成"。天黑、被方块遮住、或者玩家自己搭了个棚子，亮度就是 0，怪照样刷。实测就抓到了：上一版跑测试时 `Dev2 was slain by Slime`。

`doMobSpawning=false` 也不行——那是服务器全局开关，会把主世界的刷怪一起关掉。

### 3.2 三道门

| 门 | 挡什么 | 实现 |
|---|---|---|
| ① `SpawnHelper.spawn` 取消 | 所有自然生成：怪物、动物、水生、环境 | `SpawnHelperMixin` |
| ② `SpawnHelper.populateEntities` 取消 | 区块首次生成时的被动生物播种 | `SpawnHelperMixin` |
| ③ 周期清扫（每 60 tick） | 刷怪笼、刷怪蛋、`/summon`、其他模组的刷怪器 | `ArenaManager.sweepMobs` |

第 ② 门是实测发现的：新地块第一次生成时，`populateEntities` 会按生物群系的生成表撒一批牛和羊，**不经过** `SpawnHelper.spawn`。日志里 `Swept 7 stray mobs` 就是它们——玩家开局会发现场地里站着七只动物。

第 ③ 门只删 `LivingEntity` 且排除玩家，**不删**箭、喷溅药水、掉落物——这些是决斗正常产物，中途清掉会毁掉正在飞行的攻击。

---

## 四、方块保护与地形复原

### 4.1 关键前提：基线是可推导的，不是存下来的

维度生成器是固定的：

```
y = 0        基岩
y = 1..2     石头
y >= 3       空气
```

所以"这块地原本长什么样"是一个纯函数，不需要任何快照文件：

```java
ArenaRegion.baseline(y)
```

这一条把三件事同时变简单了：判断"这块是不是地图本体"、复原地形、判断"这块是不是玩家放的"。

### 4.2 偏差集合，而不是变更历史

每块地维护一个 `LongOpenHashSet`：**当前与基线不同的坐标**。

- 放一块石头 → 加入集合
- 挖掉一块石头 → 加入集合（现在它是空气，与基线不同）
- 把挖掉的石头放回去 → 从集合移除（又与基线相同了）

好处是集合大小只跟"当前有多少偏离"有关，不跟"玩家操作了多少次"有关；一局里反复挖填同一格不会让集合膨胀。

上限 20 万格，防止刷屏放置撑爆内存。

### 4.3 记录点：`World.setBlockState`

```java
@Mixin(World.class)
setBlockState(BlockPos, BlockState, int, int) @At("HEAD")
    → if (this instanceof ServerWorld && ArenaManager.isArena(world))
          ArenaManager.onBlockChanged(world, pos, state);
```

为什么不用 `PlayerBlockBreakEvents` / 放置事件：

- 玩家事件只覆盖玩家亲手做的事。爆炸、活塞推动、流体蔓延、火焰、末影人搬方块、局末清理，全都不经过玩家事件。
- `World.setBlockState` 是上面所有路径的唯一漏斗。挂在这里，意味着**模组不认识的路径也跑不掉**。

`ServerWorld` 没有覆写这个方法（在 `World` 里），所以 Mixin 挂在 `World` 上，靠 `instanceof ServerWorld` 把客户端挡掉。

### 4.4 破坏否决

```java
PlayerBlockBreakEvents.BEFORE → ArenaManager.allowBreak(world, player, pos)
```

| 规则 | 位置 | 结果 |
|---|---|---|
| `f.protectTerrain = 开`（默认） | 坐标不在偏差集合里（= 地图本体） | 否决 + 提示"场地方块受保护" |
| `f.protectTerrain = 关` | 任何 | 放行，局末仍会复原 |
| `f.allowBreakOwn = 关` | 坐标在偏差集合里（= 本局放的） | 否决 + 提示"本次决斗不允许拆除方块" |

**这里有一个刻意的简化，需要明确记下来**：偏差集合只知道"这一格被改过"，不知道"是谁改的"。所以规则文案里的"自己放置的方块"实际执行成"本局放置的方块"。1v1 封闭场地里两者只在一种情况下有区别——玩家故意拆对手的掩体，那是玩法问题不是正确性问题。要区分到人需要把 owner 一起存进集合（内存 ×3），等真有多人混战的需求再说。

### 4.5 复原时机

`settle()` 里的顺序：

```
清掉落物 → RESTORING → 恢复玩家快照（把人传送出场地） → 回写基线 → 释放租约 → ARCHIVED
```

回写放在恢复之后，因为恢复会把玩家传送走；先回写会让地板在玩家脚下消失。

---

## 五、会话生命周期里的新节点

```
ARENA_LEASED      region=arena-0 size=48x32x48 origin=56,56 protectTerrain=true allowBreakOwn=true
ROUND_STARTED     region=arena-0 spawns=60,3,80 | 99,3,80
BOUNDARY_EXIT     player=... pos=x,y,z world=pvpocket:arena     ← 只在越界时出现
BOUNDARY_PUSHED_BACK player=... grace=true
ARENA_CLEARED     removed=0
ARENA_REVERTED    region=arena-0 blocks=2 tracked=2
ARENA_RELEASED    region=arena-0
```

`BOUNDARY_EXIT` 带坐标是刻意的：日志里"出界"有两种完全不同的成因（玩家走出去 / 传送没生效），事后只有坐标能把它们分开。

---

## 六、开局宽限期

实测发现的问题：两个 dev 客户端进场地后，**分别在 12 秒和 22 秒后被判出界**，而开局第 1 秒查坐标是完全正确的。

成因是客户端要首次下载一个从没去过的维度，这段时间它可能把传送前的位置报回服务端，服务端接受后就变成"玩家站在 x=-20"——在场地坐标范围外。

处理方式：**开局宽限期内越界一律拉回，不判负**。

```java
grace = max(10_000ms, a.countdown * 1000)
```

10 秒不是平衡数值，是"dev 客户端首次流式加载一个维度"的粗略最坏值。这也正好是 `a.countdown` 本来就承诺的语义（传送进场后到可以行动之间的等待时间）——目前只实现了"宽限"这一半，"锁定移动"那一半还没做。

**这条是防御性修复，尚未在真实客户端上复验。** 下一次运行测试时，如果日志里出现 `BOUNDARY_PUSHED_BACK grace=true`，说明假阳性确实存在且被挡住了；如果仍然 `reason=OUT_OF_BOUNDS`，说明成因另有其他，`BOUNDARY_EXIT` 的坐标会指出来。

---

## 七、已验证 / 未验证

### 已验证（RCON 无头流程，真实服务器）

```
SESSION_CREATED challenger=Dev1 target=Dev2
RULES_DRAFTED revision=0 digest=01a06524c300
RULES_PROPOSED revision=1 author=Dev1 changed=""
ARENA_LEASED region=arena-0 size=48x32x48 origin=56,56 protectTerrain=true allowBreakOwn=true
SNAPSHOT_WRITTEN ×2
ROUND_STARTED round=1 region=arena-0 spawns=60,3,80 | 99,3,80
```

- 地块落点与手算一致：`arena-0 @ (56,56)`，48×32×48，保护开
- 两个玩家被传送到 `(60.5, 3, 80.5)` 与 `(99.5, 3, 80.5)`，维度 `pvpocket:arena`
- `summon minecraft:slime 80 4 80` → 查询时已不存在，日志 `Swept 7 stray mobs`（那 7 只是区块播种的动物）
- `setblock 80 3 80 stone` + `setblock 80 4 80 glass` → `pvpocket arena` 显示 `变更 2`；局末 `ARENA_REVERTED blocks=2 tracked=2`
- 局末 `ARENA_RELEASED` 后 `pvpocket arena` 回到 `0 / 16 已占用`

### 未验证

- 玩家亲手挖方块时 `f.protectTerrain` 的否决（`PlayerBlockBreakEvents` 路径没被脚本触发过）
- 保护关闭后挖穿地板、局末是否完整复原
- 开局宽限期是否真的消除了误判
- 16 块地同时开满时的表现（从没并发过）
- 爆炸、活塞、流体这些"非玩家路径"的记录是否真的生效（`World.setBlockState` 理论上全覆盖，但没有实测用例）
- 大尺寸地块（128×128）的 `reset` 耗时

---

## 八、已知缺口

1. **`f.allowPlace` 没有执行**。规则默认"不允许放置方块"，但目前放行——放置路径（`UseBlockCallback` / `UseItemCallback`）无法可靠覆盖所有放置方式，强行在 `setBlockState` 里拦会把爆炸和流体也一起拦掉。需要单独一层"这次写入是不是玩家放置"的判定。
2. **崩溃后的高空残留**。`reset` 只扫到 `SURFACE_Y + sizeY + 4`。如果崩在半局，而下一场的地块更矮，超出的部分会留下漂浮方块。是无人区域的观感问题，不影响对局。
3. **`purge` 与实体加载有竞态**。租地时立刻清实体可能漏掉尚未 entity-load 的区块，靠 3 秒后的清扫兜底（实测就是这样补上的）。可以改成"首 tick 再清一次"。
4. **地块之间没有物理隔离**。相邻地块中间有 16 格空带，但玩家能走进去（走进去就是越界判负）。视觉上会看到隔壁的场地。
5. **`e.sizeY` 只影响复位扫描高度**，没有真正的天花板，也没有"可搭建高度"的执行。
