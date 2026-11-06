# PvPocket S4 实现进度

> **历史存档 · 非规范。** S4 阶段记录：规则目录、协商协议、面板组件库、规则执行状态。
> 「39 条规则里只有 18 条真正生效」这一判断最早出现在这里。
> 现行规范见 [`../../zh/01-overview.md`](../../zh/01-overview.md) 起六篇；使用者可感知的变化见 [`../../../CHANGELOG.md`](../../../CHANGELOG.md)。
> 本文按当时原样保留：其中的目录（如 `run/`）、指令（如 `/pvpocket accept`）与类名反映当时状态，与当前代码不一定一致。

> 对应 `PvPocket-v0.2-方案确认.md` §3（提案协商）、§3.4（规则清单）、§6（GUI 自绘约束），
> 以及 `PvPocket-v0.4-技术方案.md` §9（GUI 架构）。
> 本阶段没有改动任何战斗数值，S2/S3 的结论不受影响。

## 一、已实现

| 模块 | 内容 |
| --- | --- |
| 规则模型 | 37 条规则、7 个分组，值统一为 `int`，编解码为 `id=value;…`，带 SHA-256 短指纹 |
| 提案协商 | `DRAFT → NEGOTIATING → …`，双向「修改后重新征询」，每次改动 `rulesRevision +1` |
| 网络协议 | `pvpocket:rules_open`（S2C，只传值）+ `pvpocket:rules_submit`（C2S，只传意图）+ `pvpocket:rules_close`（S2C，协商结束关面板） |
| 自绘组件库 | 面板、按钮、开关、滑杆、循环选择、多选筹码、滚动列表、标签页、提示气泡 |
| 规则面板 | 左侧 7 分组标签页 + 右侧可滚动规则行 + 底栏动作，双角色同一套界面 |
| 键盘操作 | Tab/Shift-Tab 遍历、方向键改值、Enter 确认、Esc 关闭、F2 截图 |
| 规则落地 | 4 条规则真正生效，其余全部记录在案（见第二节） |

## 二、规则的执行状态（重要）

面板上 37 条规则全部可配、全部写入会话、全部进日志。**但只有 4 条在当前版本真正影响对局。**
这一节是本文档最需要读的部分——不要看着面板以为整套规则都生效了。

| 状态 | 规则 | 说明 |
| --- | --- | --- |
| **已生效** | `c.mode` | 经典模式走 S2/S3 的 1.8 管线（无冷却、格挡、击退、护甲） |
| **已生效** | `c.maxHealth` | 回合开始时改 `GENERIC_MAX_HEALTH` 基值；快照恢复会整体回滚属性，不需要单独撤销 |
| **已生效** | `e.boundary` | `判负` → 越界即结算；`拉回` → 传送回出生点并记 `BOUNDARY_PUSHED_BACK` |
| **已生效** | `g.allowSurrender` | 关闭后 `/pvpocket surrender` 拒绝执行 |
| **仅记录** | 其余 33 条 | 已写入 `DuelSession.rules` 与日志，但没有任何代码读取它们 |

**为什么只做 4 条**：其余规则依赖尚未实现的切片。`d.*`（配装）依赖 S7 的配装系统，`f.*`（方块）
依赖 S6 的方块变更集，`b.*`（队伍）依赖 S5 的队伍模型，`a.roundLimit` 依赖计时器，`c.naturalRegen`
等依赖对 `tick` 的持续干预。先做一条规则需要的管线、再把 37 条都挂上去，比先做面板再补管线更省返工。

### 一处主动降级

`a.series`（赛制）**被锁成只读的 BO1**。v0.2 的默认值是 BO3，但多局赛制需要「每回合重置配装与场地」，
那是 S7 的内容。在 S7 之前把 BO3 摆成一个可选值，等于让面板承诺一场打不到的三局两胜——锁住比撒谎好。
S7 落地时把它改回 `enumOf("a.series", …, "BO1","BO3","BO5","BO7")` 即可，其余代码不用动。

## 三、三个设计取舍

**1. 提案人与受邀人共用同一个界面，而不是两套。**
v0.2 §3.3 已经定过这条，实现时确认了它的价值：两边看到的是同一批标签、同一批标签文字、同一批默认值，
差别只在底栏按钮。如果拆成「设置页」和「审阅页」，第一周就会开始漂移，而协商的全部意义就是双方在看同一个东西。
代价是面板要同时处理「没有基线可比」和「有基线可比」两种状态——解法是始终传一个 `baseline`，
发起方的基线就是默认值，于是「改了几项」对两种角色是同一个含义。

**2. 标签页竖排，不用下拉框选分组。**
7 个分组的名字都很短（2–3 字），竖排标签占 88px 宽、省下的是稀缺的纵向空间，而且一次能看到全部分组。
下拉框需要浮层、展开状态和点外关闭，在一个本身就是模态的面板里会读成「弹窗里再弹窗」。
窄窗口下标签行高会自适应压缩（`PvTabs#fitInto`），而不是把最后两组切掉——切掉的标签不是「看不见」，是「点不到」。

**3. 多选规则单独占一行，不塞进右侧控制列。**
`f.blockFilter` 有 5 个筹码、`e.hazard` 有 4 个。挤进 132px 的控制列只有两种结局：字缩到看不清，
或者控制列内部再套一个滚动区。多花 18px 纵向空间、把筹码铺满整行，是更便宜的解法。

## 四、协议与状态机

```
/pvpocket duel <目标>
   └─ SESSION_CREATED + RULES_DRAFTED        stage=DRAFT, awaiting=发起方
        └─ [发起方面板] 发送征询 ─────────► RULES_PROPOSED  stage=NEGOTIATING, awaiting=受邀方
                                              ├─ 接受 ──────► RULES_ACCEPTED → FROZEN → ROUND_ACTIVE
                                              ├─ 拒绝 ──────► VOIDED (DENIED)
                                              ├─ 修改后征询 ► RULES_PROPOSED  revision+1, awaiting=发起方
                                              └─ 关闭面板 ──► (仅 DRAFT) VOIDED (PROPOSER_DISMISSED)
```

四个必须守住的不变量：

1. **`ACCEPT` 接受的是服务端手上的那一版，不是客户端传来的那一版。** `ACTION_ACCEPT` 分支完全不读
   payload 里的规则串。否则「点同意时顺手改了三个滑杆」会静默生效，而对方同意的是上一版。
2. **只有 `awaitingUuid` 能行动。** 发起方不能替受邀方接受，受邀方不能在轮到自己之前改规则。
   这条在服务端校验，客户端按钮的可用状态只是提示。
3. **`DRAFT` 阶段关闭面板必须发 `ACTION_DISMISS`。** 否则一个没提交的草稿会永远占着两个人，
   而发起方自己也不知道它还挂着。`DRAFT` 属于 `isPreFreeze()`，重启时会被 reconcile 丢掉，不涉及回滚。
4. **每次改动都进日志。** `RULES_PROPOSED` 记录 `revision`、作者、指纹和**变化项 id 列表**。
   「我同意的是 v1，实际跑的是 v3」这个问题，事后查日志能直接回答。

`rules` 串同时存进 `DuelSession` 和 `ROUND_STARTED` 日志，指纹进 `RULES_ACCEPTED`。

## 五、组件库

| 组件 | 文件 | 要点 |
| --- | --- | --- |
| 视觉语言 | `PvTheme` | 单一强调色、8px 栅格、两级字号、四态；所有颜色集中一处 |
| 绘制原语 | `PvDraw` | 圆角矩形按行填像素（不是贴图九宫格），任意 GUI 缩放都精确 |
| 基类 | `PvWidget` / `PvValueControl` | 输入契约 + 「一个控件只编辑一个 int」的约束 |
| 按钮 | `PvButton` | PRIMARY / NORMAL / DANGER / GHOST 四风格；禁用是降对比不是隐藏 |
| 开关 | `PvToggle` | 按时间常数插值，30/60/240fps 手感一致；动画不参与取值 |
| 滑杆 | `PvSlider` | 轨道映射规则自身的区间与步长，拖不出非法值 |
| 循环选择 | `PvCycle` | 左右 20px 箭头区，中间是死区——误点不会偷偷改值 |
| 多选筹码 | `PvChips` | 等宽铺满整行，选中态同时用填充色和文字色（色盲可用） |
| 滚动列表 | `PvScrollPane` | 重排子控件坐标而非平移矩阵，命中检测不离开屏幕坐标系 |
| 标签页 | `PvTabs` | 竖排，行高可自适应压缩 |
| 屏幕基类 | `PvScreen` | 背景、提示气泡最后画（逃出裁剪）、键盘焦点 |
| 规则行 | `RuleRow` | 标签 + 控件 + 「与基线不同」的 2px 强调条 |
| 规则面板 | `RuleScreen` | 拼装；双角色同一套 |

技术约束的落实：

- **零前置依赖**：只用 `DrawContext` 的 `fill` 和 `TextRenderer`，没有贴图、没有 owo-lib/Cloth/YACL。
- **不用原版控件**：没有任何 `ButtonWidget` / `ClickableWidget` / `SliderWidget` 实例；
  `Screen` 只当作画布，`render` 完全重写，连原版的模糊背景都不调用。
- **提示气泡**：由屏幕在最后统一绘制，所以滚动区内部的行也能弹出不被裁剪的气泡。
- **裁剪**：`PvScrollPane` 用 `enableScissor` 而不是隐藏越界行——半行被裁掉比突然消失更像「还有内容」，
  再叠 10px 渐变提示（用 1px 填充叠出来，不依赖渐变 API）。

## 六、已验证 / 未验证

### 已验证（实机：独立服务器 + 两个客户端 Dev1 / Dev2）

一次完整的端到端对局，日志为证：

```
SESSION_CREATED  challenger=Dev1 target=Dev2
RULES_DRAFTED    revision=0 digest=62e2708892bf
RULES_PROPOSED   revision=1 author=Dev1 changed=""
STAGE_CHANGED    DRAFT → NEGOTIATING
RULES_ACCEPTED   revision=1 acceptedBy=Dev2 digest=62e2708892bf
SNAPSHOT_WRITTEN ×2
STAGE_CHANGED    NEGOTIATING → FROZEN → ROUND_READY → ROUND_ACTIVE
ROUND_DECIDED    reason=DEATH
ROUND_ENDED      → ROUND_SETTLED → ARENA_CLEARED → RESTORING
RESTORE_COMPLETED ×2 verified=true
STAGE_CHANGED    RESTORING → ARCHIVED
```

逐项对应：

| 验证点 | 结果 |
| --- | --- |
| 发起方面板打开、渲染、悬停提示 | 通过（截图核对） |
| 7 个分组在 240px 紧凑窗口下全部可见 | 通过（`fitInto` 生效，此前会切掉 F/G） |
| 键盘 Enter 触发主按钮（发送征询） | 通过，`RULES_PROPOSED` 由按键触发 |
| 受邀方面板自动弹出、角色为审阅者 | 通过（截图核对：标题「来自 Dev1 的征询」、底栏「拒绝 / 修改后征询 / 同意并开始」） |
| 规则串完整往返 | 通过，`RULES_ACCEPTED.rules` 含全部 37 条，默认值与面板显示一致 |
| 接受 → 冻结 → 传送 → 开打 | 通过 |
| 结算 → 恢复 → 归档，双方状态校验 | 通过，两次 `verified=true` |
| 关闭面板不发决议（`ACTION_DISMISS` 只在 Esc 时发） | 通过 |
| 协商结束后服务端主动关面板 | 通过，两端均收到 `Rules close`（此时面板已由客户端自行关闭，属预期的兜底） |
| 草稿阶段关闭面板 → 会话作废 | 通过，`SESSION_DROPPED` + `DRAFT → VOIDED` |
| 参与者离线时接受 → 启动失败回滚 | 通过，`SESSION_ABORTED reason=START_FAILED` |

### 未验证

- GUI 缩放 1 / 2 / 3 / 4 与 1280×720 / 1920×1080 / 4K 的组合（只测了自动缩放下的 426×240 与 544×306 两种）
- 「修改后征询」往返一次后 `rulesRevision` 的累加（代码路径与首次提案共用，但未实机走过）
- Tab / 方向键的焦点遍历手感（Enter 已验证，Tab 未验证）
- 33 条「仅记录」规则的行为（因为本来就没接管线）

## 七、已知缺口

1. **33 条规则只记录不生效**（见第二节）。
2. **`a.winConditions` 与 `g.allowSurrender` 语义重叠**：前者是「胜利条件集合」里的投降位，
   后者是独立开关。当前只读后者，两者在面板上可能给出矛盾的暗示，S7 需要合并。
3. **没有文本输入控件**。`f.blockFilter` 用固定筹码代替物品列表，无法自定义白名单。
   文本输入（光标、选区、粘贴、长度限制）是自绘 GUI 里最贵的一个组件，留给真正需要它的界面。
4. **`b.spectator` 的人数上限**没有单独规则，只有一个开关。
5. **`e.sizeX/sizeZ/sizeY` 拆成了三条**，而 v0.2 里是一行「长×宽×高」。功能等价，但面板上是三行。
   等场地分区真正按尺寸切割（S6）时再决定要不要合并成一个三段控件。
6. **协商没有超时**。一方挂机时提案会一直挂着，只能靠对方关闭面板或重启服务器。
7. **原版 GUI 缩放不跟随面板**。面板用固定像素 + 栅格，靠 `Math.min(width-20, …)` 适配，
   没有做「低于阈值切换紧凑布局」的第二套排版。

## 八、下一步

S5（队伍）之前建议先把**规则落地**补到「能打一场真正的自定义对局」的程度，优先级：

1. `c.naturalRegen` / `c.hunger` / `c.fallDamage` — 三个 `tick`/事件级开关，成本低、体感明显
2. `g.showOpponentHealth` — 需要 HUD，属于 GUI 层的第二块
3. `a.roundLimit` + `a.timeoutVerdict` — 需要回合计时器，是 S7 的前置
4. `d.allowConsumables` / `d.allowPickup` — 两个交互拦截点

## 九、开发工具（本次新增）

| 工具 | 用途 |
| --- | --- |
| `scripts/rcon.py` | 从脚本驱动服务器控制台。`execute as Dev1 run …` 能以玩家身份执行指令，是把面板打开在真实客户端上的唯一自动化手段 |
| `scripts/client-act.ps1` | 聚焦指定客户端窗口、发送按键序列、截取窗口。按键走真实 WM_KEYDOWN，所以它验证的是键盘焦点逻辑本身 |

`run/server.properties` 的开发配置已打开 RCON（`rcon.password=pvpocket`，仅本地 dev 目录，
该目录在 `.gitignore` 内）。
