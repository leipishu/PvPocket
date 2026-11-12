package com.leipishu.pvpocket.platform;

import com.leipishu.pvpocket.PvPocket;
import com.leipishu.pvpocket.arena.ArenaManager;
import com.leipishu.pvpocket.combat.CombatState;
import com.leipishu.pvpocket.combat.UsePolicy;
import com.leipishu.pvpocket.command.PvPocketCommands;
import com.leipishu.pvpocket.preset.PresetShareRegistry;
import com.leipishu.pvpocket.session.SessionManager;
import net.minecraft.world.item.ItemStack;

/**
 * 接线表：把所有「发生某事时该做什么」一次性挂到平台上。
 *
 * <p>这里没有一个加载器的名字。每个加载器的入口类只需要两行：</p>
 *
 * <pre>{@code
 * PvPlatform.install(new FabricPlatform());
 * PvBootstrap.wire(PvPlatform.get());
 * }</pre>
 *
 * <p>这样分的好处是可验证：移植一格时，如果 {@code wire} 需要改，说明业务逻辑真的依赖了平台
 * 差异；如果只是实现类要改，那这次移植没有风险。</p>
 *
 * <p>顺序有意义的两处，都在下面标了：友伤否决必须在伤害落地之前，方块保护必须在方块被移除
 * 之前。其余都是「通知」。</p>
 */
public final class PvBootstrap {

    private PvBootstrap() {
    }

    /** 挂上全部接线。每个入口类调一次。 */
    public static void wire(PvPlatform platform) {
        platform.onServerStarted(server -> {
            SessionManager manager = SessionManager.create(server);
            manager.load();
            manager.reconcile();
            boolean ready = ArenaManager.isReady(server);
            PvPocket.LOGGER.info("PvPocket session manager ready (arena world available: {})", ready);
            if (ready) {
                // The copy layout has to be read before the gallery is filled: it is what says which
                // extra cells each map owns, and a slot number is a stable address that must not
                // change between restarts. Loading after the warm-up would leave the copies pointing
                // at nothing until the second start.
                ArenaManager.loadLayout(server);
                // Each map owns one cell of the duel dimension and stays stamped there between
                // duels, so "select the colosseum" means "teleport to the colosseum's coordinates"
                // rather than "paste a colosseum at the origin". Filling the gallery here means the
                // dimension is laid out before anyone can pick a map.
                ArenaManager.beginWarmUp();
            }
        });

        platform.onServerStopping(server -> {
            SessionManager manager = SessionManager.get();
            if (manager != null) {
                manager.shutdown();
            }
            SessionManager.clear();
            // Share codes are per-server-start by design; a code that outlived the process it was
            // issued by would be a code nobody can revoke.
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

        // 否决点一：友伤是「不许发生」而不是「减轻」，所以必须在伤害落地之前问。
        // 能走到这里的都有攻击者与受害者，正好就是「这两人是不是一边」这个问题——会话答得了，
        // 伤害管线答不了。
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
            // Drops expired share codes. Throttled inside, so this is a counter compare on most
            // ticks — the five-minute promise is enforced even when nobody is publishing.
            PresetShareRegistry.tick();
        });

        // 否决点二：地形保护，必须在方块被移除之前。记录改动是另一条路（World 的 mixin 负责，
        // 它也能看见爆炸）。
        platform.onAllowBlockBreak((player, level, pos) -> ArenaManager.allowBreak(level, player, pos));

        // 物品使用的一半：末影珍珠、消耗品、`f.blockFilter` 禁掉的桶。放置不在这里——它走
        // `ItemStack#useOnBlock`，由 `ItemStackMixin` 否决，因为 `interactItem` 只在
        // `interactBlock` 拒绝之后才跑。
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

        // g.chatScope。取消原版的扇出，由 SessionManager 按规则投递给允许的收件人。
        platform.onAllowChat(SessionManager::allowChat);

        platform.onCommands(PvPocketCommands::register);

        PvPocket.LOGGER.info("PvPocket bootstrap complete (mod id: {}, platform: {})",
                PvPocket.MOD_ID, platform.name());
    }
}
