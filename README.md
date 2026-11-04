# PvPocket（口袋决斗）

在 SMP 生存服务器内提供一个与正常生存完全隔离的「决斗口袋」：玩家可以互相发起单挑，在独立场地中使用自定义配装进行多局对抗，可按对局开启 Legacy 1.8 战斗；每局结束状态完全重置，整个决斗不影响双方在生存模式下的任何数据。

## 仓库结构

仓库根目录只放文档与各平台工程，**每个「加载器 + 游戏版本」组合一个独立子目录**，各自拥有独立的 Gradle 构建。这样后续增加 Forge / NeoForge 或 1.21.x 其他版本时，不会互相污染。

```
PvPocket/
├── docs/                      设计与技术文档
│   ├── PvPocket-vision-v0.1.md
│   ├── PvPocket-decisions-v0.1.md
│   ├── PvPocket-v0.2-方案确认.md
│   ├── PvPocket-v0.3-战斗规格与终稿.md
│   └── PvPocket-v0.4-技术方案.md
├── fabric-1.21.1/             Fabric / Minecraft 1.21.1 工程
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   ├── gradlew / gradlew.bat
│   ├── gradle/wrapper/
│   └── src/
│       ├── main/              common：服务端与客户端共用
│       │   ├── java/com/leipishu/pvpocket/
│       │   └── resources/fabric.mod.json, pvpocket.mixins.json
│       └── client/            客户端专用（split source set）
│           ├── java/com/leipishu/pvpocket/client/
│           └── resources/pvpocket.client.mixins.json
└── .gitignore / .gitattributes
```

## 版本矩阵（fabric-1.21.1）

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.9 |
| Fabric API | 0.116.17+1.21.1 |
| Yarn mappings | 1.21.1+build.3 |
| Fabric Loom | 1.11.8 |
| Gradle | 8.11 |
| Java | 21 |

模组 ID `pvpocket`，Java 包名 `com.leipishu.pvpocket`。

## 构建

前置条件：JDK 21。**1.21.1 要求 Java 21，JDK 17 无法运行客户端**。

```bash
cd fabric-1.21.1

# 编译
./gradlew build

# 启动客户端（含开发环境）
./gradlew runClient

# 启动专用服务端
./gradlew runServer
```

产物位于 `fabric-1.21.1/build/libs/`。

如果 Gradle 未能自动找到 JDK 21，可显式指定：

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./gradlew build
```

或在 `~/.gradle/gradle.properties` 中设置 `org.gradle.java.home`。

## 文档

设计决策已锁定，进入技术方案阶段。文档阅读顺序：

1. `PvPocket-vision-v0.1.md` — 产品愿景、范围、领域概念、风险
2. `PvPocket-decisions-v0.1.md` — 9 个决策点的完整分析
3. `PvPocket-v0.2-方案确认.md` — 决策落定后的方案修订（协商流程、队伍、方块、GUI）
4. `PvPocket-v0.3-战斗规格与终稿.md` — 1.8 战斗六层规格、双端约束、验收标准
5. `PvPocket-v0.4-技术方案.md` — 工程架构、接管点、协议、存储、测试

## 许可

未指定。`fabric.mod.json` 中暂标为 `All-Rights-Reserved`，确定后再改。
