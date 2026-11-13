package com.leipishu.pvpocket.platform;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.UsePolicy;
import com.leipishu.pvpocket.command.PvPocketCommands;
import com.leipishu.pvpocket.network.PvPayloads;
import com.leipishu.pvpocket.preset.PresetShareRegistry;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.item.ItemStack;

/**
 * Wiring table: attaches every "when this happens, do that" hook to the platform.
 *
 * <p>No loader name appears here. Each loader entry point needs two lines:
 *
 * <pre>{@code
 * PvPlatform.install(new FabricPlatform()); PvBootstrap.wire(PvPlatform.get());
 * }</pre>
 *
 * <p>Order matters for the friendly-fire veto, which must run before damage lands, and for block
 * protection, which must run before a block is removed. The rest are notifications.
 */
public final class PvBootstrap {

    private PvBootstrap() {
    }

    /** Attaches every hook. Called once by each entry point. */
    public static void wire(PvPlatform platform) {
        // Payloads are registered before the receivers are bound: binding needs the types.
        PvPayloads.register(platform);

        platform.onServerStarted(server -> {
            SessionManager manager = SessionManager.create(server);
            manager.load();
            manager.reconcile();
            boolean ready = ArenaManager.isReady(server);
            PvPocket.LOGGER.info("PvPocket session manager ready (arena world available: {})", ready);
            if (ready) {
                // The copy layout is read before the gallery is filled: it says which extra cells
                // each map owns, and a slot number is a stable address across restarts.
                ArenaManager.loadLayout(server);
                // Each map owns one cell of the duel dimension and stays stamped there, so
                // "select the colosseum" means "teleport to its coordinates".
                ArenaManager.beginWarmUp();
            }
        });

        platform.onServerStopping(server -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.shutdown();
            }
            SessionManager.clear();
            // Share codes are per-server-start: a code that outlived its process could not be
            // revoked.
            PresetShareRegistry.clear();
        });

        platform.onPlayerJoin(player -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.onJoin(player);
            }
        });

        platform.onPlayerQuit(player -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.onDisconnect(player);
            }
        });

        // Veto one: friendly fire is "must not happen" rather than "hurt less", so it is asked
        // before damage lands. The session knows who shares a side; the damage pipeline does not.
        platform.onAllowDamage((victim, source, amount) ->
                SessionManager.get() == null || SessionManager.get().allowDamage(victim, source));

        platform.onPlayerDeath(player -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.onDeath(player);
            }
        });

        platform.onPlayerRespawn(player -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.onRespawn(player);
            }
        });

        platform.onServerTick(server -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.tick();
            }
            // One exhibit per tick; a no-op once the gallery is filled.
            ArenaManager.warmUpTick(server);
            // Drops expired share codes. Throttled inside, so most ticks are a counter compare.
            PresetShareRegistry.tick();
        });

        // Veto two: terrain protection, before a block is removed. Recording changes is the other
        // path, handled by the World mixin, which also sees explosions.
        platform.onAllowBlockBreak((player, level, pos) -> ArenaManager.allowBreak(level, player, pos));

        // Half of item use: ender pearls, consumables, and buckets banned by {@code f.blockFilter}.
        // Placement goes through {@code ItemStack#useOnBlock} and is vetoed by ItemStackMixin.
        platform.onUseItem((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            UsePolicy.Denial denial = UsePolicy.shieldDenial(
                    CombatState.isLegacy(player.getUUID()), stack);
            if (denial == UsePolicy.Denial.NONE) {
                denial = UsePolicy.useDenial(
                        CombatState.useGate(player.getUUID()),
                        CombatState.useBans(player.getUUID()),
                        stack);
            }
            if (denial == UsePolicy.Denial.NONE) {
                return PvPlatform.UseVerdict.ALLOW;
            }
            SessionManager.reportUseDenial(player, denial, stack);
            return PvPlatform.UseVerdict.DENY;
        });

        // {@code g.chatScope}: cancel the vanilla fan-out and let SessionManager deliver to the
        // allowed recipients.
        platform.onAllowChat(SessionManager::allowChat);

        platform.onCommands(PvPocketCommands::register);

        PvPocket.LOGGER.info("PvPocket bootstrap complete (mod id: {}, platform: {})",
                PvPocket.MOD_ID, platform.name());
    }
}
