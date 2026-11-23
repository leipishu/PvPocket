package com.leipishu.pvpocket.mixin;

import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's per-player playerdata write and the entity replacement behind a respawn.
 *
 * <p>{@code PlayerList.save(ServerPlayer)} is {@code protected} and there is no public equivalent
 * short of {@code saveAll()}. A restore is applied to the live entity and would otherwise reach the
 * disk only at the next autosave, up to five minutes later, or on a clean disconnect. Calling the
 * vanilla method also keeps the {@code .dat_old} rotation and its internal bookkeeping.
 *
 * <p>{@code respawn} is what the client's respawn request runs, and the only way to replace a dead
 * player entity. A body that nobody asked to respawn stands in the world forever; see
 * {@code SessionManager#tickCorpses}.
 */
@Mixin(PlayerList.class)
public interface PlayerManagerInvoker {

    @Invoker("save")
    void pvpocket$savePlayerData(ServerPlayer player);

    @Invoker("respawn")
    ServerPlayer pvpocket$respawn(ServerPlayer player, boolean keepInventory,
                                  Entity.RemovalReason reason);
}
