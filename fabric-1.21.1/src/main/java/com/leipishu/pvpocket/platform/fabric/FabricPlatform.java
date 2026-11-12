package com.leipishu.pvpocket.platform.fabric;

import com.leipishu.pvpocket.platform.PvPlatform;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import com.mojang.brigadier.CommandDispatcher;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fabric 侧的 {@link PvPlatform}：把 Fabric API 的事件翻译成平台接口的形状。
 *
 * <p>这个类里不该出现任何判断。写到这里来的每个 if，都是在替 {@code PvBootstrap} 回答业务
 * 问题，而业务问题必须对所有加载器给同一个答案。</p>
 *
 * <p>唯一的例外是类型收窄：Fabric 的事件把 {@code LivingEntity} 交给你，而接口只问玩家。
 * 「非玩家一律放行」是接口的约定（见 {@link #onAllowDamage}），不是策略。</p>
 */
public final class FabricPlatform implements PvPlatform {

    @Override
    public String name() {
        return "fabric";
    }

    // ------------------------------------------------------------------ 网络

    @Override
    public <T extends CustomPacketPayload> void registerPlayToServer(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.playC2S().register(type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void registerPlayToClient(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.playS2C().register(type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void onServerReceive(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler) {
        // Fabric 的 receiver 跑在 netty 线程上，碰世界就是未定义行为。这个跳线程属于平台，
        // 所以接收方的代码里看不到 `server.execute`——NeoForge 那边本来就在服务端线程，不必跳。
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> handler.accept(player, payload));
        });
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onServerStarted(Consumer<MinecraftServer> action) {
        ServerLifecycleEvents.SERVER_STARTED.register(action::accept);
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> action) {
        ServerLifecycleEvents.SERVER_STOPPING.register(action::accept);
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> action) {
        ServerTickEvents.END_SERVER_TICK.register(action::accept);
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> action) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> action.accept(handler.player));
    }

    @Override
    public void onPlayerQuit(Consumer<ServerPlayer> action) {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> action.accept(handler.player));
    }

    @Override
    public void onPlayerDeath(Consumer<ServerPlayer> action) {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                action.accept(player);
            }
        });
    }

    @Override
    public void onPlayerRespawn(Consumer<ServerPlayer> action) {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                action.accept(newPlayer));
    }

    @Override
    public void onCommands(Consumer<CommandDispatcher<CommandSourceStack>> action) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                action.accept(dispatcher));
    }

    // ------------------------------------------------------------------ 否决点

    @Override
    public void onAllowDamage(DamageVeto veto) {
        // Fabric 这条事件对所有 LivingEntity 触发；接口只问玩家，其余放行。
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer victim) || veto.allow(victim, source, amount));
    }

    @Override
    public void onAllowBlockBreak(BlockBreakVeto veto) {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
                veto.allow(player, world, pos));
    }

    @Override
    public void onAllowChat(ChatVeto veto) {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(veto::allow);
    }

    @Override
    public void onUseItem(UseItemVeto veto) {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResultHolder.pass(player.getItemInHand(hand));
            }
            ItemStack stack = serverPlayer.getItemInHand(hand);
            return veto.allow(serverPlayer, world, hand) == UseVerdict.ALLOW
                    ? InteractionResultHolder.pass(stack)
                    : InteractionResultHolder.fail(stack);
        });
    }
}
