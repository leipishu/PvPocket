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
├── _zzuf/                     zzuf-maps 的裁剪副本
│   ├── README.md              上游说明（署名用）
│   └── maps/
│       ├── arcade/bn_dark_nebula/
│       ├── gs/gs_earthborn/
│       ├── oitq/deadwood/
│       └── tdm/fun_house/
└── *.litematic                四张被否决的候选图
```

每个源目录自带 `LICENSE.txt`、`level.dat`、`map.xml`、`map.png`、`region/`。
其余 43 个目录、上游的 `.git` 与 `includes/` 已删除（46MB → 2.2MB），保留下来的四份
与上游逐字节一致。

四个 `.litematic`（battle-arena、circular-white-quartz-arena、gamarudos-gladiator-arena、
pvp-castle）是早期评估中被否决的候选，保留原因只有一个：`litematic` 读取器的正确性
是靠它们的逐块比对结果证明的，而那组数字记录在归档文档里。文档没有记录下载地址，
删掉就无法重建这组证据。

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

## 5. 新增一张图

1. **先查许可。** 打进 jar 就是再分发，默认「保留所有权利」的作品不能收。
2. 把源放进 `arena-src/`，跑上面四步。
3. 在 `ArenaMaps` 里加一个 `public static final ArenaMap`，尺寸用转换器报的包围盒，
   不要手写估计值——`ArenaMap.paste` 会复核并警告不一致，但那时面板已经按错的尺寸排版了。
4. **追加**到 `ArenaMaps.ALL` 末尾，不要插入。
5. 在语言文件两份里各加一条 `pvpocket.map.<id>`。
6. 更新 `THIRD-PARTY-NOTICES.md` 的表格与改编说明。

## 6. 相关脚本

| 脚本 | 作用 |
| --- | --- |
| `scripts/world2struct.py` | 世界存档 / litematic / schem → 结构模板 |
| `scripts/colosseum.py` | 生成斗兽场 |
| `scripts/arena-verify.py` | 与第三方读取器逐块比对 |
| `scripts/arena-map.py` | 读维度存档渲染俯视图 |
