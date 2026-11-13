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
 * The single loader contact surface.
 *
 * <p>No {@code net.fabricmc} or {@code net.neoforged} import may appear in {@code src/main} outside
 * this package. That boundary lets the same business code run on Fabric, NeoForge and Forge at the
 * cost of one implementation class per loader.
 *
 * <p>The interface only promises behaviour every loader shares: veto points answer "may this
 * happen" rather than "how much", events are registered as {@link Consumer} callbacks, and payload
 * codecs stay in the implementations because the three loader codec types differ.
 */
public interface PvPlatform {

    /** Loader name for logs and diagnostics: {@code fabric} / {@code neoforge} / {@code forge}. */
    String name();

    // ---------------------------------------------------------------- network
    // Payload field order lives in the payload classes; this registers channels only.

    /** Registers a client to server payload. */
    <T extends CustomPacketPayload> void registerPlayToServer(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec);

    /** Registers a server to client payload. */
    <T extends CustomPacketPayload> void registerPlayToClient(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec);

    /**
     * Sets what to do when a client sends a payload of this type.
     *
     * <p>The implementation delivers the callback on the server thread, so handlers may touch the
     * world directly and never need {@code server.execute}.
     */
    <T extends CustomPacketPayload> void onServerReceive(
            CustomPacketPayload.Type<T> type, BiConsumer<ServerPlayer, T> handler);

    /** Whether the target client has the mod and can receive this payload type. */
    boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

    void send(ServerPlayer player, CustomPacketPayload payload);

    // ---------------------------------------------------------------- lifecycle

    void onServerStarted(Consumer<MinecraftServer> action);

    void onServerStopping(Consumer<MinecraftServer> action);

    /** Runs at the end of every server tick. */
    void onServerTick(Consumer<MinecraftServer> action);

    void onPlayerJoin(Consumer<ServerPlayer> action);

    void onPlayerQuit(Consumer<ServerPlayer> action);

    void onPlayerDeath(Consumer<ServerPlayer> action);

    void onPlayerRespawn(Consumer<ServerPlayer> action);

    /** Command registration. The {@link Commands} dispatcher is the vanilla one on all loaders. */
    void onCommands(Consumer<com.mojang.brigadier.CommandDispatcher<CommandSourceStack>> action);

    // ---------------------------------------------------------------- veto points
    // Each answers "may this happen"; returning false cancels.

    /** Whether a hit may land. Asked only when the victim is a {@link ServerPlayer}. */
    void onAllowDamage(DamageVeto veto);

    /** Whether a block may be broken. */
    void onAllowBlockBreak(BlockBreakVeto veto);

    /** Whether a chat message may be sent. A cancelled message is delivered by SessionManager. */
    void onAllowChat(ChatVeto veto);

    /**
     * Sets the item use veto.
     *
     * <p>The veto returns an enum rather than a loader {@code InteractionResult}, because only
     * "allow" and "deny" are shared across the three loaders.
     */
    void onUseItem(UseItemVeto veto);

    /** Damage veto: {@code (victim, source, amount) -> may it happen}. */
    @FunctionalInterface
    interface DamageVeto {
        boolean allow(ServerPlayer victim, DamageSource source, float amount);
    }

    /** Block break veto: {@code (player, level, pos) -> may it happen}. */
    @FunctionalInterface
    interface BlockBreakVeto {
        boolean allow(Player player, Level level, BlockPos pos);
    }

    /**
     * Chat veto: {@code (sender, decorated) -> may it be sent}.
     *
     * <p>Receives the decorated line, with the {@code <name>} prefix already applied, not the raw
     * {@code PlayerChatMessage} plus {@code ChatType.Bound}. NeoForge only offers the finished
     * {@link Component}, so the raw pair is not available on every loader.
     */
    @FunctionalInterface
    interface ChatVeto {
        boolean allow(ServerPlayer sender, Component decorated);
    }

    /** Item use veto: {@code (player, level, hand) -> verdict}. */
    @FunctionalInterface
    interface UseItemVeto {
        UseVerdict allow(ServerPlayer player, Level level, InteractionHand hand);
    }

    /** Verdict of one item use. */
    enum UseVerdict {
        ALLOW,
        DENY
    }

    // ---------------------------------------------------------------- global handle

    /**
     * The installed platform implementation.
     *
     * <p>Call sites are static methods such as {@code PvPocketNetworking.sendHud}, so the handle is
     * a static field rather than a parameter threaded through every layer. The entry point installs
     * it once and everything after only reads it.
     *
     * @throws IllegalStateException if no implementation has been installed
     */
    static PvPlatform get() {
        PvPlatform platform = Holder.INSTANCE;
        if (platform == null) {
            throw new IllegalStateException(
                    "PvPlatform 还没装：入口类必须在 onInitialize 里先调 PvPlatform.install(...)");
        }
        return platform;
    }

    /** Installs the implementation. Called once by each loader entry point. */
    static void install(PvPlatform platform) {
        Holder.INSTANCE = platform;
    }

    /** Holds the static instance, so the interface has no initialisation-order coupling. */
    final class Holder {
        private static PvPlatform INSTANCE;

        private Holder() {
        }
    }
}
