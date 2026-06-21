package com.theoxylo.thx.client;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;

/**
 * Pilot control key bindings.
 *
 * Pitch/roll/ascend deliberately REUSE the player's vanilla movement keys
 * (forward/back/left/right/jump), read straight from {@code GameSettings} in
 * {@link ClientInputHandler}; they are not bound here. The reason is a 1.7.10
 * limitation: every {@link KeyBinding} is keyed into one global keyCode->binding
 * map (see {@code KeyBinding.hash}, last registration wins), so a separate
 * binding defaulted to W/A/S/D/Space would overwrite vanilla movement in that
 * map and stop {@code keyBindForward} & co. from ever registering a press --
 * i.e. it breaks walking. Reusing the movement bindings keeps the WASD feel,
 * leaves walking intact, and makes flight follow whatever the player rebinds
 * movement to.
 *
 * Only "descend" gets its own binding: it has no vanilla movement equivalent,
 * so X collides with nothing. It shows up under a "Helicopter Controls"
 * category in Options -> Controls, rebindable and persisted to options.txt.
 *
 * Client-only: {@link KeyBinding} is {@code @SideOnly(CLIENT)}, so this class
 * must never load on a dedicated server (it is reached only via ClientProxy).
 */
public final class ThxKeyBindings
{
    private static final String CATEGORY = "key.categories.thx";

    public static KeyBinding descend;

    public static void register()
    {
        descend = new KeyBinding("key.thx.descend", Keyboard.KEY_X, CATEGORY);
        ClientRegistry.registerKeyBinding(descend);
    }

    private ThxKeyBindings() {}
}
