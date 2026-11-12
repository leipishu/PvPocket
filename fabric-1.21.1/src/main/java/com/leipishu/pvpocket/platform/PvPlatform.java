package com.leipishu.pvpocket.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 唯一的加载器接触面。
 *
 * <p>除这个包之外，{@code src/main} 里不应该出现任何 {@code net.fabricmc} / {@code net.neoforged}
 * 的 import。做到这一点，同一份业务代码才能喂给 Fabric、NeoForge 与 Forge——而移植一格的代价
 * 就从「重写一遍」变成「写一个 200 行的实现类」。</p>
 *
 * <p>这个接口只承诺**加载器之间语义一致**的东西，这是它唯一的取舍标准：</p>
 *
 * <ul>
 *   <li><b>否决点返回 boolean，不返回改过的伤害值。</b>Fabric 的
 *       {@code ServerLivingEntityEvents.ALLOW_DAMAGE} 只能取消，NeoForge 的
 *       {@code LivingIncomingDamageEvent} 能改数值，Forge 的 {@code LivingHurtEvent} 两者都能。
 *       承诺「能改数值」就等于要求 Fabric 那条路做不到，所以这里只问「能不能发生」。项目现在
 *       也确实只用它做友伤否决。</li>
 *   <li><b>注册 payload 的 codec 不在接口里。</b>三方的 codec 类型根本不同
 *       （{@code PacketCodec} / {@code StreamCodec} / 裸 {@code FriendlyByteBuf}），强行统一
 *       会把这个接口变成 22 个方法的表。payload 的注册留在各平台的实现类里，这里只承诺「怎么发」
 *       与「能不能发」。</li>
 *   <li><b>事件用 {@link Consumer} 注册，不用返回值。</b>加载器的订阅方式各异（Fabric 的
 *       {@code .register}、NeoForge 的 {@code @EventBusSubscriber}、Forge 的
 *       {@code addListener}），但「发生了一件事，通知我」是三者都有的。</li>
 * </ul>
 *
 * <p>实现类只做转发，每个方法一到三行。一旦某个实现类里出现了 if 判断，那多半说明该判断属于
 * {@link PvBootstrap}。</p>
 */
public interface PvPlatform {

    /** 加载器名，只用于日志与诊断（{@code fabric} / {@code neoforge} / {@code forge}）。 */
    String name();

    // ------------------------------------------------------------------ 网络
    //
    // 22 个 payload 的字段顺序只写一次（在 payload 类里），这里只处理「注册、谁发给谁」。
    //
    // 参数类型能写成原版的 `StreamCodec` 而不是加载器自己的 codec，是因为 1.20.5 起原版就有了
    // 这一套，Fabric 与 NeoForge 都直接吃它。**1.20.1 是唯一的例外**（那时候还没有
    // `StreamCodec`，得用裸 `FriendlyByteBuf`），所以那一格要另写一层，而不是把这里改复杂。

    /** 注册一个客户端 → 服务端的 payload。 */
    <T extends CustomPacketPayload> void registerPlayToServer(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec);

    /** 注册一个服务端 → 客户端的 payload。 */
    <T extends CustomPacketPayload> void registerPlayToClient(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec);

    /**
     * 收到客户端发来的 payload 时做什么。
     *
     * <p>实现类负责把回调放到**服务端线程**上：Fabric 的 receiver 跑在 netty 线程，直接改世界
     * 是未定义行为；NeoForge 的 handler 本来就在服务端线程，什么都不用做。这个差异属于平台，
     * 不属于业务——所以接收方的代码里不该再出现 {@code server.execute}。</p>
     */
    <T extends CustomPacketPayload> void onServerReceive(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler);

    /** 目标客户端是否装了这个模组、且能收这个 payload。 */
    boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

    void send(ServerPlayer player, CustomPacketPayload payload);

    // ------------------------------------------------------------------ 生命周期

    void onServerStarted(Consumer<MinecraftServer> action);

    void onServerStopping(Consumer<MinecraftServer> action);

    /** 每个服务端 tick 的末尾。 */
    void onServerTick(Consumer<MinecraftServer> action);

    void onPlayerJoin(Consumer<ServerPlayer> action);

    void onPlayerQuit(Consumer<ServerPlayer> action);

    void onPlayerDeath(Consumer<ServerPlayer> action);

    void onPlayerRespawn(Consumer<ServerPlayer> action);

    /** 命令注册。{@link Commands} 的 dispatcher 就是原版那一套，三方都一样。 */
    void onCommands(Consumer<com.mojang.brigadier.CommandDispatcher<CommandSourceStack>> action);

    // ------------------------------------------------------------------ 否决点
    //
    // 全部是「能不能发生」，不是「发生成什么样」。返回 false 即取消。

    /** 受伤能否发生。平台只在受害者是 {@link ServerPlayer} 时问，其余实体一律放行。 */
    void onAllowDamage(DamageVeto veto);

    /** 方块能否被破坏。 */
    void onAllowBlockBreak(BlockBreakVeto veto);

    /** 聊天消息能否发出。取消后由 {@code SessionManager} 自己按规则投递。 */
    void onAllowChat(ChatVeto veto);

    /**
     * 物品能否被使用。
     *
     * <p>用枚举而不是加载器的 {@code InteractionResult}：那东西三方的语义和取值都不同
     * （{@code InteractionResultHolder.pass/fail} 只在 Fabric 侧存在），而这里要表达的只有
     * 「放行」与「拒绝」两种。</p>
     */
    void onUseItem(UseItemVeto veto);

    /** 受伤否决：{@code (victim, source, amount) -> 是否允许}。 */
    @FunctionalInterface
    interface DamageVeto {
        boolean allow(ServerPlayer victim, DamageSource source, float amount);
    }

    /** 破坏方块否决：{@code (player, level, pos) -> 是否允许}。 */
    @FunctionalInterface
    interface BlockBreakVeto {
        boolean allow(Player player, Level level, BlockPos pos);
    }

    /**
     * 聊天否决：{@code (sender, decorated) -> 是否允许}。
     *
     * <p>收的是**装饰好的整行**（带 {@code <名字>} 前缀的成品），不是原始的
     * {@code PlayerChatMessage} + {@code ChatType.Bound}。后者是 Fabric 事件的形状：NeoForge 的
     * {@code ServerChatEvent} 只给一个已经拼好的 {@link Component}，拿不回「未装饰的内容 + 参数」。
     * 把这个形状写进接口就等于要求 NeoForge 那一格做不到。</p>
     *
     * <p>而业务侧要的本来就是「这个人说了这一行，允不允许发出去」——拼接方式是加载器的事。</p>
     */
    @FunctionalInterface
    interface ChatVeto {
        boolean allow(ServerPlayer sender, Component decorated);
    }

    /** 使用物品否决：{@code (player, level, hand) -> 放行还是拒绝}。 */
    @FunctionalInterface
    interface UseItemVeto {
        UseVerdict allow(ServerPlayer player, Level level, InteractionHand hand);
    }

    /** 一次物品使用的结论。 */
    enum UseVerdict {
        ALLOW,
        DENY
    }

    // ------------------------------------------------------------------ 全局句柄

    /**
     * 当前平台的实现。
     *
     * <p>用静态句柄而不是把 {@code PvPlatform} 一层层传下去：业务代码里调用点是
     * {@code PvPocketNetworking.sendHud(...)} 这种静态方法，共二十余处，加一个参数等于把接口
     * 泄漏到每一层的签名里。入口在 {@code onInitialize} 里装一次，此后只读。</p>
     */
    static PvPlatform get() {
        PvPlatform platform = Holder.INSTANCE;
        if (platform == null) {
            throw new IllegalStateException(
                    "PvPlatform 还没装：入口类必须在 onInitialize 里先调 PvPlatform.install(...)");
        }
        return platform;
    }

    /** 装实现。只由各加载器的入口类调用一次。 */
    static void install(PvPlatform platform) {
        Holder.INSTANCE = platform;
    }

    /** 单独一个类持有静态字段，避免接口的初始化顺序问题。 */
    final class Holder {
        private static PvPlatform INSTANCE;

        private Holder() {
        }
    }
}
