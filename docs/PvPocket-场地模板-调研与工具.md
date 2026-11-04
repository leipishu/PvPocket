# PvPocket 场地模板：免费地图调研与转换工具

> 触发需求：「调研一下网上有没有你能直接拿来用的 pvp 地图预设，比如那种斗兽场风格的」
> 一轮之后：「不要被大小约束！大一点挺好啊」
> 二轮之后：「不行，要钱，那不行，帮我找一些免费的。最好是投影文件吧，你是不是更好解析？」

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

**剩下的一层一模一样**：调色板 + 位打包。所以 `.litematic` 不是「另一种格式」，它就是世界存档最里面那层被单独拎出来了。`world2struct.py` 里 `section_blocks()` 的位解包逻辑可以直接复用——事实上一行没改。

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

### 测过什么

- **litematic 读取**：构造 5×4×7、4 色调色板、105 个方块的样本，读回来**逐格逐名字完全一致**。
  负尺寸 `Size={-5,-4,-7}` 归一化正确（得到同样的 5×4×7 包围盒）。
- **Sponge `.schem` 读取**：4×3×6、varint 流、48 个方块，**逐格完全一致**。
- **世界存档路径回归**：读开发服 1.21.1 主世界 37×11×37 范围，12044 个方块、15 种方块，正常。
- **NBT 读写往返**：原版 `pillager_outpost/watchtower.nbt`（15×21×15、40 种方块、4725 块）
  读进来再写出去，size / palette / blocks 全一致，压缩后 13726 字节（原版 14621）。
- **改名表**：`grass_path` / `grass` 转换后分别是 `dirt_path` / `short_grass`。
- **没测过**：转换出来的文件真的被 Minecraft 加载并 `/place`。**这一条必须有一次实测**，
  尤其要确认 litematic 的轴序 `(y*sizeZ+z)*sizeX+x`——这个公式和原版区块的
  `y*256+z*16+x` 在 16×16 时完全一致，理论上是同一套，但没在游戏里看过就是没看过。

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

另外两条硬上限：

- `e.sizeY` 上限现在是 64。139×32×139 那款只有 32 高没问题，但多层看台可能更高，要放开。
- 原版结构方块最大 48×48×48。**所以超过 48 格的地图没法用结构方块抠出来**，
  这也正是要写转换工具的原因。

## 六、接下来要写的代码（还没动）

1. `ArenaRegion.baseline(int y)` → `baseline(int x, int y, int z)`，带模板回落。
2. `e.template` 从「平坦」单选项扩成模板列表，按 `data/pvpocket/structure/` 里的文件生成。
3. 模板粘贴层：租场地时清场 → 粘贴 → 记录「这块是模板的」范围。
4. **场地矩形和战斗区矩形分开**。斗兽场的包围盒包含看台，但边界和出生点必须落在
   中间的沙地上，否则玩家会在看台上打，还能从看台顶上翻出去。
   `e.sizeX/e.sizeZ` 应该描述**战斗区**，看台是它外面的装饰余量——这会同时影响分区网格的间距，
   相邻两场的看台不能重叠。
5. `e.sizeY` 上限放开到 64 以上。
6. （兜底）如果授权这一关卡死，就写一个**程序化斗兽场生成器**：参数化层数、半径、台阶高度、
   材质，在服务端直接按 `baseline` 算出来。没有外部依赖，没有授权问题，也不占仓库体积。
   缺点是不如手工地图好看。

## 七、需要你做的

1. 从 §二 里挑 1～2 个，点进链接下载。**MineSchematic 的下载按钮要过一次 CAPTCHA**（不用注册），
   BuildSchematics 是直接给文件的。
2. 下载到的文件丢到 `fabric-1.21.1/arena-src/`（目录已建好，已在 `.gitignore` 里）。
3. 告诉我文件名，我跑 `probe --palette` 出真实尺寸、地面范围、以及有没有 1.21.1 认不出的方块。
