package com.theoxylo.thx.proxy;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Server-safe proxy (also the dedicated-server proxy).
 *
 * IMPORTANT: nothing in this class — or anything it transitively loads — may
 * reference client-only types (net.minecraft.client.*, Minecraft, Render*,
 * org.lwjgl.input.Keyboard, etc.). Those belong in {@link ClientProxy}.
 */
public class CommonProxy
{
    public void preInit(FMLPreInitializationEvent event) {}

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}

    /** No-op on the server; overridden on the client (Phase 4). */
    public void registerRenderers() {}

    /** No-op on the server; overridden on the client (Phase 6). */
    public void registerKeyBindings() {}
}
