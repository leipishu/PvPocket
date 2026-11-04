# PvPocket S3 实现进度

> 对应 `PvPocket-v0.3-战斗规格与终稿.md` 的 L2（击退）+ L4（伤害/护甲）。
> 数值出处见 `PvPocket-1.8-数值对照表.md`。

## 已实现

| 模块 | 内容 | 1.8 出处 |
| --- | --- | --- |
| 受击击退 | 固定 0.4 强度、先速度减半再推、垂直分量恒定并 clamp 到 0.4、抗性按概率判定 | `EntityLivingBase#knockBack` |
| 攻击击退 | 冲刺/附魔的 `i*0.5` 水平 + `0.1` 垂直，纯叠加不二次减半 | `EntityPlayer#attackTargetEntityWithCurrentItem` |
| 护甲 | `damage * (25 - 护甲值) / 25`，无韧性项，护甲耐久照常消耗 | `EntityLivingBase#applyArmorCalculations` |
| 横扫 | legacy 攻击者不触发横扫 | 1.9 才引入 |
| 冲刺暴击 | legacy 攻击者冲刺时仍可暴击 | 1.8 的暴击条件无冲刺限制 |

## 三个 1.21.1 与 1.8 的结构性差异

这一阶段不是"改几个数"，而是三处机制本身不同。每一条都是读了双方源码后确认的：

1. **1.21.1 把两种击退合并成了一个方法。** 1.8 有两套：受击响应（`knockBack`，固定 0.4、先减半）和攻击冲量（`addVelocity`，纯叠加）。1.21.1 的 `takeKnockback(strength, x, z)` 同时承担两者，所以它必然二次减半速度。结果就是现代冲刺击退把受害者的残余动量压得比 1.8 低——冲刺打飞的距离明显更短。
2. **抗性的作用方式不同。** 1.8 是概率：`rand >= resistance` 才击退，全套下界合金（0.4）意味着 40% 的命中完全不击退。1.21.1 是乘法：`strength *= 1 - resistance`，即击退距离缩短 40%。这不是数值差，是手感差。
3. **垂直分量的处理不同。** 1.8 无论是否在空中都执行 `motionY / 2 + 0.4` 并 clamp 到 0.4；1.21.1 只在着地时施加垂直分量，空中保持原 `motionY`。1.8 的空中连击之所以成立，靠的就是这个恒定的上挑。

护甲那条更直接：1.21.1 的 `DamageUtil.getDamageLeft` 会先从护甲值里减去 `damage / (2 + toughness/4)`，再套同一个 25 分母。钻石套挨 10 点伤害时 1.8 减免 80%、现代只减免 70%。伤害越高差距越大。

## 注入点与验证

改的都是全局生效的伤害管线，所以每个注入点都做了字节码级核实（`-PmixinDebug` 导出转换后的类，`javap` 反查）。

| 目标 | 注入方式 | 验证结果 |
| --- | --- | --- |
| `PlayerEntity#getAttackCooldownProgress` | `@Inject` HEAD cancel | 命中 |
| `PlayerEntity#attack` 的两处 `takeKnockback` | `@Redirect` | 转换后 `attack` 内 `takeKnockback` 直接调用数 = 0，两处（偏移 569 / 838）均替换为重定向处理器 |
| `PlayerEntity#attack` 的 `getStackInHand` | `@ModifyExpressionValue` | 偏移 410 后接处理器，且该方法内仅此一处调用 |
| `PlayerEntity#attack` 第二次 `isSprinting` | `@ModifyExpressionValue` ordinal=1 | 偏移 324（第二次）后接处理器；偏移 201（冲刺击退判定）保持原值 |
| `LivingEntity#takeKnockback` | `@Inject` HEAD cancel | 命中 |
| `LivingEntity#applyArmorToDamage` | `@Inject` HEAD cancel | 命中 |
| `LivingEntity#damage` 的 `amount` | `@ModifyVariable` index=2 | 命中 |
| `LivingEntity#tickMovement` | `@Inject` HEAD | 命中 |

两点补充确认：

- `PlayerEntity#damage` 走 `super.damage(...)`，`PlayerEntity#applyDamage` 调 `this.applyArmorToDamage(...)` 且未重写该方法，所以格挡（在 `damage` HEAD）和护甲（在 `applyArmorToDamage` HEAD）两个钩子对玩家都真的会执行。
- 护甲钩子挂在 `applyArmorToDamage` 的 HEAD 而不是 `DamageUtil.getDamageLeft` 调用点，是为了保留 `damageArmor` —— 1.8 也在 `applyArmorCalculations` 里扣护甲耐久，跳过会让 legacy 对局少消耗耐久。

## 未验证

**没有任何一条数值在真实对局中测过。** 静态验证只能证明钩子挂在正确位置，不能证明数值正确。

## 已知缺口

1. **受击冷却仍是现代规则。** 1.8 是 20 tick 无敌窗口 + 后 10 tick 只结算"超过上次伤害"的增量；1.21.1 是 10 tick。这属于 L5（生存节奏，P1），本阶段未动。它会直接影响连击节奏，是下一个该处理的项。
2. **抗性药水与保护附魔未对齐。** 1.8 是 `*(25-k)/25` 线性，现代公式不同。抗性 I 恰好等效（都是 ×0.8），但高等级会偏离。
3. **混编规则的边界未定义。** 现在攻击侧看攻击者的规则（击退、横扫、暴击），受击侧看受害者的规则（格挡、护甲、受击击退）。legacy 打现代时，冲刺击退会被受害者的 `takeKnockback` 钩子再拦一次，行为不直观。决斗场景下双方规则一致，暂不构成问题，但 S4 的规则层需要明确这个边界。
4. **无击退抗性属性的来源。** 1.8 里玩家默认抗性为 0，下界合金才提供；本阶段没有改动属性来源。

## 手动测试步骤

两个客户端，`/pvpocket duel <对方> legacy`。

1. **护甲（L4）**：双方空手，一方穿全套钻石（20 点）。攻击一次，伤害应为 `原始伤害 × 0.2`。铁剑 6 点 → 1.2。现代规则下同一击约为 1.8，差距不大；换成高伤害（钻石斧 9 点）差距会更明显。
2. **护甲叠加格挡**：受害者按住右键，同一击应为 `(1+6)×0.5 = 3.5`，再 `×0.2 = 0.7`。
3. **受击击退与伤害无关**：分别用空手和钻石斧各打一次，击退距离应完全一致。
4. **空中上挑**：跳跃下落中被打，应仍被上挑（`motionY` 被设为约 0.4），而不是保持下落。
5. **冲刺击退**：冲刺命中应把对方明显打远（0.4 受击 + `1×0.5` 冲刺冲量），且受害者仍有残余速度。
6. **冲刺暴击**：冲刺跳跃下落中命中，应出现暴击粒子且伤害 ×1.5。
7. **无横扫**：站在两只僵尸旁用剑攻击其中一只，另一只不应掉血。
8. **现代模式不受影响**：`/pvpocket duel <对方>`（不加 legacy）重复第 7 步，横扫应正常触发。

## 下一步

S3 剩下的 L5（受击冷却 20 tick + 半窗增量）会显著影响连击手感，建议在 S4 规则层之前补上，否则"1.8 手感"缺一块。之后进入 S4：规则面板与协商流程。
