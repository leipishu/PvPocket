package com.leipishu.pvpocket.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;

/**
 * NBT and entity serialisation, which 1.21.11 changed in two ways at once.
 *
 * <p>Getters on {@code CompoundTag} return {@code Optional} from 1.21.11 on, and entity
 * save/load takes a {@code ValueOutput} / {@code ValueInput} view instead of a tag. Both are
 * mechanical, so they live here rather than as conditionals at every call site.
 *
 * <p>The getters return the empty value when the key is absent, which is what the callers relied on
 * before.
 */
public final class PvNbt {

    private PvNbt() {
    }

    /** Reads an int, or 0 when the key is absent or holds another type. */
    public static int getInt(CompoundTag tag, String key) {
//? if >=1.21.11 {
        return tag.getIntOr(key, 0);
//?} else {
        return tag.getInt(key);
//?}
    }

    /** Reads a string, or "" when the key is absent. */
    public static String getString(CompoundTag tag, String key) {
//? if >=1.21.11 {
        return tag.getStringOr(key, "");
//?} else {
        return tag.getString(key);
//?}
    }

    /** Reads a string, or null when the key is absent. */
    public static String getStringOrNull(CompoundTag tag, String key) {
//? if >=1.21.11 {
        return tag.getString(key).orElse(null);
//?} else {
        return tag.contains(key) ? tag.getString(key) : null;
//?}
    }

    /** Reads a nested compound, or an empty one when the key is absent. */
    public static CompoundTag getCompound(CompoundTag tag, String key) {
//? if >=1.21.11 {
        return tag.getCompoundOrEmpty(key);
//?} else {
        return tag.getCompound(key);
//?}
    }

    /** Reads a nested list, or an empty one when the key is absent. */
    public static ListTag getList(CompoundTag tag, String key) {
//? if >=1.21.11 {
        return tag.getListOrEmpty(key);
//?} else {
        return tag.getList(key, 10);
//?}
    }

    /** Reads the compound at an index of a list, or an empty one when the index is out of range. */
    public static CompoundTag getCompound(ListTag list, int index) {
//? if >=1.21.11 {
        return list.getCompoundOrEmpty(index);
//?} else {
        return list.getCompound(index);
//?}
    }

    /** Serialises an entity, without the id, into a fresh tag. */
    public static CompoundTag save(Entity entity) {
//? if >=1.21.11 {
        net.minecraft.world.level.storage.TagValueOutput output =
                net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(
                        net.minecraft.util.ProblemReporter.DISCARDING);
        entity.saveWithoutId(output);
        return output.buildResult();
//?} else {
        CompoundTag tag = new CompoundTag();
        entity.saveWithoutId(tag);
        return tag;
//?}
    }

    /** Loads an entity from a tag. */
    public static void load(Entity entity, CompoundTag tag, HolderLookup.Provider registries) {
//? if >=1.21.11 {
        entity.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, registries, tag));
//?} else {
        entity.load(tag);
//?}
    }
}
