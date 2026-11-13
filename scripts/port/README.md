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
生成任务自动挂在 `compileJava` 与 `processResources` 前面，不需要手动 dependsOn。

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

## 1.21.11 现状（未完成）

`1.21.11-fabric` 从 298 个错误降到 **174** 个，纯改名部分已清空。剩下的都是真实 API 差异，
按文件分：

| 文件 | 数量 | 原因 |
| --- | --- | --- |
| `PlayerSnapshot` | ~34 | `CompoundTag` 取值改 `Optional`、`ValueInput`/`ValueOutput` 取代直接读写、`getList` 要类型参数 |
| `ArenaManager` | 24 | `ServerPlayer.getServer()` 没了；`teleportTo` 换签名 |
| `SessionManager` | 18 | 同上 |
| `LoadoutBuilder` | 16 | `SwordItem` 被删、`PotionContents` 构造与 `getColor` 变了、`Unbreakable` 换组件 |
| `HomePositions` | 14 | `getSharedSpawnPos` 没了；`teleportTo` |
| `PvPocketCommands` / `Spectators` / `FabricPlatform` | 8+12+8 | `hasPermission(int)` 换签名、`Inventory.selected` 变私有、`InteractionResultHolder` 被删 |
| 其余 6 个文件 | ~20 | `ArenaGuard` 的 `HoverEvent`/`ClickEvent` 变抽象、`ItemKind`/`LoadoutLock` 的条件表达式类型 |

已核实的 1.21.11 API（省下重查）：

- `Entity.teleportTo(ServerLevel, double, double, double, Set<Relative>, float, float, boolean)`
  —— 原 6 参版没了；另有 `teleport(TeleportTransition)`。
- `Entity.getServer()` 没了，改走 `level().getServer()`。
- `hasPermission` 只剩一个重载（`method_71774`），`hasPermission(int)` 与 `hasPermissions(int)`
  都不适用。
- `getMinBuildHeight` / `getMaxBuildHeight` / `getSharedSpawnPos` / `serverLevel` 均已改名，
  新名用 `javap` 查（见下）。
- `Inventory.selected` 变私有。

查 API 的正确姿势（`javap` 要用 jdk-21，JDK 17 读不了 21 的 class 文件）：

```bash
./gradlew :1.21.11-fabric:writeCompileClasspath
CP=$(cat versions/1.21.11-fabric/build/compile-classpath.txt)
"/c/Program Files/Java/jdk-21/bin/javap" -classpath "$CP" net.minecraft.world.entity.Entity | grep teleport
```

## 为什么不是 Stonecutter

原计划用它，但它以 Kotlin DSL 为主：接进来要把根构建与四个 cell 全改成 `.kts`，`cells.gradle`
也要重写，现有两格全部要回归。`gen.py` 只有 150 行，`//? if` 语法与它一致，将来真要换，
把 `gen.py` 换成 Stonecutter 的预处理即可，源码里的条件块不用动。
