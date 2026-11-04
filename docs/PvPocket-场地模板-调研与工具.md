# PvPocket 场地模板：外部地图调研与转换工具

> 触发需求：「调研一下网上有没有你能直接拿来用的 pvp 地图预设，比如那种斗兽场风格的」
> 后续指定：「这两款很好啊，不要被大小约束！大一点挺好啊」
> —— Small Multi-Tiered Colosseum [UNDECORATED] / The Colosseum in Minecraft

## 一、先说结论

**「拿来就用」目前不成立，但差的东西不多。** 差的是两步：把世界存档转成结构模板（工具已写好，见 §四），
以及把模板系统接进场地（代码未写，见 §五）。地图本身反而最容易——你下载下来丢给我就行。

## 二、两款候选：我核实到哪一步

PMC 现在挂在 Cloudflare 后面，直接抓取只能拿到正文，**拿不到下载框**（里面才有文件格式、
体积和 License 字段）。所以下面「已确认」的都是页面正文里读到的，「待确认」的必须你点进去看。

### 2.1 Small Multi-Tiered Colosseum [UNDECORATED] — Maxxii

- 已确认：描述为「small Roman-Inspired Colosseum built in the style of a Multi-Tiered Stadium」，
  明确说明不追求还原真实罗马斗兽场，只是用罗马风格做的多层看台。
- 已确认（**这条要注意**）：页面写着「Designed to be used with the Ars Imperii resource pack」。
  也就是说它的观感依赖作者指定的材质包，**纯原版下会明显不一样**。我们不可能把别人的材质包
  塞进模组，所以要么接受原版观感，要么自己按同样思路重刷一遍方块。
- 待确认：实际尺寸、下载格式、License。

### 2.2 The Colosseum in Minecraft — Kay_Vi

- 已确认：描述为「a stylish arena with a beautiful exterior and thoughtful decor… an atmospheric
  place for battles, events, or simply admiring the architecture」。
- 已确认（**这条要注意**）：结尾是「Join my Patreon to get access to my buildings and exclusive
  maps!」——作者把建筑当付费内容经营，免费下载的可能只是展示版，或者根本走 Patreon。
- 待确认：同上。

### 2.3 授权这件事必须说清楚

Planet Minecraft 的默认版权是 **All Rights Reserved**，作者可以主动改成 CC BY / CC0，但**默认不是**。
ARR 的作品：

- 自己服务器内部用：可以（属于私人使用）。
- 打包进要分发的模组：**不可以**。哪怕只是作为 `data/pvpocket/structure/*.nbt` 附带。

所以要分发就得走「联系作者要授权」或者「换 CC0/CC-BY 的作品」两条路之一。
上一轮我列的那批里没有一款能确认是 CC 协议，这不是我漏找，是 PMC 上斗兽场类作品基本都不开放。

## 三、「大一点」到底贵在哪

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

- `e.sizeY` 上限现在是 64。多层看台的斗兽场可能超过，要放开。
- 原版结构方块最大 48×48×48。**所以超过 48 格的地图没法用结构方块抠出来**，
  这也正是要写转换工具的原因。

## 四、转换工具：`scripts/world2struct.py`

已经写好并测过。它直接读下载来的世界存档（`.zip` 或解压后的目录），绕开结构方块的 48³ 限制。

```bash
# 1) 先体检：报包围盒、方块数、调色板、以及「最密的那一层」的水平范围
python scripts/world2struct.py probe 下载的.zip

# 2) 裁剪到你真正要的部分，再转
python scripts/world2struct.py convert 下载的.zip --box x1 y1 z1 x2 y2 z2 \
       --out fabric-1.21.1/src/main/resources/data/pvpocket/structure/colosseum_a.nbt
```

`probe` 里最有用的两行是：

```
height    y=64 has the most blocks (18432)
          that layer spans x 12..91  z 12..91  (80 x 80)
```

**最密的那一层几乎总是场地地面**，它的水平范围才是「场地尺寸」该填的数——不是包围盒，
包围盒把看台也算进去了。

实现上做了这些取舍：

- 只输出方块，**不带实体**，默认也**不带方块实体 NBT**。下载来的场地里可能有箱子、漏斗、
  命令方块、物品展示框，每一个要么是白送物品，要么是能在对局里执行命令的洞。
  `barrier / light / structure_void / structure_block / jigsaw / command_block` 一律剥掉。
- 跳过空气。粘模板之前先 `region.reset()` 清场，所以不需要用空气去「覆盖」。
- **输出必须 gzip**。原版结构文件是 gzip 的（`StructureTemplateManager` 用的是
  `NbtIo.readCompressed`），同一份内容不压缩 178 KB、压缩 13.7 KB，差 13 倍。
- 转换完会把自己写的文件重新读一遍校验 size / blocks / palette，对不上就返回非 0。
  一个读不回自己输出的转换器不算转换器。

### 测过什么

- **NBT 读写往返**：拿原版 `pillager_outpost/watchtower.nbt`（15×21×15、40 种方块、4725 个方块）
  读进来再写出去，size / palette / blocks 全部一致，块级数据逐条相同。压缩后 13726 字节，
  原版 14621——比原版还小一点，因为调色板排序不同。
- **真实世界读取**：读我们开发服的 1.21.1 主世界，49×49×37 范围，59709 个方块，
  正确识别出 stone / dirt / diorite / snow / ice / spruce_leaves 等 23 种方块，13.8 秒。
- **zip 直读**：把 region 打进 `MyArena.zip` 直接 probe，正常。
- **没测过**：转换出来的文件真的被 Minecraft 加载并 `/place`。这一步要等有真实地图、
  或者至少起一次服务端才能验。

## 五、接下来要写的代码（还没动）

1. `ArenaRegion.baseline(int y)` → `baseline(int x, int y, int z)`，带模板回落。
2. `e.template` 从「平坦」单选项扩成模板列表，按 `data/pvpocket/structure/` 里的文件生成。
3. 模板粘贴层：租场地时清场 → 粘贴 → 记录「这块是模板的」范围。
4. **场地矩形和战斗区矩形分开**。斗兽场的包围盒包含看台，但边界和出生点必须落在
   中间的沙地上，否则玩家会在看台上打，还能从看台顶上翻出去。
   `e.sizeX/e.sizeZ` 应该描述**战斗区**，看台是它外面的装饰余量——这会同时影响分区网格的间距，
   相邻两场的看台不能重叠。
5. `e.sizeY` 上限放开到 64 以上。

## 六、需要你做的

把两个 zip 下载下来，放到 `fabric-1.21.1/arena-src/`（目录我建好了，已在 .gitignore 里），
我跑 `probe` 出真实尺寸和地面范围，再决定 `e.sizeX/e.sizeZ` 和分区间距。

同时麻烦看一眼下载框上方那行 **License**：如果是 All Rights Reserved，我们就只能在你自己服务器用，
不能随模组分发；要分发得联系作者，或者我按同样的构图参数化生成一套自己的。
