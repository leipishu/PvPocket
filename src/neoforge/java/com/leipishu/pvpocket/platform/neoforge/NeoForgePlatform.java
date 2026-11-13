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
 * NeoForge implementation of {@link PvPlatform}.
 *
 * <p>Same shape as {@code FabricPlatform}: forwards events and holds no decisions. Payloads are
 * buffered because {@link PayloadRegistrar} takes the codec and the handler in one call, which
 * happens on {@link RegisterPayloadHandlersEvent}, later than {@code PvBootstrap.wire}.
 */
public final class NeoForgePlatform implements PvPlatform {

    /** A registered payload: type plus codec. Handlers live in {@link #serverHandlers} and
     * {@link #clientHandlers}. */
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

    // ------------------------------------------------------------------ networking

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
     * Registers the client-side receiver for a payload type.
     *
     * <p>Not on {@link PvPlatform}, whose client side is {@code PvClientPlatform}. The handler
     * goes into the same table so {@code playToClient} has something to call.
     */
    public <T extends CustomPacketPayload> void onClientReceive(
            CustomPacketPayload.Type<T> type, Consumer<T> handler) {
        clientHandlers.put(type, payload -> handler.accept(cast(payload)));
    }

    /**
     * Hands every buffered payload to NeoForge in one call.
     *
     * <p>The protocol version is the literal {@code "1"}; both sides must agree or the handshake
     * fails. Changing it changes the wire format and requires a matching client update.
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
            // NeoForge already runs handlers on the server thread, so no execute hop is needed.
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
        // The connection records on its listener which channels it accepts.
        return player.connection.hasChannel(type);
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    // ------------------------------------------------------------------ lifecycle

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

    // ------------------------------------------------------------------ veto points

    @Override
    public void onAllowDamage(DamageVeto veto) {
        // Fires for every LivingEntity; non-players pass through, matching Fabric.
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
            // getLevel() is declared LevelAccessor; on the server path it is always a Level.
            if (!veto.allow(event.getPlayer(), (Level) event.getLevel(), event.getPos())) {
                event.setCanceled(true);
            }
        });
    }

    @Override
    public void onAllowChat(ChatVeto veto) {
        // ServerChatEvent fires before the broadcast, so cancelling suppresses it. getMessage()
        // already returns the decorated line that ChatVeto expects.
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
