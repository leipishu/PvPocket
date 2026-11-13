# versions/ —— 一格一个子项目

一个 **cell** = 一个 Minecraft 版本 × 一个加载器。cell 名就是目录名，也是 Gradle 工程路径：

```bash
./gradlew :1.21.1-fabric:runClient
./gradlew :1.21.1-neoforge:build
./gradlew buildAll
```

## 矩阵

| MC | Fabric | NeoForge | Forge | JDK | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 | — | — | `legacy/1.20.1/forge` | 17 | 未建，见 `legacy/README.md` |
| 1.21.1 | `1.21.1-fabric` | `1.21.1-neoforge` | — | 21 | **已建** |
| 1.21.11 | `1.21.11-fabric` | `1.21.11-neoforge` | — | 21 | **已建，编译通过**；未连服实测 |
| 26.1 | `26.1-fabric` | `26.1-neoforge` | — | 27（`release 25`） | 未建 |
| 26.2 | `26.2-fabric` | `26.2-neoforge` | — | 27（`release 25`） | 未建 |
| 26.3 | `26.3-fabric` | `26.3-neoforge` | — | 27（`release 25`） | 未建 |

1.20.1 用 Forge 而不是 NeoForge，因为 NeoForge 从 1.20.2 才有。

## 加一格

1. **版本号**：这个 MC 版本还没有 `gradle/catalogs/<mc>.toml` 的话，复制一份现有的改三个版本号
   （`minecraft` / `fabricApi` / `neoforge`）。catalog 名由文件名派生，不用注册，见
   `gradle/catalogs/README.md`。
2. 建目录 `versions/<mc>-<loader>/`，放一个 `build.gradle`（照抄同名加载器现有的那一份）。
3. 把 cell 名加进 `settings.gradle` 的 `cells` 列表。
4. 跑 `./gradlew :<cell>:compileJava`。

产物名从 cell 名派生，不用写：`1.21.1-fabric` → `pvpocket-fabric-1.21.1-<mod_version>.jar`。

## 为什么加载器是子项目，不是源集

Loom 与 ModDevGradle 都要接管 minecraft 依赖、run 任务与 remap，**一个 project 只能应用其中一个**。
所以「加载器」这一维必然落在 project 边界上。而「版本」这一维不落在 project 边界上——它由
Stonecutter 的 `//? if` 预处理在共享的 `src/` 里表达，一个版本一个子项目只是为了让每个版本有自己的
依赖坐标和 run 目录。

## 版本号放哪

三层，各管一段：

| 位置 | 内容 | 谁读 |
| --- | --- | --- |
| `gradle/catalogs/<mc>.toml` | `minecraft` / `fabricApi` / `neoforge` | 同版本的两个 cell |
| `gradle/libs.versions.toml` | `fabric-loader` / `fabricLoom` / `neoforgeModdev` | 全部 cell |
| `gradle.properties` | `mod_id` / `mod_name` / `mod_version` / `maven_group` | 全部 cell，12 格必须一致 |

cell 的 `build.gradle` 不直接读这些文件：`gradle/pvpocket-cells.gradle` 按 cell 名派生 catalog 名
（`1.21.1-fabric` → `v1_21_1`），取出来挂成 `project.minecraft_version` 这类属性。所以
`build.gradle` 里看不到 catalog 名字，复制格子也不会漏改版本号。

**加第二个 MC 版本之前要做的只有一件事：建那个 `.toml`。** 早期把版本号放在 `gradle.properties`
的做法已经废掉了——那里的键全局唯一，两个版本同时存在必然打架，而打架的表现是「编译过了但类属于
另一版」，构建期不报错。

## 运行目录

`runs/<cell>/{server,client1..4}`。**不同版本的存档格式不兼容，绝不能共用 runs 目录**；同一个版本
的两个加载器也不共用，因为 mod 注册表与 options.txt 不同。

## 共享的那一半

`gradle/pvpocket-cells.gradle`：产物名、Java 级别、编码、许可文件、dev 客户端清单（用户名、窗口
尺寸、自动进服、堆上限、`-P` 开关）、`statsCheck`、`writeCompileClasspath`。

**用户名与窗口尺寸只写在那里一处。** 两个 cell 各写一遍的下场是联调脚本按 `Dev1..Dev4` 找人，
另一边叫 `NeoDev1`，永远对不上——这正是把 `fabric-1.21.1/` 拆成平行 cell 的理由之一。
