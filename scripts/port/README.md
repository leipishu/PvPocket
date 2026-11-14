# scripts/port/ —— 版本差怎么落地

`src/` 只有一份，写的是**一个** Minecraft 版本的 API。别的版本不能直接编译它：1.21.9 起
Mojang 做了一次大规模改名（`ResourceLocation` → `Identifier`、一批访问器去掉 `get` 前缀、
若干类换包），共享源码在 1.21.11 上直接编译有 298 个错误。

所以 cell 编译的不是 `src/`，而是**生成树**：`gen.py` 把 `src/` 复制到
`versions/<cell>/gen-src/`，途中做两件事——按表改名、按 `//? if` 剔除分支。

## 一个 cell 怎么接进来

```groovy
// versions/1.21.11-fabric/build.gradle
ext.pvpocketPort = '1.21.11'                                  // 必须在 apply from 之前

apply from: rootProject.file('gradle/pvpocket-cells.gradle')

sourceSets {
    main { java.srcDir pvpocketSrc('main/java') }             // 不写 rootProject.file('src/...')
}
```

`pvpocketSrc` 在没有 `pvpocketPort` 时返回 `src/<相对路径>`，有则返回生成树里的同一路径。
生成任务自动挂在 `compileJava`、`processResources`、`processClientResources`、`sourcesJar` 与
`jar` 前面，不需要手动 dependsOn。

## 条件块

语法与 Stonecutter 相同，`//?` 行在生成时被删掉：

```java
//? if >=1.21.11 {
player.teleportTo(level, x, y, z, Set.of(), yaw, pitch, false);
//?} else {
player.teleportTo(level, x, y, z, yaw, pitch);
//?}
```

支持 `>=` / `>` / `<=` / `<` / `==`，省略运算符等于 `>=`，可以嵌套。

**一个条件可以写多个比较，空格分隔，全部成立才算命中**，这是三档以上版本的前提：

```java
//? if >=1.21.11 <26.1 {
    // 只有 1.21.11 这一档走这里
//?} elif >=26.1 {
    // 26.1 及以后
//?} else {
    // 1.21.1 及以前
//?}
```

`elif` 的写法是 `//?} elif <条件> {`，与 `//?} else {` 同一形状。没有区间条件时，三档只能靠
嵌套表达（`if` 里再套 `if`），档数越多缩进越深，`elif` 就是为了避免这件事。

**条件块只用来表达 API 差异，不要让它长进业务逻辑**——`session/`、`rules/` 里出现 `//?` 就该
先补一层 compat 静态方法，把条件收在一个文件里。

## 改名表

`scripts/port/<mc>.tsv`，`old<TAB>new`，整词替换，**字符串字面量也会被替换**（mixin 的
`@Inject(method = "getServer")` 里方法名是字符串，必须跟着改）。因此只在旧名不可能指别的东西时
才加一行。

带包路径的行要排在上面（生成器按长度降序处理）：先修 `import` 的包，再由短行修简单名。

补表用 `scripts/port/candidates.py`：它读两版 Loom 缓存里的映射，按 intermediary 求交集，列出
本仓库真正用到的改名。**Loom 缓存要先各编译一次**，否则没有映射文件。

**26.1 起这个工具没用了。** 它是第一个不混淆的版本，jar 里直接是官方名（`Identifier`、`Avatar`、
`AvatarRenderer` 都在），没有 Yarn 表可以对比，也就没有「候选改名」可算。26.1 的每一行都来自
编译错误，只能一条一条加。

## 覆盖目录

`scripts/port/<mc>/` 下的文件在改名之后原样覆盖到生成树，路径相对生成树根。它解决的是**形状变了
而不是名字变了**的那类文件：

```
scripts/port/26.1/main/resources/data/pvpocket/dimension_type/arena.json
```

JSON 里写不了 `//?`（`src/` 必须能直接被 1.21.1 那格编译，注释一进去就不是合法 JSON 了），
所以 26.1 的维度类型整份放在这里。`gen.py` 会先写生成树、再铺覆盖、最后按差集删掉陈旧文件，
打印里那个 `N replaced` 就是铺了几份。

## 1.21.11 现状（编译已通过）

四个 cell 全部过 `buildAll`，错误数 **298 → 0**。`1.21.11` 的两格**没有连服实测**，只有编译与
`check-logic.sh` 的保证。

移植里踩到的、加下一个版本还会再踩的坑，按症状记：

### 方法整个消失，而不只是改名

`ItemRenderer.render(ItemStack, ItemDisplayContext, boolean, PoseStack, ...)` 不存在了。1.21.9 把
物品渲染拆成两段：`ItemModelResolver` 先把 stack 解析成 `ItemStackRenderState`，提交时再由
`SubmitNodeCollector` 画出来。显示变换不再由 `ItemRenderer` 施加——`BlockModelWrapper.update` 调
`ModelRenderProperties.applyToLayer` 把一个 `ItemTransform` 存到渲染状态上。

所以「跳过显示变换」这个注入点在 1.21.11 没有对应物，改成「替换存进去的那个变换」：
`BlockModelWrapperMixin` 拦 `applyToLayer` 写入等价的 `ItemTransform`。等价性靠反汇编确认——
`ItemTransform.apply` 的顺序是 translate-rotate-scale，原手写姿态是 translate-scale-rotate，
等比缩放下两者是同一个矩阵。

**这一类编译器不会报。** `@WrapOperation(method = "render(...)")` 里的描述符只是字符串，目标方法
没了照样编译通过，运行时才以 `defaultRequire: 1` 失败崩掉。所以两个加载器各一份的
`ItemRendererMixin` 在 1.21.11 是死的，必须按版本切掉，换成共享的那一个。

### 渲染阶段事件拆成了子事件

`RenderLevelStageEvent.Stage` 枚举与 `getStage()` / `getCamera()` / `getPartialTick()` 全部删除，
改成每个阶段一个子类（`AfterSky` … `AfterLevel`）。基类只剩 `getLevelRenderer()` /
`getLevelRenderState()` / `getPoseStack()` / `getModelViewMatrix()` / `getRenderableSections()`。

camera 与 tick 改从 `Minecraft` 单例取（与 Fabric 侧同一个改法）。事件**没有**提交队列，
`WorldFrame.nodes` 只能传 null。

### 改名表要同时给斜杠形式

mixin 的 `@At(target = "Lnet/minecraft/.../Foo;bar(...)")` 用的是斜杠路径，表里写的是点号。
`gen.py` 现在给每个带包路径的行自动补一份斜杠形式，否则描述符里的类名不跟着改——症状同样是
「编译通过、运行时不匹配」。

### NeoForge 21.9 的两处

`FMLEnvironment.dist` 字段改成 `getDist()`。客户端发包从 `PacketDistributor.sendToServer` 挪到
`net.neoforged.neoforge.client.network.ClientPacketDistributor`。

### 生成树是任务输出，读它的任务都要声明依赖

`sourcesJar` 与 `processClientResources` 也会读 `gen-src` 里的资源根，Gradle 的隐式依赖
校验要求显式 `dependsOn generatePortSources`，否则整个 build 失败并提示
"uses this output ... without declaring an explicit or implicit dependency"。

### 新格的验证入口

`scripts/check-logic.sh` 默认只核 1.21.1 两格，格子由 `FABRIC_CELL` / `NEOFORGE_CELL` 指定。
加完一格要单独跑一遍，否则那一格的 mixin 目标从来没被核对过：

```bash
FABRIC_CELL=1.21.11-fabric NEOFORGE_CELL=1.21.11-neoforge bash scripts/check-logic.sh
python scripts/check-content.py 1.21.11-fabric --verify-jar <client jar>
```

`--verify-jar` 只报差异、不改清单；核对干净后手工把对应的行从 `declared` 改成 `verified`。
`since` 等于核对版本的那些行可以转，因为更早的版本已经被上一格的 jar 否掉了；`since` 夹在两个
已核对版本之间的（例如铜制品的 `1.21.9`）仍然只能算 `declared`。

## 26.1 现状（两格都连服实测过）

`26.1-fabric` 与 `26.1-neoforge` 都过 `buildAll`，服务端 `Done (...)`、客户端进服、规则面板与配装
编辑器都打开过，零 mixin 失败。错误数 **246 → 0**（服务端源集），客户端再 **38 → 0**。

26.1 的改动比 1.21.11 深，但类型只有四类，全部收进 `PvCompat` 与改名表，业务代码里没有新增
`//?`：

| 变了什么 | 26.1 之前 | 26.1 |
| --- | --- | --- |
| GUI 渲染 | `render(GuiGraphics,...)` 直接画 | `extractRenderState(GuiGraphicsExtractor,...)` 只采集 |
| 时钟 | `level.setDayTime(long)` | `clockManager().setTotalTicks(Holder<WorldClock>, long)` |
| 天气 | `level.setWeatherParameters(...)` | `getServer().setWeatherParameters(...)` |
| 消息 | `player.displayClientMessage(..., 覆盖栏?)` | `sendSystemMessage(Component[, 覆盖栏])` |

GUI 那条是最大的一处：`GuiGraphics` 整个类改叫 `GuiGraphicsExtractor`，`drawString` → `text`、
`renderItem` → `item`、`renderItemDecorations` → `itemDecorations`。所以改名表里有两行是**整句**
而不是标识符：

```
void render(GuiGraphics	void extractRenderState(GuiGraphics
.render(ctx	.extractRenderState(ctx
```

第一行让 33 个界面类的 `render` 覆写一次改完，第二行让调用点跟着走。副作用是 `PvClientPlatform`
自己的 `HudLayer.render(GuiGraphics, DeltaTracker)` 也被这两行命中——它的签名和原版控件一模一样，
表分不出来。所以那个接口方法叫 `draw`，避开整句规则。

### 元数据里的版本区间也要改

`fabric.mod.json` 的 `"minecraft": "~1.21.1"` 与 `neoforge.mods.toml` 的 `[1.21.1,1.22)` 会把
26.1 排除在外，加载器在跑任何 mod 代码之前就拒绝加载，日志里只有一句「不兼容」。这两行同样走
改名表，因为 `//?` 进不了 JSON/TOML。**改表时注意整词匹配要求两端都是单词字符**：
`~1.21.1` 以 `~` 开头，前面又是引号，`\b` 不成立，所以写成 `minecraft": "~1.21.1`。

### mixin 目标的方法名会变

`Gui.renderCrosshair` 在 26.1 叫 `extractCrosshair`，而 `@Inject(method = ...)` 里是字符串，
编译器不管。`MixinTargetCheck` 就是为这一类存在的，加完一格必须跑：

```bash
FABRIC_CELL=26.1-fabric NEOFORGE_CELL=26.1-neoforge bash scripts/check-logic.sh
```

### Fabric API 也跟着改了包

`PayloadTypeRegistry.playC2S/playS2C` → `serverboundPlay/clientboundPlay`；
`KeyBindingHelper`（`keybinding.v1`）→ `KeyMappingHelper`（`keymapping.v1`）；
`HudRenderCallback` 整个没了，换成 `HudElementRegistry.addLast(Identifier, HudElement)`，**id 不能
重复**，所以每层 HUD 要自己编号；`WorldRenderEvents`（`rendering.v1.world`）→ `LevelRenderEvents`
（`rendering.v1.level`），`WorldRenderContext` → `LevelRenderContext`。

## 改源码时的一个坑

批量替换脚本必须先读后写。`open(path, 'w')` 会立刻截断文件，若在同一个表达式里再 `read()`，
读到的是空文件——`SessionManager.java` 就这样被清过一次，靠 `git checkout` 救回。改完先
`wc -l` 比对行数。

## 为什么不是 Stonecutter

原计划用它，但它以 Kotlin DSL 为主：接进来要把根构建与四个 cell 全改成 `.kts`，`cells.gradle`
也要重写，现有两格全部要回归。`gen.py` 不到 200 行，`//? if` 语法与它一致，将来真要换，
把 `gen.py` 换成 Stonecutter 的预处理即可，源码里的条件块不用动。
