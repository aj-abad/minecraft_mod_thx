package com.theoxylo.thx.client;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;

/**
 * Pilot control key bindings. Registering them with the game makes them appear
 * under a "THX Helicopter" category in Options -> Controls, rebindable in-game
 * and persisted to options.txt (Minecraft also flags any conflicts there).
 *
 * Defaults mirror the original hardcoded scheme: W/S pitch, A/D roll, Space/X
 * collective. The descriptions and category are translation keys resolved from
 * the lang file. The W/A/S/D/Space defaults overlap vanilla movement, so the
 * Controls screen highlights them as conflicts — harmless, since these are only
 * read while piloting; rebind them if the highlight bothers you.
 *
 * Client-only: {@link KeyBinding} is {@code @SideOnly(CLIENT)}, so this class
 * must never load on a dedicated server (it is reached only via ClientProxy).
 */
public final class ThxKeyBindings
{
    private static final String CATEGORY = "key.categories.thx";

    public static KeyBinding pitchForward;
    public static KeyBinding pitchBack;
    public static KeyBinding rollLeft;
    public static KeyBinding rollRight;
    public static KeyBinding ascend;
    public static KeyBinding descend;

    public static void register()
    {
        pitchForward = make("key.thx.pitch_forward", Keyboard.KEY_W);
        pitchBack    = make("key.thx.pitch_back",    Keyboard.KEY_S);
        rollLeft     = make("key.thx.roll_left",     Keyboard.KEY_A);
        rollRight    = make("key.thx.roll_right",    Keyboard.KEY_D);
        ascend       = make("key.thx.ascend",        Keyboard.KEY_SPACE);
        descend      = make("key.thx.descend",       Keyboard.KEY_X);
    }

    private static KeyBinding make(String description, int defaultKey)
    {
        KeyBinding kb = new KeyBinding(description, defaultKey, CATEGORY);
        ClientRegistry.registerKeyBinding(kb);
        return kb;
    }

    private ThxKeyBindings() {}
}
