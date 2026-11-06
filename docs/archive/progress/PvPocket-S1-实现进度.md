# PvPocket S1 实现进度

> **历史存档 · 非规范。** S1 阶段记录：会话骨架、快照与恢复、崩溃对账、决斗维度与边界判定。
> 阶段目标为 2 人 BO1 现代规则的最小闭环。
> 现行规范见 [`../../zh/01-overview.md`](../../zh/01-overview.md) 起六篇；使用者可感知的变化见 [`../../../CHANGELOG.md`](../../../CHANGELOG.md)。
> 本文按当时原样保留：其中的目录（如 `run/`）、指令（如 `/pvpocket accept`）与类名反映当时状态，与当前代码不一定一致。

> 对应 `PvPocket-v0.4-技术方案.md` 第 14 节的 S1：会话骨架 + 快照 + 恢复 + 崩溃对账（2 人 BO1 现代）。

## 已实现

| 模块 | 内容 | 关键约束 |
| --- | --- | --- |
| 存储层 | `<world>/pvpocket/` 下的 sessions / snapshots / journal 目录；原子写（临时文件 + rename） | 崩溃不会留下半截文件 |
| 日志 | 每会话一份 JSONL，先写日志后改状态，逐行 flush | 最多丢最后一行，且可判定为"未发生" |
| 生存快照 | 完整玩家 NBT + 维度、游戏模式、队伍、摔落距离；SHA-256 状态摘要 | 快照落盘成功后才允许改动玩家状态 |
| 恢复 | 先传送、再回写 NBT、再修正非 NBT 字段、最后同步容器与客户端位置 | 恢复后重算摘要并比对，不一致写 ANOMALY |
| 会话状态机 | DRAFT → NEGOTIATING → FROZEN → ROUND_READY → ROUND_ACTIVE → ROUND_SETTLED → RESTORING → ARCHIVED / VOIDED | 每次迁移先写日志再落盘 |
| 启动对账 | 扫描未归档会话，参与者进入待恢复队列，系列赛标记作废 | 玩家登录时才真正恢复 |
| 决斗维度 | `pvpocket:arena`，平坦生成器，bedrock + stone，地表 y=3 | 固定维度 + 区域，非动态创建 |
| 边界判定 | 每 tick 检查参与者是否离开场地（半径 40，y < -8） | 出界即判负 |
| 场地清理 | 结算时清除场地内所有掉落物与经验球 | 防止死亡掉落 → 恢复背包 → 捡回导致的复制 |
| 指令 | `/pvpocket duel|accept|deny|surrender|status` | 无需权限 |

## 已验证

- `./gradlew build` 通过，产物 `pvpocket-fabric-1.21.1-0.1.0.jar`。
- 专用服务端启动：`PvPocket session manager ready (arena world available: true)`。
- 维度正常注册并参与存档：`Saving chunks for level 'ServerLevel[world]'/pvpocket:arena`。
- 指令树注册成功，控制台执行 `/pvpocket` 输出帮助。
- 关服流程走 `SERVER_STOPPING` 分支，正常结算与清理。
- **双人对局端到端跑通**（见 `PvPocket-S2-实现进度.md` 的双客户端联调环境）：完整事件序列
  `SESSION_CREATED → NEGOTIATING → SNAPSHOT_WRITTEN ×2 → FROZEN → ROUND_READY →
  ROUND_STARTED → ROUND_ACTIVE → ROUND_ENDED → ARENA_CLEARED → RESTORING →
  RESTORE_COMPLETED ×2 (verified=true) → ARCHIVED`，双方状态摘要校验通过。
- 断线路径：一方在对局中断线 → `ROUND_ENDED reason=DISCONNECT`，在线方立即恢复，离线方
  `RESTORE_DEFERRED`，下次登录时恢复。

## 联调中发现并修复的三个缺陷

首次双人对局暴露了三个只在"玩家中途离线"时才会触发的问题，全部与**延迟恢复**这条路径有关。
它们叠在一起会让玩家丢失生存状态，因此单独记录。

1. **`ARCHIVED` 会话被对账跳过，恢复债务永久丢失。**
   `reconcile()` 开头 `if (stage.isTerminal()) continue;`，而 `ARCHIVED` 正是终态。玩家在对局
   中断线 → 会话正常走完并归档 → 服务端重启 → 对账跳过该会话 → 这名玩家的快照再也不会被应用。
   修复：把"恢复债务"拆成独立的一遍扫描，**不按阶段过滤**，只按 `snapshotFile != null &&
   !restored` 判定；第二遍才做未完成会话的回滚。

2. **延迟恢复没有落盘 `restored=true`。**
   `restoreParticipant` 成功后会置 `p.restored = true`，但只靠调用方的 `transition()` 落盘。
   正常结算路径有 `transition(ARCHIVED)` 兜住，延迟恢复路径没有。后果不是"少恢复一次"，而是
   **多恢复一次**：下次重启时该参与者仍被判定为未恢复，快照被第二次应用，把玩家在恢复之后取得
   的一切回滚掉。修复：在 `restoreParticipant` 内部 `persist(session)`，并加 `if (p.restored)
   return true;` 的幂等短路。

3. **`ServerPlayConnectionEvents.JOIN` 触发时玩家还不在 `PlayerManager` 里。**
   延迟恢复挂在 JOIN 事件上，而该事件早于玩家注册，`getPlayer(uuid)` 必然返回 null，于是恢复
   被"再次推迟"，永远等不到。修复：把延迟恢复从事件回调改为在 `tick()` 里排空
   （`flushPendingRestores`），与事件顺序无关，也顺带覆盖任何其他留下债务的路径。排空时先摘除
   条目再尝试，失败不重排，避免坏快照每 tick 刷一次错误。

验证方式：制造一笔真实债务（Dev1 对局中断线 → 服务端重启）后逐项确认——
对账打出 `Outstanding restore for Dev1 ... (stage ARCHIVED)`；Dev1 登录后
`RESTORE_COMPLETED verified=true` 且磁盘 `restored=True`；再重启一次，债务告警数为 0。

## 未实现（S2 及以后）

- 配装系统（S1 玩家使用自己的生存背包进场）。
- 战斗规则层（S3 及以后）。S2 已补上 L1 无攻击冷却与 L3 剑格挡，见 `PvPocket-S2-实现进度.md`。
- 规则协商与 GUI（S4）。
- 队伍与多人（S5）。
- 方块放置与还原（S6）。
- 局间重置（S1 只有 BO1，无第二局）。
- 玩家离线期间的会话超时策略（当前断线即判负）。
- 场地内死亡时**取消掉落**（当前是"先掉落、再清场"，机制上安全但不优雅）。

## 已知限制

1. **死亡路径依赖重生事件。** 玩家死亡时无法传送，所以结算推迟到 `AFTER_RESPAWN`。若玩家死在死亡界面直接断线，会话会由断线分支结算。
2. **S1 不隔离背包。** 玩家带自己的物品进场，死亡掉落后被清场 + 快照恢复，因此不会复制，但"进场前把装备放箱子"这类行为不在本阶段防护范围。
3. **场地是固定区域。** 并发会话数受维度容量限制，S1 只支持一场。

## 手动测试步骤

需要两个客户端（或一个客户端 + 一个正版小号），服务端 `./gradlew runServer`。

1. 两个玩家各带若干物品、经验、药水效果，记下背包内容。
2. A 执行 `/pvpocket duel B`，B 执行 `/pvpocket accept`。
3. 观察：双方被传送到 `pvpocket:arena` 的两个出生点，面对面。
4. 一方杀死另一方，或执行 `/pvpocket surrender`。
5. 验证：双方回到原位置，背包、经验、效果、生命、饥饿与第 1 步一致。
6. 验证：`run/world/pvpocket/journal/<id>.jsonl` 中出现完整事件序列，`RESTORE_COMPLETED` 的 `verified` 为 `true`。
7. 崩溃测试：在 ROUND_ACTIVE 阶段直接杀进程，重启服务端，玩家登录后应收到"上一场决斗因服务器中断已作废"并恢复状态。
