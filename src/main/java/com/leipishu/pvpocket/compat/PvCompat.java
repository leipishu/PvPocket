package com.leipishu.pvpocket.compat;


import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
//?}

import java.util.Set;

/**
 * Minecraft API differences between the versions this mod builds for, kept in one file.
 *
 * <p>Every method here is a call the source used to make directly. Keeping the conditional blocks
 * here rather than at the call sites means the session, arena and snapshot code stays free of
 * version markers, which is what makes a new Minecraft version a change to this file plus a rename
 * table entry instead of a sweep through the whole tree.
 */
public final class PvCompat {

    private PvCompat() {
    }

    /** The server an entity belongs to. */
    public static MinecraftServer server(Entity entity) {
//? if >=1.21.11 {
        return entity.level().getServer();
//?} else {
        return entity.getServer();
//?}
    }

    /** The world spawn point of a level. */
    public static BlockPos sharedSpawn(ServerLevel level) {
//? if >=1.21.11 {
        return level.getRespawnData().pos();
//?} else {
        return PvCompat.sharedSpawn(level);
//?}
    }

    /** Permission check by vanilla numeric level: 1 moderator, 2 gamemaster, 3 admin, 4 owner. */
    public static boolean hasPermission(CommandSourceStack source, int level) {
//? if >=1.21.11 {
        return source.permissions().hasPermission(permission(level));
//?} else {
        return source.hasPermission(level);
//?}
    }

    /** Permission check on a player, same numeric levels as the command source one. */
    public static boolean hasPermission(Player player, int level) {
//? if >=1.21.11 {
        return player.permissions().hasPermission(permission(level));
//?} else {
        return player.hasPermissions(level);
//?}
    }

//? if >=1.21.11 {
    private static Permission permission(int level) {
        return switch (level) {
            case 4 -> Permissions.COMMANDS_OWNER;
            case 3 -> Permissions.COMMANDS_ADMIN;
            case 2 -> Permissions.COMMANDS_GAMEMASTER;
            default -> Permissions.COMMANDS_MODERATOR;
        };
    }
//?}

    /**
     * Moves a player to an absolute position in another level.
     *
     * <p>The rotation is absolute: relative flags are not set. Callers that want to keep the
     * current rotation pass the current yaw and pitch.
     */
    public static void teleport(ServerPlayer player, ServerLevel level,
                                double x, double y, double z, float yaw, float pitch) {
//? if >=1.21.11 {
        player.teleportTo(level, x, y, z, Set.of(), yaw, pitch, false);
//?} else {
        player.teleportTo(level, x, y, z, yaw, pitch);
//?}
    }
}
