# PvPocket 多版本移植方案

> 状态：**方案（待实施）**。非规范，属于 `docs/archive/` 的存档性质，但它是接下来一周的执行依据。
> 写于 2026-11-11，基于当时本机可验证的事实与公开资料；标 **待验证** 的条目必须在动手第一天确认，
> 它们会改变后面的排期。

---

## 0. 结论先行

八句话，后面每一条都有展开：

1. **目标矩阵是 8 个格子，但代码不是 8 份。** 加载器维度 3 份（fabric / neoforge / forge），版本维度
   4 份（1.20.1 / 1.21.1 / 1.21.11 / 26.3），两者正交：**3 个适配器 + 4 份版本差 + 1 份共享主体**。
2. **全面改用 Mojmap（官方名），这是整套方案里唯一不可协商的决定。** 因为 **26.1 起 Minecraft 不再混淆**，
   官方名是唯一不会过期的一套；继续用 Yarn 等于给未来留一条必须重写两次的路。
3. **版本维用 Stonecutter，加载器维用 MultiLoader 风格的手写分层，不引 Architectury。** 理由见 §2.2。
4. **`common` 的语言级别锁在 Java 17。** 1.20.1 跑在 17 上，共享代码一旦用了 21 的语法就再也回不去。
5. **1.20.1 单独一个 Gradle 根。** ForgeGradle 6 与现代 Loom / NeoGradle 抢同一个 Gradle 版本，
   把它们塞进一个 build 是纯粹的浪费时间。
6. **一周内能落地的是 1.21.1 双加载器 + 26.3 Fabric；1.20.1 只做探路，不承诺完成。**
   33,636 行、20 个 mixin、22 个 payload，这不是一周能铺满四个版本的量。
7. **1.21.11 建议顺带而不是主攻。** 它是 1.21.1 与 26.x 之间的过渡版本，等 26.x 跑通之后它的边际成本
   接近零；现在单独为它开一列等于提前付一次全矩阵的账。
8. **真正的工作量在映射迁移和 20 个 mixin，不在"加个加载器"。** 22 个 payload 与 28 个注入点是
   全部风险所在，其余 70% 的代码只是换个类名。

---

## 1. 目标矩阵与取舍

| # | 版本 | 加载器 | 运行时 JDK | 加载器版本 | 价值 | 成本 |
|---|---|---|---|---|---|---|
| 1 | 1.21.1 | Fabric | 21 | loader 0.16.9 / Loom 1.11 | **已有** | 0 |
| 2 | 1.21.1 | NeoForge | 21 | 21.1.265 | 高：同一版游戏，零 API 差 | **最低** |
| 3 | 26.3 | Fabric | **25** | 待验证 | 高：未来主线 | 高（渲染管线） |
| 4 | 26.3 | NeoForge | 25 | 26.3.0.102 | 高 | 高 |
| 5 | 1.20.1 | Fabric | 17 | 待验证 | 中：老整合包存量 | **最高**（API 断代） |
| 6 | 1.20.1 | Forge | 17 | 47.x | 中 | 最高 + FG6 工具链 |
| 7 | 1.21.11 | Fabric | 21 | 待验证 | 低：过渡版本 | 低（26.x 之后） |
| 8 | 1.21.11 | NeoForge | 21 | 21.11.48 | 低 | 低 |

公开数据（2026-08 的 Modrinth 口径）：1.21.1 上 Fabric 有 19,830 个模组，而当时的最新版 26.2 只有
10,324 个，NeoForge 侧的 26.x 迁移率只有 28%。**结论：1.21.1 是当下最大的存量池，26.x 是唯一值得
提前占位的未来，1.21.11 两头不占。**

### 关于"26.1 还是 26.2"：两个都不是，应该做 26.3

写这份方案时核对的公开事实：

- **Java 版 26.3 是当前稳定版**（minecraft.net 的服务器下载页给的就是 `minecraft_server.26.3`，
  Fabric meta 把 26.3 列为 `stable: true`，26.4 只在快照里）。
- **NeoForge 26.3.0.102** 已发布（NeoForge maven 上的最高版本）。
- 26.1 与 26.2 分别发布于 2026-03 与 2026-06 前后，都已不是主线；26.1.2 是 26.1 的补丁线。

所以在矩阵里取 **26.3**。若你更看重"26.1 上的模组更多"，那也是短期的：26.x 的迁移是从 26.1 一路向
26.3 走的，而 26.3 的加载器两端都已就绪。**除非有明确的下游用户钉在 26.1，否则不要从 26.1 起步。**

### 建议的落地顺序

```
第 1 周  ① 1.21.1-Fabric 重构（映射迁移 + 分层）   ← 一切的地基
        ② 1.21.1-NeoForge                          ← 最便宜的一格，验证抽象面
        ③ 26.3-Fabric                              ← 验证"未来"这一维
第 2 周  ④ 1.20.1-Fabric + Forge                   ← 断代适配，单独 Gradle 根
        ⑤ 1.21.11 × 2                              ← 顺带，成本≈0
```

**不建议**把 1.21.11 放在 26.3 之前：它的 API 与 26.x 同代（1.21.9 起的渲染管线），先做它等于把
26.x 的工作做一遍再重做一遍。

---

## 2. 五个必须先定的技术决策

### 2.1 映射：全面改用 Mojmap（最高优先级）

**事实**：Mojang 于 2025-10-29 宣布、从 **26.1 起 Java 版不再混淆**，jar 里直接是可读的官方名。
这意味着 26.x 根本不需要 mappings，而官方名（`Level` / `ResourceLocation` / `ServerPlayer` /
`GuiGraphics`）就是那套唯一不会过期的名字。

**推论**：

- Fabric 侧继续用 Yarn，就等于共享代码里同时存在两套名字。要么给 `common` 做一次 remap（需要
  Architectury 那种工具），要么写两份。
- NeoForge / Forge 原生就是 Mojmap。共享代码只有落在 Mojmap 上，才能被三方同时编译。
- 26.x 无 mappings，`common` 直接对官方 jar 编译 —— 只有 Mojmap 这条路能一路走到 26.x。

**代价**：全仓 148 个文件里的 MC 标识符改名。`Identifier` → `ResourceLocation`、
`ServerPlayerEntity` → `ServerPlayer`、`PlayerEntity` → `Player`、`ItemStack` 不变、
`World` → `Level`、`DrawContext` → `GuiGraphics`、`TextRenderer` → `Font`、
`MinecraftClient` → `Minecraft`、`ScreenHandler` → `AbstractContainerMenu` …

**做法**：单独一个提交，只做重命名，不夹带任何逻辑改动。先跑通编译，再跑 `audit-ui` 与
`check-logic` 证明行为未变。**mixin 的目标描述符必须一起改**（`attack` → `hurt`、
`applyArmorToDamage` → `getDamageAfterArmorAbsorb` 等），改完用
`build/classes/java/*/…-refmap.json` 离线核对（这条已经在 `AGENTS.md` 里）。

> 待验证：Loom 对 1.21.1 / 1.21.11 提供 `officialMojangMappings()`；26.x 是否仍需声明 mappings。

### 2.2 版本维 Stonecutter，加载器维手写分层，**不用 Architectury**

- **Stonecutter**：一个 Gradle 插件，把工程拆成若干"目标"，共享 `src/` 用 `//? if <条件> {` 预处理。
  它解决的是"同一段逻辑在 1.20.1 与 26.x 里 API 不同"的问题。选它的理由：IDE 有插件、条件写在
  源码里、比 git 分支（每个改动都要 cherry-pick 四次）省太多。
- **加载器维不用 Architectury**：Architectury 的核心价值是把 `common` 编译一次再 remap 到各加载器。
  但 **26.x 不再混淆之后 remap 这个前提本身就消失了**，而 Architectury 对 26.x 的支持节奏是未知数。
  引它等于给 8 个格子绑一个外部依赖，而这个依赖恰好会在最新的那一维上滞后。
  项目自己的加载器接触面很小（见 §6.1：约 15 个方法），手写 3 个实现比引框架更可控。

**代价**：`common` 里会出现 `//?` 条件块。**约束：条件块只允许出现在加载器适配层和版本差异点，
不允许出现在业务逻辑里。** 一旦 `session/` 里出现 `//? if 1.20.1`，说明抽象面漏了东西，应该补接口。

### 2.3 `common` 的语言级别锁在 Java 17

- 1.20.1 的运行时是 Java 17，`common` 编译到 class 61 才能在 17/21/25 上都跑。
- 已经在用的 record（payload 全是 record）是 Java 16+，没问题。
- **禁止**在 `common` 用：`switch` 的模式匹配（21）、`SequencedCollection`（21）、虚拟线程（21）。
- 平台模块可以各自用高版本，但没必要 —— 统一 17 省掉一类"在我这编译得过，在他那炸了"。

### 2.4 1.20.1 单独一个 Gradle 根

冲突点：ForgeGradle 6（1.20.1）绑 Gradle 8.x；现代 Loom 与 NeoGradle 想要 Gradle 9.x
（为了 JDK 25 工具链支持）。一个 build 只能有一个 Gradle 版本。

```
PvPocket/
├── settings.gradle.kts          ← 现代根：1.21.1 / 1.21.11 / 26.3
└── legacy/
    └── 1.20.1/
        └── settings.gradle.kts  ← 老根：1.20.1 fabric + forge，Gradle 8.x
```

两个根通过**目录**共享 `src/`（`sourceSets.main.java.srcDirs += "../../../src/main/java"`），
不通过项目依赖。两个根各自跑自己的 Stonecutter，所以共享源码里的 `//?` 条件在老根里同样生效。

> 待验证：FG6 + Stonecutter 的兼容性。若不兼容，退路是 1.20.1 不共享源码、改成"从 1.21.1 复制一份
> 并长期冻结"——那条路会让 1.20.1 变成维护负担，所以只作为最后的退路。

### 2.5 客户端代码的隔离

现状：`splitEnvironmentSourceSets()` 产出 `main` / `client` 两个源集，专用服务端不会加载 client 类。
多加载器之后这个性质必须保住 —— 16,073 行客户端代码里有 41 处 `Minecraft` 引用，在服务端加载即崩。

- **Fabric**：继续用 `splitEnvironmentSourceSets()`，原样。
- **NeoForge / Forge**：单 jar。客户端入口只在 `Dist.CLIENT` 分支里被引用，服务端永不触碰那些类。
- **客户端 mixin**（6 个文件）：Fabric 用 `environment: client` 的独立 config。NeoForge / Forge 没有
  这个字段，用 **`MixinConfigPlugin#shouldApplyMixin` 检查 `FMLLoader.getDist()`** —— 这是保证
  服务端不加载客户端 mixin 的可靠办法，比"配置里分文件"稳。

---

## 3. 目录结构（目标形态）

```
PvPocket/
├── settings.gradle.kts              # 现代根：includeBuild("build-logic") + 各 cell
├── stonecutter.gradle.kts           # IDE 当前激活的 cell
├── build.gradle.kts                 # 全 build 共用：java 17、编码、许可、发布
├── gradle/
│   ├── libs.versions.toml           # 所有版本号只在这里出现
│   └── wrapper/
├── build-logic/                     # 约定插件（includeBuild）
│   └── src/main/kotlin/
│       ├── pvpocket.fabric-cell.gradle.kts
│       ├── pvpocket.neoforge-cell.gradle.kts
│       └── pvpocket.forge-cell.gradle.kts
│
├── src/                             # ★ 共享源码，被所有 cell 编译（Stonecutter 预处理）
│   ├── main/java/com/leipishu/pvpocket/     # 无加载器代码
│   │   ├── rules/ session/ arena/ combat/ loadout/ storage/ preset/ stats/ snapshot/ i18n/
│   │   ├── network/                 # payload 定义 + PvCodec（不含加载器接线）
│   │   └── platform/PvPlatform.java # ★ 唯一的加载器接触面（接口）
│   ├── fabric/java/...              # FabricPlatform + 入口 + 事件接线
│   ├── neoforge/java/...            # NeoForgePlatform + 入口 + 事件接线
│   ├── forge/java/...               # ForgePlatform（仅 1.20.1）
│   ├── client/java/...              # 自绘 GUI / HUD / 渲染（与加载器无关的部分）
│   ├── client-fabric/java/...       # 客户端加载器接线（按键、HUD 回调）
│   ├── client-neoforge/java/...
│   └── main/resources/
│       ├── assets/pvpocket/{lang,icon.png}   # 共享资源
│       └── data/                             # 共享数据包
│
├── versions/                        # 每个 cell 一个 build 文件（约 20 行）
│   ├── 1.21.1-fabric/build.gradle.kts
│   ├── 1.21.1-neoforge/build.gradle.kts
│   ├── 26.3-fabric/build.gradle.kts
│   └── 26.3-neoforge/build.gradle.kts
│
├── legacy/1.20.1/                   # 独立 Gradle 根
│   ├── settings.gradle.kts
│   ├── versions/1.20.1-fabric/
│   ├── versions/1.20.1-forge/
│   └── src/                         # 只放 1.20.1 独有的东西（FG6 接线、NBT 配装序列化）
│
├── scripts/                         # 全部参数化：scripts/xxx.sh <cell>
├── runs/<cell>/{server,client1..4}  # 运行目录按 cell 分开
└── docs/
```

**关键点**：`src/main` 是唯一放业务代码的地方；`src/<loader>` 与 `src/client-<loader>` 只放接线，
每个文件都应该短到"看一眼就知道它在转发什么"。

---

## 4. Gradle 怎么管

### 4.1 版本号只有一个出处

`gradle/libs.versions.toml`：

```toml
[versions]
mc-1_21_1    = "1.21.1"
mc-1_21_11   = "1.21.11"
mc-26_2      = "26.3"
neoforge-1_21_1  = "21.1.265"
neoforge-26_2    = "26.3.0.102"
fabric-api-1_21_1 = "0.116.17+1.21.1"
# …
```

cell 的 build 文件只写"我是哪个 cell"，版本号从 catalog 取。**禁止**在 cell 文件里硬编码版本。

### 4.2 约定插件消灭重复

三个 convention plugin 各约 60 行，覆盖 90% 的重复配置（源集、java 17、mixin AP、jar 命名、
LICENSE 打包、`processResources` 的版本替换）。这样每个 `versions/<cell>/build.gradle.kts` 只剩：

```kotlin
plugins { id("pvpocket.fabric-cell") }
pvpocket {
    minecraft = libs.versions.mc.get().1_21_1
    loader    = libs.versions.fabric.loader
    api       = libs.versions.fabric.api.1_21_1
}
```

### 4.3 任务与产物命名

- 任务：`./gradlew :1.21.1-fabric:runClient`、`:26.3-neoforge:build`。
- 聚合：`./gradlew buildAll`（依赖所有 `:*-*:build`），`./gradlew runAllClients` 不用做，没意义。
- 产物：`pvpocket-fabric-1.21.1-0.1.0.jar`、`pvpocket-neoforge-26.3-0.1.0.jar`。
- 元数据版本：`mod_version` 保持 `0.1.0`，各 cell 在 `version` 后面拼 `+mc<版本>`（SemVer build metadata），
  这样同一个 jar 在日志里能自证是哪个格子编出来的。

### 4.4 运行目录

`runs/<cell>/client1..4`、`runs/<cell>/server`。理由不变（日志文件互相覆盖、options.txt 打架），
但现在多了一层：**不同版本的存档格式不兼容，绝不能共用 runs 目录**。

`gradle.properties` 里的 `org.gradle.java.home` 必须删掉，改用工具链：

```properties
org.gradle.java.installations.paths=C:/Program Files/Java/jdk-17,C:/Program Files/Java/jdk-21,<jdk25>
```

> **本机现状：只装了 JDK 17 与 21，没有 25。** 26.x 的格子跑不起来，这是第一天要解决的第一件事。

---

## 5. 代码怎么分层：哪些能复用

### 5.1 按包的复用等级（实测行数）

等级：**A** 原样复用 / **B** 只换类名（映射迁移，机械） / **C** 需要版本条件编译 /
**D** 每个加载器或版本都要重写。

| 包 | 行数 | 等级 | 说明 |
|---|---:|---|---|
| `session` | 6,282 | B | 纯状态机，只碰 `ServerPlayer`/`Level`/`Component` |
| `client/gui` | 5,285 | B | 自绘，只用到 `GuiGraphics` 的 6 个方法（见 §5.2） |
| `client/home` | 2,913 | B | 面板逻辑，同上 |
| `arena` | 2,820 | B→C | `SpawnHelper`/`World` 的 mixin 是版本敏感的 |
| `client/gui/rule` | 2,094 | B | — |
| `loadout` | 1,917 | **C** | `DataComponents.CUSTOM_DATA` 在 1.20.1 不存在，要退回 NBT |
| `network` | 1,668 | **D** | 1.20.1 没有 payload API；Fabric/NeoForge 的 codec 类型不同 |
| `client/gui/loadout` | 1,446 | B | — |
| `rules` | 1,022 | **A** | 6 个文件里只有 `RuleDefinition` 碰 `Component` |
| `mixin` | 922 | **D** | 14 个类 / 28 个注入点，每个版本重新定位 |
| `client/hud` | 869 | **C** | 1.21.9+ 渲染管线改动 |
| `client/render` | 647 | **C** | 同上（`VertexConsumer` / `PoseStack`） |
| `client/gui/result` | 644 | B | 海报导出用 `Screenshot`，签名各版本不同 |
| `command` | 640 | B | Brigadier 稳定 |
| `client` | 622 | **D** | 接线 |
| `stats` | 590 | **A** | 零 MC 依赖 |
| `combat` | 517 | B | — |
| `client/gui/preset` | 515 | B | — |
| `storage` | 473 | **C** | `WorldSavePath` 是 Fabric 专有，要换成平台服务 |
| `mixin/client` | 410 | **D** | 6 个类 |
| `client/gui/match` | 387 | B | — |
| `preset` | 281 | **A** | — |
| `client/preset` | 241 | **A** | — |
| `snapshot` | 209 | B | — |
| `PvPocket`（根） | 160 | **D** | 接线 |
| `i18n` | 62 | **A** | — |

**合计 33,636 行**：A ≈ 2.2k（7%）、B ≈ 25k（74%）、C ≈ 2.6k（8%）、D ≈ 3.8k（11%）。

一句话：**四分之三的代码只是换名字，真正要动脑的是 network + mixin + 渲染，合计约 4,000 行。**

### 5.2 客户端为什么便宜

自绘 GUI 的红利在这里兑现了。全客户端只用到 `GuiGraphics` 这 6 个方法：

```
ctx.fill() 14 处 · ctx.getMatrices() 9 · ctx.drawText() 3 · ctx.drawItem() 2
ctx.enableScissor()/disableScissor() 各 1 · drawItemInSlot() 1
```

没有 `RenderSystem`、没有自建 `VertexConsumer` 管线、没有贴图（`PvRaster` 是纯 CPU 光栅化）。
**所以 GUI 层跨版本的成本是"改 6 个调用点"，不是"重写 5,285 行"。**
唯一的雷是 §7.2 的 `drawItem` 深度层。

---

## 6. 三个必须抽象的面

### 6.1 `PvPlatform` —— 加载器接触面

现在 `PvPocket.onInitialize()` 就是一张事件注册清单，这正是要抽象的东西。接口大约 15 个方法：

```java
public interface PvPlatform {
    // 注册与网络
    void registerPayloads();
    <T extends PvPayload> void onServerReceive(Class<T> type, BiConsumer<ServerPlayer, T> handler);
    <T extends PvPayload> void onClientReceive(Class<T> type, Consumer<T> handler);
    void sendToPlayer(ServerPlayer player, PvPayload message);
    void sendToServer(PvPayload message);

    // 生命周期
    void onServerStarted(Consumer<MinecraftServer> action);
    void onServerStopping(Consumer<MinecraftServer> action);
    void onServerTick(Consumer<MinecraftServer> action);
    void onPlayerJoin(Consumer<ServerPlayer> action);
    void onPlayerQuit(Consumer<ServerPlayer> action);
    void onPlayerDeath(Consumer<ServerPlayer> action);
    void onPlayerRespawn(Consumer<ServerPlayer> action);

    // 否决点（返回 false 即取消）
    boolean allowDamage(LivingEntity victim, DamageSource source, float amount);
    boolean allowBlockBreak(ServerPlayer player, Level level, BlockPos pos);
    boolean allowChat(ServerPlayer sender, String message);
    UseResult onUseItem(ServerPlayer player, Level level, InteractionHand hand);

    // 命令与路径
    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher);
    Path worldDataDir(MinecraftServer server, String child);
}
```

**三份实现**：`FabricPlatform`（`ServerPlayNetworking` + `ServerTickEvents` + `CommandRegistrationCallback`）、
`NeoForgePlatform`（`RegisterPayloadHandlersEvent` + `@EventBusSubscriber`）、
`ForgePlatform`（`SimpleChannel` + `MinecraftForge.EVENT_BUS`）。

**注意 `allowDamage` 这种"否决点"是设计上的关键**：Fabric 用 `ServerLivingEntityEvents.ALLOW_DAMAGE`、
NeoForge 用 `LivingIncomingDamageEvent` 的 `setCanceled`、Forge 用 `LivingHurtEvent`。三者语义不完全
一致（时机、能否改伤害值），接口只能承诺"能否取消"，**不要承诺"能改伤害数值"** —— 项目现在也确实
只用它做友伤否决。

### 6.2 `PvBuf` / `PvCodec` —— 网络层

这是最大的一块硬骨头，因为三方类型根本不同：

| 目标 | payload 基类 | 编解码类型 |
|---|---|---|
| Fabric 1.21.1 | `CustomPayload` | `PacketCodec<RegistryByteBuf, T>` |
| NeoForge 1.21.1+ | `CustomPacketPayload` | `StreamCodec<RegistryFriendlyByteBuf, T>` |
| 26.x | `CustomPacketPayload` | `StreamCodec<…>` |
| 1.20.1（全部） | 无此概念 | 裸 `FriendlyByteBuf` + channel id |

**方案：payload 的字段顺序只写一次，写在一个极小的缓冲接口上。**

```java
// common
public interface PvBuf {
    void writeVarInt(int v);  int readVarInt();
    void writeInt(int v);     int readInt();
    void writeBool(boolean v);boolean readBool();
    void writeDouble(double v);double readDouble();
    void writeLong(long v);   long readLong();
    void writeUuid(UUID v);   UUID readUuid();
    void writeString(String v);String readString();
    <T> void writeList(List<T> list, PvCodec<T> codec);
    <T> List<T> readList(PvCodec<T> codec);
}

public record ArenaBoundsPayload(boolean active, double x0, /* … */ int mode) implements PvPayload {
    public static final PvCodec<ArenaBoundsPayload> CODEC = PvCodec.of(
        (b, p) -> { b.writeBool(p.active); b.writeDouble(p.x0); /* … */ b.writeVarInt(p.mode); },
        b -> new ArenaBoundsPayload(b.readBool(), b.readDouble(), /* … */ b.readVarInt()));
}
```

每个加载器只需 **3 个约 10 行的适配器**（`PvBuf` 包住 `RegistryByteBuf` / `RegistryFriendlyByteBuf` /
`FriendlyByteBuf`，`PvCodec` 转成各自的 codec 类型）。22 个 payload 的定义保持单份。

**为什么不直接用 `StreamCodec` 当共享货币**：它到 1.20.5 才存在，1.20.1 根本没有；而且
`StreamCodec.tuple` 的元组上限不够，几个 payload 的字段数超过它。显式 `write/read` 反而更好读。

**成本**：重写 22 个 payload 的 codec，约 600 行，一次性。之后每加一个版本只是"再实现一个 10 行适配器"。

### 6.3 mixin —— 保持 mixin，不要换成加载器事件

反直觉但重要：**mixin 是跨加载器最稳的一层**，因为三个加载器都支持它，而事件 API 三家各不相同。
20 个 mixin 类里，14 个是服务端、6 个是客户端，注入点共 28 个。

策略：
- **mixin 类名按"关注点"命名，不按目标类命名**（现状已经如此：`ExplosionMixin` 说的是"爆炸要记账"）。
- **mixin 体保持 5–15 行，逻辑全部下沉到 `common` 的普通类**。这样重定位 mixin 时只是改注解。
- 版本差异用 Stonecutter 条件包住**整个注解**，而不是包住方法体：
  ```java
  //? if >=1.21.9 {
  @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
  //? } else {
  /* @Inject(method = "damage", at = @At("HEAD"), cancellable = true) */
  //? }
  ```
- 每次改完跑一遍 refmap 核对（`build/classes/java/*/…-refmap.json`），`defaultRequire: 1` 炸的是类加载。

**预估**：每个版本重定位 28 个注入点约 1–2 天，26.x 因为渲染管线改动会更多。

---

## 7. 各版本的具体坑

### 7.1 1.21.1 NeoForge（最便宜的一格）

游戏 API 完全一致，只有加载器 API 不同。清单：

- `fabric.mod.json` → `META-INF/neoforge.mods.toml`（`modLoader="javafml"`、`loaderVersion`、`[[mixins]]`）。
- 入口从 `ModInitializer` 换成 `@Mod("pvpocket")` 构造器 + `IEventBus`。
- `PayloadTypeRegistry` → `RegisterPayloadHandlersEvent`；`ServerPlayNetworking` → `PacketDistributor`。
- `ServerTickEvents` → `ServerTickEvent.Post`；`ALLOW_DAMAGE` → `LivingIncomingDamageEvent`。
- `PlayerBlockBreakEvents.BEFORE` → `PlayerEvent.BreakSpeed` / `BlockEvent.BreakEvent`（后者能取消）。
- `UseItemCallback` → `UseItemOnBlockEvent` + `RightClickItem`（注意两者时机不同）。
- `ServerMessageEvents.ALLOW_CHAT_MESSAGE` → `ServerChatEvent`（在 NeoForge 里是"已经决定要发"之后，
  取消语义要重新确认）。
- `CommandRegistrationCallback` → `RegisterCommandsEvent`。
- 客户端：`KeyBinding` 注册 → `RegisterKeyMappingsEvent`；HUD → `RenderGuiEvent`。
- `WorldSavePath` → `server.getWorldPath(LevelResource.ROOT)`（Mojmap 本来就有，不依赖 Fabric）。

### 7.2 26.3（最贵，但必须做）

- **JDK 25**，本机没有，先装。
- **不再混淆**：不再声明 mappings，`common` 直接对官方 jar 编译。这一条会把 `build.gradle` 简化不少。
- **渲染管线**（1.21.6 起，26.x 延续）：`GuiGraphics` 的绘制走 `GuiRenderState` 批处理。
  **本项目最脆弱的一处**：`PvDraw.itemsVisible = false` 那套"弹窗盖不住物品图标"的绕法，建立在
  "`drawItem` 送到 z=150 且开深度测试"之上。新管线里这个前提可能不成立，**必须重新验证**，
  必要时改成"弹窗时根本不提交图标"（其实现在的做法已经接近，只是判断依据要换）。
- `Screenshot` / 海报导出：`Screenshot.takeScreenshot(framebuffer)` 的签名与 `NativeImage.writeTo` 要重查。
- `PlayerSkinDrawer` / `PlayerRenderer` 的 mixin 目标在 1.21.9+ 改过名。
- **待验证**：26.3 已在 Fabric meta 里标为 stable，所以 Fabric 侧是支持的；要确认的是 **Loom 版本号、
  Fabric API 版本号，以及 Loom 在"游戏不再混淆"之后是否还要求声明 mappings**（这一条会决定 26.x 的
  `build.gradle` 长什么样）。

### 7.3 1.20.1（API 断代，最贵）

四道坎，每一道都要单独处理：

1. **没有 payload API**（1.20.5 才引入）。网络层走 `ClientPlayNetworking.registerGlobalReceiver(Identifier, …)`
   与裸 `PacketByteBuf`。`PvCodec` 直接对 `FriendlyByteBuf` 实现即可，反而是三家里最简单的。
2. **没有数据组件**（1.20.5 才引入）。`LoadoutLock` 的 `DataComponents.CUSTOM_DATA` 要退回
   `ItemStack.getOrCreateNbt()` / `getNbt()`，`LoadoutEntry.fingerprint()` 存进 NBT 字符串。
   这是 `loadout` 包 1,917 行的主要改动点。
3. **Forge 用 ForgeGradle 6 + `mods.toml` + `@Mod`**，与 NeoForge 的 NeoGradle 完全不同的一套工具链。
4. **mixin 目标大面积不同**：1.20.1 的伤害管线、`SpawnHelper`、`ScreenHandler#onSlotClick` 签名都不同。

**建议**：1.20.1 只承诺"Fabric 先通，Forge 随后"，且不排进第一周。

### 7.4 1.21.11（顺带）

26.x 跑通之后，它与 26.3 的差异应该只是少数几个 API 签名。**先不做，做完 26.3 再看还差多少。**

---

## 8. 测试与验证怎么跟着走

现有三件套全部保留，但都要参数化：

| 脚本 | 改法 | 为什么 |
|---|---|---|
| `scripts/audit-rules.py` | 路径改到 `src/main/java/.../rules/`，版本无关 | 规则表在 `common`，一份源码 |
| `scripts/check-logic.sh <cell>` | 类路径按 cell 取 | 它跑的是编译产物 |
| `scripts/audit-ui.sh <cell>` | 起 `<cell>` 的客户端跑 82 个布局 | 布局代码在 `common`，但要真渲染 |

**已经落地的第一条**（2026-11-11）：`check-logic.sh` 里的 `LoadoutContentCheck` 用三份手写的 id 集合
冒充 1.20.1 / 1.21.1 / 1.21.11 的注册表，断言每个版本应当看到哪些武器与材质。它验的是"按（种类 ×
材质）问注册表"这套机制，不是真实注册表内容，所以不需要启动游戏。真实内容仍然要靠下面第 1 条。

**新增两个（都便宜、都能挂 CI）**：

1. **`scripts/check-mixins.py <cell>`** —— 静态核对：把每个 `@Mixin` 的目标类与方法描述符拿到该版本的
   MC jar 里查，存在性 + 可见性。**不用启动游戏**，一次跑完 8 个格子只要几十秒。
   价值：现在每换一个版本都要靠"启动看看崩不崩"来发现 mixin 目标写错，这等于把 20 次崩溃换成一次静态检查。
2. **`scripts/check-lang.py`** —— zh/en 键集与 `%s` 数量一致。`audit-rules.py` 里已有这段逻辑，
   拆出来单独跑，因为语言文件在 `common` 里，与规则无关。

**联调脚本**：`scripts/dev.sh <cell>`（原来叫 `dev-2clients.sh`），内部按 cell 拼 `runs/<cell>` 与
`PVP_CLIENTS`。四个客户端 + 服务端那套不变。

**审计基线**：82 个布局的期望值是版本无关的（自绘），所以 `audit-ui.sh` 的输出可以跨版本比对——
如果 26.3 上某个布局报遮挡而 1.21.1 不报，那就是真的渲染差异，不是布局写错了。

---

## 9. 一周排期（可验收）

| Day | 目标 | 验收标准 |
|---|---|---|
| 1 | 装 JDK 25；建 `common/fabric/neoforge` 三层骨架；**Yarn → Mojmap 全量重命名** | `:1.21.1-fabric:build` 出 jar；进游戏开一场；`audit-ui` 与 `check-logic` 仍通过 |
| 2 | 抽出 `PvPlatform`，Fabric 实现落地；`PvBuf/PvCodec` 重写 22 个 payload | 行为与 Day 1 完全一致（同一个 jar 打两遍，`sha` 不同但 journal 相同） |
| 3 | 1.21.1-NeoForge 接线 | `:1.21.1-neoforge:runClient` 连上服务器，开一场，观战一次 |
| 4 | 两个 cell 的脚本参数化 + `check-mixins.py` | `./gradlew buildAll` 全绿；审计脚本对两个 cell 都退出 0 |
| 5 | 26.3 探路：Gradle 工具链、mappings、能否编译 `common` | `:26.3-fabric:compileJava` 通过（哪怕跑不起来） |
| 6 | 26.3 客户端：渲染管线、`GuiGraphics` 差异、mixin 重定位 | 面板能在 26.3 上打开且不崩 |
| 7 | 26.3 收尾 + 文档同步（README / docs 六篇 / AGENTS / CHANGELOG） | 一次提交，`git log` 能说清"现在支持哪几格" |

**明确不在一周内**：1.20.1（Fabric 与 Forge）、1.21.11、发布到 Modrinth/CurseForge。
硬塞进去的结果是每一格都是半成品 —— 那比只做两格更糟。

---

## 10. 风险与止损

| 风险 | 触发信号 | 止损 |
|---|---|---|
| Mojmap 迁移引入隐性行为变化 | 重命名后 journal 里的 `RULES_ACCEPTED` 与旧版不同 | 重命名单独一个提交，只改名字；用 `git diff -w` 逐文件确认没有逻辑改动 |
| FG6 与 Stonecutter 不兼容 | Day 7 探路时 `legacy/` 根跑不起来 | 1.20.1 不共享源码，复制一份冻结；或直接放弃 1.20.1 |
| 26.x 渲染管线让 `drawItem` 绕法失效 | 弹窗下面漏出物品图标 | 改成"弹窗时不提交图标"，这与现有 `itemsVisible` 是同一个思路，只是判断依据换成渲染管线的事实 |
| Stonecutter 条件块蔓延到业务逻辑 | `session/` 或 `arena/` 里出现 `//?` | 立刻停下来补抽象面；条件块进业务层等于把"四份代码"伪装成"一份" |
| 4 个客户端 × 8 个 cell 内存/磁盘爆炸 | `runs/` 超过几 GB | 只有当前 cell 保留 `runs/`，其余在 `.gitignore` 里按 cell 排除；截图继续用完即删 |
| 抽象面漏了东西，直到 26.x 才暴露 | 26.x 上被迫在 `common` 里加加载器判断 | 这就是 Day 2 先把 `PvPlatform` 抽干净的理由；宁可 Day 2 慢一天 |

---

## 11. 需要先确认的三件事（阻塞项）

1. **本机没有 JDK 25** —— 26.x 完全跑不起来。先装（Temurin 25 或 Zulu 25）。
2. **26.3 的 Fabric 工具链细节**：Fabric 侧已确认支持 26.3（meta 标为 stable），但 Loom 的版本号、
   Fabric API 的版本号，以及"不再混淆"之后 Loom 是否还需要 mappings，都要在 Day 1 查清 ——
   它决定 26.x 那一格的 `build.gradle` 怎么写。
3. **1.20.1 是否值得** —— 它是最贵的一格（API 断代 + FG6）。如果目标用户群是"新开服"，可以砍掉；
   如果是"给现有 1.20.1 整合包用"，那它是刚需。这个只有你能定。
