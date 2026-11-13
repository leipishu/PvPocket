package com.leipishu.pvpocket.mixin;

import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's per-player playerdata write.
 *
 * <p>{@code PlayerList.save(ServerPlayer)} is {@code protected} and there is no public equivalent
 * short of {@code saveAll()}. A restore is applied to the live entity and would otherwise reach the
 * disk only at the next autosave, up to five minutes later, or on a clean disconnect. Calling the
 * vanilla method also keeps the {@code .dat_old} rotation and its internal bookkeeping.
 */
@Mixin(PlayerList.class)
public interface PlayerManagerInvoker {

    @Invoker("save")
    void pvpocket$savePlayerData(ServerPlayer player);
}
