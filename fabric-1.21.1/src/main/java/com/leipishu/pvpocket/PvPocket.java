package com.leipishu.pvpocket;

import com.leipishu.pvpocket.command.PvPocketCommands;
import com.leipishu.pvpocket.network.PvPocketNetworking;
import com.leipishu.pvpocket.session.SessionManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Loaded on both the client and the dedicated server.
 *
 * <p>Wiring only: session logic lives in {@link SessionManager}, storage in
 * {@code storage}, and combat rules will live in their own layer.</p>
 */
public final class PvPocket implements ModInitializer {

    public static final String MOD_ID = "pvpocket";
    public static final Logger LOGGER = LoggerFactory.getLogger("PvPocket");

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        PvPocketNetworking.registerCommon();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SessionManager manager = SessionManager.create(server);
            manager.load();
            manager.reconcile();
            LOGGER.info("PvPocket session manager ready (arena world available: {})",
                    com.leipishu.pvpocket.arena.ArenaManager.isReady(server));
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.shutdown();
            }
            SessionManager.clear();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.onJoin(handler.player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.onDisconnect(handler.player);
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity player) {
                SessionManager manager = SessionManager.get();
                if (manager != null) {
                    manager.onDeath(player);
                }
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.onRespawn(newPlayer);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.tick();
            }
        });

        // Terrain protection is a veto, so it has to run before the block is removed. Recording the
        // resulting change is handled separately by the World mixin, which also sees explosions.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
                com.leipishu.pvpocket.arena.ArenaManager.allowBreak(world, player, pos));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                PvPocketCommands.register(dispatcher));

        LOGGER.info("PvPocket common bootstrap complete (mod id: {})", MOD_ID);
    }
}
