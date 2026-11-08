package com.leipishu.pvpocket.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's per-player playerdata write.
 *
 * <p>{@code PlayerManager.savePlayerData(ServerPlayerEntity)} is {@code protected} and there is no
 * public equivalent short of {@code saveAllPlayerData()}. It matters because a restore is applied to
 * the live entity and would otherwise only reach the disk at the next autosave (up to five minutes)
 * or a clean disconnect: a hard stop inside that window reloads the pre-match inventory on the next
 * login, and the following match then snapshots the loadout as the player's baseline. Calling the
 * vanilla method rather than re-implementing it also keeps the {@code .dat_old} rotation and the
 * internal bookkeeping it does.</p>
 */
@Mixin(PlayerManager.class)
public interface PlayerManagerInvoker {

    @Invoker("savePlayerData")
    void pvpocket$savePlayerData(ServerPlayerEntity player);
}
