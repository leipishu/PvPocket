# 地图与素材

> 规范文档 · 中文版。英文版：[`../en/06-maps.md`](../en/06-maps.md)

## 1. 内置地图

| 键 | 名称 | 尺寸 (X×Y×Z) | 来源 | 许可 |
| --- | --- | --- | --- | --- |
| `flat` | 平坦 | 规则决定 | 运行时生成 | 自有 |
| `arena_fun_house` | 游乐屋 | 82 × 28 × 57 | zzuf `maps/tdm/fun_house` | CC BY-SA 4.0 |
| `arena_earthborn` | 浮岛 | 45 × 34 × 60 | zzuf `maps/gs/gs_earthborn` | CC BY-SA 4.0 |
| `arena_dark_nebula` | 暗星云 | 87 × 43 × 79 | zzuf `maps/arcade/bn_dark_nebula` | CC BY-SA 4.0 |
| `arena_deadwood` | 枯木镇 | 88 × 36 × 87 | zzuf `maps/oitq/deadwood` | CC BY-SA 4.0 |
| `arena_colosseum` | 斗兽场 | 147 × 29 × 119 | `scripts/colosseum.py` 生成 | 自有 |

四张第三方地图改编自 [zzuf-maps](https://github.com/zzufx/zzuf-maps)。改编后的 `.nbt`
**仍然受 CC BY-SA 4.0 约束**，与模组代码的许可无关。把地图打进 jar 就是再分发，
必须随附 `THIRD-PARTY-NOTICES.md`。完整条款与改编清单见该文件。

斗兽场是唯一「战斗区小于包围盒」的地图：沙子战斗区 100×80，盒子 147×119，
内缩 23 / 19。边界光幕只圈战斗区，否则坐在看台上也会被判为「还在场内」。

**地图顺序是线格式的一部分。** `e.template` 存的是索引，重排会让所有已保存的规则集
指向另一张图。**只能追加，不能插入。** 曾经下架过三张图（试炼密室、蓟桥、阿瓦隆）
并把它们直接删掉而不是留空位——代价是那次改动之前保存的规则集会解析成别的图。
如果以后在正式版之后还要下架，**留一个空位**。

## 2. 素材目录

`fabric-1.21.1/arena-src/` 是本地素材目录（不进版本库），只保留能重建成品图的源：

```
arena-src/
└── _zzuf/                     zzuf-maps 的裁剪副本
    ├── README.md              上游说明（署名用）
    └── maps/
        ├── arcade/bn_dark_nebula/
        ├── gs/gs_earthborn/
        ├── oitq/deadwood/
        └── tdm/fun_house/
```

每个源目录自带 `LICENSE.txt`、`level.dat`、`map.xml`、`map.png`、`region/`。
其余 43 个目录、上游的 `.git` 与 `includes/` 已删除（46MB → 1.3MB），保留下来的四份
与上游逐字节一致。

早期评估还用过四张候选图（battle-arena、circular-white-quartz-arena、
gamarudos-gladiator-arena、pvp-castle），全部否决，投影文件已删除。它们唯一的作用是证明
`litematic` 读取器正确——逐块比对的结果记在
[`../archive/research/PvPocket-场地模板-调研与工具.md`](../archive/research/PvPocket-场地模板-调研与工具.md)，
需要复现时按同名重新下载投影即可。

## 3. 转换流程

源素材是 Minecraft 世界存档（`region/*.mca`），成品是原版结构模板（gzip NBT）。

```bash
# 1. 探查：确认尺寸与调色板
python scripts/world2struct.py probe fabric-1.21.1/arena-src/_zzuf/maps/tdm/fun_house --palette

# 2. 转换：输出到资源目录
python scripts/world2struct.py convert fabric-1.21.1/arena-src/_zzuf/maps/tdm/fun_house \
    --out fabric-1.21.1/src/main/resources/data/pvpocket/structure/arena_fun_house.nbt

# 3. 校验：与第三方读取器逐块比对
python scripts/arena-verify.py

# 4. 可选：渲染俯视图，肉眼确认朝向
python scripts/arena-map.py
```

转换做的六件事（也是 CC BY-SA 要求的「标明改动」清单）：

1. **格式转换**：世界存档 → 结构模板，方块位置、状态、调色板保留。1.8 存档用数字 id，
   转换时通过 legacy id 表展平为 1.13+ 名称。
2. **剥掉目标机制方块**：`barrier`、`light`、`structure_void`、`structure_block`、`jigsaw`、
   `command_block`。它们编码的是 PGM 区域定义与命令逻辑，PvPocket 用不上，且在场内可被利用。
3. **方块替换**：1.21.1 不存在的方块换成等价物（`pale_oak_*` → `oak_*`、
   `waxed_exposed_copper_bars` → `iron_bars` 等 26 项）。
4. **重力方块稳定化**：`*_concrete_powder` → `*_concrete`，否则一放就塌。
5. **不稳定方块删除**：会自行脱落的压力板、火把、铁轨，会烧完的 `fire`，会被走过的
   `tripwire`。留着会让场地永久偏离自己的基线。
6. **实体与方块实体删除**：物品展示框、盔甲架、箱子及其内容物。场地只有方块。

**校验的参照物必须是第三方实现。** `arena-verify.py` 的第一版拿自己生成的 `.nbt` 当参照，
80/80 全过但两边错得一样。现在用 `litemapy` 独立读一遍源文件再比对。

## 4. 斗兽场

`scripts/colosseum.py` 是生成器，不是转换器：椭圆套椭圆、十排看台、15 格外墙、X 轴两个门。
写它的原因是找不到可再分发的角斗场——PGM 社区仓库只发 XML 不发存档，常见地图站的作品默认
保留所有权利，那种东西不能塞进 jar。

```bash
python scripts/colosseum.py --out fabric-1.21.1/src/main/resources/data/pvpocket/structure/arena_colosseum.nbt
```

生成器的 `A_ARENA = 50`、`A_OUT` 决定战斗区与包围盒的比例，改这两个值就能改形状。
**改完必须同步 `ArenaMaps.COLOSSEUM` 的 inset 参数**（23 / 19），否则边界光幕会落在看台后面。

沙子与第一排看台之间只有**一格**讲台墙，而准备期的 barrier 环正好画在战斗区外一格上。
那一圈以前是无条件写入的，于是每局都在同一位置把讲台墙挖成空气，拆墙时又只能变回空气
（写入被 `suppressRecording` 包着，`revertRegion` 也补不回来）。现在环只在**空气**上写，
实心方块本身就是障碍，环不需要在那里加东西。已经挖坏的存档重启或 `arena reset` 重贴模板即可复原。

## 5. 场地分区与副本

决斗维度是虚空，内部切成 4×4 的网格。每个格子（cell）160 格见方，中心距 256，
所以格子之间留 96 格空地，两张图不会连成一片。地图按 `ArenaMaps.ALL` 的索引
固定占一个格子——那个索引就是它的「家」，永久不变。

一张地图可以拥有多个格子。**副本就是容量**：一场决斗租一个格子，租不到就是
「这张图排满了」，面板上会把它灰掉。每多一份副本，服务器就能多同时开一场这张图的决斗。

| 地图 | 默认份数 |
| --- | --- |
| `flat` | 4 |
| `arena_colosseum` | 2 |
| 其余 | 1 |

默认份数只决定**全新存档**的初始布局。改份数用（需要 OP，权限等级 3）：

```bash
/pvpocket arena copy flat 2            # 再给平坦图两份
/pvpocket arena copy arena_colosseum   # 不写数量就是一份
/pvpocket arena remove flat            # 收回一份
```

副本的格子编号记在 `<存档>/pvpocket/arena.json`，**编号就是地址**：第三份平坦图
永远是同一组坐标，重启、`arena reset`、换地图都不会挪。这正是「复制结构」而不是
「每次开局重新贴一张」的理由——运维可以拿 `/tp` 记住位置，出问题时人找得到现场。

- 非平坦地图的副本在**下达命令时**就贴好，可以立刻飞过去看。
- 平坦地图的副本只**占位不铺地**：它的平台尺寸由规则决定（16–224），在有人真要用之前
  没有确定的大小。第一场落在它上面的决斗会把它铺出来。
- `arena reset` 把**所有**副本退回「已占位、未铺地」：它清空维度后只重贴每张图的第一份，
  副本由第一场落在它上面的决斗重贴。格子编号不变，所以 `/tp` 记的位置依然有效。

**`remove` 是 `copy` 的逆操作，而且没有下限。** 它从最后加的那份收起，其余副本的地址
因此不会动；收掉的那一格会被清回虚空并还给网格。正在对局中的格子会被跳过——拆掉它等于
把里面的人扔进虚空——全都在打就拒绝，等这一场结束。

收到 0 份是合法状态：这张图从规则面板里消失，`allocate` 也拿不到格子。`arena.json` 里
用 `noHome` 记这件事，因为「家」是从 `ArenaMaps.ALL` 的索引推出来的，没法凭空抹掉。
再按一次「复制」它会**回到自己原来的地址**，而不是网格末尾的新格子——地址是运维记在
纸上的东西，不能因为删过一次就搬走。

网格一共 16 个格子，6 张内置图各占一个，剩下 10 个留给副本。满了以后 `copy` 会拒绝并
报出格子总数——那说明该换更大的维度，而不是继续加副本。

**地块可以溢出格子。** 地块在格子里居中摆放，比格子宽就两侧一起探进空地；硬上限是
「不许碰到邻居」，也就是 `MAX_SIZE_XZ = 中心距 256 − 32 = 224`。所以 `ArenaRegion.reset()`
清的是**格子与地块的并集**，只清格子会把上一张 224 宽平地的外圈留在空地里。

启动预热只贴每张图的**第一份**：副本是容量不是展品，预热全部副本会让启动卡顿和磁盘
占用都翻倍，而没人会去看一个空副本。

## 6. 新增一张图

1. **先查许可。** 打进 jar 就是再分发，默认「保留所有权利」的作品不能收。
2. 把源放进 `arena-src/`，跑上面四步。
3. 在 `ArenaMaps` 里加一个 `public static final ArenaMap`，尺寸用转换器报的包围盒，
   不要手写估计值——`ArenaMap.paste` 会复核并警告不一致，但那时面板已经按错的尺寸排版了。
4. **追加**到 `ArenaMaps.ALL` 末尾，不要插入。
5. 在语言文件两份里各加一条 `pvpocket.map.<id>`。
6. 更新 `THIRD-PARTY-NOTICES.md` 的表格与改编说明。

## 7. 相关脚本

| 脚本 | 作用 |
| --- | --- |
| `scripts/world2struct.py` | 世界存档 / litematic / schem → 结构模板 |
| `scripts/colosseum.py` | 生成斗兽场 |
| `scripts/arena-verify.py` | 与第三方读取器逐块比对 |
| `scripts/arena-map.py` | 读维度存档渲染俯视图 |
