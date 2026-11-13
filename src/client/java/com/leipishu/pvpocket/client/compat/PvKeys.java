package com.leipishu.pvpocket.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
//? if >=1.21.11 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.client.KeyMapping;

/**
 * Key bindings, whose category argument and match check both changed in 1.21.11.
 *
 * <p>The category used to be a translation key string; it is now a registered object with an id, and
 * its label comes from {@code key.category.<namespace>.<path>}. The bindings are built through
 * {@link #key} so the call sites never name either shape.
 */
public final class PvKeys {

    /** The id of the "PvPocket" heading on the controls page. */
    private static final String CATEGORY_ID = "pvpocket:main";

//? if >=1.21.11 {
    private static KeyMapping.Category category;
//?}

    private PvKeys() {
    }

    /** A key binding in the mod's own category. */
    public static KeyMapping key(String name, int defaultKey) {
//? if >=1.21.11 {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, defaultKey, category());
//?} else {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, defaultKey,
                "key.categories.pvpocket");
//?}
    }

    /** Whether a key press is the one this binding holds. */
    public static boolean matches(KeyMapping mapping, int keyCode, int scanCode) {
//? if >=1.21.11 {
        return mapping.matches(new KeyEvent(keyCode, scanCode, 0));
//?} else {
        return mapping.matches(keyCode, scanCode);
//?}
    }

    /** The key value for a keyboard code, as {@code KeyMapping.setKey} wants it. */
    public static InputConstants.Key keyOf(int keyCode, int scanCode) {
//? if >=1.21.11 {
        return InputConstants.getKey(new KeyEvent(keyCode, scanCode, 0));
//?} else {
        return InputConstants.getKey(keyCode, scanCode);
//?}
    }

//? if >=1.21.11 {
    /** The category, registered on first use: registering the same id twice throws. */
    private static KeyMapping.Category category() {
        if (category == null) {
            category = KeyMapping.Category.register(ResourceLocation.parse(CATEGORY_ID));
        }
        return category;
    }
//?}
}
