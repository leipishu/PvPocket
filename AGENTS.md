# AGENTS.md

给在本仓库工作的编码代理。人类读者请从 [`README.md`](README.md) 开始。

这个文件只写**代码里读不出来、但踩错就要返工**的东西：命令在哪跑、哪些约定是硬性的、怎么验证。
设计理由在 `docs/`，那里是规范；这里是指南。

## 项目

Minecraft Fabric 1.21.1 模组「口袋决斗」（`pvpocket`）：在 SMP 里开一个与生存完全隔离的决斗口袋，
自定义配装、多局对抗、打完不留痕迹。

**服务端与客户端必须同版本。** 两端协议是自定义 payload，版本不一致时表现为面板不出现或规则不生效，
而不是一个明确的报错。

## 工作目录

仓库根不是工程根。**所有 Gradle 命令都在 `fabric-1.21.1/` 下执行。**

| 路径 | 内容 |
| --- | --- |
| `fabric-1.21.1/src/main/java/` | 服务端与两端共用（`session`、`rules`、`loadout`、`preset`、`network`） |
| `fabric-1.21.1/src/client/java/` | 只在客户端（`client/gui`、`client/hud`、`client/preset`） |
| `fabric-1.21.1/src/main/resources/assets/pvpocket/lang/` | `zh_cn.json` / `en_us.json` |
| `fabric-1.21.1/runs/` | 运行目录，`server` 与 `client1..4`，**由 `build.gradle` 决定** |
| `docs/zh/`、`docs/en/` | 六篇规范，平行且一一对应 |
| `docs/archive/` | 历史存档，**非规范**，不追改 |
| `scripts/` | 联调、地图转换、界面自动化 |

## 构建与运行

```bash
cd fabric-1.21.1
./gradlew compileJava compileClientJava      # 唯一需要记的编译命令
```

**五个 run 任务就是唯一入口**，在 IDEA 右侧 Gradle 面板 `Tasks > fabric` 里双击即可：
`runServer`、`runClient`、`runClient2`、`runClient3`、`runClient4`。

- 客户端默认 `--quickPlayMultiplayer localhost:25565` 直接进服，堆上限 1G。
- `-PquickJoin=off` 停在标题界面；`-PclientHeap=<size>` 改堆上限。
- datagen 是 client 环境的子 run，会继承客户端参数，`build.gradle` 里已清空它的 `programArgs`。

联调（服务端独占终端，客户端另起）：

```bash
./gradlew runServer                                  # 终端 1
PVP_CLIENTS=3 scripts/dev-2clients.sh                # 终端 2；三个才能测对局面板 + 观战
```

日志：服务端 `runs/server/logs/latest.log`，客户端 `build/devlogs/client{1,2,3}.out`。
**「面板没出来」先看客户端日志里 `canSend=` 那一行。**

## 硬性约定

### 1. 本地化（最容易忘的一条）

**所有玩家可见文本一律走翻译键，代码里不出现中文字符串字面量。** 注释可以用中文，字符串不行。

- `PvLang.t(key, args…)` → `MutableText`，**服务端拼消息只能用这个**，由客户端按自己的语言解析。
- `PvLang.str(key, args…)` → `String`，**只给客户端自绘 GUI 用**。在服务端调用会吞掉玩家语言
  （专用服务端只加载 `en_us`），等于硬编码英文。
- 键布局：`pvpocket.rule.<id>.{label,hint,c<N>,unit}`、`pvpocket.item|material|flag|option|map.*`、
  `pvpocket.gui.<screen>.<element>`、`pvpocket.msg.<area>.<event>`、`pvpocket.command.*`、
  `key.pvpocket.*`。
- 目录类（`ItemKind` / `Material` / `WeaponFlag` / `LoadoutCatalog` / `ArenaMap` / `RuleDefinition`）
  **不持有文案**，只有 `labelKey` + `labelText()`（Text）+ `label()`（String）。
- 规则键从规则 id 派生——id 就是线格式，天然稳定。
- **命令语法不翻译**：`/pvpocket spectate leave` 是 token 不是句子。
- 中英键集必须完全一致，`%s` 占位符数量也必须一致。数字也用 `%s`，不要混 `%d`。

改完语言文件跑一次：

```bash
cd fabric-1.21.1 && python -c "
import json,re
z=json.load(open('src/main/resources/assets/pvpocket/lang/zh_cn.json',encoding='utf-8'))
e=json.load(open('src/main/resources/assets/pvpocket/lang/en_us.json',encoding='utf-8'))
print(len(z),len(e),set(z)^set(e))
print([k for k in z if k in e and len(re.findall(r'%s',z[k]))!=len(re.findall(r'%s',e[k]))])"
```

### 2. 指令面收敛

**面板能做的，指令就不该有第二条路。** 新增指令前先问「面板能不能做」。保留的都有不可替代的理由：
控制台/RCON 没有客户端（`arena`、`guard`、`loadout give`）、按 id 找一场（`spectate <前缀>`）、
只读诊断（`status`、`loadout`）、GUI 通道失效时的兜底。

### 3. 界面（画布只有 426×240）

1280×720 窗口在 GUI scale 3 下就是 426×240。规范见 `docs/zh/04-visual.md`，以下是踩过的坑：

- **`PvDraw.roundRect` 必须按行切分，不能按列**。按列会让中间行左右各漏 r 像素，表现为所有按钮和
  滚动条两侧黑条；窄形状（2px/6px）中段直接消失。
- **1px 的线一律用 `ctx.fill`**，不要用 `roundRect(..., h=1, radius=0)`。
- **模态弹窗挂 `PvScreen.overlay`**，不要只 append 到 `roots`。`roots` 只决定「画在上面」，
  不决定「拦住下面的点击」。
- **物品图标盖不住，只能不画。** `ctx.drawItem` 送到 `z=150` 的深度测试层，任何顺序的半透明遮罩
  都盖不住；弹窗存在时置 `PvDraw.itemsVisible = false`。
- **`PvScrollPane` 的顺序是「宽度 → `onWidth()` → 高度」**。高度依赖宽度的控件必须走
  `PvWidget.onWidth()` 钩子，先算高度会让滚动范围永远是错的。
- **`init()` 在窗口 resize 时也会跑**。选中的标签页、正在编辑的名册、已填的输入必须跨 `init()`
  保留，否则改一次窗口大小就丢一次工作。
- 面板里加按钮前先算宽度：页脚一行在 426px 画布上只有 382px，`RuleScreen` 在评审方角色下已经用掉
  386px。**放不下就放标题行**（`PvButton.chip`，16px 高）。

### 4. 沙盒

决斗维度内聊天只放行 `/pvpocket` 根字面量（白名单，不是黑名单）；任何人不得自行切游戏模式
（命令层 + `ServerPlayerInteractionManager.changeGameMode` 两层），但控制台/RCON 发起的放行，
否则运维救不了卡住的玩家。

钩子必须挂在 `CommandManager.execute`，不是 `executeWithPrefix`——玩家聊天指令直接调前者。

### 5. 文档

- `docs/zh/` 与 `docs/en/` 平行，六篇一一对应。**改一篇必须同步另一篇。**
- 改动历史写 `CHANGELOG.md`，只记使用者可感知的变化与改变后续做法的决定，不记内部重构。
- `docs/archive/` 是历史存档，不追改其中的旧路径与旧指令。
- README 只做入口：定位、构建、联调、目录树、文档索引、脚本表、许可。技术细节写进 `docs/`。

## 验证

**编译通过不等于可用。** 规则类改动至少要在两端跑一局，服务端 journal
（`runs/server/world/pvpocket/journal/*.jsonl`）里有 `ROUND_STARTED`、`RULES_ACCEPTED` 等事件，
能直接回答「这一局按哪版规则打的」。注意 journal 里的 `=` 是 `\u003d`，`grep 'c.maxHealth='` 匹配不到。

界面自动化两条铁律：

- 抓图用 `scripts/agent/shot-window.ps1`（`PrintWindow` + `PW_RENDERFULLCONTENT`）。
  `CopyFromScreen` 在窗口被遮挡或失焦时**静默返回桌面壁纸**，看起来像模组卡死。
- 点击用 `scripts/agent/click-frac.ps1`（客户区比例），不要绝对像素——窗口最大化/还原后会点到别处，
  失败表现是「模组忽略了点击」，与真实 bug 无法区分。
- 暂停的 Minecraft 不出新帧，重复抓同一画面会拿到相同 PNG，别据此判断「客户端卡住」。
- **截图用完即删**（`find runs -name '*.png' -delete`），只有最终要展示的才保留。

## 提交

- 提交信息用中文，一句话说清「改了什么」，正文写**为什么**（尤其是取舍）。
- 不要为了过编译而关掉检查，也不要 `--no-verify`。

## 许可

模组代码按 **GNU Lesser General Public License v3.0** 分发，全文见 [`LICENSE`](LICENSE)；
它引用 GPL-3.0 的条款，副本见 [`LICENSE-GPL-3.0.txt`](LICENSE-GPL-3.0.txt)。

**内置地图不受此影响**：`data/pvpocket/structure/*.nbt` 中四张来自
[zzuf-maps](https://github.com/zzufx/zzuf-maps)，按 CC BY-SA 4.0 分发；斗兽场由 `scripts/colosseum.py`
生成。署名与改动声明见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。
