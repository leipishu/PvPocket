package com.leipishu.pvpocket.compat;


import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.gamerules.GameRules;
//?} else {
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.GameRules;
//?}

import java.util.List;
import java.util.Optional;
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
        return level.getSharedSpawnPos();
//?}
    }

    /** Looks a registry entry up as a holder, or null when the id names nothing. */
    public static <T> Holder.Reference<T> holder(Registry<T> registry, ResourceKey<T> key) {
//? if >=1.21.11 {
        return registry.get(key).orElse(null);
//?} else {
        return registry.getHolder(key).orElse(null);
//?}
    }

    /** Looks a registry entry up as a holder, or null when the id names nothing. */
    public static <T> Holder.Reference<T> holder(Registry<T> registry, ResourceLocation id) {
//? if >=1.21.11 {
        return registry.get(id).orElse(null);
//?} else {
        return registry.getHolder(id).orElse(null);
//?}
    }

    /** A splash potion carrying one custom effect, tinted the way vanilla tints effect colours. */
    public static PotionContents potionContents(MobEffectInstance effect) {
//? if >=1.21.11 {
        int color = PotionContents.getColorOptional(List.of(effect))
                .orElse(PotionContents.BASE_POTION_COLOR);
        return new PotionContents(Optional.empty(), Optional.of(color), List.of(effect),
                Optional.empty());
//?} else {
        int color = PotionContents.getColor(List.of(effect));
        return new PotionContents(Optional.empty(), Optional.of(color), List.of(effect));
//?}
    }

    /** Turns the daylight cycle of a level on or off. */
    public static void setDaylightCycle(ServerLevel level, boolean running) {
//? if >=1.21.11 {
        level.getGameRules().set(GameRules.ADVANCE_TIME, running, level.getServer());
//?} else {
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(running, level.getServer());
//?}
    }

    /**
     * Puts hunger back to a fresh-round state: full bar, five points of saturation, no exhaustion.
     *
     * <p>1.21.11 dropped {@code setExhaustion}, so the values are read in through the save format,
     * whose absent-key defaults are exactly the values wanted here.
     */
    public static void resetFood(Player player) {
        FoodData food = player.getFoodData();
//? if >=1.21.11 {
        food.readAdditionalSaveData(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING,
                player.level().registryAccess(), new net.minecraft.nbt.CompoundTag()));
//?} else {
        food.setFoodLevel(20);
        food.setSaturation(5.0F);
        food.setExhaustion(0.0F);
//?}
    }

    /** Looks a value up in a registry by id. */
    public static <T> T value(Registry<T> registry, ResourceLocation id) {
//? if >=1.21.11 {
        return registry.getValue(id);
//?} else {
        return registry.get(id);
//?}
    }

    /** Marks an item stack as unbreakable. */
    public static void makeUnbreakable(ItemStack stack) {
//? if >=1.21.11 {
        stack.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
//?} else {
        stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
//?}
    }

    /** Attaches a click event that runs a command. */
    public static Style clickCommand(Style style, String command) {
//? if >=1.21.11 {
        return style.withClickEvent(new ClickEvent.RunCommand(command));
//?} else {
        return style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
//?}
    }

    /** Attaches a hover event that shows text. */
    public static Style hoverText(Style style, Component text) {
//? if >=1.21.11 {
        return style.withHoverEvent(new HoverEvent.ShowText(text));
//?} else {
        return style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, text));
//?}
    }

    /** The identifier of a dimension key. */
    public static ResourceLocation dimensionId(ResourceKey<Level> key) {
//? if >=1.21.11 {
        return key.identifier();
//?} else {
        return key.location();
//?}
    }

    /** The name of a game profile. */
    public static String profileName(GameProfile profile) {
//? if >=1.21.11 {
        return profile.name();
//?} else {
        return profile.getName();
//?}
    }

    /** The hotbar slot the player has selected. */
    public static int selectedSlot(Player player) {
//? if >=1.21.11 {
        return player.getInventory().getSelectedSlot();
//?} else {
        return player.getInventory().selected;
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
