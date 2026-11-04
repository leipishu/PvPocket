# PvPocket 1.8 数值对照表

> 来源：**MCP-919**（Minecraft 1.8.9 + MCP stable 22 反编译源）。每条都标注了出处方法与
> 「已验证 / 推定」状态。推定项必须在 S3 动到之前补验，不得直接写进代码。
>
> 本表是 S3（1.8 的 L2 击退 + L4 伤害/护甲）的输入。S2 只用到第 1、2、3 条。

## 一、攻击节奏（L1）

| 项 | 1.8.9 | 1.21.1 | 状态 |
| --- | --- | --- | --- |
| 攻击冷却 | 无。1.8 不存在 `generic.attackSpeed` 属性，`getAttackCooldownProgress` 也不存在 | `generic.attack_speed` 决定冷却，伤害按充能比例缩放 | 已验证 |
| 附魔 `knockback` 对击退 | 加在 `i` 上，每级 +1（见 L2） | 同为每级 +1 | 已验证 |
| 横扫 | **无**。横扫之刃是 1.9 引入的 | 有 `sweeping` 伤害 | 推定（依版本史） |

S2 的做法：把 `PlayerEntity#getAttackCooldownProgress` 对 legacy 玩家强制返回 `1.0F`。这是
1.21.1 里 `ServerPlayNetworkHandler` 判定"这次攻击是否成立"的唯一闸门（要求 `> 0.9F`），因此
返回满充能等价于"每一下都是满伤害、没有冷却"。

## 二、受击击退（L2）

`EntityLivingBase#knockBack(Entity, float, double, double)`：

```java
if (this.rand.nextDouble() >= knockbackResistance) {
    this.isAirBorne = true;
    float f1 = 0.4F;                      // 固定强度，参数 amount 完全未使用
    this.motionX /= 2.0D; this.motionY /= 2.0D; this.motionZ /= 2.0D;
    this.motionX -= dx / f * f1;
    this.motionY += f1;
    this.motionZ -= dz / f * f1;
    if (this.motionY > 0.4D) this.motionY = 0.4D;
}
```

- 水平强度恒为 **0.4**，与伤害大小无关。签名里的 `float amount` 是死参数。
- 垂直固定 **+0.4**，上限 0.4。
- 施加前先把当前速度**减半**。
- 只有 `knockbackResistance` 属性能减免，且是概率式（`rand >= resistance` 才击退）。

`EntityPlayer#attackTargetEntityWithCurrentItem` 的主动击退：

```java
i = i + EnchantmentHelper.getKnockbackModifier(this);
if (this.isSprinting()) ++i;
...
targetEntity.addVelocity(-sin(yaw)*i*0.5, 0.1, cos(yaw)*i*0.5);
this.motionX *= 0.6D; this.motionZ *= 0.6D;
this.setSprinting(false);
```

- 冲刺攻击把 `i` 加 1，等价于"冲刺额外击退一级"。
- 命中后攻击者自身速度 ×0.6 并取消冲刺。

**关键结论：1.8 的格挡不减免击退。** `knockBack` 与 `attackTargetEntityWithCurrentItem` 都没有
任何 blocking 判定，全库搜索 `isBlocking` 仅命中 `damageEntity` 一处。S2 曾按"格挡减半击退"实
现过，核对源码后删除。

## 三、格挡（L3）

**进入条件**（`EntityPlayer#isBlocking`）：

```java
return this.isUsingItem() && this.itemInUse.getItem().getItemUseAction(this.itemInUse) == EnumAction.BLOCK;
```

`ItemSword#getItemUseAction` 返回 `EnumAction.BLOCK`，`onItemRightClick` 调 `setItemInUse`。所以
1.8 的剑格挡**就是物品使用状态**——手臂姿势、`isUsingItem()` 都由原版机制免费提供。

**减伤**（`EntityPlayer#damageEntity`，在护甲之前）：

```java
if (!damageSrc.isUnblockable() && this.isBlocking() && damageAmount > 0.0F) {
    damageAmount = (1.0F + damageAmount) * 0.5F;
}
```

- 不是"减半"，是**减半再 +0.5**：2 点伤害 → 1.5，10 点 → 5.5。
- 作用点在护甲与药水计算**之前**，护甲减免的是已经减过的值。
- `isUnblockable` 由 `DamageSource#setDamageBypassesArmor` 置位；1.8 里带这个标记的有
  onFire、inWall、drown、starve、fall、outOfWorld、generic、magic、wither、indirectMagic。
  现代对应 `DamageTypeTags.BYPASSES_ARMOR`。

**移速**（`EntityPlayerSP#onLivingUpdate`，仅客户端）：

```java
if (this.isUsingItem() && !this.isRiding()) {
    this.movementInput.moveStrafe *= 0.2F;
    this.movementInput.moveForward *= 0.2F;
}
```

- 系数是 **0.2**，不是 0.3。
- 1.8 的移动是客户端权威，所以这段只在客户端跑；1.21.1 服务端会用 `PlayerInput` 重算移动，
  因此**双端都必须缩放**，否则会出现回拉抖动。

## 四、伤害与护甲（L4）

`EntityLivingBase#applyArmorCalculations`：

```java
if (!source.isUnblockable()) {
    int i = 25 - this.getTotalArmorValue();
    damage = damage * i / 25.0F;
    this.damageArmor(damage);
}
```

- 线性：每点护甲 **4%** 减免，20 点封顶 **80%**。没有韧性、没有护甲耐久递减。

`EntityLivingBase#applyPotionDamageCalculations`：

```java
if (source.isDamageAbsolute()) return damage;
if (isPotionActive(resistance)) damage = damage * (25 - 5*(amplifier+1)) / 25.0F;
int k = min(20, EnchantmentHelper.getEnchantmentModifierDamage(...));
if (k > 0) damage = damage * (25 - k) / 25.0F;
```

- 抗性 I 等效 ×0.8，与现代 `-20%/级` 一致，无需改动。
- 保护附魔同样是线性 `(25-k)/25`，上限 20 点。

**与 1.21.1 的差异（S3 必须处理）**：现代 `CombatRules.getDamageAfterAbsorb` 引入韧性：

```java
float f = 2.0F + toughness / 4.0F;
float g = clamp(armor - amount / f, armor * 0.2F, 20.0F);
return amount * (1.0F - g / 25.0F);
```

钻石套（护甲 20 / 韧性 8）受 10 点伤害时：1.8 减免 80%（受伤 2.0），现代减免 70%（受伤 3.0）。
即**现代护甲在高伤害下更弱**，1.8 是纯线性。

## 五、暴击与受击冷却（L4/L5）

**暴击**（`EntityPlayer#attackTargetEntityWithCurrentItem`）：

```java
boolean flag = fallDistance > 0 && !onGround && !isOnLadder() && !isInWater()
               && !isPotionActive(blindness) && ridingEntity == null
               && targetEntity instanceof EntityLivingBase;
if (flag && f > 0.0F) f *= 1.5F;
```

- 条件与现代基本一致（跳跃下落中命中），倍率 **1.5×**。
- 1.8 无"暴击粒子/音效"之外的额外机制，`onCriticalHit` 只负责表现。

**受击冷却**（`EntityLivingBase#attackEntityFrom`，`maxHurtResistantTime = 20`）：

```java
if (hurtResistantTime > maxHurtResistantTime / 2.0F) {   // 即 > 10 tick
    if (amount <= lastDamage) return false;              // 更小的伤害被完全忽略
    damageEntity(source, amount - lastDamage);           // 只结算增量
    lastDamage = amount;
} else {
    lastDamage = amount;
    hurtResistantTime = maxHurtResistantTime;            // 20 tick
    damageEntity(source, amount);
}
```

- 无敌窗口 20 tick，前 10 tick 内完全免疫；后 10 tick 内只有**超过上次伤害**的部分生效，且按
  增量结算。这是 1.8 连击手感的核心，现代实现不同，S3 处理。

## 六、对 S2 实现的影响

| 项 | 实现位置 | 值 |
| --- | --- | --- |
| 攻击满充能 | `PlayerEntityMixin#pvpocket$legacyFullCharge` | `1.0F` |
| 格挡减伤 | `LivingEntityMixin#pvpocket$blockingDamage` | `(1+amount)*0.5`，跳过 `BYPASSES_ARMOR` |
| 格挡移速 | `LivingEntityMixin#pvpocket$blockingSlowdown` | `×0.2`，双端 |
| 格挡击退 | 不实现 | 1.8 无此机制 |
