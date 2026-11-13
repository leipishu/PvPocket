# scripts/port/ —— 版本差怎么落地

`src/` 只有一份，写的是**一个** Minecraft 版本的 API。别的版本不能直接编译它：1.21.9 起
Mojang 做了一次大规模改名（`ResourceLocation` → `Identifier`、一批访问器去掉 `get` 前缀、
若干类换包），共享源码在 1.21.11 上直接编译有 298 个错误。

所以 cell 编译的不是 `src/`，而是**生成树**：`gen.py` 把 `src/` 复制到
`versions/<cell>/build/gen-src/`，途中做两件事——按表改名、按 `//? if` 剔除分支。

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
**条件块只用来表达 API 差异，不要让它长进业务逻辑**——`session/`、`rules/` 里出现 `//?` 就该
先补一层 compat 静态方法，把条件收在一个文件里。

## 改名表

`scripts/port/<mc>.tsv`，`old<TAB>new`，整词替换，**字符串字面量也会被替换**（mixin 的
`@Inject(method = "getServer")` 里方法名是字符串，必须跟着改）。因此只在旧名不可能指别的东西时
才加一行。

带包路径的行要排在上面（生成器按长度降序处理）：先修 `import` 的包，再由短行修简单名。

补表用 `scripts/port/candidates.py`：它读两版 Loom 缓存里的映射，按 intermediary 求交集，列出
本仓库真正用到的改名。**Loom 缓存要先各编译一次**，否则没有映射文件。

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

`sourcesJar` 与 `processClientResources` 也会读 `build/gen-src` 里的资源根，Gradle 的隐式依赖
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

## 改源码时的一个坑

批量替换脚本必须先读后写。`open(path, 'w')` 会立刻截断文件，若在同一个表达式里再 `read()`，
读到的是空文件——`SessionManager.java` 就这样被清过一次，靠 `git checkout` 救回。改完先
`wc -l` 比对行数。

## 为什么不是 Stonecutter

原计划用它，但它以 Kotlin DSL 为主：接进来要把根构建与四个 cell 全改成 `.kts`，`cells.gradle`
也要重写，现有两格全部要回归。`gen.py` 只有 150 行，`//? if` 语法与它一致，将来真要换，
把 `gen.py` 换成 Stonecutter 的预处理即可，源码里的条件块不用动。
