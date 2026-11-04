# PvPocket 场地模板：免费地图调研与转换工具

> 触发需求：「调研一下网上有没有你能直接拿来用的 pvp 地图预设，比如那种斗兽场风格的」
> 一轮之后：「不要被大小约束！大一点挺好啊」
> 二轮之后：「不行，要钱，那不行，帮我找一些免费的。最好是投影文件吧，你是不是更好解析？」
> 三轮之后：「四个文件都放进去了……把这几个地图添加到设置对局时新的预设类型」
> 四轮之后：「场景完全是错乱的，根本没有正确解析文件！上网查询一下怎么正确解析 litematic」
>
> **最后这句是本篇最值钱的部分**——它推翻了我当时「解析没问题」的判断，也推翻了当时那套
> 80/80 通过的校验。根因见 §四「位打包」。记录在这里是为了下次别再犯：**拿自己的输出当
> 参照的测试等于没测。**

## 一、先回答「投影文件是不是更好解析」

**是，而且是明显更好。** 但要说清楚好在哪一层，因为这不是「能不能读」的区别，是「少写多少容错」的区别。

`.litematic` 是 gzip 压缩的 NBT，剥开之后是：

```
Regions
  <名字>
    Position        {x,y,z}          // 这一块的角点
    Size            {x,y,z}          // 允许负数：负 = 从 Position 往回长
    BlockStatePalette  [ {Name, Properties}, ... ]
    BlockStates     LongArray        // 位打包，每格 max(2, bits(len(palette)-1)) 位
    TileEntities    [...]
Metadata { Name, Author, TotalBlocks, EnclosingSize, ... }
```

对比世界存档（`.mca`），省掉的是整整三层：

| 层 | 世界存档要做的事 | litematic |
|---|---|---|
| 文件层 | 8192 字节扇区表，每块 4 字节偏移+扇区数，还要处理 255 扇区溢出 | 无 |
| 块层 | 每块 5 字节头（长度 + 压缩类型 1=gzip/2=zlib/3=裸），三种都要试 | 无，整个文件一种压缩 |
| 结构层 | 1.18 前后 `Level` 嵌套不一样，要判两次 | 无，`sections` 直接在根 |

**剩下的一层看着一样，实际上不一样。** 调色板 + 位打包听起来和世界存档同构，我一开始就是这么
以为的，直接把 `section_blocks()` 的位解包搬过来用了——**这是错的，而且错得很隐蔽**：小样本、
2 的幂调色板都能过，真实地图一贴就整场错位。详见 §四的「位打包」一节。这里先记住结论：
litematic 是**连续比特流**，原版区块是**每 long 装 floor(64/bits) 个**，两者只在 `bits` 整除
64 时巧合一致。

另外两个附带好处：

- **没有 48×48×48 限制**。原版结构方块的天花板只约束「用结构方块抠图」，投影文件本来就不是它导出的。
- **`Size` 可以是负数**。负号表示从 `Position` 往回长，读到之后归一化成「最小角 + 正尺寸」就行。

`.schem`（Sponge）也支持了，因为它更常见：调色板是 `名字 -> 索引` 的 compound，方块数据是 varint 流而不是位打包，比 litematic 还简单一点。

**结论：投影文件优先。** 三种输入现在都能吃：世界存档（目录或 `.zip`）、`.litematic`、`.schem`。

## 二、免费候选清单

放弃了 PMC 那两款——一款依赖 Ars Imperii 材质包，一款结尾推 Patreon。下面是**免费、无需注册**能拿到文件的，
按「拿来就能用」的程度排序。全部来自两个站点：

- **MineSchematic**（mineschematic.com）— 免费社区站，每条都同时提供 `.litematic` / `.schematic` / `.schem` / `.nbt` 四种格式
- **BuildSchematics**（buildschematics.com）— 免费，只给 `.schem`

| # | 作品 | 尺寸 (x×y×z) | 方块数 | 版本 | 站点 |
|---|---|---|---|---|---|
| 1 | **1v1 Garden Arena** — skyblocktc | **121 × 63 × 112** | 79,235 | 1.16.5 起，标称支持到最新 | BuildSchematics |
| 2 | **Gamarudo's Gladiator Arena** | **139 × 32 × 139** | 84,696 | 未标注（Java & Bedrock） | MineSchematic |
| 3 | battle arena — RougeRex | 64 × 32 × 48 | 10,746 | 1.21.10 | MineSchematic |
| 4 | PvP Castle | 45 × 18 × 49 | 11,405 | 1.21.11 | MineSchematic |
| 5 | Small PvP Arena | 42 × 16 × 50 | 5,325 | 26.2 | MineSchematic |
| 6 | Amphitheater | 37 × 17 × 31 | 3,026 | 26.1.2 | MineSchematic |
| 7 | Circular White Quartz Arena | 76 × 49 × 83 | 14,817 | 1.21.10（**Bedrock**） | MineSchematic |

链接：

1. https://buildschematics.com/presets/1v1-garden-arena
2. https://mineschematic.com/s/gamarudos-gladiator-arena-1be42365
3. https://mineschematic.com/s/battle-arena-98e06645
4. https://mineschematic.com/s/pvp-castle-72c9b228
5. https://mineschematic.com/s/small-pvp-arena-fb6d5af0
6. https://mineschematic.com/s/amphitheater-00bbe8d9
7. https://mineschematic.com/s/circular-white-quartz-arena-42c52417

### 我的判断

**1 号和 2 号是唯二真正「斗兽场尺度」的**，其余都是 40×50 上下的单体小场地，当副场地可以，当主场地撑不起来。

- **1 号 1v1 Garden Arena** 是我最推荐的：尺寸 121×63×112，81 种方块，作者本来就是当 1v1 场地做的。
  121 宽意味着中间战斗区大概能划到 60×60，外面全是看台和绿化——这正好就是我们要的
  「战斗区矩形 ≠ 包围盒」的形态。**但它是 1.16.5 时代的方块**，`grass_path`、`grass` 这类
  老 id 需要改名（工具里已经自动处理了，见 §四）。
- **2 号 Gladiator Arena** 是纯正的罗马斗兽场形制：139×139 圆/椭圆看台，84,696 方块，
  19 种方块（smooth stone slab / stone / polished andesite），457 KB。方块种类少反而是优点——
  少一分踩到新版本方块的风险。**但它没标版本**，需要下载后跑一次 `probe --palette` 看名字。

### 两个必须注意的坑

**坑一：版本。** 我们是 1.21.1。3/4/5/6/7 号都是 1.21.9 之后的版本导出的，
里面可能有 1.21.1 不存在的方块。这种情况下转换不会报错，**方块会在游戏里静默变成空气**——
对一个场地来说是能直接毁掉对局的失败方式。所以：优先选 1.21.1 及更早的，其余下载后必须先看调色板。

**坑二：Bedrock。** 7 号标的是 Bedrock Edition。方块名字虽然大体一致，但 Bedrock 的
blockstate 语义和 Java 不完全对齐，不建议用。

## 三、授权

这两个站都自称免费社区站，但**没有逐条的 License 字段**（MineSchematic 的每页底部只有
「Request takedown」，说明它是聚合性质）。这意味着：

- 自己服务器内部用：没问题。
- 打包进要分发的模组（`data/pvpocket/structure/*.nbt`）：**没把握**。

这一点和 PMC 的 All Rights Reserved 是同一个问题的不同表现。真到了要公开发布的阶段，
两条路：联系作者要授权，或者我按同样的构图参数化生成一套自己的（见 §六最后一条）。

## 四、转换工具：`scripts/world2struct.py`

三种输入，按扩展名自动分派：

```bash
# 投影文件（推荐）
python scripts/world2struct.py probe   下载的.litematic --palette
python scripts/world2struct.py convert 下载的.litematic \
       --out fabric-1.21.1/src/main/resources/data/pvpocket/structure/colosseum_a.nbt

# Sponge
python scripts/world2struct.py convert 下载的.schem --out .../colosseum_b.nbt

# 世界存档（目录或 .zip），要裁剪就加 --box
python scripts/world2struct.py convert 下载的.zip --box x1 y1 z1 x2 y2 z2 --out .../x.nbt
```

`probe` 里最有用的两行是：

```
height    y=64 has the most blocks (18432)
          that layer spans x 12..91  z 12..91  (80 x 80)
```

**最密的那一层几乎总是场地地面**，它的水平范围才是 `e.sizeX/e.sizeZ` 该填的数——不是包围盒，
包围盒把看台也算进去了。

实现上做了这些取舍：

- 只输出方块，**不带实体**，默认也**不带方块实体 NBT**。下载来的场地里可能有箱子、漏斗、
  命令方块、物品展示框，每一个要么是白送物品，要么是能在对局里执行命令的洞。
  `barrier / light / structure_void / structure_block / jigsaw / command_block` 一律剥掉。
- 跳过空气。粘模板之前先 `region.reset()` 清场，所以不需要用空气去「覆盖」。
- **方块改名表**：`grass_path→dirt_path`、`grass→short_grass`、`sign→oak_sign`、
  `wall_sign→oak_wall_sign`。1.16 时代的地图必然踩到前两个。转换时会打印改了多少个。
  这张表覆盖不了所有情况，更晚版本的方块还是要靠 `--palette` 人工看一眼。
- **输出必须 gzip**。原版结构文件是 gzip 的（`StructureTemplateManager` 用的是
  `NbtIo.readCompressed`），同一份内容不压缩 178 KB、压缩 13.7 KB，差 13 倍。
- 转换完会把自己写的文件重新读一遍校验 size / blocks / palette，对不上就返回非 0。
  一个读不回自己输出的转换器不算转换器。

### 位打包：litematic 和原版区块不是一回事

**这是整个转换里唯一真正会错的地方，而且我第一版就是错的。** 症状是场地贴上去完全错乱：
方块被摊成一层层的乱纹，整块整块地平移，看起来像「轴序搞反了」，实际上轴序是对的。

第一版用的解包是原版区块的规则：

```
每个 long 装 floor(64 / bits) 个值，尾部剩下的比特浪费掉
```

Litematica 不是这样。它是一条**连续比特流**：第 N 个值的起始位是 `N * bits`，可以跨 long
——从某个 long 的第 61 位开始，下一个 long 的第 0 位接着往下放。

两者只在 `bits` 整除 64（4 / 8 / 16 / 32）时**巧合一致**。四张图调色板 18～29 个方块，
`bits = ceil(log2(29)) = 5`，5 不整除 64，所以从第二个 long 起每一位都错开。前 12 个值
（`12 × 5 = 60 < 64`）还是对的，所以小样本、2 的幂调色板、甚至「前几个方块看起来正常」
都会让你以为没问题。

正确读法：

```python
mask  = (1 << bits) - 1
span  = (1 << 64) - 1
start = index * bits
first = start >> 6
off   = start & 63
value = (longs[first] & span) >> off
if off + bits > 64 and first + 1 < len(longs):     # 跨 long 续上高位
    value |= (longs[first + 1] & span) << (64 - off)
out.append(value & mask)
```

`world2struct.py` 的 `unpack_longs()` 现在就是这个，docstring 里明写了
`This is not the vanilla chunk packing`，免得以后有人「顺手复用」回去。

> 顺带一条：`bits` 的下限是 2，不是 1。调色板只有 2 个方块时仍然按 2 位存。

### 怎么验证「真的解析对了」

**不能拿自己的输出当参照。** 第一版校验脚本就是这么写的：把生成的 `.nbt` 读回来，和转换器
内部认为的内容比，80/80 通过——但两边错得一模一样，所以这个测试的价值是零。用户一进游戏
就看出场地是乱的。

现在有两道**互相独立**的关卡：

1. **litemapy 全量比对**（`litemapy` 是第三方 Python 实现，与我的代码没有任何共享逻辑）。
   逐格比四张图，输出 `wrong / missing / extra`：

   | 文件 | litemapy | 我的输出 | wrong | missing | extra |
   |---|---|---|---|---|---|
   | `pvp-castle` | 11405 | 11405 | 0 | 0 | 0 |
   | `battle-arena` | 10746 | 9046 | 0 | **1700** | 0 |
   | `circular-white-quartz-arena` | 14817 | 14817 | 0 | 0 | 0 |
   | `gamarudos-gladiator-arena` | 84857 | 84696 | 0 | **161** | 0 |

   `missing` 不是解析错误，是**主动剥掉的方块**：battle-arena 的 1700 个 `barrier`（剥掉
   名单里），gladiator 的 161 个火（浮空点不着，`drop_unstable()` 删掉）。`extra` 全 0
   说明没有凭空多出来的方块——这一项比 `missing` 重要得多。

2. **游戏内逐格回读**（`scripts/arena-verify.py`）。以 litemapy 为参照，在服务端真实
   `/place` 之后用 `/execute if block` 采样比对，一半采样点来自真实方块、一半来自"这里应该
   是空气「的位置——只采真实方块会漏掉」多放了东西"这类错误。

   ```
   arena_castle     45x18x49   11405 blocks   60/60 passed
   arena_mud        57x25x42    9046 blocks   60/60 passed
   arena_quartz     76x49x83   14817 blocks   60/60 passed
   arena_gladiator 139x32x139  84696 blocks   60/60 passed
   ```

### 测过什么（其余）

- **Sponge `.schem` 读取**：4×3×6、varint 流、48 个方块，**逐格完全一致**。
- **世界存档路径回归**：读开发服 1.21.1 主世界 37×11×37 范围，12044 个方块、15 种方块，正常。
- **NBT 读写往返**：原版 `pillager_outpost/watchtower.nbt`（15×21×15、40 种方块、4725 块）
  读进来再写出去，size / palette / blocks 全一致，压缩后 13726 字节（原版 14621）。
- **改名表**：`grass_path` / `grass` 转换后分别是 `dirt_path` / `short_grass`。
- **负尺寸**：`Size={-5,-4,-7}` 归一化正确（得到同样的 5×4×7 包围盒）。

### 下载来的地图不能直接贴

四张图里有两类方块会让「贴上去」和「文件里」不一致，转换时就得处理掉，否则会变成对局事故：

- **重力方块**。`black_concrete_powder` / `white_concrete_powder` 一贴就塌到地面，
  场地瞬间变形。`stabilise()` 把它们换成同色 `*_concrete`。
- **需要支撑的方块**。石英圆场有 188 个压力板下面是空的，贴上去当场弹掉，只剩 6 个；
  斗兽场有 253 处火立在铁栏杆/台阶上，点不着。`drop_unstable()` 直接删掉浮空的压力板、
  火把、火。删掉比留着好——留着的会在对局中途掉下来，掉下来的方块会被记成「玩家改动」。

`NON_SOLID_TOP` 这个元组就是判定依据：`_bars / _fence / _fence_gate / _wall / _trapdoor /
_pane / _chain` 的顶面不算支撑。

还有一张 `FALLBACK` 表处理 1.21.1 里已经不存在的方块（`waxed_exposed_copper_bars → iron_bars`、
`pale_oak_* → oak_*` 等 26 条）——不处理的话模板加载时那个方块会变成空气，墙上凭空多洞。

## 五、「大一点」到底贵在哪

你说不要被大小约束，方向没问题——现在的 48×48 是当初为了省资源随手定的，不是设计约束。
但有几处是真的会疼，需要先摊开：

| 项 | 现状 | 放大到 100×100 | 放大到 200×200 |
|---|---|---|---|
| 常驻区块 | 3×3 = 9 | 7×7 = 49 | 13×13 = 169 |
| 结构模板体积（gzip 后，估） | — | 1–3 MB | 5–15 MB |
| 每次 revert 的工作量 | 只写回玩家改过的方块 | 不变（见下） | 不变 |

关键在最后一行：**模板必须走「基线」而不是「变更集」**。现在 `ArenaRegion` 用
`LongOpenHashSet changed` 记录玩家动过的方块，上限 `MAX_CHANGES = 200_000`。
一个 100×100 的斗兽场粘上去就是几十万方块，如果这些方块进 `changed`，内存和上限立刻爆。
所以模板粘贴出来的方块要成为**新的基线**——`baseline(int y)` 变成 `baseline(x, y, z)`，
先查模板，查不到再回落到原版平坦生成。这是这次改造的核心，也是唯一必须改对的地方。

另外几条硬上限，现在都按实际地图调整过了：

- `e.sizeX / e.sizeZ` 上限从 128 提到 **160**（= `PITCH 192 - 2 × MARGIN 16`）。139 宽的
  斗兽场用 128 会被裁掉一圈看台，被裁掉的部分会被判成「场外」。
- `e.sizeY` 上限保持 **64**。139×32×139 那款只有 32 高，64 够用；再高的地图要先改这个值，
  同时确认 `PITCH` 的垂直空间够。
- 分区间距 `PITCH` 从 160 提到 **192**。两张 139 宽的图相距 160 会看台重叠。
- 原版结构方块最大 48×48×48。**所以超过 48 格的地图没法用结构方块抠出来**，
  这也正是要写转换工具的原因。

## 六、已经落地的部分

四张图已经在仓库里，选择方式在规则面板 E 组「场地模板」。

| 规则项 | 说明 |
|---|---|
| `e.template` | 平坦 / 斗兽场 / 泥砖场 / 石英圆场 / 城堡，五项循环。**索引是线格式的一部分，只能追加不能插入** |
| `ArenaMaps.ALL` | 地图常量表，尺寸写死在代码里，不依赖运行时读模板（面板要在客户端就能布局） |
| `ArenaMap.paste()` | `StructurePlacementData` 关掉实体、邻居更新、生物初始化 |

三个关键设计：

1. **地图尺寸接管场地尺寸**。选中地图后 `e.sizeX/Y/Z` 被地图包围盒覆盖，面板上会同步刷新。
   规则值仍然存着，只是对地图不生效——切回「平坦」时原来填的数字还在。
2. **粘贴出来的方块成为新基线**。`ArenaRegion.template` 存相对坐标 → 非空气方块，
   `baseline(x,y,z)` 先查表、查不到回落到平坦生成。72k 条记录 vs 58 万格，这是能跑的前提。
   只存非空气是有意的：`SURFACE_Y` 以上的平坦基线本来就是空气，回落天然正确。
3. **粘贴后回读世界，而不是信模板**。`capture()` 扫一遍整块体积，把世界里的实际内容当基线。
   这样「模板里有什么」和「贴完是什么」的差异（重力方块塌了、需要支撑的方块弹掉了）会被自动
   吸收，revert 回到的是**贴完之后的样子**，不是**文件里的样子**——后者回不去。

出生点也有两处要按地图走：

- `spawnX` 用 `centerX() ± max(6, sizeX/4)`，不是平坦场的「两端内缩 4」。斗兽场两端是看台，
  在那儿出生等于把人扔到座位上。
- `spawnY` 从 `SURFACE_Y` **向上**扫第一个非空气方块再 +1。有屋顶的场地不能从上往下扫，
  否则会生成在屋顶上，然后掉进场地中间。

### 一次真实对局的日志（斗兽场）

```
Pasted arena_gladiator at 26,26 (ok) in 242 ms
Leased arena-0[139x32x139 @26,26 arena_gladiator] to session a5553062
       (reset 65710 blocks, map arena_gladiator pasted true, 84696 template blocks, purged 30 entities)
ARENA_LEASED  region=arena-0 size=139x32x139 map=arena_gladiator mapBlocks=84696 origin=26,26
ROUND_STARTED round=1 spawns=61,4,95 | 129,4,95
ARENA_REVERTED region=arena-0 blocks=0 tracked=0
```

四张图各自的实测（`Leased` 行）：

| 地图 | 尺寸 | 模板方块 | 说明 |
|---|---|---|---|
| `arena_gladiator` | 139×32×139 | 84696 | 与文件完全一致 |
| `arena_quartz` | 76×49×83 | 14817 | 与文件完全一致 |
| `arena_mud` | 57×25×42 | 9045 | 文件 9046，差 1 块：放置阶段被游戏拒掉，不是解析问题 |
| `arena_castle` | 45×18×49 | 11405 | 与文件完全一致 |

### `ARENA_REVERTED blocks=0` 是怎么来的

这一行值得单独说，因为它是一个**看起来像 bug、实际上是设计缺陷**的问题。

修复前这局会打出 `blocks=2050`：整局没人碰过一个方块，却有 2050 处被判成「玩家改动」。
来源是火——斗兽场有 253 处火把，火的 `age` 属性每 tick 随机增长，每次增长都是一次
`setBlockState`，都会被 `onBlockChanged` 记一笔。

根因是变更判定用了 **BlockState 身份比较**：`newState == baseline(pos)`。BlockState 是按
属性组合 intern 的，`age` 变一次就是另一个实例，所以永远不等。同类问题不止火：水会改
`level`、耕地会掉 `moisture`、树叶会改 `distance`、红石会改 `power`、雪会加 `layers`。

改法是把比较降到**方块层**：`newState.getBlock() == baseline(pos).getBlock()`。

代价是：玩家用「同一种方块但转了个方向」替换时不会被记录（比如把朝东的楼梯换成朝西）。
这纯属外观，而且下一次租场地会整个清掉；相比之下属性漂移每局要白写几千次方块。

## 七、还没做的

1. **战斗区矩形和场地矩形分开**。现在是同一个：斗兽场的 139×139 包含看台，边界光幕画在
   看台外沿，玩家可以跑到看台上打。要做到「边界只圈中间的沙地」，得给 `ArenaMap` 加一组
   `pitX/pitZ/pitWidth/pitLength`，`contains()` 和 `spawnX/Z` 都改读它。
2. **授权**（见 §三）。四张图都是第三方作品，自己玩没问题，公开发布前要联系作者或换成
   程序化生成的替代品。
3. **`e.sizeY` 上限 64** 对更高的多层看台不够。
4. （兜底）如果授权卡死，就写一个**程序化斗兽场生成器**：参数化层数、半径、台阶高度、
   材质，在服务端直接按 `baseline` 算出来。没有外部依赖，没有授权问题，也不占仓库体积。
   缺点是不如手工地图好看。
