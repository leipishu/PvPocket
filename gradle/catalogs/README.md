# 每个 Minecraft 版本一个版本目录

```
gradle/catalogs/1.21.1.toml   →  catalog `v1_21_1`
gradle/catalogs/26.1.toml     →  catalog `v26_1`
```

文件名去掉 `.toml` 就是 MC 版本，`settings.gradle` 扫这个目录自动注册，点换成下划线并加
`v` 前缀。**加一个 MC 版本 = 加一个文件**，不需要改 `settings.gradle`。

## 为什么不是一个文件

`gradle/libs.versions.toml` 是 Gradle 的默认目录，键名全局唯一。两个 MC 版本同时存在时，那里的
`minecraft` 只能有一个值，第二个版本就得叫 `minecraft2` 之类——而 cell 的构建文件里写的是哪个
键，从名字上看不出来。

分成多个文件之后，**每个文件内部的键名完全一样**：

```toml
[versions]
minecraft = "..."
fabricApi = "..."
neoforge = "..."
```

区分靠 catalog 名（`v1_21_1` / `v26_1`），复制一个新版本的文件不需要改任何键名。这不是洁癖：
键名一旦带版本后缀，填错的表现是「编译过了但类属于另一版」，构建期不会报错，运行时才炸。

## cell 怎么取

cell 名是 `<mc>-<loader>`，`gradle/pvpocket-cells.gradle` 从 `project.name` 派生 catalog 名，
再挂成普通 project 属性：

```groovy
// 由 1.21.1-fabric 自动得到 v1_21_1
def cellCatalogName = 'v' + project.name.split('-')[0].replaceAll('[^0-9A-Za-z]', '_')
ext.minecraft_version = pickVersion(cellCatalogName, 'minecraft')
```

所以 cell 的 `build.gradle` 里仍然写 `project.minecraft_version`，不用管 catalog 叫什么。

## 哪些东西不在这里

| 内容 | 位置 | 理由 |
|---|---|---|
| fabric-loader / Loom / ModDevGradle | `gradle/libs.versions.toml` | 跨 MC 版本共享，一个 Loom 版本能构建整个 1.21.x |
| `mod_id` / `mod_name` / `mod_version` / `maven_group` | `gradle.properties` | 模组自身的身份，12 格必须一致，不属于任何一格 |

## `yarn` 键

每个文件里还有一个 `yarn`，**构建不读它**。只有 `scripts/mojmap-table.py` 读：生成
Yarn→Mojmap 对照表要下载 Yarn 映射，坐标里带这个版本号。它和 `minecraft` 严格绑定，所以放在
同一个文件里——分两处存必然不同步。日常构建用 `loom.officialMojangMappings()`，不碰 Yarn。

## 加一个版本的步骤

1. 复制一个现有的 `.toml`，改文件名与三个版本号。
2. 在 `settings.gradle` 的 `cells` 列表里加上对应的两个 cell 名。
3. 建 `versions/<mc>-fabric/build.gradle` 与 `versions/<mc>-neoforge/build.gradle`
   （见 `versions/README.md`）。
