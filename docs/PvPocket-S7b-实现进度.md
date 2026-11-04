# PvPocket S7b 实现进度：配装编辑界面

> 对应需求：**一、关于配装** 第 8 条
> 「在配装GUI设计的时候一定要显示物品图标而不是只有文字」
>
> 以及第 2–5 条里"可设置/可选择/可编辑"的那部分——S7a 只做了数据，没有做能改数据的界面。
>
> 本阶段还修了一个**入口缺失**问题：规则面板上原本没有任何通往配装的按钮，所以配装虽然能存、能发，玩家在发起申请前根本走不到。

---

## 一、交付表

| 需求原文 | 落地位置 | 状态 |
|---|---|---|
| 显示物品图标而不是只有文字 | `LoadoutRow` 用 `ctx.drawItem` 画真实物品 | ✅ |
| 四大类分页 | 左列 `PvTabs`：武器 / 装备 / 消耗品 / 方块 | ✅ |
| 武器按类别添加 | 中列「可添加」区，列出该页全部类别 | ✅ |
| 剑斧可选材质 | 右列「材质」cycle，6 种 | ✅ |
| 材质只影响显示 | 右列提示文案写明，S7a 用替换组件实现 | ✅ |
| 伤害 / 耐久 / 击退可编辑 | 右列 slider | ✅ |
| 特殊附魔可开关 | 右列 toggle，按类别动态出现 | ✅ |
| 弓无限开关 + 箭矢数 | 右列 toggle + 独立 slider | ✅ |
| 三叉戟激流开关 | 右列 toggle | ✅ |
| 钓鱼竿伤害 | 右列「攻击伤害」slider（S7a 用 attack_damage 实现） | ✅ |
| 护甲 / 盾牌材质与属性 | 右列「材质」「护甲值」 | ✅ |
| 食物 / 药水 / 方块种类 | 右列 cycle，白名单内 | ✅ |
| 药水时长自定义 | 右列「持续时间」slider | ✅ |
| 发起申请前可进入 | 规则面板页脚新增「配装」按钮 | ✅ |
| 羊毛双方不同色 | S7a 按队伍改写，编辑器里标为「按队伍染色」 | ✅（未实测） |
| 小局结束物品恢复 | S7a 走快照恢复，非本阶段 | ⏳ |

---

## 二、入口：为什么按钮不直接开界面

「配装」按钮点下去**不发屏幕，发一个请求**：

```
RuleScreen.配装 ──LoadoutRequestPayload()──▶ 服务端
                                              │ 找玩家所在的开局前会话
                                              │ 没有配装就补默认预设
                                              ▼
                        ◀──LoadoutOpenPayload(sessionId, loadout, role, otherName)
                        │
                        ▼
                 new LoadoutScreen(payload, parent = 当前 RuleScreen)
```

看起来是绕路，但绕的这一步换来三件事：

1. **服务端是配装的权威副本。** 界面打开时显示的是服务端存着的那一份，不是客户端凭记忆拼的。玩家关掉界面再打开，看到的东西不会变。
2. **界面拿得到它拿不到的信息。** `otherName`（对手叫什么）、`role`（我是在提案还是在评审）只有服务端知道。客户端自己 new 一个屏幕，标题栏就只能写「配装」。
3. **没有会话时按钮要能失败。** 玩家在战斗中间点配装，服务端回一句「当前没有可以配置配装的决斗」，比打开一个存不进去的界面诚实。

`PvPocketNetworking.openLoadout` 里有 `ServerPlayNetworking.canSend` 判断——对面没装模组就不发，避免包在 `send` 里抛异常打断整个指令处理。

---

## 三、三列布局

面板 640 × 360，三列各回答一个问题：

```
┌──────────────────────────────────────────────────────────────────────┐
│ 配装   与 Dev2 的决斗 · 进场时发放                        3f9c21       │ 52
│ ──────────────────────────────────────────────────────────────────── │
│ 武器：剑、斧、弓、弩、三叉戟、重锤、钓鱼竿。材质只影响外观。            │
│ ┌────────┬──────────────────────┬────────────────────────────────┐  │
│ │ 武器   │ 已添加                │ 攻击伤害        7.0 点  ▬▬●── │  │
│ │ 装备   │ ◆ 剑·铁        7.0   │ 耐久             800 次 ▬▬●── │  │
│ │ 消耗品 │ ◆ 弓·木      ×32     │ 不可破坏              [ ]      │  │
│ │ 方块   │ ◆ 盾牌·铁            │ 击退等级          1 级  ▬●─── │  │
│ │        │                       │ 横扫                  [✓]      │  │
│ │  88px  │ 可添加                │                                │  │
│ │        │ ＋ 剑                 │                                │  │
│ │        │ ＋ 斧                 │                                │  │
│ │        │ ＋ 弓                 │                                │  │
│ │        │   196px               │  剩余宽度                       │  │
│ └────────┴──────────────────────┴────────────────────────────────┘  │
│ 已添加「剑」 · 未保存                         返回 恢复默认 保存配装   │ 36
└──────────────────────────────────────────────────────────────────────┘
```

三列而不是"列表 → 详情"两页，是因为配装的核心操作是**对照**：看着伤害 7.0 的剑，想把它和列表里伤害 8.0 的斧比一比。两页布局每次比较都要来回切。

左列 88px 是 `PvTheme.TAB_H` 的既有宽度，`fitInto(contentH)` 会在窗口变矮时压缩行距而不是裁掉最后一页——被裁掉的 tab 没有滚动条可以救回来。

---

## 四、复用规则面板的行

`LoadoutFields.of(entry)` 返回一串 `Field(RuleDefinition def, IntSupplier get, IntConsumer set)`。

这是本阶段最重要的一个决定：**配装编辑器不引入第二套控件词汇**。伤害是 slider，材质是 cycle，附魔是 toggle——全都是规则面板里已经有的东西，连 `RuleRow`（左侧强调条、label、右侧控件）都直接复用。

好处不是省代码，是省**认知**：两个界面看起来是同一个产品。玩家学会一次怎么拖滑块，在配装页和规则页都成立。

```java
out.add(new Field(
        RuleDefinition.decimal("lo.damage", RuleGroup.D, "攻击伤害",
                "空手是 1.0。这里填的是总伤害，不是加成。",
                LoadoutCatalog.DAMAGE_MIN_TENTHS, LoadoutCatalog.DAMAGE_MAX_TENTHS, 5,
                entry.damageTenths == 0 ? 70 : entry.damageTenths, " 点"),
        () -> entry.damageTenths,
        v -> entry.damageTenths = v));
```

`RuleDefinition.decimal` 是 S7b 顺带加的：伤害和护甲要显示成 `7.0 点` 而不是 `70`。做法是给 `RuleDefinition` 加一个 `divisor` 字段，`format()` 里 `divisor > 1` 时用 `%.1f` 渲染，值本身仍是整数（×10 存）。不引入浮点，避免编码和比较时踩 `0.1 + 0.2` 的坑。

### 不适用的字段不生成，而不是灰掉

给剑显示一个灰色的「无限」开关，等于承诺"它有一天能开"。玩家会去点，点不动，然后来问为什么。字段直接不存在，就是真话。

```java
if (kind.page == ItemKind.Page.WEAPON) { /* 击退等级 */ }
if (kind == ItemKind.BOW) { /* 箭矢数量 */ }
if (kind.isArmor()) { /* 护甲值 */ }
```

食物、药水、方块不显示耐久和不可破坏——它们本来就没有耐久条，给一个能调的耐久数字是纯粹的谎。

---

## 五、图标

```java
ctx.drawItem(icon, x + 4, y + 3);   // 16×16，行高 22
```

需求点名要求图标，理由也很实在：「金苹果」和「附魔金苹果」在中文里差三个字，在游戏里是两种完全不同的东西；「白色羊毛」和「红色羊毛」在列表里都是"羊毛"。

三个降级点：

| 情况 | 处理 |
|---|---|
| `variant` 是非法 id | `Identifier.tryParse` 返回 null → 返回 null → `ItemStack.EMPTY`，画空 |
| 物品不在注册表 | `Registries.ITEM.get(id)` 返回 `Items.AIR` → 也画空 |
| 类别图标 | 药水固定用 `SPLASH_POTION`，食物用 `GOLDEN_CARROT`，方块用 `WHITE_WOOL`，其余用铁质 |

第一行不是理论问题。`Loadout.decode` 容忍字段不足和未知值（为了向前兼容），所以一个被手改过的存档或者跨版本残留的 `variant` 完全可能走到这里。`Registries.ITEM.get(Identifier.tryParse(null))` 会直接抛异常——一个画不出来的图标不应该让整个界面崩掉。

---

## 六、四个易错点

### 6.1 选中索引是全局的，不是页内的

```java
/** Index into {@link Loadout#entries}, not into the visible page. -1 means nothing selected. */
private int selected = -1;
```

中列只画当前页的条目，但 `selected` 指向 `loadout.entries` 的下标。如果用页内下标，切页之后 `selected = 2` 会指到另一件物品身上——而且不报错，只是右列悄悄换成了别人的属性，玩家改完保存才发现改错了东西。

对应地，`remove(index)` 之后要做三路调整：删的是选中项 → 清空选中；删的在选中项之前 → 选中项前移；删的在之后 → 不动。

### 6.2 拖滑块不能让列表跳回顶部

中列显示伤害和数量，所以右列每改一个值都要刷新中列。但刷新中列 = 重建它的行 = `PvScrollPane` 的偏移被重置。表现是：拖伤害滑块，左边列表每帧跳回顶部。

```java
private void rebuildListQuietly() {
    int scroll = listPane.scroll();
    rebuildList();
    listPane.scrollTo(scroll);
}
```

为此给 `PvScrollPane` 加了 `scrollTo(int)`。它和"滚轮滚到某处"的区别是要**不触发边界钳制之前就设定**——重建后内容高度变了，先设偏移再 `layout()` 才不会把偏移钳成旧高度允许的最大值。

### 6.3 `PvTabs` 的泛型擦除

`PvTabs` 原本只服务规则面板，构造参数是 `List<RuleGroup>`。配装页要传 `List<String>`。

`PvTabs(List<RuleGroup>, int)` 和 `PvTabs(List<String>, int)` **擦除后是同一个签名**，不能作为两个构造器共存。所以改成：

```java
public PvTabs(List<String> labels, int selected)                       // 通用
public static PvTabs ofRules(List<RuleGroup> groups, int selected)     // 规则页专用工厂
```

`groups` 字段只在这个工厂里非 null，`group()` 对非规则条返回 null。同时把 badges（右对齐的组字母 A–G）也变成可选——配装页的 tab 上没有徽标。

### 6.4 返回不等于取消

```java
/** Returns to the rule panel. Never cancels the negotiation — that is the 取消 button's job. */
private void back() {
    MinecraftClient.getInstance().setScreen(parent);
}
```

`parent` 是打开配装时**那一个** `RuleScreen` 实例，不是新建的。`PvPocketClient` 的接收器里：

```java
RuleScreen parent = context.client().currentScreen instanceof RuleScreen screen ? screen : null;
context.client().setScreen(new LoadoutScreen(payload, parent));
```

重建 `RuleScreen` 会丢掉玩家改了但还没发送的规则——他可能为了决定"要不要开方块模式"才去配装页看了看，回来发现规则被重置了。

`close()` 也走 `back()`，所以 Esc 和「返回」是同一个行为。想取消决斗得回规则面板点「取消」。

会话结束时 `RulesClosePayload` 会把配装页一起关掉：

```java
if (context.client().currentScreen instanceof LoadoutScreen editor
        && editor.sessionId().equals(payload.sessionId())) {
    context.client().setScreen(null);
}
```

否则对手拒绝之后，配装页还开着，玩家可以继续编辑一套永远不会被发放的配装。

---

## 七、保存与校验

```
编辑（内存里的 Loadout）
  ├─ 每次改动 → isDirty() → 页脚显示「未保存」（WARN 色）
  ├─ 每次改动 → loadout.validate() → 有问题时页脚显示第一条（DANGER 色）
  └─ 保存配装 → 再校验一遍 → 通过才 autoAssignSlots() + 发送 LoadoutSubmitPayload
```

页脚优先级是 **问题 > 未保存 > 普通提示**。一个既没保存又有问题的配装，玩家该先看到"哪里不对"。

校验跑两遍（客户端一次、服务端一次）不是冗余：客户端那次是为了让保存按钮诚实（不会发出一个必被拒的包），服务端那次是因为客户端不是可信来源。`SessionManager.onLoadoutSubmit` 里被拒的配装**保持原样不动**，不会半应用。

标题栏右上角常驻显示 `loadout.digest()`（SHA-256 前 6 字节）。这个值是玩家能看见的、和日志里 `LOADOUT_SAVED digest=...` 对得上的东西——将来有争议时，"我当时带的是 3f9c21"是可以验证的。

`role` 字段已经随包传过来了，但**当前界面没有用它**。它是给 S7c 留的：同一套界面要能切换"我的配装"和"对手的配装"两种只读/可写状态。

---

## 八、规则面板的改动

两处：

1. **页脚新增「配装」按钮**，放在最左边（离「发送征询」最远）。配置物品和提交规则是两个不同的决定，不该挨着——手滑点错的代价不一样。
2. **页脚提示文案**补上「· 「配装」调整进场物品」。原来的文案只说"设置完成后发送给对方"，玩家没有理由去猜页脚上那个新按钮是干什么的。

`RuleScreen` 的页脚是右对齐累加的（`place` 从右往左排），所以按钮顺序在代码里是从右往左写的：保存/发送 → 还原/取消 → 配装。

---

## 九、验证状态

### 已验证

- `./gradlew build` 通过
- 服务端启动无错误
- 三个新包（`loadout_open` / `loadout_submit` / `loadout_request`）注册成功，双端能建立连接

### 未验证（需要真人开客户端点）

- 点「配装」能不能真的打开界面（这是本次修复的核心，也最需要实测）
- 图标是否渲染成真实物品而不是空白
- 拖滑块时中列是否跟着变、且不跳回顶部
- 切页之后 `selected` 是否被清空（应清空）
- 「保存配装」后重开，改动是否还在
- 页脚「未保存」提示是否随编辑出现、随保存消失
- 校验失败时是否显示红字且不发送
- Esc / 返回 是否回到原来那个规则面板、且规则改动还在

### 已知缺口

1. **配装没有进入协商**。双方各自配置自己那份，谁也看不到对方的。这是 S7c，也是这套界面真正变成"我们双方同意的配装"的一步。
2. **`role` 字段未使用**。提案者和评审者看到的界面完全一样（除了标题里的对手名）。
3. **没有预览**。不能试穿、不能试挥——只能靠数字想象 7.0 伤害是什么手感。
4. **没有预设管理**。只有「恢复默认」一个动作，改好的配装不能存下来下次用。
5. **中列不显示总格子占用**。36 项上限只在加满时才提示，玩家不知道还剩多少。
6. **无法拖拽排序**。槽位由 `autoAssignSlots()` 推导，玩家不能手动指定哪一格放什么。
7. **`LoadoutScreen` 没有做窗口过小的降级**。面板固定 640×360，窗口小于这个尺寸时右列会被压得很窄（有 `fit` 截断，不会溢出，但可读性下降）。

---

## 十、下一步

**S7c 配装并入协商**：双方配装互相可见、接受时冻结、写进 `rulesDigest` 同级的 `loadoutDigest`。

这一步依赖 v0.5 §4 第 4 个问题（配装是否纳入协商、对手是否可见）的答复。我推荐的方案 A 是：**双方配装都可见、都要对方同意**——理由是配装能直接决定胜负（40 点伤害的剑 vs 7 点），如果不进协商，"规则谈好了"就只是谈好了一半。

在拿到答复之前，可以先做的无争议部分：

- 规则面板上加一块只读的"我的配装摘要"（项数 + 指纹 + 前三件），让玩家不必点进配装页就知道自己带的是什么
- `/pvpocket loadout show` 指令，把配装以可读形式打印到聊天栏，方便贴给别人看
