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
//? if >=1.21.11 {
import net.minecraft.world.InteractionResult;
//?} else {
import net.minecraft.world.InteractionResultHolder;
//?}
import net.minecraft.world.item.ItemStack;
import com.mojang.brigadier.CommandDispatcher;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fabric implementation of {@link PvPlatform}, mapping Fabric API events onto the platform
 * interface.
 *
 * <p>Methods here translate events only. Business decisions belong to {@code PvBootstrap} so
 * that every loader answers the same way. Type narrowing is the one exception: Fabric hands
 * over a {@code LivingEntity} where the interface asks for a player, and
 * {@link #onAllowDamage} passes non-players through by contract.
 */
public final class FabricPlatform implements PvPlatform {

    @Override
    public String name() {
        return "fabric";
    }

    // ------------------------------------------------------------------ networking

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
        // Fabric delivers on the netty thread, where touching the world is undefined. The hop to
        // the server thread lives here so handlers never call server.execute.
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

    // ------------------------------------------------------------------ lifecycle

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

    // ------------------------------------------------------------------ veto points

    @Override
    public void onAllowDamage(DamageVeto veto) {
        // Fires for every LivingEntity; non-players pass through, as the interface requires.
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
        // Fabric supplies undecorated content and params; joining them is platform work.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
                veto.allow(sender, params.decorate(message.decoratedContent())));
    }

    @Override
    public void onUseItem(UseItemVeto veto) {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
//? if >=1.21.11 {
                return InteractionResult.PASS;
//?} else {
                return InteractionResultHolder.pass(player.getItemInHand(hand));
//?}
            }
            ItemStack stack = serverPlayer.getItemInHand(hand);
//? if >=1.21.11 {
            return veto.allow(serverPlayer, world, hand) == UseVerdict.ALLOW
                    ? InteractionResult.PASS
                    : InteractionResult.FAIL;
//?} else {
            return veto.allow(serverPlayer, world, hand) == UseVerdict.ALLOW
                    ? InteractionResultHolder.pass(stack)
                    : InteractionResultHolder.fail(stack);
//?}
        });
    }
}
