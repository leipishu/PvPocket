package com.leipishu.pvpocket.platform.neoforge;

import com.leipishu.pvpocket.platform.PvPlatform;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * NeoForge 侧的 {@link PvPlatform}。
 *
 * <p>与 {@code FabricPlatform} 同形：只转发，不写判断。三处不能一一对应的地方都在下面标了。</p>
 *
 * <p><b>网络为什么要先攒起来。</b>Fabric 允许「先注册 codec，之后再绑接收器」；NeoForge 的
 * {@link PayloadRegistrar} 要求两者在同一次调用里给齐，而那次调用发生在
 * {@link RegisterPayloadHandlersEvent} 触发时——比 {@code PvBootstrap.wire} 晚。所以这里先把
 * codec 与处理器各存一份，等事件来了再一起交出去。这个时间差属于加载器，不属于业务。</p>
 */
public final class NeoForgePlatform implements PvPlatform {

    /** 一条已登记的 payload：类型 + codec。处理器在 {@link #serverHandlers}/{@link #clientHandlers} 里。 */
    private record Entry<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
    }

    private final IEventBus gameBus;
    private final Map<CustomPacketPayload.Type<?>, Entry<?>> toServer = new LinkedHashMap<>();
    private final Map<CustomPacketPayload.Type<?>, Entry<?>> toClient = new LinkedHashMap<>();
    private final Map<CustomPacketPayload.Type<?>, BiConsumer<ServerPlayer, Object>> serverHandlers =
            new LinkedHashMap<>();
    private final Map<CustomPacketPayload.Type<?>, Consumer<Object>> clientHandlers =
            new LinkedHashMap<>();

    public NeoForgePlatform(IEventBus modBus, IEventBus gameBus) {
        this.gameBus = gameBus;
        modBus.addListener(this::registerPayloads);
    }

    @Override
    public String name() {
        return "neoforge";
    }

    // ------------------------------------------------------------------ 网络

    @Override
    public <T extends CustomPacketPayload> void registerPlayToServer(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        toServer.put(type, new Entry<>(type, codec));
    }

    @Override
    public <T extends CustomPacketPayload> void registerPlayToClient(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        toClient.put(type, new Entry<>(type, codec));
    }

    @Override
    public <T extends CustomPacketPayload> void onServerReceive(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler) {
        serverHandlers.put(type, (player, payload) -> handler.accept(player, cast(payload)));
    }

    /**
     * 客户端侧的接收器。**不在 {@link PvPlatform} 上**，因为那是客户端的接口
     * （{@code PvClientPlatform}）——但它要写进同一张表，否则 {@code playToClient} 就没有处理器
     * 可交。两个接口在 NeoForge 上共享一次注册，是这一格的实现细节。
     */
    public <T extends CustomPacketPayload> void onClientReceive(
            CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        clientHandlers.put(type, payload -> handler.accept(cast(payload)));
    }

    /**
     * 把所有攒下来的 payload 一次性交给 NeoForge。
     *
     * <p>版本号写死 {@code "1"}：这是 NeoForge 的网络协议版本，双方不一致就握手失败。项目只有
     * 一个版本，所以它没有理由是个变量——真要改，那是「线格式变了」，应该同时改所有客户端。</p>
     */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        for (Entry<?> entry : toServer.values()) {
            playToServer(registrar, entry);
        }
        for (Entry<?> entry : toClient.values()) {
            playToClient(registrar, entry);
        }
    }

    private <T extends CustomPacketPayload> void playToServer(
            PayloadRegistrar registrar, Entry<T> entry) {
        registrar.playToServer(entry.type(), entry.codec(), (payload, context) -> {
            // NeoForge 的处理器本来就在服务端线程上，所以这里没有 execute 一跳。
            if (context.player() instanceof ServerPlayer player) {
                BiConsumer<ServerPlayer, Object> handler = serverHandlers.get(entry.type());
                if (handler != null) {
                    handler.accept(player, payload);
                }
            }
        });
    }

    private <T extends CustomPacketPayload> void playToClient(
            PayloadRegistrar registrar, Entry<T> entry) {
        registrar.playToClient(entry.type(), entry.codec(), (payload, context) -> {
            Consumer<Object> handler = clientHandlers.get(entry.type());
            if (handler != null) {
                handler.accept(payload);
            }
        });
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        // NeoForge 把「这条连接认不认这个频道」记在 listener 上；原版没有这个概念，所以这一项
        // 在 Fabric 那边问的是另一套 API，语义相同。
        return player.connection.hasChannel(type);
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onServerStarted(Consumer<MinecraftServer> action) {
        gameBus.addListener(ServerStartedEvent.class, event -> action.accept(event.getServer()));
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> action) {
        gameBus.addListener(ServerStoppingEvent.class, event -> action.accept(event.getServer()));
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> action) {
        gameBus.addListener(ServerTickEvent.Post.class, event -> action.accept(event.getServer()));
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> action) {
        gameBus.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                action.accept(player);
            }
        });
    }

    @Override
    public void onPlayerQuit(Consumer<ServerPlayer> action) {
        gameBus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                action.accept(player);
            }
        });
    }

    @Override
    public void onPlayerDeath(Consumer<ServerPlayer> action) {
        gameBus.addListener(LivingDeathEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                action.accept(player);
            }
        });
    }

    @Override
    public void onPlayerRespawn(Consumer<ServerPlayer> action) {
        gameBus.addListener(PlayerEvent.PlayerRespawnEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                action.accept(player);
            }
        });
    }

    @Override
    public void onCommands(Consumer<CommandDispatcher<CommandSourceStack>> action) {
        gameBus.addListener(RegisterCommandsEvent.class, event -> action.accept(event.getDispatcher()));
    }

    // ------------------------------------------------------------------ 否决点

    @Override
    public void onAllowDamage(DamageVeto veto) {
        // NeoForge 这条事件对所有 LivingEntity 触发；接口只问玩家，其余放行。与 Fabric 一致。
        gameBus.addListener(LivingIncomingDamageEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer victim
                    && !veto.allow(victim, event.getSource(), event.getAmount())) {
                event.setCanceled(true);
            }
        });
    }

    @Override
    public void onAllowBlockBreak(BlockBreakVeto veto) {
        gameBus.addListener(BlockEvent.BreakEvent.class, event -> {
            // getLevel() 声明成 LevelAccessor；服务端跑这条事件时它一定是 Level。
            if (!veto.allow(event.getPlayer(), (Level) event.getLevel(), event.getPos())) {
                event.setCanceled(true);
            }
        });
    }

    @Override
    public void onAllowChat(ChatVeto veto) {
        // NeoForge 的 ServerChatEvent 在广播之前触发，取消即不广播——与 Fabric 的
        // ALLOW_CHAT_MESSAGE 是同一个时机。它给的 getMessage() 已经是装饰好的整行，
        // 正好是 ChatVeto 要的形状。
        gameBus.addListener(ServerChatEvent.class, event -> {
            Component decorated = event.getMessage();
            if (!veto.allow(event.getPlayer(), decorated)) {
                event.setCanceled(true);
            }
        });
    }

    @Override
    public void onUseItem(UseItemVeto veto) {
        gameBus.addListener(PlayerInteractEvent.RightClickItem.class, event -> {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (veto.allow(player, player.level(), event.getHand()) == UseVerdict.DENY) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
