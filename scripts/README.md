# scripts/ —— 想做什么，跑哪个

脚本按**用途**分组。日常只有前两组需要记，其余是特定场景才用的工具。

所有脚本都从**仓库根**运行（`bash scripts/xxx.sh`、`python scripts/xxx.py`），不要在
`scripts/` 里 `cd` 进去跑——它们用 `$ROOT` 定位 `src/`、`versions/`、`runs/`。

## 我想做…… → 跑这个

| 我想…… | 命令 |
| --- | --- |
| 一条命令起服务端 + 两个客户端，自动进服 | `scripts/dev-duel.sh` |
| 同上，但换一格（1.21.11 / Fabric） | `CELL=1.21.11-fabric scripts/dev-duel.sh` |
| 同上，Windows 双击或 cmd | `scripts\dev.cmd 1.21.11-fabric` |
| 服务端自己开，只拉客户端 | `scripts/dev-clients.sh`（`PVP_CLIENTS=3` 加观战） |
| 改完代码做全套静态 + 逻辑自检 | `scripts/check-logic.sh` |
| 只查注释规范 | `python scripts/check-comments.py` |
| 只查语言键 | `python scripts/check-lang.py` |
| 查配装清单在哪些格子成立 | `python scripts/check-content.py --all` |
| 查 main / client 有没有混进加载器 API | `python scripts/check-boundary.py` |
| 查规则文档与代码是否一致 | `python scripts/check-rules.py --docs` |
| 查界面有没有遮挡 | `scripts/check-ui.sh` |
| 把世界存档 / litematic 转成结构模板 | `python scripts/map/world2struct.py convert ...` |
| 生成斗兽场 | `python scripts/map/colosseum.py --out ...` |
| 给服务端发指令 | `python scripts/map/rcon.py "execute as Dev1 run ..."` |
| 加一个新版本格子 | 见 [`port/README.md`](port/README.md) |

## 换版本怎么跑

1.21.11 与 26.1 都没有专用脚本——**所有脚本都用 `CELL=` 选格子**，和 1.21.1 是同一套。六格的名字
是 `1.21.1-fabric`、`1.21.1-neoforge`、`1.21.11-fabric`、`1.21.11-neoforge`、`26.1-fabric`、
`26.1-neoforge`。

```bash
# 联调：服务端在后台，Dev1 + Dev2 自动进服，Ctrl+C 全停
CELL=1.21.11-neoforge scripts/dev-duel.sh

# 格子也可以作为第一个参数，这样 PowerShell 里同样能跑（PowerShell 不认 CELL=xxx 前缀）
scripts/dev-duel.sh 1.21.11-neoforge
scripts/dev-clients.sh 1.21.11-fabric

# 三个客户端（第三个用来看观战面板）
CELL=1.21.11-neoforge PVP_CLIENTS=3 scripts/dev-duel.sh

# 只拉客户端，服务端自己在另一个终端开
./gradlew :1.21.11-fabric:runServer          # 终端 1
CELL=1.21.11-fabric scripts/dev-clients.sh   # 终端 2

# 自检：格子由 FABRIC_CELL / NEOFORGE_CELL 指定，默认是 1.21.1
FABRIC_CELL=26.1-fabric NEOFORGE_CELL=26.1-neoforge scripts/check-logic.sh

# 界面遮挡审计（约两分钟，不需要服务端）
CELL=1.21.11-fabric scripts/check-ui.sh
```

### PowerShell / cmd 里怎么跑

`CELL=xxx command` 是 POSIX 语法，PowerShell 会把它当成要执行的程序名而报
`无法将"CELL=..."项识别为 cmdlet`。Windows 下用 `scripts\dev.cmd`，它自己找 Git Bash：

```
scripts\dev.cmd 26.1-fabric
```

`dev.cmd` 只包装了 `dev-duel.sh`。要单独跑 `dev-clients.sh`，用位置参数形式：

```powershell
& "C:\Program Files\Git\bin\bash.exe" scripts/dev-clients.sh 26.1-fabric
```

`dev-duel.sh` 会先 `./gradlew :<cell>:classes`，再起服务端，等日志里出现 `Done (` 才拉客户端——
不是等端口，Minecraft 在初始化早期就 bind 了端口，那时连上去会被踢。

## 在 IDEA 里点着跑

`.run/` 下是提交进仓库的运行配置，IDEA 打开工程后直接出现在右上角的运行下拉里，分三组：

- **联调 / 自检 / 构建**：脚本类的入口，点绿三角就跑，输出在 Run 窗口。
- 联调配置已经带好 `CELL` 环境变量，换格就选另一条。
- 构建用 Gradle 配置跑 `buildAll`，等价于命令行的 `./gradlew buildAll`；产物归到 `dist/<cell>/`。
- **单跑**：六格各五条 `<cell> - Client / Client2 / Client3 / Client4 / Server`，对应
  `runClient` / `runClient2..4` / `runServer`，全部收在 `PvPocket 单跑` 文件夹里。NeoForge 的
  ModDevGradle 本来会另写一套同名配置到 `.idea/workspace.xml`（文件夹外），已由
  `gradle/pvpocket-cells.gradle` 关掉 `neoForgeIdeSync` 阻止，否则每条都会出现两份。

脚本类配置的解释器固定为 `C:\Program Files\Git\bin\bash.exe`（只有 Git Bash 能看见
`netstat` / `taskkill`，PATH 上的 `bash` 常常是 WSL 启动器）。换机器后如果路径不同，在
Run → Edit Configurations 里改 Interpreter path。

## 目录

| 目录 | 放什么 |
| --- | --- |
| `checks/` | 三个 `*Check.java`，由 `check-logic.sh` 编译并逐个运行，退出码 0 才算过 |
| `map/` | 地图工具：转换、校验、俯视图渲染、斗兽场生成、RCON |
| `ui/` | 窗口截图与按键（PowerShell）。**给 AI Agent 做界面验证用的，普通开发不需要** |
| `mojmap/` | Yarn→Mojmap 迁移工具。**一次性，已完成**，只在需要重跑迁移时用 |
| `port/` | 版本差：`gen.py` 生成 `versions/<cell>/gen-src/`，改名表是 `*.tsv` |

## 约定

- **`CELL`** 选格子，六格通吃。少数脚本用两个变量（`check-logic.sh` 同时核 Fabric 与 NeoForge）。
- **`PVP_CLIENTS`** 1–4，对应 `runClient` / `runClient2` / `runClient3` / `runClient4` 四个 run 任务。
- **退出码**：0 通过，1 有发现，2 环境不全（没编译、找不到 JDK、审计没跑完）。CI 只需要看 0/非 0。
- **日志**落在 `versions/<cell>/build/devlogs/`，不在仓库根的 `logs/`。
- `mojmap/` 与 `ui/` 里的脚本不是给人手动跑的，删改前先确认没有别的自动化在引用。
