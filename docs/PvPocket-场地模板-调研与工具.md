# PvPocket 场地模板：地图来源、授权与转换工具

> 触发需求（六轮，按时间）：
> 1. 「调研一下网上有没有你能直接拿来用的 pvp 地图预设，比如那种斗兽场风格的」
> 2. 「不要被大小约束！大一点挺好啊」
> 3. 「不行，要钱，那不行，帮我找一些免费的。最好是投影文件吧，你是不是更好解析？」
> 4. 「四个文件都放进去了……把这几个地图添加到设置对局时新的预设类型」
> 5. 「场景完全是错乱的，根本没有正确解析文件！上网查询一下怎么正确解析 litematic」
> 6. 「考虑到法律因素，请移除这些地图。转化脚本请保留在仓库中……现在从 zzuf-maps 挑选合适的」
> 7. 「gs_earthborn 这个必须加入图，其他再选 4 张加进去……最好是有张类似角斗场的大图，
>    如果这个里面没有，尝试上网找可以 redistribute 的」
>
> 第 5 句推翻了当时「解析没问题」的判断，也推翻了当时那套 80/80 通过的校验——根因见 §四。
> 第 6 句推翻了第 4 句选的地图：那四张来自聚合站的图**没有明确授权**，而它们是要打进 jar 的。
> 第 7 句要的「斗兽场」最终没有下载到，是自己算出来的——原因见 §二.1。
>
> 这几句合起来是这篇文档的两条主线：**解析要对，授权要清。**

---

## 一、授权：为什么撤掉了最初那四张

最初四张（斗兽场 / 泥砖场 / 石英圆场 / 城堡）来自 MineSchematic 和 BuildSchematics。这两个站
自称免费社区站，但**没有逐条的 License 字段**，MineSchematic 每页底部只有一句「Request
takedown」——说明它是聚合性质，作者是否授权再分发并不清楚。

这件事的性质要说准：**自己服务器里用没问题，打进 jar 就是再分发。** 模组一发布，那四个
`.nbt` 就跟着进了别人的硬盘。没有授权的情况下，这是侵权，不是「合理使用」。

换到 [zzuf-maps](https://github.com/zzufx/zzuf-maps) 之后这个问题消失了，因为**每张图自带
`LICENSE.txt`**。仓库里 49 个 `LICENSE.txt` 绝大多数是 CC BY-SA 4.0，个别是 CC BY-NC-SA 4.0
（不可商用）。**只挑 CC BY-SA 的那批**，加图前先 `head -1 图目录/LICENSE.txt` 确认。

现在内置的八张 zzuf 图全部是 **CC BY-SA 4.0**，作者 zzuf。义务有三条，都写进了仓库根目录的
`THIRD-PARTY-NOTICES.md`：

1. **署名**原作者 zzuf，并给出来源链接；
2. **声明改动**（我们做了格式转换、剥离目标物、替换 1.21.1 不存在的方块等六项）；
3. **相同方式共享**：`data/pvpocket/structure/*.nbt` 这几个文件本身仍是 CC BY-SA 4.0，
   不随模组代码的许可证走。

第 3 条要特别说清楚：这个义务**只挂在美术资源上，不传染 Java 源码**。模组代码的许可证可以
和它们不同，只要把这几个 `.nbt` 单独标注出来即可。

> 结论：以后往 `ArenaMaps` 里加图之前，先看 `LICENSE.txt`。没有明确授权的图，只能本地用，
> 不能进仓库。

## 二、现在内置的九张

选择标准：单场 1v1 能打、封闭或半封闭、尺寸在 `160 × 64 × 160` 以内。**「源世界是 1.21 系」
这条限制已经不存在了**——1.8 的世界存档现在能读，见 §四.1，八张里其实只有游乐屋是 1.21。

| 规则面板显示 | 模板 id | 尺寸 (x×y×z) | 方块数 | 形态 | 源版本 |
|---|---|---|---|---|---|
| 游乐屋 | `arena_fun_house` | 82 × 28 × 57 | 38,355 | 封闭室内竞技场，石英，四面有墙 | 1.21 |
| 试炼密室 | `arena_trial_chambers` | 47 × 29 × 95 | 113,899 | 凝灰岩洞窟，一条曲折长廊 | 1.21 |
| 蓟桥 | `arena_thistle` | 102 × 44 × 99 | 24,188 | 两端基地 + 中间桥，正对双出生点 | 1.21 |
| 浮岛 | `arena_earthborn` | 45 × 34 × 60 | 7,045 | 悬空圆盘岛，陶瓦镶边，边缘之外是空的 | 1.8 |
| 阿瓦隆 | `arena_avalon` | 85 × 32 × 79 | 11,042 | 两座基地隔空对望，左右镜像 | 1.8 |
| 暗星云 | `arena_dark_nebula` | 87 × 43 × 79 | 14,509 | 封闭空间站，两层走廊，中间一个洞 | 1.8 |
| 枯木镇 | `arena_deadwood` | 88 × 36 × 87 | 60,005 | 西部小镇街道，两侧店铺夹着一条路 | 1.8 |
| 斗兽场 | `arena_colosseum` | 147 × 29 × 119 | 109,586 | **自己生成的**椭圆竞技场，十排看台 | — |

八张 zzuf 图的形态是刻意错开的：围起来的房间、一条线、两端加桥、悬空圆盘、镜像双基地、
封闭空间站、小镇街道。边界光幕在这些形态下的观感完全不同，留着方便以后调 `e.boundary` 的表现。
斗兽场则是唯一一张「四周真的有一圈高墙」的图。

体积合计 1.06 MB（gzip 后）。其中斗兽场独占 291 KB，是九张里最大的一张——它也是最规整的一张，
同一段高度上整圈都是同一种方块，gzip 压得其实不错，纯方块数 109k 换 291 KB。

### 2.1 斗兽场：为什么是生成的，不是下载的

第 1 句需求里那个「斗兽场风格」最后没能下载到，这不是没找，是找过之后确认找不到可再分发的：

| 找过的地方 | 结果 |
|---|---|
| zzuf-maps（12 个目录、约 47 张） | 桥、洞窟、小镇、空间站都有，**没有环形看台**。最大的是 `dtcm/armature` 171 宽（超限）和 `bridge/double_cross` 149 宽（双桥，形态和蓟桥重复） |
| OvercastCommunity/PublicMaps | PGM 社区最大的地图库，但**只有 XML 和缩略图，没有世界存档**；授权也是逐图声明，不是整库统一 |
| Planet Minecraft / 聚合站 | 默认 All Rights Reserved，没有 License 字段 |
| mc-schematics 之类「免费」站 | 站点说免费下载，但**没有授权条款**，免费 ≠ 可再分发 |

也就是说，唯一一张形态对、授权清、尺寸可控的做法，是自己算一张。`scripts/colosseum.py` 就是
这件事的产物——**同心椭圆套一个循环**，比下载更省事，而且尺寸是设计出来的，不是裁出来的：

```bash
python scripts/colosseum.py \
    --out fabric-1.21.1/src/main/resources/data/pvpocket/structure/arena_colosseum.nbt
```

```
size      147 x 29 x 119  (x, y, z)
blocks    109586
wrote     .../arena_colosseum.nbt (291.2 KB, gzipped)
verify    size=(147, 29, 119) blocks=109586 -> OK
```

结构（y 相对模板底面）：

```
y=-1       砂岩地基
y= 0       沙地
y= 1.. 3   竞技场边缘的矮墙（podium），X 轴两端各开一个 3 宽 3 高的门，门后是铁栏杆
y= 4..13   十排看台，每排升 1 格，踏面深浅交替两色
y=13..27   外墙，两道出挑的檐口 + 48 根壁柱
y=27       墙顶
```

这张图**没有第三方授权义务**——它不属于 zzuf，也不来自任何下载站，是这个仓库自己产出的，
所以 `THIRD-PARTY-NOTICES.md` 里单独列一节说明「这不是第三方美术资源」。

### 实测

离线交叉校验（以 anvil-parser2 / litemapy 独立读取源文件，逐格比对）：

```
arena_fun_house      82x28x57    38355 blocks   wrong=0 missing=0 extra=0
arena_trial_chambers 47x29x95   113899 blocks   wrong=0 missing=0 extra=0
arena_thistle       102x44x99    24188 blocks   wrong=0 missing=0 extra=0
arena_earthborn      45x34x60     7045 blocks   wrong=0 missing=0 extra=0
arena_avalon         85x32x79    11042 blocks   wrong=0 missing=0 extra=0
arena_dark_nebula    87x43x79    14509 blocks   wrong=0 missing=0 extra=0
arena_deadwood       88x36x87    60005 blocks   wrong=0 missing=0 extra=0
```

斗兽场没有源文件可比，它的校验只有生成器自带的那一步：写出去再读回来，比对 `size` 和方块数。

游戏内 `/place template` 后回读采样（每张 60 个点，一半来自真实方块、一半来自应为空气处）：

```
arena_fun_house      60/60 passed
arena_trial_chambers 60/60 passed
arena_thistle        60/60 passed
```

后五张**没有做游戏内采样**——按需求跳过了实机测试。风险集中在两处：1.8 地图的方块名可能
有 1.21.1 不认的（转换期会打印 `mapped` / `dropped`，都看过了），以及出生点落点靠
`resolvePads` 的环形搜索兜底。这两处如果出问题，现象是「玩家卡在方块里」或「某面墙缺一块」，
不会静默。

再走一遍完整的租场地链路（试炼密室），确认模板方块数与粘贴、出生点、revert 都对：

```
Leased arena-0[47x29x95 @72,48 arena_trial_chambers]  113899 template blocks
ROUND_STARTED spawns=95,14,72 | 95,14,118
ARENA_REVERTED blocks=0 tracked=0
```

## 三、自己转换一张图

这一节是给「想用自己的地图」的人看的。整条链路不需要打开游戏，也不需要结构方块
（原版结构方块上限 48³，而 1v1 场地普遍超过这个尺寸，所以它根本走不通）。

### 3.1 准备

```bash
python -m pip install nbtlib litemapy anvil-parser2
```

- `nbtlib` — 读写 NBT（结构模板、litematic、schem 都是 NBT）
- `litemapy` — **第三方** litematic 实现，只在验证时用，转换本身不依赖它
- `anvil-parser2` — **第三方** `.mca` 读取，同上

把下载到的文件丢进 `fabric-1.21.1/arena-src/`。这个目录在 `.gitignore` 里——**源素材不进仓库**，
只有转换产物进。

### 3.2 先 probe，别急着转

```bash
python scripts/world2struct.py probe fabric-1.21.1/arena-src/你的图.litematic --palette
python scripts/world2struct.py probe fabric-1.21.1/arena-src/你的世界存档/
```

输出里要看的四行：

```
bbox      x -51..30  y 53..80  z 85..141
size      82 x 28 x 57  (x, y, z)
blocks    38355 non-air
palette   34 distinct block names
height    y=65 has the most blocks (4254)
```

**判断能不能用：**

| 看什么 | 门槛 |
|---|---|
| `size` | 三轴都要 ≤ `160 × 64 × 160`（见 §七）。超了就先 `--box` 裁，或者放弃 |
| `blocks` | 太低说明只是个平台（几千块），太高说明密度过大（十几万块以上要留意粘贴耗时） |
| `palette` | 用 `--palette` 看有没有 1.21.1 不认识的方块名 |
| `height` | 最密那层通常是地面，看它离 `bbox` 底部多远，决定出生点高度 |

### 3.3 转换

```bash
python scripts/world2struct.py convert fabric-1.21.1/arena-src/你的图.litematic \
    --out fabric-1.21.1/src/main/resources/data/pvpocket/structure/arena_你的图.nbt
```

世界存档、`.zip`、`.schem` 同理，按扩展名自动分派。世界存档要裁剪时加
`--box x1 y1 z1 x2 y2 z2`。

转换器会打印它做了哪些改动（改名、稳定化、丢弃），最后一行是自检：

```
wrote     .../arena_thistle.nbt (74.6 KB, gzipped)
verify    size=(102, 44, 99) blocks=24188 palette=245 -> OK
```

### 3.4 验证（这一步不要跳）

```bash
# 离线：以第三方读取器为参照，逐格比对。不需要服务端。
python scripts/arena-verify.py --source fabric-1.21.1/arena-src/你的图 \
                               --nbt fabric-1.21.1/src/main/resources/data/pvpocket/structure/arena_你的图.nbt
```

要看到 `wrong=0 missing=0 extra=0`。任何一项非 0 都不要往下走。

```bash
# 游戏内：真的 /place 一次，然后采样回读。需要服务端在跑。
python scripts/arena-verify.py --source fabric-1.21.1/arena-src/你的图 \
                               --template arena_你的图 --origin 2000 3 2000 --samples 40
```

`--origin` 挑一个远离所有场地分区的坐标（`PITCH` 是 192，4×4 网格占 0..768，所以 2000 之后
都是空的）。要看到 `60/60 passed`。

离线通过但游戏内不过，通常不是解析问题，而是**有方块贴上去就掉**——见 §四.4。

### 3.5 注册

编辑 `ArenaMaps.java`，加一个常量并把尺寸填成 probe 报的 `size`：

```java
public static final ArenaMap MY_MAP = new ArenaMap("arena_my_map", "我的图",
        PvPocket.id("arena_my_map"), 82, 28, 57);
```

然后追加进 `ALL`。**只能追加，不能插入、不能重排**——`e.template` 是按索引存进规则集和
journal 的，插一个进去会让所有历史规则集的第 N 项变成另一张图。撤图时留空位，不要压缩。

最后在 `THIRD-PARTY-NOTICES.md` 里补一行来源与授权，`./gradlew build` 一次。

### 3.6 常见坑

- **1.8 的世界存档要单独走一条路。** `level.dat` 里没有 `DataVersion` 就是 1.8 格式：区块里
  是 `Blocks`/`Data`/`Add` 三个字节数组加数字方块 ID，没有 `Palette`。现在支持了（见 §4.1），
  但**转换器能读不等于方块名都对**——`132:2` 这种在两张 ID 表里都缺的组合会退到 meta 0。
  转完一定要看 `mapped` 那行有没有陌生的映射。
- **更新版本的图会静默缺方块。** 1.21.9+ 导出的图里有 1.21.1 没有的方块，转换时走 `FALLBACK`
  表替换；表里没有的会在游戏里变成空气，墙上凭空多洞。用 `--palette` 先看。
- **会自己变状态的方块要丢掉。** 火会烧完、绊线被玩家走过就改 `attached`、红石线会重算连接
  形状——这些都会让场地和它自己的基准对不上，`revert` 会一直触发。`SELF_DESTRUCTING` 里已经
  处理了前两种。
- **Bedrock 版的图不要用。** blockstate 语义和 Java 不完全对齐。

## 四、转换工具的技术细节

### 4.1 三种输入，四种区块布局

| 输入 | 读取难点 | 现状 |
|---|---|---|
| 世界存档（目录或 `.zip`） | 8192 字节扇区表、每块压缩类型、1.18 前后 `Level` 嵌套 | 支持 |
| `.litematic` | 只有调色板 + 位打包，但**打包方式和区块不同**（见 4.2） | 支持 |
| `.schem` | 调色板是 `名字→索引`，方块数据是 varint 流 | 支持 |

世界存档内部其实有三种区块布局，`chunk_sections()` / `section_blocks()` 都按 key 去探：

| 版本 | section 列表在哪 | 方块怎么存 |
|---|---|---|
| 1.18+ | 根上的 `sections` | `block_states` + 调色板 |
| 1.13–1.17 | `Level` 下的 `sections` | `Palette` + `BlockStates` |
| 1.8 | `Level` 下的 `Sections`（大写） | `Blocks` / `Data` / `Add` 字节数组 + 数字 ID |

1.8 那条路是后来补的，起因是 zzuf-maps 里想用的图有七张是 1.8 存档，转换器直接报
`no non-air blocks found`。补的时候有两个坑：

1. **位序是 `index = y*256 + z*16 + x`**，即 `x = i & 15`、`z = (i >> 4) & 15`、`y = i >> 8`。
2. **`Add` 是每字节两个 4 位值**，低半字节是偶数索引，高半字节是奇数索引，用来把 ID 抬到
   12 位。写反了会让一部分方块跑到 256 以上，然后被当成空气静默丢掉。

数字 ID 到现代名字的映射用 `anvil.legacy.LEGACY_ID_MAP`（1631 条，`"id:meta"` 形式）。查不到
的组合退到 `"id:0"`，再查不到就跳过——`tripwire` 的 `132:2` 就是靠这个兜住的。

验证脚本也得跟着改：`read_source_world()` 原来只认 `chunk.data["sections"]`，遇到 1.8 就
读出零个方块，然后一路安静地报 `no non-air blocks found`。现在按两种 key 探，并且走
`OldBlock.convert()`——那是 anvil-parser2 自己的扁平化表，和转换器手写的那条路不是同一份代码，
这样才算真的交叉校验。

### 4.2 位打包：litematic 和原版区块不是一回事

**这是整个转换里唯一真正会错的地方，而且第一版就是错的。** 症状是场地贴上去完全错乱：
方块被摊成一层层的乱纹，整块整块地平移，看起来像「轴序搞反了」，实际上轴序是对的。

第一版用的解包是原版区块的规则：

```
每个 long 装 floor(64 / bits) 个值，尾部剩下的比特浪费掉
```

Litematica 不是这样。它是一条**连续比特流**：第 N 个值的起始位是 `N * bits`，可以跨 long
——从某个 long 的第 61 位开始，下一个 long 的第 0 位接着往下放。

两者只在 `bits` 整除 64（4 / 8 / 16 / 32）时**巧合一致**。真实地图的调色板大多在 18～29 个
方块，`bits = 5`，5 不整除 64，所以从第二个 long 起每一位都错开。前 12 个值
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

### 4.3 怎么验证「真的解析对了」

**不能拿自己的输出当参照。** 第一版校验脚本就是这么写的：把生成的 `.nbt` 读回来，和转换器
内部认为的内容比，80/80 通过——但两边错得一模一样，所以这个测试的价值是零。进游戏一眼就
看出场地是乱的。

现在参照物永远是**第三方实现**，绝不可能是自己的读取器：

| 源格式 | 参照 |
|---|---|
| `.litematic` | `litemapy` |
| 世界存档 | `anvil-parser2` |
| `.schem` | 无（脚本直接拒绝，而不是假装验证过） |

两道关卡：

1. **离线逐格比对**（`--nbt`）。全量，不是采样。输出 `wrong / missing / extra`。
   `extra` 这一项最重要——它说明模板里多出了源里没有的方块，那是真正的解析错误。
   `missing` 可能是主动剥离的（barrier、浮空火把），要能逐条解释。
2. **游戏内采样回读**（`--template`）。真的 `/place` 一次，再用 `/execute if block` 抽 60 个点。
   一半采样点来自真实方块，一半来自**应为空气**的位置——只采真实方块会漏掉「整体位移」这类
   错误，因为位移之后每个方块都还在，只是位置不对。

被撤掉的那四张 litematic 的比对记录，留作 `.litematic` 读取器正确性的证据：

| 文件 | litemapy | 转换输出 | wrong | missing | extra |
|---|---|---|---|---|---|
| `pvp-castle` | 11405 | 11405 | 0 | 0 | 0 |
| `battle-arena` | 10746 | 9046 | 0 | 1700 | 0 |
| `circular-white-quartz-arena` | 14817 | 14817 | 0 | 0 | 0 |
| `gamarudos-gladiator-arena` | 84857 | 84696 | 0 | 161 | 0 |

`missing` 是主动剥掉的：battle-arena 的 1700 个 `barrier`，gladiator 的 161 处点不着的火。

### 4.4 下载来的地图不能直接贴

转换时必须处理掉三类方块，否则「文件里」和「世界里」会不一致，而这会变成对局事故。

**一、重力方块。** `*_concrete_powder` 一贴就塌到地面，场地瞬间变形。`stabilise()` 换成同色
`*_concrete`。fun_house 里有 308 个。

**二、需要支撑的方块。** 贴上去当场弹掉。`drop_unstable()` 删掉它们：

| 类型 | 判定 | 实例 |
|---|---|---|
| 火 | 下方顶面必须是实心（`NON_SOLID_TOP` 排除栏杆/栅栏/墙/活板门/玻璃板/锁链） | 斗兽场 253 处火立在铁栏杆上 |
| 压力板 / 火把 / 红石火把 | 下方必须有方块 | 石英场 188 个浮空压力板 |
| 红石线 / 铁轨 / 睡莲 | 下方必须有方块 | **蓟桥 254 条红石线里 220 条下面全是空气** |

蓟桥这一条是实测抓出来的：转换完贴上去，60 个采样点里 2 个失败，都是 `redstone_wire`。
追下去发现源世界里它们就是悬空的——PGM 加载世界时不触发方块更新所以没事，但 `/place`
会触发，于是当场脱落。所以正确的做法是**在转换阶段就丢掉**，而不是留到粘贴时听天由命。

删掉比留着好，理由不只是好看：一个在对局中途掉下来的方块会被 `onBlockChanged` 记成
「玩家改动」，然后 revert 把它放回去，然后再掉一次。

**三、1.21.1 不存在的方块。** 走 `FALLBACK` 表替换（`pale_oak_* → oak_*`、
`waxed_exposed_copper_bars → iron_bars` 等 26 条）。表里没有的，模板加载时会变成空气。

## 五、投影文件还是世界存档更好解析

**`.litematic` 明显更省事**，因为它少三层：没有扇区表、没有逐块的压缩类型、没有 1.18 前后的
`Level` 嵌套。剩下的只有调色板 + 位打包——而这一层恰恰是坑（§4.2）。

**世界存档的优势在素材。** PGM 地图库（zzuf-maps 这类）都是世界存档形态，质量和数量都远好于
免费投影站；代价是要处理 `.mca` 那一整套。两条路现在都通，选哪个取决于素材从哪来。

`.schem` 也支持：调色板是 `名字→索引` 的 compound，方块数据是 varint 流，比 litematic 还简单。

## 六、落地形态

选择方式在规则面板 E 组「场地模板」，五项循环：平坦 / 游乐屋 / 试炼密室 / 蓟桥。

| 规则项 | 说明 |
|---|---|
| `e.template` | **索引是线格式的一部分，只能追加不能插入** |
| `ArenaMaps.ALL` | 地图常量表，尺寸写死在代码里，不依赖运行时读模板（面板要在客户端就能布局） |
| `ArenaMap.paste()` | `StructurePlacementData` 关掉实体、邻居更新、生物初始化 |

三个关键设计：

1. **地图尺寸接管场地尺寸**。选中地图后 `e.sizeX/Y/Z` 被地图包围盒覆盖，面板上会同步刷新。
   规则值仍然存着，只是对地图不生效——切回「平坦」时原来填的数字还在。
2. **粘贴出来的方块成为新基线**。`ArenaRegion.template` 存相对坐标 → 非空气方块，
   `baseline(x,y,z)` 先查表、查不到回落到平坦生成。11 万条记录 vs 130 万格，这是能跑的前提。
   只存非空气是有意的：`SURFACE_Y` 以上的平坦基线本来就是空气，回落天然正确。
3. **粘贴后回读世界，而不是信模板**。`capture()` 扫一遍整块体积，把世界里的实际内容当基线。
   这样「模板里有什么」和「贴完是什么」的差异会被自动吸收，revert 回到的是**贴完之后的样子**，
   不是**文件里的样子**——后者回不去。

出生点有三个问题要处理，前两个是形态问题，第三个是「猜的地方站不住」：

**一、两侧沿哪个轴分开。** `ArenaMap.alongZ` 记录这件事。游乐屋的源 XML 把两队放在
`x=-48..28, z=114`，所以沿 X；试炼密室是 `x=-30, z=-162..-102`，蓟桥是 `x=0, z=-82..10`，
**这两张都沿 Z**。如果一律按 X 摆，后两张会把人并排放进走廊中段——不是「稍微偏一点」，
是直接站错地方。

**二、朝向。** 沿 X 时 yaw 是 -90 / 90，沿 Z 时是 0 / 180。两者都要让对方在视野正前方。

**三、名义点只是搜索起点。** 一开始的实现是「中心 ± 跨度的 1/4」，对着三张图实测：

| 图 | 名义落点 | 实际情况 |
|---|---|---|
| 游乐屋 | 中心线两侧 | 站在方块里，头顶没有净空 |
| 试炼密室 | 中心线两侧 | 同上，且离地面 11 格全是岩石 |
| 蓟桥 | 中心线两侧 | **踩在水面上** |

所以 `resolvePads()` 把名义点当起点，向外一圈圈搜「非流体地面 + 头顶两格空气」的列，取第一个
命中。搜到的落点：

| 图 | spawn0 | spawn1 | 踩到的方块 |
|---|---|---|---|
| 游乐屋 | (21, 13, 28) | (61, 13, 28) | `orange_concrete` / `purple_concrete` |
| 试炼密室 | (23, 11, 24) | (23, 11, 70) | `waxed_copper_block` |
| 蓟桥 | (53, 18, 26) | (53, 17, 71) | `polished_deepslate_slab` / `_stairs` |
| 浮岛 | (11, 5, 30) | (33, 5, 30) | `coarse_dirt` / `dirt` |
| 阿瓦隆 | (39, 27, 6) | (39, 27, 72) | `smooth_stone_slab` |
| 暗星云 | (43, 10, 20) | (43, 10, 58) | `quartz_stairs` |
| 枯木镇 | (22, 4, 43) | (66, 4, 43) | `red_sand` |
| 斗兽场 | (37, 2, 59) | (109, 2, 59) | `sand` |

（坐标是模板坐标，y 相对模板底面，所以等于世界坐标的 y 减 3。）

游乐屋那两个落点正好是地图自己的橙队/紫队平台——说明搜索找到的是作者原本设计的出生区，
不是随便找了个空地。蓟桥偏移了 3～4 格才躲开水。

**搜索半径是 24，不是 12。** 阿瓦隆暴露了这一点：它深 79，两座基地在 `z=±35`，名义点是
`z=±19`，正对中间那片虚空。半径 12 够不到基地，`findStand` 返回「没找到」，`resolvePads`
回落到名义点，两个人都被放到**地图底下那块裸石头地面**上。提到 24 之后落到 `z=6` / `z=72`
——关于中心 39 对称，正好是两座基地的台阶。其余七张都在半径 0 或 1 就命中，改半径不影响它们。

实机日志（试炼密室，`alongZ=true`）：

```
Pasted arena_trial_chambers at 72,48 (ok) in 523 ms
Leased arena-0[47x29x95 @72,48 arena_trial_chambers]
       (reset 10863 blocks, map arena_trial_chambers pasted true, 113899 template blocks)
ROUND_STARTED round=1 spawns=95,14,72 | 95,14,118
ARENA_REVERTED region=arena-0 blocks=0 tracked=0
```

场地原点 `72,48`，所以两个出生点的相对坐标是 `(23,11,24)` 和 `(23,11,70)`——与上表的离线
模拟逐格一致。

### `ARENA_REVERTED blocks=0` 是怎么来的

这一行值得单独说，因为它是一个**看起来像 bug、实际上是设计缺陷**的问题。

修复前有一局打出 `blocks=2050`：整局没人碰过一个方块，却有 2050 处被判成「玩家改动」。
来源是火——那张图有 253 处火把，火的 `age` 属性每 tick 随机增长，每次增长都是一次
`setBlockState`，都会被 `onBlockChanged` 记一笔。

根因是变更判定用了 **BlockState 身份比较**：`newState == baseline(pos)`。BlockState 是按
属性组合 intern 的，`age` 变一次就是另一个实例，所以永远不等。同类问题不止火：水会改
`level`、耕地会掉 `moisture`、树叶会改 `distance`、红石会改 `power`、雪会加 `layers`。

改法是把比较降到**方块层**：`newState.getBlock() == baseline(pos).getBlock()`。

代价是：玩家用「同一种方块但转了个方向」替换时不会被记录（比如把朝东的楼梯换成朝西）。
这纯属外观，而且下一次租场地会整个清掉；相比之下属性漂移每局要白写几千次方块。

## 七、大小与性能上限

| 项 | 现在 | 说明 |
|---|---|---|
| `e.sizeX / e.sizeZ` 上限 | 160 | `= PITCH 192 - 2 × MARGIN 16` |
| `e.sizeY` 上限 | 64 | 目前最高的图是 44（蓟桥） |
| 分区间距 `PITCH` | 192 | 按 160 的最大场地留 32 格空隙，不按当前最大的图算 |
| 网格 | 4 × 4 = 16 场 | 决斗维度 |

粘贴耗时（开发机，`/place template` 一次）：

| 图 | 方块 | 耗时 |
|---|---|---|
| 游乐屋 | 38,355 | 约 100 ms |
| 试炼密室 | 113,899 | 约 250 ms |
| 蓟桥 | 24,188 | 约 120 ms |

**revert 的工作量不随地图变大**——这是基线设计的全部意义。`changed` 只记玩家动过的方块，
一个 11 万方块的场地在无人操作时是 0 条。

## 八、还没做的

1. **边界光幕仍然画在场地外沿，不是战斗区外沿**。蓟桥的 102×99 把两端基地也算进去，
   光幕就落在基地外面；斗兽场更明显，147×119 是整圈外墙，而真正打架的沙地只有 100×80。
   要做到「边界只圈中间的战斗区」，得给 `ArenaMap` 加一组 `pitX/pitZ/pitWidth/pitLength`，
   `contains()` 改读它。出生点已经不用改了——现在靠 `resolvePads()` 搜索落脚点，不依赖这个矩形。
2. **有流体的图要盯着**。蓟桥 3818 个水方块、浮岛 12 个水 + 28 个岩浆，边界之外都没有容器。
   目前靠 `setUpdateNeighbors(false)` 压住，但水流是独立 tick 的，长时间挂着可能渗出边界。
   真出问题就把水换成冰或者填掉。
3. **后五张没有实机测**。需求明确说了「不用测试」，所以浮岛/阿瓦隆/暗星云/枯木镇/斗兽场只有
   离线交叉校验，没走 `/place` 回读。斗兽场更只有自校验，它没有源文件。
   替代手段是把 `nominalSpawnX/Z → findStand → standY` 这一串纯函数在 Python 里照抄一遍跑，
   八张图都确认落在地图自己的方块上（见 §六）。这条模拟抓到了阿瓦隆的落点问题，但它证明不了
   粘贴本身、方块名解析、以及「贴上去会不会掉」——那三件事只有实机能验。
4. **`e.sizeY` 上限 64** 对更高的多层结构不够。
5. **`.schem` 没有独立验证**。转换能读，但没有第三方实现做参照，所以 `arena-verify.py`
   对 `.schem` 直接拒绝而不是假装通过。
